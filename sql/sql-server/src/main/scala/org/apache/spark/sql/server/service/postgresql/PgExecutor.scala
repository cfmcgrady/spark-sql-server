/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.server.service.postgresql

import java.sql.SQLException
import java.util.UUID
import javax.annotation.concurrent.ThreadSafe

import scala.util.control.NonFatal

import org.apache.spark.internal.Logging
import org.apache.spark.sql.{Dataset, SQLContext}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.catalog.CatalogTableType
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.command._
import org.apache.spark.sql.execution.datasources.CreateTable
import org.apache.spark.sql.server.{SQLServer, SQLServerConf, SQLServerEnv}
import org.apache.spark.sql.server.SQLServerConf._
import org.apache.spark.sql.server.service._
import org.apache.spark.sql.server.service.{Operation, OperationExecutor, OperationType}
import org.apache.spark.sql.server.service.postgresql.execution.command.BeginCommand
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.{Utils => SparkUtils}


// TODO: Recheck thread safety
@ThreadSafe
private[postgresql] case class PgOperation(
    sessionId: Int,
    query: (String, LogicalPlan),
    isCursor: Boolean)(
    sqlContext: SQLContext,
    activePools: java.util.Map[Int, String]) extends Operation with Logging {

  private val sqlParser = new PgParser(SQLServerEnv.sqlConf)
  private val statementId = UUID.randomUUID().toString()
  private var outputSchemaOption: Option[StructType] = None

  override val queryType: OperationType = query._2 match {
    case BeginCommand() => BEGIN
    case _ if isCursor => FETCH
    case _ => SELECT
  }

  override def cancel(): Unit = {
    logInfo(
      s"""Cancelling query with $statementId:
         |${query._2}
       """.stripMargin)
    if (statementId != null) {
      sqlContext.sparkContext.cancelJobGroup(statementId)
    }
    setState(CANCELED)
    SQLServer.listener.onStatementCanceled(statementId)
  }

  override def outputSchema(): StructType = {
    outputSchemaOption.getOrElse(StructType(Seq.empty))
  }

  override def close(): Unit = {
    // RDDs will be cleaned automatically upon garbage collection.
    sqlContext.sparkContext.clearJobGroup()
    logDebug(s"CLOSING $statementId")
    setState(CLOSED)
  }

  override def run(): Iterator[InternalRow] = {
    logInfo(
      s"""Running query with $statementId:
         |${query._1}
       """.stripMargin)
    setState(RUNNING)

    // Always use the latest class loader provided by SQLContext's state.
    Thread.currentThread().setContextClassLoader(sqlContext.sharedState.jarClassLoader)

    SQLServer.listener.onStatementStart(statementId, sessionId, query._1, statementId)
    sqlContext.sparkContext.setJobGroup(statementId, query._1, true)

    if (activePools.containsKey(sessionId)) {
      val pool = activePools.get(sessionId)
      sqlContext.sparkContext.setLocalProperty("spark.scheduler.pool", pool)
    }

    val resultRowIterator = try {
      val df = Dataset.ofRows(sqlContext.sparkSession, sqlParser.parsePlan(query._1))
      logDebug(df.queryExecution.toString())
      SQLServer.listener.onStatementParsed(statementId, df.queryExecution.toString())

      val useIncrementalCollect = SQLServerEnv.sqlConf.sqlServerIncrementalCollectEnabled
      val rowIter = if (useIncrementalCollect) {
        df.queryExecution.executedPlan.executeToIterator()
      } else {
        df.queryExecution.executedPlan.executeCollect().iterator
      }

      // If query suceeds, set the output schema
      outputSchemaOption = Some(df.schema)

      // Based on the assumption that DDL commands succeed, we then update internal states
      query._2 match {
        case SetCommand(Some((SQLServerConf.SQLSERVER_POOL.key, Some(value)))) =>
          logInfo(s"Setting spark.scheduler.pool=$value for future statements in this session.")
          activePools.put(sessionId, value)
        case CreateDatabaseCommand(dbName, _, _, _, _) =>
          PgMetadata.registerDatabase(dbName, sqlContext)
        case CreateTable(desc, _, _) =>
          val dbName = desc.identifier.database.getOrElse("default")
          val tableName = desc.identifier.table
          PgMetadata.registerTable(dbName, tableName, desc.schema, desc.tableType, sqlContext)
        case CreateTableCommand(table, _) =>
          val dbName = table.identifier.database.getOrElse("default")
          val tableName = table.identifier.table
          PgMetadata.registerTable(dbName, tableName, table.schema, table.tableType, sqlContext)
        case CreateViewCommand(table, _, _, _, _, child, _, _, _) =>
          val dbName = table.database.getOrElse("default")
          val tableName = table.identifier
          val qe = sqlContext.sparkSession.sessionState.executePlan(child)
          val schema = qe.analyzed.schema
          PgMetadata.registerTable(dbName, tableName, schema, CatalogTableType.VIEW, sqlContext)
        case CreateFunctionCommand(dbNameOption, funcName, _, _, _) =>
          val dbName = dbNameOption.getOrElse("default")
          PgMetadata.registerFunction(dbName, funcName, sqlContext)
        case DropDatabaseCommand(dbName, _, _) =>
          logInfo(s"Drop a database `$dbName` and refresh database catalog information")
          PgMetadata.refreshDatabases(dbName, sqlContext)
        case DropTableCommand(table, _, _, _) =>
          val dbName = table.database.getOrElse("default")
          val tableName = table.identifier
          logInfo(s"Drop a table `$dbName.$tableName` and refresh table catalog information")
          PgMetadata.refreshTables(dbName, sqlContext)
        case DropFunctionCommand(dbNameOption, funcName, _, _) =>
          val dbName = dbNameOption.getOrElse("default")
          logInfo(s"Drop a function `$dbName.$funcName` and refresh function catalog information")
          PgMetadata.refreshFunctions(dbName, sqlContext)
        case _ =>
      }
      rowIter
    } catch {
      case NonFatal(e) =>
        if (state == CANCELED) {
          val errMsg =
            s"""Cancelled query with $statementId
               |${query._1}
             """.stripMargin
          logWarning(errMsg)
          throw new SQLException(errMsg)
        } else {
          val exceptionString = SparkUtils.exceptionString(e)
          val errMsg =
            s"""Caught an error executing query with with $statementId:
               |${query._1}
               |Exception message:
               |$exceptionString
             """.stripMargin
          logError(errMsg)
          setState(ERROR)
          SQLServer.listener.onStatementError(statementId, e.getMessage, exceptionString)
          throw new SQLException(errMsg)
        }
    }

    setState(FINISHED)
    SQLServer.listener.onStatementFinish(statementId)
    resultRowIterator
  }
}

private[server] class PgExecutor extends OperationExecutor {

  // Create a new instance for service-specific operations
  override def newOperation(
      sessionId: Int,
      plan: (String, LogicalPlan),
      isCursor: Boolean)(
      sqlContext: SQLContext,
      activePools: java.util.Map[Int, String]): Operation = {
    new PgOperation(sessionId, plan, isCursor)(sqlContext, activePools)
  }
}
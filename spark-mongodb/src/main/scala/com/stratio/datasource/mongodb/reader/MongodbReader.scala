/**
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stratio.datasource.mongodb.reader

import java.util.regex.Pattern

import com.mongodb.QueryBuilder
import com.mongodb.casbah.Imports._
import com.mongodb.casbah.MongoCursorBase
import com.stratio.datasource.mongodb.client.MongodbClientFactory
import com.stratio.datasource.mongodb.config.{MongodbSSLOptions, MongodbCredentials, MongodbConfig}
import com.stratio.datasource.mongodb.partitioner.MongodbPartition
import com.stratio.datasource.util.Config
import org.apache.spark.Partition
import org.apache.spark.sql.sources._

import scala.util.Try

/**
 *
 * @param config Configuration object.
 * @param requiredColumns Pruning fields
 * @param filters Added query filters
 */
class MongodbReader(config: Config,
                     requiredColumns: Array[String],
                     filters: Array[Filter]) {

  private var mongoClient: Option[MongodbClientFactory.Client] = None

  private var mongoClientKey: Option[String] = None

  private var dbCursor: Option[MongoCursorBase] = None

  private val batchSize = config.getOrElse[Int](MongodbConfig.CursorBatchSize, MongodbConfig.DefaultCursorBatchSize)

  private val connectionsTime = config.get[String](MongodbConfig.ConnectionsTime).map(_.toLong)

  def close(): Unit = {
    dbCursor.fold(ifEmpty = ()) { cursor =>
      cursor.close()
      dbCursor = None
    }

    mongoClient.fold(ifEmpty = ()) { client =>
      mongoClientKey.fold({
        MongodbClientFactory.setFreeConnectionByClient(client, connectionsTime)
        MongodbClientFactory.closeByClient(client)
      }) {key =>
        MongodbClientFactory.setFreeConnectionByKey(key, connectionsTime)
        MongodbClientFactory.closeByKey(key)
      }

      mongoClient = None
    }
  }

  def hasNext: Boolean = {
    dbCursor.fold(ifEmpty = false)(cursor => cursor.hasNext)
  }

  def next(): DBObject = {
    dbCursor.fold(ifEmpty = throw new IllegalStateException("DbCursor is not initialized"))(cursor => cursor.next())
  }

  /**
   * Initialize MongoDB reader
   * @param partition Where to read from
   */
  def init(partition: Partition): Unit = {
    Try {
      val mongoPartition = partition.asInstanceOf[MongodbPartition]
      val hosts = mongoPartition.hosts.map(add => new ServerAddress(add)).toList
      val credentials = config.getOrElse[List[MongodbCredentials]](MongodbConfig.Credentials, MongodbConfig.DefaultCredentials).map {
        case MongodbCredentials(user, database, password) =>
          MongoCredential.createCredential(user, database, password)
      }
      val sslOptions = config.get[MongodbSSLOptions](MongodbConfig.SSLOptions)
      val clientOptions = config.properties.filterKeys(_.contains(MongodbConfig.ListMongoClientOptions))

      val mongoClientResponse = MongodbClientFactory.getClient(hosts, credentials, sslOptions, clientOptions)
      mongoClient = Option(mongoClientResponse.clientConnection)
      mongoClientKey = Option(mongoClientResponse.key)

      val emptyFilter = MongoDBObject(List())
      val filter = Try(queryPartition(filters)).getOrElse(emptyFilter)

      dbCursor = (for {
        client <- mongoClient
        collection <- Option(client(config(MongodbConfig.Database))(config(MongodbConfig.Collection)))
        dbCursor <- Option(collection.find(filter, selectFields(requiredColumns)))
      } yield {
        mongoPartition.partitionRange.minKey.foreach(min => dbCursor.addSpecial("$min", min))
        mongoPartition.partitionRange.maxKey.foreach(max => dbCursor.addSpecial("$max", max))
        dbCursor.batchSize(batchSize)
      }).headOption
    }.recover {
      case throwable =>
        throw MongodbReadException(throwable.getMessage, throwable)
    }
  }

  /**
   * Create query partition using given filters.
   *
   * @param filters the Spark filters to be converted to Mongo filters
   * @return the dB object
   */
  private def queryPartition(
                              filters: Array[Filter]): DBObject = {

    def filtersToDBObject( sFilters: Array[Filter], parentFilterIsNot: Boolean = false ): DBObject = {
      val queryBuilder: QueryBuilder = QueryBuilder.start

      if (parentFilterIsNot) queryBuilder.not()

      sFilters.foreach {
        case EqualTo(attribute, value) =>
          queryBuilder.put(attribute).is(checkObjectID(attribute, value))
        case GreaterThan(attribute, value) =>
          queryBuilder.put(attribute).greaterThan(checkObjectID(attribute, value))
        case GreaterThanOrEqual(attribute, value) =>
          queryBuilder.put(attribute).greaterThanEquals(checkObjectID(attribute, value))
        case In(attribute, values) =>
          queryBuilder.put(attribute).in(values.map(value => checkObjectID(attribute, value)))
        case LessThan(attribute, value) =>
          queryBuilder.put(attribute).lessThan(checkObjectID(attribute, value))
        case LessThanOrEqual(attribute, value) =>
          queryBuilder.put(attribute).lessThanEquals(checkObjectID(attribute, value))
        case IsNull(attribute) =>
          queryBuilder.put(attribute).is(null)
        case IsNotNull(attribute) =>
          queryBuilder.put(attribute).notEquals(null)
        case And(leftFilter, rightFilter) if !parentFilterIsNot =>
          queryBuilder.and(filtersToDBObject(Array(leftFilter)), filtersToDBObject(Array(rightFilter)))
        case Or(leftFilter, rightFilter)  if !parentFilterIsNot =>
          queryBuilder.or(filtersToDBObject(Array(leftFilter)), filtersToDBObject(Array(rightFilter)))
        case StringStartsWith(attribute, value) if !parentFilterIsNot =>
          queryBuilder.put(attribute).regex(Pattern.compile("^" + value + ".*$"))
        case StringEndsWith(attribute, value) if !parentFilterIsNot =>
          queryBuilder.put(attribute).regex(Pattern.compile("^.*" + value + "$"))
        case StringContains(attribute, value) if !parentFilterIsNot =>
          queryBuilder.put(attribute).regex(Pattern.compile(".*" + value + ".*"))
        case Not(filter) =>
          filtersToDBObject(Array(filter), true)
      }

      queryBuilder.get
    }

    filtersToDBObject(filters)
  }

  /**
   * Check if the field is "_id" and if the user wants to filter by this field as an ObjectId
   *
   * @param attribute Name of the file
   * @param value Value for the attribute
   * @return The value in the correct data type
   */
  private def checkObjectID(attribute: String, value: Any) : Any = attribute  match {
    case "_id" if idAsObjectId => new ObjectId(value.toString)
    case _ => value
  }

  private lazy val idAsObjectId: Boolean =
    config.getOrElse[String](MongodbConfig.IdAsObjectId, MongodbConfig.DefaultIdAsObjectId).equalsIgnoreCase("true")

  /**
   *
   * Prepared DBObject used to specify required fields in mongodb 'find'
   * @param fields Required fields
   * @return A mongodb object that represents required fields.
   */
  private def selectFields(fields: Array[String]): DBObject =
    MongoDBObject(
      if (fields.isEmpty) List()
      else fields.toList.filterNot(_ == "_id").map(_ -> 1) ::: {
        List("_id" -> fields.find(_ == "_id").fold(0)(_ => 1))
      })
}

case class MongodbReadException(
                                 msg: String,
                                 causedBy: Throwable) extends RuntimeException(msg, causedBy)

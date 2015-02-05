package com.stratio.deep.mongodb.rdd

import com.mongodb.DBObject
import com.stratio.deep.DeepConfig
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SQLContext
import org.apache.spark.{Partition, TaskContext}

/**
 * Created by rmorandeira on 29/01/15.
 */


class MongodbRDD(
  sc: SQLContext,
  config: DeepConfig)
  extends RDD[DBObject](sc.sparkContext, deps = Nil) {

  override def getPartitions: Array[Partition] = {
    val sparkPartitions = new Array[Partition](1)
    val idx: Int = 0
    sparkPartitions(idx) = new MongodbPartition(id, idx)
    sparkPartitions
  }

  override def getPreferredLocations(split: Partition): Seq[String] =
    super.getPreferredLocations(split)

  override def compute(
    split: Partition,
    context: TaskContext): MongodbRDDIterator =
    new MongodbRDDIterator(context, split.asInstanceOf[MongodbPartition], config)

}
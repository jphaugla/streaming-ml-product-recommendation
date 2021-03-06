package com.datastax.powertools.analytics

/**
  * Created by sebastianestevez on 4/13/17.
  */

import com.datastax.powertools.analytics.ddl.DSECapable
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.ml.recommendation.ALS
import org.apache.spark.sql.cassandra.DataFrameWriterWrapper
import org.apache.spark.sql.{Row, SQLContext}
import org.apache.spark.sql.functions._

class SparkMLProductRecommendationBatchJob {
}

object SparkMLProductRecommendationBatchJob extends DSECapable{
  def main(args: Array[String]): Unit = {

    case class Observation(user: Int, item: Int, preference: Float)
    def parseObservations(row: Row): Observation = {
      Observation(row.getInt(0), row.getInt(1), row.getDouble(2).toFloat)
    }

    // Create the context
    val sc = connectToDSE("SimpleSparkML")

    //setup schema
    setupSchema("recommendations", "predictions", "(user int, item int, preference float, prediction float, PRIMARY KEY((user), item))")

    val sqlContext = new SQLContext(sc)
    val observations = sqlContext.read.format("com.databricks.spark.csv")
      .option("header", "true")
      .option("inferSchema", "true")
      .option("delimiter", ":")
      .load("dsefs:///sales_observations")

    //get training set
    val Array(training, test) = observations.randomSplit(Array(0.8, 0.2))

    // Build the recommendation model using ALS on the training data
    val als = new ALS()
      .setMaxIter(5)
      .setRegParam(0.01)
      .setImplicitPrefs(true)
      .setUserCol("user")
      .setItemCol("item")
      .setRatingCol("preference")

    //in Spark ML Pipelines, als is an Estimator which needs to be .fit() against a traning DataFrame/DataSet and produces a Transformer
    //in this case the transformer is an ALSModel.
    val model = als.fit(training)

    // Uncomment starting with Spark 2.2 https://issues.apache.org/jira/browse/SPARK-14489
    // Note we set cold start strategy to 'drop' to ensure we don't get NaN evaluation metrics
    //model.setColdStartStrategy("drop")

    // model is the Transformer which can transform an incoming test DataFrame/DataSet to generate predictions
    // Evaluate the model by computing the RMSE on the test data
    val predictions = model.transform(test)

    predictions.filter(predictions("user") === 2664).orderBy(desc("prediction")).show

    predictions.write.
      cassandraFormat("predictions", "recommendations").
      save()

  }

  var conf: SparkConf = _
  var sc: SparkContext = _
}
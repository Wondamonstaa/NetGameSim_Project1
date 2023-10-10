package com.lsc

import org.apache.hadoop.conf.*
import org.apache.hadoop.fs.Path
import org.apache.hadoop.conf._
import org.apache.hadoop.io._
import org.apache.hadoop.util._
import org.apache.hadoop.mapred._
import java.io.IOException
import scala.jdk.CollectionConverters._
import Utilz.CreateLogger
import org.slf4j.Logger
import com.lsc.DataConverter._
import java.text.DecimalFormat
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{DoubleWritable, IntWritable, LongWritable, Text}
import org.apache.hadoop.mapreduce.{Job, Mapper, Reducer}
import scala.io.Source
import java.io.{BufferedWriter, File, FileWriter, PrintWriter}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import java.util.ArrayList

object SimRankMapReduce {

  private val logger = CreateLogger(classOf[SimRankMapReduce.type])
  logger.info("SimRank MapReduce Job Started")

  //An object of SimRank class to access the methods
  val obj = SimRank

  class MyMapper extends Mapper[LongWritable, Text, Text, DoubleWritable] {

    override def map(
                      key: LongWritable,
                      value: Text,
                      context: Mapper[LongWritable, Text, Text, DoubleWritable]#Context
                    ): Unit = {
      val lineTracker = key.get()

      // Skip the header line
      if (lineTracker > 0) {

        val line = value.toString
        val fields = line.split(",") // Split the key in for mapper with ","

        val nodeId = new Text(fields(0))

        logger.info("Calculating SimRank score for perturbed and original nodes")
        val calculatedScore = obj.calculateSimRank(line)

        logger.info("Creating a DoubleWritable object to store the calculated score")
        val scoreWritable = new DoubleWritable(calculatedScore)

        logger.info("Sending Original Graph Node as key & SimRank Score Between Two Nodes As The Value To Reducer")
        context.write(nodeId, scoreWritable)
      }
    }
  }

  class MyReducer extends Reducer[Text, DoubleWritable, Text, Text] {
    override def reduce(
                         key: Text,
                         values: java.lang.Iterable[DoubleWritable],
                         context: Reducer[Text, DoubleWritable, Text, Text]#Context
                       ): Unit = {

      logger.info("Reduce Function is Being Executed")

      val outputMessage = obj.calculateSimRankWithTracebilityLinks(key, values)

      logger.info("Writing each unique key with its value to a csv file")
      context.write(key, new Text(outputMessage))
    }
  }

  def main(args: Array[String]): Unit = {
  }


  //@main
  def runSimRankMR(inputPathS: String, outputPathS: String): Unit = {
    
    logger.info("Creating a Hadoop configuration for SimRank MapReduce Job")
    val configuration = new Configuration()
    require(inputPathS.nonEmpty && outputPathS.nonEmpty)
    println(inputPathS)
    println(outputPathS)
    
    logger.info("Setting the output path")
    val inputPath = new Path(inputPathS)
    val outputPath = new Path(outputPathS)
    val conf: JobConf = new JobConf(this.getClass)

    //This will help to identify the jar file when it is distributed across the cluster
    val job = Job.getInstance(configuration, "SimRankMapReduce")
    job.setJobName("SimRankMapReduce")

    logger.info("Set Mapper and Reducer classes")
    job.setMapperClass(classOf[MyMapper])
    //job.setCombinerClass(classOf[MyReducer])
    job.setReducerClass(classOf[MyReducer])

    logger.info("Set Mapper and Reducer output key-value types")
    job.setMapOutputKeyClass(classOf[Text])
    job.setMapOutputValueClass(classOf[DoubleWritable])
    job.setOutputKeyClass(classOf[Text])
    job.setOutputValueClass(classOf[Text])

    logger.info("Set input and output paths")
    org.apache.hadoop.mapreduce.lib.input.FileInputFormat.addInputPath(job, inputPath)
    org.apache.hadoop.mapreduce.lib.output.FileOutputFormat.setOutputPath(job, outputPath)
    
    logger.info("Submitting the job and waiting for completion")
    if (job.waitForCompletion(true)) {
      logger.info("Job completed successfully!")
      System.exit(1)
    } else {
      logger.info("Job failed!")
      System.exit(0)
    }
  }
}












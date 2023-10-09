package com.lsc

import Utilz.CreateLogger
import com.lsc.SimRankMapReduce.logger
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{DoubleWritable, IntWritable, LongWritable, Text}
import org.apache.hadoop.mapred.{FileInputFormat, FileOutputFormat, JobClient, JobConf}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.{Job, Mapper, Reducer}
import org.slf4j.LoggerFactory
import java.text.DecimalFormat
import scala.collection.JavaConverters.*
import scala.collection.mutable
import org.apache.hadoop.conf.Configuration
import com.typesafe.config.{Config, ConfigFactory}


//The map reduce job to calculate the similarity between the edges
object EdgesSimilarityMapReduce {

  //Logger
  private val logger = CreateLogger(classOf[EdgesSimilarityMapReduce.type])

  logger.info(s"SimRank EdgesSimilarityMapReduce Job Started")
  logger.info(s"Loading application.conf")

  val config: Config = ConfigFactory.load("application.conf")
  private val obj = SimRank

  class MyMapper extends Mapper[LongWritable, Text, Text, DoubleWritable] {

    @throws[Exception]
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
        val calculatedScore = obj.calculateEdgeSimRank(line)

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
      val outputMessage = obj.calculateSimRankWithTracebilityLinksEdge(key, values)
      logger.info("Writing each unique key with its value to a csv file")
      context.write(key, new Text(outputMessage))
    }
  }


  //@main of the program
  def runEdgeSimMR(inputPathE: String, outputPathE: String): Unit = {

    logger.info("Creating a Hadoop configuration for Edges Similraity MapReduce")
    val configuration = new Configuration()
    require(inputPathE.nonEmpty && outputPathE.nonEmpty)
    println(inputPathE)
    println(outputPathE)

    logger.info("Setting the output path")
    val inputPath = new Path(inputPathE)
    val outputPath = new Path(outputPathE)
    val conf: JobConf = new JobConf(this.getClass)
    //This will help to identify the jar file when it is distributed across the cluster
    val job = Job.getInstance(configuration, "EdgesSimilarityMapReduce")

    job.setJobName("EdgesSimilarityMapReduce")
    //job.set("mapreduce.job.maps", "1")
    //job.set("mapreduce.job.reduces", "1")
    job.setOutputKeyClass(classOf[Text])

    logger.info("Set Mapper and Reducer output key-value types")
    job.setMapOutputKeyClass(classOf[Text])
    job.setMapOutputValueClass(classOf[DoubleWritable])
    job.setOutputKeyClass(classOf[Text])
    job.setOutputValueClass(classOf[Text])

    logger.info("Settting Mapper and Reducer classes")
    job.setMapperClass(classOf[MyMapper])
    //job.setCombinerClass(classOf[MyReducer])
    job.setReducerClass(classOf[MyReducer])
    //job.setInputFormat(classOf[org.apache.hadoop.mapred.TextInputFormat])
    //job.setOutputFormat(classOf[org.apache.hadoop.mapred.TextOutputFormat[Text, Text]])
    // Set the input path to the directory containing shard files
    //FileInputFormat.addInputPath(job, inputPath)

    logger.info("Set input and output paths")
    org.apache.hadoop.mapreduce.lib.input.FileInputFormat.addInputPath(job,inputPath)
    org.apache.hadoop.mapreduce.lib.output.FileOutputFormat.setOutputPath(job,outputPath)

    logger.info("Job configurations set. Creating a new job" + job.getJobName)
    //JobClient.runJob(conf)

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





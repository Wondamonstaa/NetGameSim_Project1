package com.lsc

import NetGraphAlgebraDefs.GraphPerturbationAlgebra.ModificationRecord
import NetGraphAlgebraDefs.NetModelAlgebra.{actionType, outputDirectory}
import NetGraphAlgebraDefs.{GraphPerturbationAlgebra, NetGraph, NetModelAlgebra}
import NetModelAnalyzer.Analyzer
import Randomizer.SupplierOfRandomness
import Utilz.NGSConstants.{CONFIGENTRYNAME, config, obtainConfigModule}
import Utilz.{CreateLogger, NGSConstants}
import com.google.common.graph.ValueGraph

import java.util.concurrent.{ThreadLocalRandom, TimeUnit}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import com.typesafe.config.ConfigFactory
import guru.nidi.graphviz.engine.Format
import org.apache.hadoop.fs.Path
import org.slf4j.Logger

import java.net.{InetAddress, NetworkInterface, Socket}
import scala.util.{Failure, Success}
import com.typesafe.config.{Config, ConfigFactory}
import com.lsc.ArgumentParser

object Main:
  val logger:Logger = CreateLogger(classOf[Main.type])
  val ipAddr: InetAddress = InetAddress.getLocalHost
  val hostName: String = ipAddr.getHostName
  val hostAddress: String = ipAddr.getHostAddress

  def main(args: Array[String]): Unit =
    import scala.jdk.CollectionConverters.*
    val outGraphFileName = if args.isEmpty then NGSConstants.OUTPUTFILENAME else args(0).concat(NGSConstants.DEFOUTFILEEXT)
    val perturbedOutGraphFileName = outGraphFileName.concat(".perturbed")
    logger.info(s"Output graph file is $outputDirectory$outGraphFileName and its perturbed counterpart is $outputDirectory$perturbedOutGraphFileName")
    logger.info(s"The netgraphsim program is run at the host $hostName with the following IP addresses:")
    logger.info(ipAddr.getHostAddress)
    NetworkInterface.getNetworkInterfaces.asScala
        .flatMap(_.getInetAddresses.asScala)
        .filterNot(_.getHostAddress == ipAddr.getHostAddress)
        .filterNot(_.getHostAddress == "127.0.0.1")
        .filterNot(_.getHostAddress.contains(":"))
        .map(_.getHostAddress).toList.foreach(a => logger.info(a))

    val existingGraph = java.io.File(s"$outputDirectory$outGraphFileName").exists
    val g: Option[NetGraph] = if existingGraph then
      logger.warn(s"File $outputDirectory$outGraphFileName is located, loading it up. If you want a new generated graph please delete the existing file or change the file name.")
      NetGraph.load(fileName = s"$outputDirectory$outGraphFileName")
    else
      val config = ConfigFactory.load()
      logger.info("for the main entry")
      config.getConfig("NGSimulator").entrySet().forEach(e => logger.info(s"key: ${e.getKey} value: ${e.getValue.unwrapped()}"))
      logger.info("for the NetModel entry")
      config.getConfig("NGSimulator").getConfig("NetModel").entrySet().forEach(e => logger.info(s"key: ${e.getKey} value: ${e.getValue.unwrapped()}"))
      NetModelAlgebra()

    if g.isEmpty then logger.error("Failed to generate a graph. Exiting...")
    else
      logger.info(s"The original graph contains ${g.get.totalNodes} nodes and ${g.get.sm.edges().size()} edges; the configuration parameter specified ${NetModelAlgebra.statesTotal} nodes.")
      if !existingGraph then
        g.get.persist(fileName = outGraphFileName)
        logger.info(s"Generating DOT file for graph with ${g.get.totalNodes} nodes for visualization as $outputDirectory$outGraphFileName.dot")
        g.get.toDotVizFormat(name = s"Net Graph with ${g.get.totalNodes} nodes", dir = outputDirectory, fileName = outGraphFileName, outputImageFormat = Format.DOT)
        logger.info(s"A graph image file can be generated using the following command: sfdp -x -Goverlap=scale -Tpng $outputDirectory$outGraphFileName.dot > $outputDirectory$outGraphFileName.png")
      end if
      logger.info("Perturbing the original graph to create its modified counterpart...")
      val perturbation: GraphPerturbationAlgebra#GraphPerturbationTuple = GraphPerturbationAlgebra(g.get.copy)
      perturbation._1.persist(fileName = perturbedOutGraphFileName)

      logger.info(s"Generating DOT file for graph with ${perturbation._1.totalNodes} nodes for visualization as $outputDirectory$perturbedOutGraphFileName.dot")
      perturbation._1.toDotVizFormat(name = s"Perturbed Net Graph with ${perturbation._1.totalNodes} nodes", dir = outputDirectory, fileName = perturbedOutGraphFileName, outputImageFormat = Format.DOT)
      logger.info(s"A graph image file for the perturbed graph can be generated using the following command: sfdp -x -Goverlap=scale -Tpng $outputDirectory$perturbedOutGraphFileName.dot > $outputDirectory$perturbedOutGraphFileName.png")

      val modifications:ModificationRecord = perturbation._2
      GraphPerturbationAlgebra.persist(modifications, outputDirectory.concat(outGraphFileName.concat(".yaml"))) match
        case Left(value) => logger.error(s"Failed to save modifications in ${outputDirectory.concat(outGraphFileName.concat(".yaml"))} for reason $value")
        case Right(value) =>
          logger.info(s"Diff yaml file ${outputDirectory.concat(outGraphFileName.concat(".yaml"))} contains the delta between the original and the perturbed graphs.")
          logger.info(s"Done! Please check the content of the output directory $outputDirectory")


    logger.info("Please, specify the required arguments for input and output paths: " +
      "name and path of the original graph, name and path of the perturbed graph, " +
      "name and path of the output file for the Node csv file, name and path of the output file for the Edges csv file, " +
      "name and path of the output file for the Node csv file after sharding, name and path of the output file for the Eges csv file after sharding, " +
      "input Node.csv directory  SimRankMapReduce, input Edge.csv directory for EdgesSimilarityMapReduce, and the corresponding output directories for both.")
    logger.info("Creating and object of ArgumentParser class.")
    logger.info("Loading file configurations.")

    import com.typesafe.config.ConfigFactory
    val config: Config = ConfigFactory.load()
    val globalConfig: Config = obtainConfigModule(config, CONFIGENTRYNAME)
    
    val originalGraphFileName = globalConfig.getString("originalGraphFileName")
    val originalGraphPath = globalConfig.getString("originalGraphPath")
    val perturbedGraphFileName = globalConfig.getString("perturbedGraphFileName")
    val perturbedGraphPath = globalConfig.getString("perturbedGraphPath")
    val filePath = globalConfig.getString("nodesCSVOutputDirectory")
    val filePath1 = globalConfig.getString("edgesCSVOutputDirectory")
    val output = globalConfig.getString("nodeShardsOutputDirectory")
    val output1 = globalConfig.getString("edgeShardsOutputDirectory")
    val inputPathS = globalConfig.getString("simRankInputPath")
    val outputPathS = globalConfig.getString("simRankOutputPath")
    val inputPathE = globalConfig.getString("edgeSimInputPath")
    val outputPathE = globalConfig.getString("edgeSimOutputPath")

    //The following array contains the arguments that are passed to the ArgumentParser class
    val argsArray = Array(
      originalGraphFileName,
      originalGraphPath,
      perturbedGraphFileName,
      perturbedGraphPath,
      filePath,
      filePath1,
      output,
      output1,
      inputPathS,
      outputPathS,
      inputPathE,
      outputPathE
    )

    //An object of ArgumentParser class is created
    val argumentParser = new ArgumentParser()
    val parsedArguments = argumentParser.parse(argsArray)

    //Check if the args are valid
    parsedArguments match {
      case Some((originalGraphFileName, originalGraphPath, perturbedGraphFileName, perturbedGraphPath, filePath, filePath1, output, output1, inputPathSimRankMR, outputPathSimRankMR, inputPathEdgeSimMR, outputPathEdgeSimMR)) =>

        logger.info(s"Original Graph File Name: $originalGraphFileName")
        logger.info(s"Original Graph Path: $originalGraphPath")
        logger.info(s"Perturbed Graph File Name: $perturbedGraphFileName")
        logger.info(s"Perturbed Graph Path: $perturbedGraphPath")
        logger.info(s"Nodes CSV Output Directory: $filePath")
        logger.info(s"Edges CSV Output Directory: $filePath1")
        logger.info(s"Node Shards Output Directory: $output")
        logger.info(s"Edge Shards Output Directory: $output1")
        logger.info(s"Input Path for SimRank MapReduce: $inputPathSimRankMR")
        logger.info(s"Output Path for SimRank MapReduce: $outputPathSimRankMR")
        logger.info(s"Input Path for Edge Similarity MapReduce: $inputPathEdgeSimMR")
        logger.info(s"Output Path for Edge Similarity MapReduce: $outputPathEdgeSimMR")

      case None =>

        logger.info("Invalid arguments provided.")
    }



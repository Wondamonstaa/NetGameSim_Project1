package com.lsc

import NetGraphAlgebraDefs.GraphPerturbationAlgebra.ModificationRecord
import NetGraphAlgebraDefs.NetModelAlgebra.{actionType, outputDirectory}
import NetGraphAlgebraDefs.{GraphPerturbationAlgebra, NetGraph, NetModelAlgebra}
import NetModelAnalyzer.Analyzer
import Randomizer.SupplierOfRandomness
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


//The following class is used to parse the args for the main function
class ArgumentParser {

  val logger = CreateLogger(classOf[ArgumentParser])
  logger.info("ArgumentParser is ready to parse the arguments.")

  def parse(args: Array[String]): Option[(String, String, String, String, String, String, String, String, String, String, String, String)] = {
    if (args.length != 12) {
      println("Usage: YourMainObject <originalGraphFileName> <originalGraphPath> <perturbedGraphFileName> <perturbedGraphPath> <nodes.csv output directory> <edges.csv output directory> <Node shards output directory> <Edge shards output directory> <inputPathSimRankMR> <outputPathSimRankMR> <inputPathEdgeSimMR> <outputPathEdgeSimMR>")
      return None
    }
    
    logger.info("Arguments are parsed successfully.")
    val originalGraphFileName = args(0)
    val originalGraphPath = args(1)
    val perturbedGraphFileName = args(2)
    val perturbedGraphPath = args(3)
    val filePath = args(4)
    val filePath1 = args(5)
    val output = args(6)
    val output1 = args(7)
    val inputPathS = args(8)
    val outputPathS = args(9)
    val inputPathE = args(10)
    val outputPathE = args(11)

    logger.info("Attempting to run the implemented functions.")
    import com.lsc.DataConverter._

    // The object of the class responsible for processing the input file
    val sharder = new Exec()

    val originalGraph: Option[NetGraph] = NetGraph.load(originalGraphFileName, originalGraphPath)
    val perturbedGraph: Option[NetGraph] = NetGraph.load(perturbedGraphFileName, perturbedGraphPath)

    logger.info("Running functionality one for sharding")
    
    // Call the processFile method with the graphs as parameters
    sharder.processFile(originalGraph, perturbedGraph, filePath, filePath1, output, output1)

    import com.lsc.SimRankMapReduce.MyMapper
    import com.lsc.SimRankMapReduce.MyReducer
    import com.lsc.EdgesSimilarityMapReduce.MyMapper
    import com.lsc.EdgesSimilarityMapReduce.MyReducer

    logger.info("Running functionality two for SimRank")
    //SimRankMapReduce.runSimRankMR(inputPathS, outputPathS)

    logger.info("Running functionality three for traceability links calculation")
    EdgesSimilarityMapReduce.runEdgeSimMR(inputPathE, outputPathE)

    Some((originalGraphFileName, originalGraphPath, perturbedGraphFileName, perturbedGraphPath, filePath, filePath1, output, output1, inputPathS, outputPathS, inputPathE, outputPathE))
  }
}



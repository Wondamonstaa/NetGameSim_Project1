package com.lsc

import NetGraphAlgebraDefs.GraphPerturbationAlgebra.ModificationRecord
import NetGraphAlgebraDefs.NetModelAlgebra.{actionType, outputDirectory}
import NetGraphAlgebraDefs.{Action, GraphPerturbationAlgebra, NetGraph, NetModelAlgebra, NodeObject}
import NetModelAnalyzer.Analyzer
import Randomizer.SupplierOfRandomness
import Utilz.{CreateLogger, NGSConstants}
import com.google.common.graph.{EndpointPair, ValueGraph}

import java.util.concurrent.{ThreadLocalRandom, TimeUnit}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import com.typesafe.config.ConfigFactory
import guru.nidi.graphviz.engine.Format
import org.slf4j.Logger

import java.net.{InetAddress, NetworkInterface, Socket}
import scala.collection.parallel.ParSeq
import scala.util.{Failure, Random, Success}
import NetGraphAlgebraDefs.NetGraph.*

import scala.collection.JavaConverters.*
import java.io.{BufferedWriter, File, FileWriter, PrintWriter}
import java.nio.file.{Files, Paths, StandardCopyOption}
import scala.collection.mutable.ListBuffer
import scala.io.Source
import java.io.{BufferedWriter, FileWriter}

object DataConverter {

  def main(args: Array[String]): Unit = {

    val dataConverter = new Exec()
    //dataConverter.processFile()

    //val originalGraph: Option[NetGraph] = NetGraph.load(outGraphFileName, "$outputDirectory$outGraphFileName")
    //val perturbedGraph: Option[NetGraph] = NetGraph.load(perturbedOutGraphFileName, "$outputDirectory$perturbedOutGraphFileName")
    //dataConverter.processFile(originalGraph, perturbedGraph)
  }

  extension (m: Array[Float])
    def toCsv: String =
      val sb = new StringBuilder
      m.foreach(cell => if cell != Float.PositiveInfinity then sb.append(f"$cell%.3f").append(",") else sb.append("-").append(","))
      sb.toString
    end toCsv

  class Exec {

    val logger: Logger = CreateLogger(classOf[Exec])
    logger.info(s"Attempting to load the graph object into the DataConverter")


    def generateCombinationsAndOutput(
                                       originalDirectory: String,
                                       perturbedDirectory: String,
                                       outputDirectory: String
                                     ): Unit = {
      val originalFiles = listFilesInDirectory(originalDirectory)
      val perturbedFiles = listFilesInDirectory(perturbedDirectory)

      val combinations = generateCombinations(originalFiles, perturbedFiles)

      // Create the output directory if it doesn't exist
      val outputDir = new File(outputDirectory)
      if (!outputDir.exists()) {
        outputDir.mkdirs()
      }

      // Iterate through combinations and copy files to the output directory
      combinations.foreach { combination =>
        val originalFileName = combination._1
        val perturbedFileName = combination._2
        val combinedFileName = combination._3

        val originalFile = new File(s"$originalDirectory/$originalFileName")
        val perturbedFile = new File(s"$perturbedDirectory/$perturbedFileName")
        val combinedFile = new File(s"$outputDirectory/$combinedFileName")

        // Copy original and perturbed files to the output directory with the combined name
        Files.copy(originalFile.toPath, combinedFile.toPath, StandardCopyOption.REPLACE_EXISTING)
        Files.copy(perturbedFile.toPath, combinedFile.toPath, StandardCopyOption.REPLACE_EXISTING)
      }
    }


    def generateCombinations(
                              originalFiles: List[String],
                              perturbedFiles: List[String]
                            ): List[(String, String, String)] = {
      val combinations = ListBuffer[(String, String, String)]()

      for {
        originalPiece <- originalFiles
        perturbedPiece <- perturbedFiles
      } yield {
        val combinedFileName = s"$originalPiece-$perturbedPiece"
        combinations += ((originalPiece, perturbedPiece, combinedFileName))
      }

      combinations.toList
    }

    // Add this function to list files in a directory
    def listFilesInDirectory(directory: String): List[String] = {
      val dir = new java.io.File(directory)
      if (dir.exists && dir.isDirectory) {
        dir.listFiles.filter(_.isFile).map(_.getName).toList
      } else {
        Nil
      }
    }

    //The file processor => reads the data from the file and stores it in a list
    def processFile(
                     originalGraphLoad: Option[NetGraph],
                     perturbedGraphLoad: Option[NetGraph],
                     filePath: String, // result.csv file path
                     filePath1: String, // edges.csv file path
                     output: String, // Output directory path for sharding
                     output1: String // Output1 directory path for sharding
                   ): Unit = {
      
      val originalGraph = originalGraphLoad
      val perturbedGraph = perturbedGraphLoad

      logger.info("Collecting the information about nodes and edges of the graphs.")
      val netOriginalGraph: NetGraph = originalGraph.get
      val netPerturbedGraph: NetGraph = perturbedGraph.get

      logger.info("Saving the nodes of the original graph.")
      val originalGraphNodes: java.util.Set[NodeObject] = netOriginalGraph.sm.nodes()
      val originalGraphEdges: java.util.Set[EndpointPair[NodeObject]] = netOriginalGraph.sm.edges()

      logger.info("Saving the nodes of the perturbed graph.")
      val perturbedGraphNodes: java.util.Set[NodeObject] = netPerturbedGraph.sm.nodes()
      val perturbedGraphEdges: java.util.Set[EndpointPair[NodeObject]] = netPerturbedGraph.sm.edges()

      //Define output directory for original and perturbed graphs
      (originalGraph, perturbedGraph) match {
        case (Some(originalNetGraph), Some(perturbedNetGraph)) =>
          logger.info("Original and perturbed graphs loaded successfully.")

          // Define output directory for original and perturbed graphs
          val outputDirectory = "/Users/wondamonsta/Desktop/UIC/2023/Fall2023/CS441/CSV/"

          processFilesToCsv(filePath, originalGraphNodes, perturbedGraphNodes)
          processEdgesToCsv(filePath1, originalGraphEdges, perturbedGraphEdges, netOriginalGraph)

          sharder(filePath, output, 35)
          sharder(filePath1, output1, 35)

          logger.info(s"CSV files generated successfully in directory: $output")

        case _ =>
          logger.info("Failed to load one or both of the graphs.")
      }
    }


    //The following function is used to shard the graphs and store the shards in the specified directory
    def processFilesToCsv(filePath: String, originalNodes: java.util.Set[NodeObject], perturbedNodes: java.util.Set[NodeObject]): Unit = {

      //A buffered writer to write to the CSV file
      val writer = new BufferedWriter(new FileWriter(filePath))

      //Write the header of the CSV file
      writer.write("OriginalNodeID, oChildren, oProps, oCurrentDepth, oPropValueRange," +
        "oMaxDepth, oMaxBranchingFactor, oMaxProperties, oStoredValue," +
        "PerturbedNodeID, pChildren, pProps, pCurrentDepth,pPropValueRange," +
        "pMaxDepth, pMaxBranchingFactor, pMaxProperties, pStoredValue\n")

      //A stream of originalNodes + perturbedNodes
      val originalStream = originalNodes.stream().iterator().asScala.toStream
      val perturbedStream = perturbedNodes.stream().iterator().asScala.toStream

      //Here I generate combinations of original and perturbed nodes using flatMap
      val nodeCombinations = originalStream.flatMap { originalNode =>
        perturbedStream.map { perturbedNode =>
          val perturbedNodeId = if (perturbedNode != null) perturbedNode.id.toString else ""

          //Concatenation all values into a single string
          s"${originalNode.id},${originalNode.children},${originalNode.props},${originalNode.currentDepth},${originalNode.propValueRange}," +
            s"${originalNode.maxDepth},${originalNode.maxBranchingFactor},${originalNode.maxProperties},${originalNode.storedValue}," +
            s"${perturbedNodeId},${perturbedNode.children},${perturbedNode.props},${perturbedNode.currentDepth},${perturbedNode.propValueRange}," +
            s"${perturbedNode.maxDepth},${perturbedNode.maxBranchingFactor},${perturbedNode.maxProperties},${perturbedNode.storedValue}"
        }
      }

      // Write the node combinations to the file
      nodeCombinations.foreach { line =>
        writer.write(line + "\n")
      }

      writer.close()
    }


    // Function to process and store edge data in a CSV file
    def processEdgesToCsv(filePath: String,
                          originalEdges: java.util.Set[EndpointPair[NodeObject]],
                          perturbedEdges: java.util.Set[EndpointPair[NodeObject]],
                          netGraph: NetGraph): Unit = {
      // A buffered writer to write to the CSV file
      val writer = new BufferedWriter(new FileWriter(filePath))

      // Write the header of the CSV file for edges
      writer.write("oSource, oTarget, oWeight, pSource, pTarget, pWeight\n")

      // Create a stream of original and perturbed edges
      val originalStream = originalEdges.asScala.toStream
      val perturbedStream = perturbedEdges.asScala.toStream

      // Generate combinations of original and perturbed edges using flatMap
      val edgeCombinations = originalStream.flatMap { originalEdge =>
        perturbedStream.map { perturbedEdge =>
          val edgeSource = originalEdge.source().id.toString
          val edgeTarget = originalEdge.target().id.toString
          val edgeCost = calculateEdgeCost(netGraph, originalEdge)

          val perturbedEdgeSource = perturbedEdge.source().id.toString
          val perturbedEdgeTarget = perturbedEdge.target().id.toString
          val perturbedEdgeCost = calculateEdgeCost(netGraph, perturbedEdge)

          // Create a row string for the edge combination
          s"$edgeSource, $edgeTarget, $edgeCost, $perturbedEdgeSource, $perturbedEdgeTarget, $perturbedEdgeCost"
        }
      }

      // Write the edge combinations to the file
      edgeCombinations.foreach { line =>
        writer.write(line + "\n")
      }

      writer.close()
    }

    // Function to calculate the cost of an edge
    def calculateEdgeCost(netGraph: NetGraph, edge: EndpointPair[NodeObject]): Float = {
      
      //Obtain the source and target nodes of the edge to calculate the cost
      val edgeCostOptional = netGraph.sm.edgeValue(edge.source(), edge.target())
      if (edgeCostOptional.isPresent) {
        
        //Return the cost of the edge if it exists
        edgeCostOptional.get().cost.toFloat
      } 
      else {
        Float.PositiveInfinity
      }
    }


    //Helper function to shard data from an input file into multiple shards of the specified size
    def sharder(input: String, output: String, size: Int): Unit = {

      // Open the input file for reading
      val source = Source.fromFile(input)

      logger.info(s"Sharding file: $input")
      // Read all lines from the input file and store them as a list of strings
      val lines = source.getLines().toList

      // Close the input file
      source.close()

      // Extract the header line (the first line) from the list of lines
      val header = lines.head

      // Extract the data lines (excluding the header) from the list of lines
      val data = lines.tail

      // Split the data into chunks of the specified size
      val chunkedData = data.grouped(size).toList

      // Use flatMap to flatten the chunks and zipWithIndex to get the index of each chunk
      val flattenedData = chunkedData.zipWithIndex.flatMap { case (chunk, index) =>

        // Create the output file path for the current chunk
        val outputFilePath = s"$output/shard$index.csv"

        logger.info(s"Writing shard $index to file: $outputFilePath")
        val writer = new PrintWriter(new File(outputFilePath))

        // Write the header line to the output file
        writer.println(header)

        //Via Iterator's mkString I join the lines in the chunk with line breaks and write it to the output file
        val flattenedChunk = chunk.mkString("\n")

        logger.info(s"Writing shard $index to file: $outputFilePath")
        writer.println(flattenedChunk)

        logger.info(s"Closing file: $outputFilePath")
        
        //Close the output file
        writer.close()

        Seq(flattenedChunk)
      }
    }


    // The following function allows to extract the data from the graph and save it to CSV files
    def extractDataToCsv(netGraph: NetGraph, outputDirectory: String, prefix: String): Unit = {

      // Extract the nodes from the graph
      logger.info(s"Attempting to access the nodes of the graph")
      val nodes: java.util.Set[NodeObject] = netGraph.sm.nodes()
      val nodeList = nodes.asScala.toList

      // Get the edges from the graph
      val edges: java.util.Set[EndpointPair[NodeObject]] = netGraph.sm.edges()

      // Shard the entire list of nodes into groups of 10 nodes each
      val groupedNodes = nodeList.grouped(10).toList
      val groupedEdges = edges.asScala.toList.grouped(10).toList

      //The names of the columns for CSV format
      val nodeColumnNames = Array(
        "ID",
        "Children",
        "Props",
        "CurrentDepth",
        "PropValueRange",
        "MaxDepth",
        "MaxBranchingFactor",
        "MaxProperties",
        "StoredValue"
      )

      //Storage for the edges
      val edgeColumnNames = Array(
        //"EdgeID",
        "EdgeSource",
        "EdgeTarget",
        "EdgeCost"
      )

      // Using zipWithIndex, we can generate shard indices
      groupedNodes.zipWithIndex.foreach { case (group, index) =>
        val outputFileName = s"$outputDirectory/$prefix/nodes_group_$index.csv"
        val writer = new PrintWriter(outputFileName)

        try {

          // Prints the header of each column for nodes separately
          logger.info(s"Writing node CSV data to $outputFileName")

          try {
            // Iterate through each node in the group via using flatMap to convert each node to a row string for nodes
            group.flatMap { node =>
              val children = netGraph.sm.successors(node).asScala.map(child => child.id).mkString(",") //Extract children IDs
              val rowData = Array(
                node.id.toFloat,
                node.children.toFloat,
                node.props.toFloat,
                node.currentDepth.toFloat,
                node.propValueRange.toFloat,
                node.maxDepth.toFloat,
                node.maxBranchingFactor.toFloat,
                node.maxProperties.toFloat,
                node.storedValue.toFloat
              )
              // Convert the data to a row string for nodes
              val rowString = rowData.map(cell => if (cell != Float.PositiveInfinity) f"$cell%.3f" else "-").mkString(",")
              Seq(rowString)
            }.foreach(writer.println) // Save each row to the node file
          } finally {

            logger.info(s"Closing the node writer for $outputFileName")
            writer.close()
            logger.info("Shuffling the nodes")
            //generateCombinationsAndOutput(s"$outputDirectory/$prefix", s"$outputDirectory/$prefix", s"/Users/wondamonsta/Desktop/UIC/2023/Fall2023/CS441/CSV/TEST")
          }

          logger.info(s"Node CSV data for shard $index successfully saved to $outputFileName")

          // Create a separate CSV file for edges
          val edgeOutputFileName = s"$outputDirectory/$prefix/edges_group_$index.csv"
          val edgeWriter = new PrintWriter(edgeOutputFileName)

          try {
            //Prints the header of each column for edges
            val edgeHeaderString = edgeColumnNames.mkString(",")
            edgeWriter.println(edgeHeaderString)

            logger.info(s"Writing edge CSV data to $edgeOutputFileName")

            // Iterate through each edge in the group via using flatMap
            groupedEdges(index).foreach { edge =>

              //val edgeID = edge.hashCode() // Generate a unique ID for the edge
              val edgeSource = edge.source().id.toFloat
              val edgeTarget = edge.target().id.toFloat

              // Use Java Optional methods to handle edge cost
              val edgeCostOptional = netGraph.sm.edgeValue(edge.source(), edge.target())
              val edgeCost = if (edgeCostOptional.isPresent) {
                edgeCostOptional.get().cost.toFloat
              }
              else {
                Float.PositiveInfinity
              }

              val edgeRowData = Array(
                //edgeID.toFloat,
                edgeSource,
                edgeTarget,
                edgeCost
              )
              //The data will be converted to a row string for edges using map function
              val edgeRowString = edgeRowData.map(cell => if (cell != Float.PositiveInfinity) f"$cell%.3f" else "-").mkString(",")
              edgeWriter.println(edgeRowString) // Save each row to the edge file
            }
          } 
          finally {
            logger.info(s"Closing the edge writer for $edgeOutputFileName")
            edgeWriter.close()
            logger.info("Shuffling the edges")
            //generateCombinationsAndOutput(s"$outputDirectory/$prefix", s"$outputDirectory/$prefix", s"/Users/wondamonsta/Desktop/UIC/2023/Fall2023/CS441/CSV/TEST")
          }

          logger.info(s"Edge CSV data for shard $index successfully saved to $edgeOutputFileName")
        }
      }
    }
  }
}

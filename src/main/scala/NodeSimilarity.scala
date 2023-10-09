package com.lsc

import scala.io.Source
import java.io.{File, PrintWriter}
import Utilz.CreateLogger
import guru.nidi.graphviz.model.{Node, Graph as DotGraph}
import NetGraphAlgebraDefs._
import org.slf4j.Logger
import scala.annotation.tailrec
import scala.collection.JavaConverters._
import com.google.common.graph.EndpointPair._
import com.google.common.graph.{EndpointPair, ValueGraph}
import NetGraphAlgebraDefs._
import org.apache.hadoop.fs.Path
import org.apache.hadoop.conf.*
import org.apache.hadoop.io.*
import org.apache.hadoop.util.*
import org.apache.hadoop.mapred.*
import org.apache.hadoop.fs.{FileSystem, FileStatus, Path}
import org.apache.hadoop.conf.Configuration


object NodeSimilarity {

  //Tracebility links variables
  var ATL: Double = 0.0 // Number of correctly accepted TLs
  var CTL: Double = 0.0 // Number of correct TLs mistakenly discarded
  var DTL: Double = 0.0 // Number of correctly discarded TLs
  var WTL: Double = 0.0 // Number of wrong TLs accepted

  //Precision + recall variables
  var TP: Double = 0.0 //True positive
  var FP: Double = 0.0 //False positive
  var FN: Double = 0.0 //False negative

  val logger: Logger = CreateLogger(classOf[NodeSimilarity.type])
  logger.info(s"Starting SimRank - loading configs")


  def main(args: Array[String]): Unit = {

    computeAndWriteSimilarities()
    //computeAndWriteSimilarities("nodes_group_0.ngs", "nodes_group_0.ngs" )
  }

  //The following function computes the similarity between 2 nodes and writes it to the output file
  def computeAndWriteSimilarities(): Unit = {

    /*
    1. SimRank takes 2 nodes: 1 node from Original and 1 node from Perturbed graph from CSV files
    2. Using the property values of the nodes, we compute the similarity between the nodes
    3. We write the similarity to a CSV file => reducer's job
    4. We repeat this process for all nodes in the Original graphx
    5. We repeat this process for all nodes in the Perturbed graph
    */

    // The following variables hold the paths to the CSV files
    val originalDirectoryPath = "/Users/wondamonsta/Desktop/UIC/2023/Fall2023/CS441/CSV/original"
    val perturbedDirectoryPath = "/Users/wondamonsta/Desktop/UIC/2023/Fall2023/CS441/CSV/perturbed"
    val outputDirectory = "/Users/wondamonsta/Desktop/UIC/2023/Fall2023/CS441/CSV/Similarities"

    val outputCSVFilePath = new File(outputDirectory, "similarities.csv")
    val outputCSVFile = new PrintWriter(outputCSVFilePath)

    //The following configuration is used to access the HDFS and all the files in the folder specified
    val conf = new Configuration()
    val fs = FileSystem.get(conf)

    val originalDirectory = new Path(originalDirectoryPath)
    val perturbedDirectory = new Path(perturbedDirectoryPath)

    // Get a list of files in the original directory
    val originalFiles: Array[FileStatus] = fs.listStatus(originalDirectory)
    // Get a list of files in the perturbed directory
    val perturbedFiles: Array[FileStatus] = fs.listStatus(perturbedDirectory)
    
    // Load the original and perturbed graphs => .ngs
    val originalGraph: Option[NetGraph] = NetGraph.load("NetGameSimnetgraph.ngs", "/Users/wondamonsta/Desktop/UIC/2023/Fall2023/CS441/Shards/")
    val perturbedGraph: Option[NetGraph] = NetGraph.load("NetGameSimnetgraph.ngs.perturbed", "/Users/wondamonsta/Desktop/UIC/2023/Fall2023/CS441/Shards/")
    
    (originalGraph, perturbedGraph) match {
      case (Some(originalNetGraph), Some(perturbedNetGraph)) =>
        try {
          // Open the output CSV file
          outputCSVFile.write("OriginalNodeID, PerturbedNodeID, Similarity Ratio, ATL, CTL, DTL, WTL, RTL, ACC, VPR, BTLR, Precision Ratio, Recall Ratio")

          // The list of original node objects
          val originalNodes: List[NodeObject] = originalNetGraph.sm.nodes().asScala.toList

          // Start the recursive function to extract data and compute similarities for all nodes in both graphs
          extractDataToCsvAndComputeSimilarities(
            originalNetGraph,
            perturbedNetGraph,
            outputDirectory,
            originalNodes,
            outputCSVFile
          )

          // Close the output CSV file
          outputCSVFile.close()
        }
        catch {
          // In case there is a problem with the output CSV file
          case e: Exception =>
            logger.error(e.getMessage)
            // Close the output file in case of an error
            outputCSVFile.close()
        }
      // In case one or both of the graphs failed to load
      case _ =>
        logger.error("Failed to load one or both of the graphs.")
    }
  }

    // Helper function to compute the similarity between 2 nodes
  @tailrec
  def extractDataToCsvAndComputeSimilarities(
                                              netGraph: NetGraph,
                                              netGraphPerturbed: NetGraph,
                                              outputDirectory: String,
                                              originalNodes: List[NodeObject],
                                              outputCSVFile: PrintWriter
                                            ): Unit = {

    // Check if we have reached the end of nodes in both graphs
    if (originalNodes.isEmpty || netGraphPerturbed.sm.nodes().isEmpty) {
      // We have processed all nodes in the original graph or the perturbed graph, so stop here
      outputCSVFile.close()
    }
    else {
      val originalNode = originalNodes.head
      val perturbedNodes = netGraphPerturbed.sm.nodes().asScala.toList
      val originalEdges = netGraph.sm.incidentEdges(originalNode).asScala.toList
      val perturbedEdges = perturbedNodes.flatMap(netGraphPerturbed.sm.incidentEdges(_).asScala.toList)

      //The following code allows to check how many nodes were discarded from the original graph
      val orig = netGraph.sm.nodes
      val pert = netGraphPerturbed.sm.nodes

      //Using the above 2 variables, we can find the discarded nodes by subtracting the perturbed nodes from the original nodes, and then converting the result to a list
      val discardedNodes = orig.asScala.filterNot(node => pert.contains(node)).toList

      // Use 'map' to calculate similarity scores for each discarded node and collect the results in a list
      val similarityScores = discardedNodes.map { disc =>

        // Call the simRank function to calculate the similarity score
        val similarityScore = simRank(originalNode, disc)

        similarityScore
      }

      // Use 'foreach' to update CTL and DTL based on similarity scores
      similarityScores.foreach { similarityScore =>
        if (similarityScore >= 0.3) {

          //Mistakenly discarded => CTL update
          NodeSimilarity.CTL += 1
          NodeSimilarity.FN += 1
        }
        else if (similarityScore <= 0.4 && similarityScore >= 0.06) {
          // Cannot determine if the node is correctly discarded or not, so no increment here
          NodeSimilarity.CTL = CTL
        }
        else {
          // This means the node is correctly discarded, so increment DTL => this is the case when the similarity ratio is small enough to state that the node is correctly discarded
          NodeSimilarity.DTL += 1
        }
      }

      // Iterate through all combinations of nodes in the original and perturbed graphs
      for (originalNode <- originalNodes) {
        for (perturbedNode <- perturbedNodes) {

          // Calculate the similarity score between the nodes using your 'simRank' function
          val similarityScore = simRank(originalNode, perturbedNode)

          // Check if the similarity score is above the threshold
          if (similarityScore >= 0.5) {

            //This means the TL is correctly accepted, so increment ATL
            NodeSimilarity.ATL += 1

            //True positive if the TL was correctly accepted
            NodeSimilarity.TP += 1
          }
          if (similarityScore < 0.5 && similarityScore >= 0.03) {
            //Stays the same
            NodeSimilarity.ATL = NodeSimilarity.ATL
          }
          if (similarityScore < 0.03) {

            // This means the TL is wrongly accepted, so increment WTL
            NodeSimilarity.WTL += 1

            //False positive if the TL was wrongly accepted
            NodeSimilarity.FP += 1
          }
        }
      }

      //Process the current original node with all perturbed nodes
      perturbedNodes.foreach { perturbedNode =>
        processNodePair(originalNode, perturbedNode, originalEdges, perturbedEdges, outputCSVFile)
      }

      // Remove the processed original node
      val updatedOriginalNodes = originalNodes.tail

      // Continue processing the next original nodes
      extractDataToCsvAndComputeSimilarities(
        netGraph,
        netGraphPerturbed,
        outputDirectory,
        updatedOriginalNodes,
        outputCSVFile
      )
    }
  }


  // Helper function to produce a pair of similar nodes based on the similarity coefficient
  def processNodePair(
                       originalNode: NodeObject,
                       perturbedNode: NodeObject,
                       originalEdges: List[EndpointPair[NodeObject]],
                       perturbedEdges: List[EndpointPair[NodeObject]],
                       outputCSVFile: PrintWriter
                     ): Unit = {


    // Compute similarity between original and perturbed nodes
    val similarity = computePairSimilarity(originalNode, perturbedNode, originalEdges, perturbedEdges)

    // The statistics about traceability links
    val GTL: Double = DTL + ATL // Total number of good traceability links
    val BTL: Double = CTL + WTL // Total number of bad traceability links
    val RTL: Double = GTL + BTL // Total number of traceability links
    val ACC: Double = NodeSimilarity.ATL / RTL
    var VPR: Double = (NodeSimilarity.ATL - NodeSimilarity.WTL) / (RTL * 2.0) + 0.5
    val BTLR: Double = NodeSimilarity.WTL / RTL

    // Calculate the VPR based on the description
    if (GTL == RTL && BTL == 0) {
      // All recovered TLs are good, so VPR is 1.0
      VPR = 1.0
    } else if (BTL == RTL && GTL == 0) {
      // All recovered TLs are bad, so VPR is 0.0
      VPR = 0.0
    }

    //Precision and recall ration calculation in %
    val precision: Double = NodeSimilarity.TP / (NodeSimilarity.TP + NodeSimilarity.FP) * 100
    val recall: Double = NodeSimilarity.TP / (NodeSimilarity.TP + NodeSimilarity.FN) * 100

    outputCSVFile.println(s"${originalNode.id.toString},${perturbedNode.id.toString},${similarity.toString},$ATL,$CTL,$DTL,$WTL,$RTL,$ACC,$VPR,$BTLR, $precision%, $recall%")
  }
  
  
  // Helper function to compute the similarity between a node pair
  def computePairSimilarity(
                             originalNode: NodeObject,
                             perturbedNode: NodeObject,
                             originalEdges: List[EndpointPair[NodeObject]],
                             perturbedEdges: List[EndpointPair[NodeObject]]
                           ): Double = {
    val nodeSimilarity = simRank(originalNode, perturbedNode)
    val edgeSimilarity = computeEdgeSimilarity(originalEdges, perturbedEdges)

    // Combine node and edge similarity (you can adjust weights as needed)
    //val combinedSimilarity = (nodeSimilarity) + (edgeSimilarity)

    nodeSimilarity
  }


  //The SimRank function to compare the properties of the two nodes and return a similarity score
  def simRank(originalNode: NodeObject, perturbedNode: NodeObject): Double = {

    // Check if properties have changed (PropValueRamge, MaxBranchingFactor, StoredValue)
    val propertiesChanged = List(
      originalNode.propValueRange != perturbedNode.propValueRange,
      originalNode.maxBranchingFactor != perturbedNode.maxBranchingFactor,
      // For example, 0.897/0.567 ~ 1.56 => if the ratio is greater than 1.56, then the values are not similar since they are not close to each other. Just approximate.
      (originalNode.storedValue / perturbedNode.storedValue) <= 1.56,
      originalNode.maxDepth != perturbedNode.maxDepth,
      originalNode.children != perturbedNode.children,
      originalNode.currentDepth != perturbedNode.currentDepth,
      originalNode.props != perturbedNode.props,
      originalNode.maxProperties != perturbedNode.maxProperties
    )

    // Define the rewards for each property match
    val rewards = List(
      0.15, //propValueRange match
      0.10, //maxBranchingFactor match
      0.17, //storedValue match
      0.08, //maxDepth match
      0.08, //children match
      0.02, //currentDepth match
      0.20, //props match
      0.10 //maxProperties match
    )

    // Calculate the total number of changed properties
    val numChangedProperties = propertiesChanged.count(_ == true)

    // Calculate the total reward for property matches using foldLeft
    val totalReward = propertiesChanged.zip(rewards).foldLeft(0.0) { case (acc, (propertyChanged, reward)) =>
      if (!propertyChanged) acc + reward else acc
    }

    // Define the threshold for similarity
    val similarityThreshold = 0.9

    // Calculate the total similarity score based on the total reward and the threshold
    val similarityScore = totalReward / (rewards.sum)

    // If the total reward is zero or the similarity score is below the threshold, return 0.0
    if(totalReward == 0) return 0.0

    //If the similarity score is above the threshold, I return the similarity score + 1.0 to indicate that the nodes are similar
    if((similarityScore/similarityThreshold) >= 0.8) return (similarityScore/similarityThreshold).toDouble

    //If the similarity score is in the range between 0.4 and 0.8, then there is a possibility of a match, but not 100%, so I return the similarity score + 0.5
    if(((similarityScore/similarityThreshold) < 0.8) && ((similarityScore/similarityThreshold) > 0.4)) return (similarityScore/similarityThreshold).toDouble

    //If the similarity score is below 0.4, then I return the similarity score - 1.0 to indicate that the nodes are somewhat similar, but not enough to be considered a match
    if((similarityScore/similarityThreshold) < 0.4) return (similarityScore/similarityThreshold).toDouble

    similarityScore
  }


  //Helper function to compute the similarity between two lists of edges
  def computeEdgeSimilarity(originalEdges: List[EndpointPair[NodeObject]], perturbedEdges: List[EndpointPair[NodeObject]]): Double = {

    //Convert the lists of edges to sets for easy intersection and union operations
    val originalEdgeSet = originalEdges.toSet
    val perturbedEdgeSet = perturbedEdges.toSet

    //Intersection will tell us how many edges are common between the two sets
    val intersection = originalEdgeSet.intersect(perturbedEdgeSet)

    //Union will point out how many edges are in total between the two sets
    val union = originalEdgeSet.union(perturbedEdgeSet)

    //Similarity coefficient between the edges
    val similarity = if (union.isEmpty) 0.0 else intersection.size.toDouble / union.size.toDouble

    similarity
  }
}
package com.lsc

import scala.io.Source
import java.io.{File, PrintWriter}
import Utilz.CreateLogger
import guru.nidi.graphviz.model.{Node, Graph as DotGraph}
import NetGraphAlgebraDefs.*
import org.slf4j.Logger

import scala.annotation.tailrec
import scala.collection.JavaConverters.*
import com.google.common.graph.EndpointPair.*
import com.google.common.graph.{EndpointPair, ValueGraph}
import NetGraphAlgebraDefs.*
import com.lsc.SimRankMapReduce.logger
import org.apache.hadoop.fs.Path
import org.apache.hadoop.conf.*
import org.apache.hadoop.io.*
import org.apache.hadoop.util.*
import org.apache.hadoop.mapred.*
import org.apache.hadoop.fs.{FileStatus, FileSystem, Path}
import org.apache.hadoop.conf.Configuration

import java.text.DecimalFormat

object SimRank {

  //The following case class is used to store the properties of the nodes
  case class NodeProperties(
                             children: Double,
                             props: Double,
                             currentDepth: Double,
                             propValueRange: Double,
                             maxDepth: Double,
                             maxBranchingFactor: Double,
                             maxProperties: Double,
                             storedValue: Double
                           )

//The following case class is used to store the properties of the edges
  case class EdgeProperties(
                             sourceNode: String, // The source node identifier of the edge
                             targetNode: String, // The target node identifier of the edge
                             weight: Double, // The weight or similarity measure of the edge
                           )


  //Tracebility links variables
  var ATL: Double = 0.0 // Number of correctly accepted TLs
  var CTL: Double = 0.0 // Number of correct TLs mistakenly discarded
  var DTL: Double = 0.0 // Number of correctly discarded TLs
  var WTL: Double = 0.0 // Number of wrong TLs accepted

  //Precision + recall variables
  var TP: Double = 0.0 //True positive
  var FP: Double = 0.0 //False positive
  var FN: Double = 0.0 //False negative
  val TN: Double = 0.0 //True negative

  val logger: Logger = CreateLogger(classOf[SimRank.type])
  logger.info(s"Starting SimRank - loading configs")

  def main(args: Array[String]): Unit = {

  }

  //Allows to compare properties with a threshold
  def comparator(original: Double, perturbed: Double, thresholdPercentage: Double): Boolean = {
    val threshold = original * (thresholdPercentage / 100.0)
    Math.abs(original - perturbed) <= threshold
  }

  // Function to calculate F1-score
  def calculateF1Score(tp: Double, fp: Double, fn: Double): Double = {
    val precision = tp / (tp + fp)
    val recall = tp / (tp + fn)
    if (precision + recall == 0) 0.0
    else 2.0 * (precision * recall) / (precision + recall)
  }

  // Function to calculate specificity
  def calculateSpecificity(tn: Double, fp: Double): Double = {
    tn / (tn + fp)
  }

  def calculateEdgeSimRank(csvLine: String): Double = {

    logger.info(s"Calculating Edge SimRank for $csvLine")
    println(csvLine)

    logger.info("Splitting CSV line")
    val fields = csvLine.split(",")

    var score: Double = 0.0

    // Properties of the original edge
    val originalEdgeProps = EdgeProperties(
      sourceNode = fields(0).trim,
      targetNode = fields(1).trim,
      weight = fields(2).trim.toDouble, // Assuming the weight is the similarity measure for edges
    )

    // Properties of the perturbed edge
    val perturbedEdgeProps = EdgeProperties(
      sourceNode = fields(3).trim,
      targetNode = fields(4).trim,
      weight = fields(5).trim.toDouble, // Assuming the weight is the similarity measure for edges
    )

    // Check if properties have changed (you can customize these thresholds)
    val propertiesChanged = List(
      !comparator(originalEdgeProps.weight, perturbedEdgeProps.weight, 5.75), // 5.75% threshold for weight
    )

    // Define the rewards for each property match
    val rewards = List(
      0.9, // Weight match
    )

    // Calculate the total number of changed properties
    val numChangedProperties = propertiesChanged.count(_ == true)

    logger.info(s"Number of changed properties: $numChangedProperties")
    val totalReward = propertiesChanged.zip(rewards).foldLeft(0.0) { case (acc, (propertyChanged, reward)) =>
      if (!propertyChanged) acc + reward else acc
    }

    // Define the threshold for similarity
    val similarityThreshold = 0.9

    // Calculate the total similarity score based on the total reward and the threshold
    val similarityScore = totalReward / rewards.sum

    // Adjust the similarity score based on the threshold
    val adjustedScore = if (totalReward > 0) {
      val normalizedScore = similarityScore / similarityThreshold
      normalizedScore
    }
    else {
      0.0
    }

    val df = new DecimalFormat("#.##")

    // Return the adjusted score
    df.format(adjustedScore).toDouble
  }

  def calculateSimRankWithTracebilityLinksEdge(key: Text, values: java.lang.Iterable[DoubleWritable]): String = {

    logger.info(s"Calculating the number of traceability links and ratios for $values")

    val similarityScore = values.asScala.map(_.get())

    // Define thresholds
    val originalThreshold = 0.41
    val newThreshold = 0.3

    // Define thresholds for traceability links
    val correctThreshold = 0.36
    val mistakenDiscardThreshold = 0.31
    val wrongAcceptThreshold = 0.15

    // Calculate ATL, TP, WTL, FP, TN using functional operations
    val (atl, tp, wtl, fp, tn) = similarityScore
      .map { score =>
        if (score >= originalThreshold) (1, 1, 0, 1, 0)
        else if (score >= newThreshold) (0, 1, 0, 0, 0)
        else (0, 0, 1, 0, 1)
      }
      .foldLeft((0, 0, 0, 0, 0)) { case ((atl1, tp1, wtl1, fp1, tn1), (atl2, tp2, wtl2, fp2, tn2)) =>
        (atl1 + atl2, tp1 + tp2, wtl1 + wtl2, fp1 + fp2, tn1 + tn2)
      }

    // Thresholds for Modification Detection
    val above = similarityScore.count(_ > originalThreshold)
    val even = similarityScore.count(_ == originalThreshold)
    val below = similarityScore.count(_ < newThreshold)

    val CTL = similarityScore.count(_ > mistakenDiscardThreshold)
    val WTL = similarityScore.count(_ < wrongAcceptThreshold)
    val DTL = similarityScore.count(_ < correctThreshold)
    val ATL = even + above

    // The statistics about traceability links
    val GTL: Double = DTL + ATL
    val BTL: Double = CTL + WTL
    val RTL: Double = GTL + BTL
    val ACC: Double = if (RTL != 0) atl.toDouble / RTL else 0.0
    var VPR: Double = (atl - wtl) / (RTL * 2.0) + 0.5
    val BTLR: Double = if (RTL != 0) wtl.toDouble / RTL else 0.0

    // Calculate the VPR based on the description
    if (GTL == RTL && BTL == 0) {
      VPR = 1.0
    }
    else if (BTL == RTL && GTL == 0) {
      VPR = 0.0
    }

    val info = if (even == 0) {
      s"Edge $key: Traceability Links"
    } else if (above > 0) {
      s"Edge $key: Traceability Links"
    } else {
      s"Edge $key: Traceability Links"
    }

    logger.info("Outputting Information to a Csv File")
    val outputMessage = s"\n$info \nBTL: $BTL \nGTL: $GTL \nRTL: $RTL \nWTL: $WTL \nDTL: $DTL\nBTLR: $BTLR\n\n"

    logger.info("Outputting Information to a Csv File")
    outputMessage
  }

  //Helper function to calculate the similarity score for a given node
  def calculateSimRank(csvLine: String): Double = {

    logger.info(s"Calculating SimRank for $csvLine")
    println(csvLine)

    logger.info("Splitting CSV line")
    val fields = csvLine.split(",")

    var score: Double = 0.0

    //Properties of the original nodes
    val originalNodeProps = NodeProperties(
      children = fields(1).trim.toDouble,
      props = fields(2).trim.toDouble,
      currentDepth = fields(3).trim.toDouble,
      propValueRange = fields(4).trim.toDouble,
      maxDepth = fields(5).trim.toDouble,
      maxBranchingFactor = fields(6).trim.toDouble,
      maxProperties = fields(7).trim.toDouble,
      storedValue = fields(8).trim.toDouble
    )

    //Properties of the perturbed nodes
    val perturbedNodeProps = NodeProperties(
      children = fields(10).trim.toDouble,
      props = fields(11).trim.toDouble,
      currentDepth = fields(12).trim.toDouble,
      propValueRange = fields(13).trim.toDouble,
      maxDepth = fields(14).trim.toDouble,
      maxBranchingFactor = fields(15).trim.toDouble,
      maxProperties = fields(16).trim.toDouble,
      storedValue = fields(17).trim.toDouble
    )

    // Check if properties have changed (PropValueRange, MaxBranchingFactor, StoredValue)
    val propertiesChanged = List(
      !comparator(originalNodeProps.propValueRange, perturbedNodeProps.propValueRange, 5.75), // 5.75% threshold
      !comparator(originalNodeProps.maxBranchingFactor, perturbedNodeProps.maxBranchingFactor, 14.50), // 14.5% threshold
      !comparator(originalNodeProps.storedValue, perturbedNodeProps.storedValue, 13.5), // 13.5% threshold
      !comparator(originalNodeProps.maxDepth, perturbedNodeProps.maxDepth, 13.75), // 13.75% threshold
      !comparator(originalNodeProps.children, perturbedNodeProps.children, 7.50), // 7.5% threshold
      !comparator(originalNodeProps.currentDepth, perturbedNodeProps.currentDepth, 10.0), // 10% threshold
      !comparator(originalNodeProps.props, perturbedNodeProps.props, 8.85), // 8.85% threshold
      !comparator(originalNodeProps.maxProperties, perturbedNodeProps.maxProperties, 16.75) // 16.75% threshold
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

    logger.info(s"Number of changed properties: $numChangedProperties")
    val totalReward = propertiesChanged.zip(rewards).foldLeft(0.0) { case (acc, (propertyChanged, reward)) =>
      if (!propertyChanged) acc + reward else acc
    }

    // Define the threshold for similarity
    val similarityThreshold = 0.9

    // Calculate the total similarity score based on the total reward and the threshold
    val similarityScore = totalReward / (rewards.sum)

    // Adjust the similarity score based on the threshold
    val adjustedScore = if (totalReward > 0) {

      //Jaccard
      //val jaccard = calculateJaccardSimilarity(csvLine)

      val normalizedScore = similarityScore / similarityThreshold
      // If the similarity score is greater than the threshold, then add 1 to the score
      if (normalizedScore >= 0.8) normalizedScore
      // If the similarity score is greater than 0.4 and less than 0.8, then add 0.5 to the score
      else if (normalizedScore >= 0.4) normalizedScore
      // If the similarity score is less than 0.4, then subtract 1 from the score
      else normalizedScore
    }
    else {
      // If the similarity score is 0 => return 0
      0.0
    }

    val df = new DecimalFormat("#.##")

    // Return the adjusted score
    df.format(adjustedScore).toDouble
  }

  //Helper function to calculate the number of ATL, TP, WTL, FP for each node
  def calculateSimRankWithTracebilityLinks(key: Text, values: java.lang.Iterable[DoubleWritable]): String = {

    logger.info(s"Calculating the number of tracebility links and ratios for $values")

    val similarityScore = values.asScala.map(_.get()) // Extract Double values from the Iterable

    import math.Ordered.orderingToOrdered

    val value: Double = 0.5
    val iterableDouble: Iterable[Double] = List(value)
    val newIterableDouble: Iterable[Double] = List(value - 0.2)

    //Here I calculate the number of ATL, TP, WTL, FP for the current node compared with the original node (i.e. the node with the highest similarity score)
    val (updatedATL, updatedTP, updatedWTL, updatedFP, updatedTN) = similarityScore.foldLeft((SimRank.ATL, TP, SimRank.WTL, FP, TN)) {
          // If the similarity score is greater than the threshold, then add 1 to the ATL, TP
      case ((atl, tp, wtl, fp, tn), score) if score >= iterableDouble.head =>
        (atl + 1, tp + 1, wtl, fp+1, tn)
        // If the similarity score is greater than the threshold, then add 1 to the ATL, TP
      case ((atl, tp, wtl, fp, tn), score) if score < iterableDouble.head && score >= newIterableDouble.head =>
        (atl, tp + 1, wtl, fp, tn)
        // If the similarity score is greater than the threshold, then add 1 to the ATL, TP
      case ((atl, tp, wtl, fp, tn), score) if score < newIterableDouble.head =>
        (atl, tp, wtl + 1, fp, tn + 1)
    }

    //Here I calculate the number of ATL, TP, WTL, FP for the current node compared with the original node (i.e. the node with the highest similarity score)
    SimRank.ATL = updatedATL
    TP = updatedTP
    SimRank.WTL = updatedWTL
    FP = updatedFP

    // Thresholds for Modification Detection
    val modificationThreshold = 0.9 // Adjust as needed
    val candidateThreshold = 0.9 // Adjust as needed
    val removalThreshold = 0.84 // Adjust as needed

    // Thresholds for Traceability Links
    val correctThreshold = 0.9
    val mistakenDiscardThreshold = 0.75
    val wrongAcceptThreshold = 0.47

    logger.info("Calculating the Number of Nodes compared with Original Node exceeded the Threshold indicating Modification")
    val above = similarityScore.count(_ > modificationThreshold)
    logger.info("Calculating If Any Score from SimRank Matched the Threshold Indicating Node was Found")
    val even = similarityScore.count(_ == candidateThreshold)
    logger.info("Calculating the Number of Nodes Compared with Original Node were under the Threshold indicating Removed")
    val below = similarityScore.count(_ < removalThreshold)

    val CTL = similarityScore.count(_ > mistakenDiscardThreshold) // Number of correct TLs mistakenly discarded
    val WTL = similarityScore.count(_ < wrongAcceptThreshold) // Number of wrong TLs accepted
    val DTL = similarityScore.count(_ < correctThreshold) // Number of correctly discarded TLs
    val ATL = even + above// Number of correctly accepted TLs


    // The statistics about traceability links
    val GTL: Double = DTL + ATL // Total number of good traceability links
    val BTL: Double = CTL + WTL // Total number of bad traceability links
    val RTL: Double = GTL + BTL // Total number of traceability links
    val ACC: Double = SimRank.ATL / RTL
    var VPR: Double = (SimRank.ATL - SimRank.WTL) / (RTL * 2.0) + 0.5
    val BTLR: Double = SimRank.WTL / RTL

    // Calculate the VPR based on the description
    if (GTL == RTL && BTL == 0) {
      // All recovered TLs are good, so VPR is 1.0
      VPR = 1.0
    }
    else if (BTL == RTL && GTL == 0) {
      // All recovered TLs are bad, so VPR is 0.0
      VPR = 0.0
    }

    //Precision and recall ration calculation in %
    val precision: Double = SimRank.TP / (SimRank.TP + SimRank.FP) * 100
    val recall: Double = SimRank.TP / (SimRank.TP + SimRank.FN) * 100

    var info = ""

    if (even == 0.0) {
      info = s"Node-Edge $key: Matched in Perturbed Graph"
    }
    else if (above > 0.0) {
      info = s"Node-Edge $key: Modified in Original Graph"
    }
    else {
      info = s"Node-Edge $key: Removed in Perturbed Graph"
    }

    //Calculate F1 -score and specificity
    val f1Score = calculateF1Score(SimRank.TP, SimRank.FP, SimRank.FN)
    val specificity = calculateSpecificity(TN, FP)

    logger.info("Outputting Information to a Csv File")
    val outputMessage = s"\n$info \nBTL: $BTL \nGTL: $GTL \nRTL: $RTL \nCTL:" +
      s" $CTL \nWTL: $WTL \nDTL: $DTL \nATL: $ATL\nACC: $ACC \nBTLR: $BTLR \nPrecision: $precision\nRecall:" +
      s" $recall\nF1-Score: $f1Score\n\n"

    logger.info("Outputting Information to a Csv File")
    //val outputMessage = s"Node Modified: $above, Node Found Unchanged: $even, Less than 0.9: $below"

    outputMessage
  }

  //What is the purpose of this function? The function is used to calculate the Jaccard Similarity between the original and perturbed nodes
  //Jaccard Similarity is a measure of how similar two sets are. The Jaccard Similarity of two sets A and B is the ratio of the number of elements in their intersection and the number of elements in their union.
  def calculateJaccardSimilarity(csvLine: String): Double = {

    logger.info(s"Calculating Jaccard Similarity for $csvLine")

    // Split CSV line
    val fields = csvLine.split(",")

    // Properties of the original nodes
    val originalNodeProps = NodeProperties(
      children = fields(1).trim.toDouble,
      props = fields(2).trim.toDouble,
      currentDepth = fields(3).trim.toDouble,
      propValueRange = fields(4).trim.toDouble,
      maxDepth = fields(5).trim.toDouble,
      maxBranchingFactor = fields(6).trim.toDouble,
      maxProperties = fields(7).trim.toDouble,
      storedValue = fields(8).trim.toDouble
    )

    // Properties of the perturbed nodes
    val perturbedNodeProps = NodeProperties(
      children = fields(10).trim.toDouble,
      props = fields(11).trim.toDouble,
      currentDepth = fields(12).trim.toDouble,
      propValueRange = fields(13).trim.toDouble,
      maxDepth = fields(14).trim.toDouble,
      maxBranchingFactor = fields(15).trim.toDouble,
      maxProperties = fields(16).trim.toDouble,
      storedValue = fields(17).trim.toDouble
    )

    //JS for each of the mentioned properties
    val jaccardChildren = calculateJaccard(originalNodeProps.children, perturbedNodeProps.children)
    val jaccardProps = calculateJaccard(originalNodeProps.props, perturbedNodeProps.props)
    val jaccardCurrentDepth = calculateJaccard(originalNodeProps.currentDepth, perturbedNodeProps.currentDepth)
    val jaccardPropValueRange = calculateJaccard(originalNodeProps.propValueRange, perturbedNodeProps.propValueRange)
    val jaccardMaxDepth = calculateJaccard(originalNodeProps.maxDepth, perturbedNodeProps.maxDepth)
    val jaccardMaxBranchingFactor = calculateJaccard(originalNodeProps.maxBranchingFactor, perturbedNodeProps.maxBranchingFactor)
    val jaccardMaxProperties = calculateJaccard(originalNodeProps.maxProperties, perturbedNodeProps.maxProperties)
    val jaccardStoredValue = calculateJaccard(originalNodeProps.storedValue, perturbedNodeProps.storedValue)

    //Average Jaccard Similarity
    val averageJaccardSimilarity = (jaccardChildren + jaccardProps + jaccardCurrentDepth +
      jaccardPropValueRange + jaccardMaxDepth + jaccardMaxBranchingFactor +
      jaccardMaxProperties + jaccardStoredValue) / 8.0

    averageJaccardSimilarity
  }

  // Helper function to calculate Jaccard similarity between two values
  def calculateJaccard(value1: Double, value2: Double): Double = {
    // If both values are equal, return 1.0
    if (value1 == value2) 1.0
    else 0.0
  }
}

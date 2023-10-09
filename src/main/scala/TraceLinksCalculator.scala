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
import com.lsc.SimRank.{FP, TN, TP, calculateF1Score, calculateSpecificity, logger}
import com.lsc.SimRankMapReduce.logger
import org.apache.hadoop.fs.Path
import org.apache.hadoop.conf.*
import org.apache.hadoop.io.*
import org.apache.hadoop.util.*
import org.apache.hadoop.mapred.*
import org.apache.hadoop.fs.{FileStatus, FileSystem, Path}
import org.apache.hadoop.conf.Configuration

import java.text.DecimalFormat

object TraceLinksCalculator {

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

  val logger: Logger = CreateLogger(classOf[TraceLinksCalculator.type])
  logger.info(s"Starting TraceLinksCalculator - loading configs")

  def main(args: Array[String]): Unit = {

  }

  def calculateSimRank(csvLine: String): (Double, String) = {

    logger.info(s"Calculating SimRank for $csvLine")

    // Split CSV line
    val fields = csvLine.split(",")

    // Parse edge properties for the original edges
    val oSource = fields(0).trim.toInt
    val oTarget = fields(1).trim.toInt
    val oWeight = fields(2).trim.toDouble

    // Parse edge properties for the perturbed edges
    val pSource = fields(3).trim.toInt
    val pTarget = fields(4).trim.toInt
    val pWeight = fields(5).trim.toDouble

    // Check if edge properties have changed
    val weightThreshold = 0.05 // Adjust the threshold as needed
    val isModified = Math.abs(oWeight - pWeight) > weightThreshold
    val isAdded = pWeight > 0 && oWeight == 0
    val isRemoved = pWeight == 0 && oWeight > 0

    // Calculate the similarity ratio
    val similarityRatio = if (isModified) 0.0 else if (isAdded || isRemoved) 1.0 else 0.5

    // Update traceability link statistics
    val (updatedATL, updatedTP, updatedWTL, updatedFP, updatedTN) = (isModified, isAdded, isRemoved) match {
      case (true, _, _) => (SimRank.ATL, TP, SimRank.WTL, FP + 1, TN) // Modified
      case (_, true, _) => (SimRank.ATL + 1, TP + 1, SimRank.WTL, FP, TN) // Added
      case (_, _, true) => (SimRank.ATL + 1, TP, SimRank.WTL + 1, FP, TN + 1) // Removed
      case (_, _, _) => (SimRank.ATL, TP, SimRank.WTL, FP, TN) // Not changed
    }

    // Update the traceability link statistics
    SimRank.ATL = updatedATL
    TP = updatedTP
    SimRank.WTL = updatedWTL
    FP = updatedFP

    // Prepare an informative message about the edge
    val info = if (isAdded) {
      s"Edge $oSource -> $oTarget: Added in Perturbed Graph"
    } else if (isRemoved) {
      s"Edge $oSource -> $oTarget: Removed in Perturbed Graph"
    } else if (isModified) {
      s"Edge $oSource -> $oTarget: Modified in Perturbed Graph"
    } else {
      s"Edge $oSource -> $oTarget: Not changed"
    }

    (similarityRatio, info)
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
        (atl + 1, tp + 1, wtl, fp + 1, tn)
      // If the similarity score is greater than the threshold, then add 1 to the ATL, TP
      case ((atl, tp, wtl, fp, tn), score) if score < iterableDouble.head && score >= newIterableDouble.head =>
        (atl, tp + 1, wtl, fp, tn)
      // If the similarity score is greater than the threshold, then add 1 to the ATL, TP
      case ((atl, tp, wtl, fp, tn), score) if score < newIterableDouble.head =>
        (atl, tp, wtl + 1, fp, tn + 1)
    }

    //Here I calculate the number of ATL, TP, WTL, FP for the current node compared with the original node (i.e. the node with the highest similarity score)
    TraceLinksCalculator.ATL = updatedATL
    TP = updatedTP
    TraceLinksCalculator.WTL = updatedWTL
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
    val ATL = even + above // Number of correctly accepted TLs


    // The statistics about traceability links
    val GTL: Double = DTL + ATL // Total number of good traceability links
    val BTL: Double = CTL + WTL // Total number of bad traceability links
    val RTL: Double = GTL + BTL // Total number of traceability links
    val ACC: Double = TraceLinksCalculator.ATL / RTL
    var VPR: Double = (TraceLinksCalculator.ATL - TraceLinksCalculator.WTL) / (RTL * 2.0) + 0.5
    val BTLR: Double = TraceLinksCalculator.WTL / RTL

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
    val precision: Double = TraceLinksCalculator.TP / (TraceLinksCalculator.TP + TraceLinksCalculator.FP) * 100
    val recall: Double = TraceLinksCalculator.TP / (TraceLinksCalculator.TP + TraceLinksCalculator.FN) * 100

    var info = ""

    if (even == 0.0) {
      info = s"Edge $key: Matched in Perturbed Graph"
    }
    else if (above > 0.0) {
      info = s"Edge $key: Modified in Original Graph"
    }
    else {
      info = s"Edge $key: Removed in Perturbed Graph"
    }

    //Calculate F1 -score and specificity
    val f1Score = calculateF1Score(TraceLinksCalculator.TP, TraceLinksCalculator.FP, TraceLinksCalculator.FN)
    val specificity = calculateSpecificity(TN, FP)

    logger.info("Outputting Information to a Csv File")
    val outputMessage = s"\n$info \nBTL: $BTL \nGTL: $GTL \nRTL: $RTL \nCTL:" +
      s" $CTL \nWTL: $WTL \nDTL: $DTL \nATL: $ATL\nACC: $ACC \nBTLR: $BTLR \nPrecision: $precision\nRecall:" +
      s" $recall\nF1-Score: $f1Score\n\n"

    logger.info("Outputting Information to a Csv File")
    //val outputMessage = s"Node Modified: $above, Node Found Unchanged: $even, Less than 0.9: $below"

    outputMessage
  }
}


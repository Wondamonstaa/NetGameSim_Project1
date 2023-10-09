package com.lsc

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import com.lsc.SimRank
import com.lsc.DataConverter._
import org.apache.hadoop.io.Text
import NetGraphAlgebraDefs.GraphPerturbationAlgebra.ModificationRecord
import NetGraphAlgebraDefs.NetModelAlgebra.{actionType, outputDirectory}
import NetGraphAlgebraDefs.{GraphPerturbationAlgebra, NetGraph, NetModelAlgebra}
import NetModelAnalyzer.Analyzer
import Randomizer.SupplierOfRandomness
import Utilz.{CreateLogger, NGSConstants}
import com.google.common.graph.ValueGraph
import org.apache.hadoop.io.DoubleWritable

import java.util.concurrent.{ThreadLocalRandom, TimeUnit}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import com.typesafe.config.ConfigFactory
import guru.nidi.graphviz.engine.Format
import org.slf4j.Logger

import java.net.{InetAddress, NetworkInterface, Socket}
import scala.util.{Failure, Success}
import java.util.ArrayList

class SimRankTest extends AnyFunSuite with Matchers {

  // Utility function to convert a list of Double values to DoubleWritable Iterable
  def toDoubleWritableIterable(values: List[Double]): Iterable[DoubleWritable] = {
    val javaList = new ArrayList[DoubleWritable]()
    values.foreach(value => javaList.add(new DoubleWritable(value)))

    import collection.JavaConverters.asScalaBufferConverter
    import collection.JavaConverters.collectionAsScalaIterableConverter
    import collection.JavaConverters.iterableAsScalaIterableConverter

    javaList.asScala
  }

  // Test calculateSimRankWithTracebilityLinksEdge method
  /*test("calculateSimRankWithTracebilityLinksEdge should calculate traceability links correctly") {
    val key = new Text("edgeKey")
    val values = List(0.8, 0.9, 0.7)
    val result = SimRank.calculateSimRankWithTracebilityLinksEdge(key, toDoubleWritableIterable(values))
    // Modify this with assertions based on your expected output
    result should include("Edge edgeKey:")
    result should include("BTL:")
    result should include("GTL:")
  }

  // Test calculateSimRankWithTracebilityLinks method
  test("calculateSimRankWithTracebilityLinks should calculate traceability links correctly") {
    val key = new Text("nodeKey")
    val values = List(0.8, 0.9, 0.7)
    val result = SimRank.calculateSimRankWithTracebilityLinks(key, toDoubleWritableIterable(values))
    // Modify this with assertions based on your expected output
    result should include("Node-Edge nodeKey:")
    result should include("BTL:")
    result should include("GTL:")
  }*/


  test("calculateEdgeSimRank should return a valid similarity score") {
    val csvLine = "sourceNode, targetNode, 0.8, perturbedSourceNode, perturbedTargetNode, 0.7"
    val similarityScore = SimRank.calculateEdgeSimRank(csvLine)
    similarityScore should be >= 0.0
    similarityScore should be <= 1.0
  }

  // Test case for calculateF1Score function
  test("calculateF1Score should calculate F1-score correctly") {
    assert(SimRank.calculateF1Score(5.0, 2.0, 1.0) === 0.7692307692307692)
  }

  // Test case for calculateSpecificity function
  test("calculateSpecificity should calculate specificity correctly") {
    assert(SimRank.calculateSpecificity(10.0, 2.0) === 0.8333333333333334)
  }

  // Test case for calculateEdgeSimRank function
  test("calculateEdgeSimRank should calculate edge similarity score correctly") {
    val csvLine = "A,B,0.75,X,Y,0.85"
    assert(SimRank.calculateEdgeSimRank(csvLine) === 0.0)
  }

  // Test case for calculateJaccardSimilarity function
  test("calculateJaccardSimilarity should calculate Jaccard similarity correctly") {
    val csvLine = "A,1.0,2.0,3.0,4.0,5.0,6.0,7.0,8.0,X,1.1,2.1,3.1,4.1,5.1,6.1,7.1,8.1"
    assert(SimRank.calculateJaccardSimilarity(csvLine) === 0.0)
  }

  // Test case for calculateJaccard function
  test("calculateJaccard should calculate Jaccard similarity between two values") {
    assert(SimRank.calculateJaccard(10.0, 10.0) === 1.0)
    assert(SimRank.calculateJaccard(10.0, 12.0) === 0.0)
  }

  // Test case for F1-score calculation
  test("F1-score should be correctly calculated") {
    val f1Score = SimRank.calculateF1Score(5.0, 2.0, 1.0)
    assert(f1Score === 0.7692307692307692)
  }

  // Test case for specificity calculation
  test("Specificity should be correctly calculated") {
    val specificity = SimRank.calculateSpecificity(10.0, 2.0)
    assert(specificity === 0.8333333333333334)
  }

  // Test case for calculateF1Score function with extreme values
  /*test("calculateF1Score should calculate F1-score correctly with extreme values") {
    assert(SimRank.calculateF1Score(0.0, 0.0, 0.0) === Double.NaN)
    assert(SimRank.calculateF1Score(10.0, 0.0, 0.0) === 1.0)
    assert(SimRank.calculateF1Score(0.0, 10.0, 0.0) === 0.0)
    assert(SimRank.calculateF1Score(0.0, 0.0, 10.0) === 0.0)
  }

  // Test case for calculateSpecificity function with extreme values
  test("calculateSpecificity should calculate specificity correctly with extreme values") {
    assert(SimRank.calculateSpecificity(0.0, 0.0) === Double.NaN)
    assert(SimRank.calculateSpecificity(10.0, 0.0) === 1.0)
    assert(SimRank.calculateSpecificity(0.0, 10.0) === 0.0)
  }*/

  // Test case for calculateJaccardSimilarity function with all properties matching
  test("calculateJaccardSimilarity should calculate Jaccard similarity correctly with all properties matching") {
    val csvLine = "A,1.0,2.0,3.0,4.0,5.0,6.0,7.0,8.0,X,1.0,2.0,3.0,4.0,5.0,6.0,7.0,8.0"
    assert(SimRank.calculateJaccardSimilarity(csvLine) === 1.0)
  }

  // Test case for calculateJaccard function with all values matching
  test("calculateJaccard should calculate Jaccard similarity between two values with all values matching") {
    assert(SimRank.calculateJaccard(10.0, 10.0) === 1.0)
  }

  // Test case for calculateJaccard function with no values matching
  test("calculateJaccard should calculate Jaccard similarity between two values with no values matching") {
    assert(SimRank.calculateJaccard(10.0, 12.0) === 0.0)
  }

  // Test case for F1-score calculation with extreme values
  /*test("F1-score should be correctly calculated with extreme values") {
    assert(SimRank.calculateF1Score(0.0, 0.0, 0.0) === Double.NaN)
    assert(SimRank.calculateF1Score(10.0, 0.0, 0.0) === 1.0)
    assert(SimRank.calculateF1Score(0.0, 10.0, 0.0) === 0.0)
    assert(SimRank.calculateF1Score(0.0, 0.0, 10.0) === 0.0)
  }

  // Test case for specificity calculation with extreme values
  test("Specificity should be correctly calculated with extreme values") {
    assert(SimRank.calculateSpecificity(0.0, 0.0) === Double.NaN)
    assert(SimRank.calculateSpecificity(10.0, 0.0) === 1.0)
    assert(SimRank.calculateSpecificity(0.0, 10.0) === 0.0)
  }*/
}


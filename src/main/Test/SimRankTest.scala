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
  test("calculateF1Score should calculate F1-score correctly with complex values") {
    // Test case 1: Balanced precision and recall
    val tp1 = 20.0
    val fp1 = 20.0
    val fn1 = 10.0
    assert(SimRank.calculateF1Score(tp1, fp1, fn1) === 0.5714285714285715)

    // Test case 2: High precision but low recall
    val tp2 = 25.0
    val fp2 = 5.0
    val fn2 = 50.0
    assert(SimRank.calculateF1Score(tp2, fp2, fn2) === 0.47619047619047616)

    // Test case 3: Low precision but high recall
    val tp3 = 10.0
    val fp3 = 30.0
    val fn3 = 5.0
    assert(SimRank.calculateF1Score(tp3, fp3, fn3) === 0.36363636363636365)

    // Test case 4: Extreme values
    val tp4 = 10.0
    val fp4 = 0.0
    val fn4 = 0.0
    assert(SimRank.calculateF1Score(tp4, fp4, fn4) === 1.0)

    // Test case 5: Random values
    val tp5 = 15.0
    val fp5 = 7.0
    val fn5 = 9.0
    assert(SimRank.calculateF1Score(tp5, fp5, fn5) === 0.6521739130434783)
  }

  // Test case for calculateSpecificity function with extreme values
  test("calculateSpecificity should calculate specificity correctly with extreme values") {
    assert(SimRank.calculateSpecificity(10.0, 0.0) === 1.0)
    assert(SimRank.calculateSpecificity(0.0, 10.0) === 0.0)
  }

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

  test("calculateSimRank should calculate SimRank correctly with provided CSV data") {

    // Sample CSV containing original and perturbed node information
    val csvLines = List(
      "1,2,17,1,15,1,4,17,0.21110074722006755,1,2,17,1,15,1,4,17,0.21110074722006755",
      "1,2,17,1,15,1,4,17,0.21110074722006755,2,2,1,1,44,2,5,10,0.723816418",
      "1,2,17,1,15,1,4,17,0.21110074722006755,4,0,5,1,47,0,4,2,0.32314616738453317",
      "1,2,17,1,15,1,4,17,0.21110074722006755,5,6,13,1,84,0,3,9,0.9280760335036189",
      "1,2,17,1,15,1,4,17,0.21110074722006755,6,4,2,1,30,2,5,8,0.3186782398622673"
    )

    // Expected similarity scores for the given CSV lines
    val expectedScores = List(1.11, 0.12, 0.15, 0.02, 0.02)

    // Calculate the similarity scores using your function for each CSV line
    val calculatedScores = csvLines.map(SimRank.calculateSimRank)

    // Assert that the calculated scores match the expected scores
    calculatedScores.zip(expectedScores).foreach { case (calculatedScore, expectedScore) =>
      assert(calculatedScore === expectedScore)
    }
  }
}


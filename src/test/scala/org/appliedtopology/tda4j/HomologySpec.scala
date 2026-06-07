package org.appliedtopology.tda4j

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.specs2.mutable
import org.specs2.ScalaCheck

class HomologySpec extends mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-25)

  "Homology of a triangle" >> {
    given shc: SimplicialHomologyContext[Int, Double, Double] = SimplicialHomologyContext()
    import shc.{*, given}

    val streamBuilder = ExplicitStreamBuilder[Int, Double]
    streamBuilder.addAll(List(1, 2, 3).map(i => (0.0, ∆(i))))
    streamBuilder.addAll(List((1.0, ∆(1, 2)), (2.0, ∆(1, 3)), (3.0, ∆(2, 3)), (4.0, ∆(1, 2, 3))))
    val stream = streamBuilder.result()
    val homology = persistentHomology(stream)
    homology.diagramAt(5.0) must containTheSameElementsAs(
      List(
        (0, 0.0, 1.0), // one 0-component dies when 1-2 shows up
        (0, 0.0, 2.0), // one 0-component dies when 1-3 shows up
        (0, 0.0, Double.PositiveInfinity), // one 0-component lives forever
        (1, 3.0, 4.0) // one 1-component created from 2-3 and killed by 1-2-3.
      )
    )
  }

class BarcodeRegressionSpec extends org.specs2.mutable.Specification with ScalaCheck:
  given Double is Field = Field.DoubleApproximated(1e-25)

  val shc = PersistenceInChunksContext[Int, Double](3)

  val cases: Seq[(String, Array[Array[Double]] => StratifiedSimplexStream[Int, Double])] = Seq(
    ("Alpha", (pts: Array[Array[Double]]) => AlphaShapes(pts)),
    (
      "VR",
      (pts: Array[Array[Double]]) =>
        LimitedCofaceSimplexStream(EnumeratingCofaceSimplexStream(EuclideanMetricSpace(pts)), 4)
    )
  )
  for (name, streamBuilder) <- cases do
    s"$name complex should have births before deaths" >> {
      // matrixGen is defined in VietorisRipsSpec.scala
      // forAll(matrixGen[Double](Gen.double, Gen.chooseNum(2, 10), Gen.chooseNum(25, 250))) { (points: Array[Array[Double]]) =>
      val points = matrixGen[Double](Gen.double, Gen.chooseNum(2, 10), Gen.chooseNum(25, 250)).sample.get
      val vrstream = streamBuilder(points)
      val homology = shc.persistentHomology(vrstream)
      val dgm = homology.diagramAt(5.0)
      forall(dgm)((bar: (Int, Double, Double)) => bar._2 <= bar._3)
      // }
    }

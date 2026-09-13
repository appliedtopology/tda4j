package org.appliedtopology.tda4j

import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll
import org.specs2.{ScalaCheck, Specification}

class AlphaComplexSpec extends org.specs2.mutable.Specification with ScalaCheck:
  private val dispatches = Seq("miniball", "helix", "DQP")
  private val pointsGen =
    matrixGen[Double](Gen.double, Gen.chooseNum(2, 5), Gen.chooseNum(6, 12))

  private def layers(
                      points: Array[Array[Double]],
                      dispatch: String
                    ): Seq[Seq[Simplex[Int]]] =
    val alpha = Alpha(points, dispatch)
    (0 to points.head.length).map(d => alpha.iterateDimension(d).toSeq)

  private def everySimplexHasExpectedFaces(
                                            layerByDimension: Seq[Seq[Simplex[Int]]]
                                          ): Boolean =
    val simplicesByDimension = layerByDimension.map(_.toSet)
    layerByDimension.zipWithIndex.forall { case (layer, dimension) =>
      layer.forall { simplex =>
        simplex.dim == dimension &&
          simplex.toSeq.forall(vertex => vertex >= 0 && vertex < layerByDimension.head.size) &&
          (dimension == 0 ||
            simplex.toSeq.forall(vertex =>
              simplicesByDimension(dimension - 1).contains(simplex - vertex)
            ))
      }
    }

  private def alphaProperties(points: Array[Array[Double]], dispatch: String): Prop =
    val alpha = Alpha(points, dispatch)
    val layerByDimension = (0 to points.head.length).map(d => alpha.iterateDimension(d).toSeq)
    val allSimplices : IndexedSeq[Simplex[Int]] = layerByDimension.flatten
    val simplicesByDimension = layerByDimension.map(_.toSet)

    (
      layerByDimension.head.toSet must containTheSameElementsAs(
        points.indices.map(Simplex(_))
      )
      ) and
      (allSimplices.forall(_.dim >= 0) must beTrue) and
      (layerByDimension.forall(layer => layer.distinct.size == layer.size) must beTrue) and
      (everySimplexHasExpectedFaces(layerByDimension) must beTrue) and
      (layerByDimension.forall(layer =>
        layer.map(alpha.filtrationValue).toSeq == layer.map(alpha.filtrationValue).toSeq.sorted
      ) must beTrue) and
      (allSimplices.forall { simplex =>
        simplex.dim == 0 || simplex.toSeq.forall { vertex =>
          alpha.filtrationValue(simplex - vertex) <= alpha.filtrationValue(simplex)
        }
      } must beTrue) and
      (allSimplices.forall(simplex =>
        simplicesByDimension(simplex.dim).contains(simplex)
      ) must beTrue)

  for dispatch <- dispatches do
    s"$dispatch alpha complex should" >> {
      "satisfy the simplicial-stream properties" >>
        forAll(pointsGen) { points =>
          alphaProperties(points, dispatch)
        }
    }

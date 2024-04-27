package org.appliedtopology.tda4j

import org.apache.commons.numbers.fraction.GeneralizedContinuedFraction.Coefficient
import org.specs2.matcher
import org.specs2.mutable.Specification

import scala.collection.mutable.ListBuffer
import scala.util.Random
import scala.math
import scala.math.Numeric.DoubleIsFractional
import scala.runtime.Tuple2Zipped


class HomologySpec extends Specification {
  """  HomologySpec """ should {
    """ be able to use Chain to compute a simple persistent homology barcode""" in {
      val random = new Random
      val a = random.nextDouble() * Double.MaxValue * 2 - Double.MaxValue //these cover all possible values of Double
      val b = random.nextDouble() * Double.MaxValue * 2 - Double.MaxValue // note: revise a and b definitions. too broad. refer to spec2 docs?
      val subPointSet1 = Seq[Double](0.0, 0.0) //setting up points for point set for VR Homology
      val subPointSet2 = Seq[Double](a, 0.0)
      val subPointSet3 = Seq[Double](0.0, b)
      val subPointSet4 = Seq[Double](a, b)
      val points = Seq(subPointSet1, subPointSet2, subPointSet3, subPointSet4) //why a Sequence? Is it because they have an order?


      given chain_ctx: TDAContext[Int, Double]()
      import chain_ctx.{given, *} //used to import all functionality of variable (chain.ctx) that uses TDAContext


      //Start of setup for PH Barcode Check
      //VR Setup
      val metric = EuclideanMetricSpace(points)
      val computationsOfEuclideanSpaceSansSqrt = a * a + b * b //distance between 2 points in ES in 2D sans sqrt. Needed so I can use in pow
      val testValuesFormaxFiltrationValueEuclideanSpace = List[Double](scala.math.pow(computationsOfEuclideanSpaceSansSqrt, 0.5)).max
      val simplices = VietorisRips[Int](metric, testValuesFormaxFiltrationValueEuclideanSpace, 3)

      //what do I want to do here? Use given so I can use Chain class to specify coeff and VertexT


      val boundaries: ListBuffer[Chain[Simplex, Double]] = ListBuffer[Chain[Simplex, Double]]() //empty list buffer created
      val cycles: ListBuffer[Chain[Simplex, Double]] = ListBuffer[Chain[Simplex, Double]]()
      val intervals: ListBuffer[(Simplex, Double)] = ListBuffer[(Simplex, Double)]() //returns tuple of interval


     // extension (chain: Chain[Simplex, Double]) {
        def reduceByBasis(basis: List[ChainElement[Simplex, Double]]): Chain[Simplex, Double] = {
          var changed: Boolean = true
          var sigma = ChainElement[Simplex, Double]()  //working on current chain. Here assigning sigma as an empty ChainElement
          while (changed) {
            changed = false
            for (basisElement <- basis) {
              if (sigma.leadingCell == basisElement.leadingCell) {
                val factor1: Double = basisElement.leadingCoefficient / sigma.leadingCoefficient //doing ops for boundary finding, just broken up to be easier to do
                val factor2: Double = sigma.leadingCoefficient / basisElement.leadingCoefficient
                sigma = scale(factor1, sigma) - scale(factor2, basisElement) //using Chain's scale to do multiplication of coeff and chain, since scala can't work with these datatypes natively
                changed = true

              }
            }
          }
          sigma
        }
     // }

      //TDAContext is great! Due to the context-driven paradigm, the tda package object extends ChainOps and SimplexOps,
      //thus you don't have to keep on rewriting parameter names and function calls. Instead its assigned to a singular variable, that does all that in the backend
      //of TDAContext, which itself goes into the backend of ChainOps and SimplexContext. SC sets up the functionality to work with simplexes in a practical matter


    }


  }

}

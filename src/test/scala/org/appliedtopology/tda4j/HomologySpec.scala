package org.appliedtopology.tda4j

import org.specs2.matcher
import org.specs2.mutable.Specification
import scala.collection.mutable.ListBuffer
import scala.util.Random
import scala.math

class HomologySpec extends Specification {
  """  HomologySpec """ should {
    """ be able to use Chain to compute a simple persistent homology barcode""" in {
       val random = new Random
       val a = random.nextDouble() * Double.MaxValue * 2 - Double.MaxValue //these cover all possible values of Double
       val b = random.nextDouble() * Double.MaxValue * 2 - Double.MaxValue
       val subPointSet1 = Seq[Double](0.0,0.0) //setting up points for point set for VR Homology
       val subPointSet2 = Seq[Double](a, 0.0)
       val subPointSet3 = Seq[Double](0.0,b)
       val subPointSet4 = Seq[Double](a,b)
       val points = Seq(subPointSet1,subPointSet2,subPointSet3,subPointSet4) //why a Sequence? Is it because they have an order?

      //Start of setup for PH Barcode Check
       //VR Setup
       val metric = EuclideanMetricSpace(points)
       val computationsOfEuclideanSpaceSansSqrt = a*a+b*b    //distance between 2 points in ES in 2D sans sqrt. Needed so I can use in pow
       val testValuesFormaxFiltrationValueEuclideanSpace = List[Double](scala.math.pow(computationsOfEuclideanSpaceSansSqrt,0.5)).max
       val simplices = VietorisRips[Int](metric,testValuesFormaxFiltrationValueEuclideanSpace,3)

       //val boundaries = new ListBuffer[Chain[Int,Double]]


    }

  }



}

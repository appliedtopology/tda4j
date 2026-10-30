package tda4juser

import org.specs2.mutable.Specification

/** The snippets of the user guide page "Maps between barcodes", from a user's side of the package boundary. */
class BarcodeAlgebraPageSpec extends Specification:
  import org.appliedtopology.tda4j.*

  "the snippet over another field" should {
    "be an isomorphism mod 3 and zero mod 2" in {
      val bar = IndexedSeq(MapAlgebra.Interval(0.0, Double.PositiveInfinity))
      def kernelOver(p: Int) =
        val coefficients = Coefficients(p)
        import coefficients.given
        MapAlgebra.kernel(bar, bar, IndexedSeq(Map(0 -> coefficients.fromInt(2))))
      (kernelOver(3) must beEmpty) and (kernelOver(2).map(_._2) must beEqualTo(
        List(MapAlgebra.Interval(0.0, Double.PositiveInfinity))
      ))
    }
  }

  "the snippet of the subsample of a circle" should {
    "have the loop of the subsample as the image and the earlier loop of the sample as the cokernel" in {
      val whole = Array.tabulate(24)(i => Array(math.cos(2 * math.Pi * i / 24), math.sin(2 * math.Pi * i / 24)))
      val sub = Array.tabulate(12)(i => whole(2 * i))
      def diagram(points: Array[Array[Double]]) =
        Persistence(points, maxDimension = 1, maxFiltrationValue = 2.0, includeZeroLength = true)
      val inclusion = PersistenceMap.simplicial(diagram(sub), diagram(whole))(i => 2 * i)
      val (subBirth, wholeBirth, death) = (2 * math.sin(math.Pi / 12), 2 * math.sin(math.Pi / 24), math.sqrt(3.0))
      val image = inclusion.image(1).map(b => (b.birth, b.death))
      val cokernel = inclusion.cokernel(1).map(b => (b.birth, b.death))
      (image.size must beEqualTo(1))
        .and(image.head._1 must beCloseTo(subBirth, 1e-9))
        .and(image.head._2 must beCloseTo(death, 1e-9))
        .and(cokernel.size must beEqualTo(1))
        .and(cokernel.head._1 must beCloseTo(wholeBirth, 1e-9))
        .and(cokernel.head._2 must beCloseTo(subBirth, 1e-9))
        .and(inclusion.kernel(1) must beEmpty)
    }
  }
end BarcodeAlgebraPageSpec

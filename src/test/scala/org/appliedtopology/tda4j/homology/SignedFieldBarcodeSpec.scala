package org.appliedtopology.tda4j
package homology

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}
import org.appliedtopology.tda4j.streams.{given, *}
import org.appliedtopology.tda4j.barcode.*

import org.specs2.mutable

/** Every subcomplex of a simplex is torsion-free, so its barcode must be the same over F2 and F3 -- and F2 cannot see
  * signs, so any F3-vs-F2 disagreement is a sign error. The fixtures are random monotone filtrations of the 5-skeleton
  * of the 6-simplex, i.e. full of 5- and 6-vertex simplices, the sizes at which a hash-ordered boundary shows up (see
  * `SimplexBoundarySpec`). With that bug present, the chunks engine disagreed on 19 of 20 such fixtures.
  */
class SignedFieldBarcodeSpec extends mutable.Specification:
  val GF2 = new FiniteField(2)
  val GF3 = new FiniteField(3)
  import GF2.given
  import GF3.given

  type Diagram = Seq[(Int, Double, Double)]

  def fixture(seed: Int): Seq[(Double, Simplex[Int])] =
    val rnd = scala.util.Random(seed)
    val weight = Array.fill(7)(rnd.nextInt(5).toDouble)
    val raw = (1 to 6).flatMap(k =>
      (0 until 7).combinations(k).map { vs =>
        (vs.map(weight).max + (k - 1) * 0.1 + (if k >= 5 then rnd.nextInt(4) else 0), Simplex(vs*))
      }
    )
    // Make the filtration monotone: every simplex enters no earlier than its latest facet.
    val fv = scala.collection.mutable.Map.from(raw.map((v, s) => s -> v))
    for k <- 2 to 6; (_, s) <- raw if s.size == k do fv(s) = (fv(s) +: s.iterator.map(v => fv(s - v)).toSeq).max
    raw.map((_, s) => (fv(s), s))

  def stratified(cells: Seq[(Double, Simplex[Int])]): StratifiedCellStream[Simplex[Int], Double] =
    val fv: Map[Simplex[Int], Double] = cells.map((v, s) => s -> v).toMap
    val maxDim = cells.map(_._2.dim).max
    new StratifiedCellStream[Simplex[Int], Double] with DoubleFiltration[Simplex[Int]]:
      val filtrationValue: PartialFunction[Simplex[Int], Double] = fv
      val filtrationOrdering: Ordering[Simplex[Int]] =
        FiltrationOrdering.canonical(fv, _.dim, simplexOrdering[Int])
      def iterateDimension: PartialFunction[Int, Iterator[Simplex[Int]]] = {
        case d if d >= 0 && d <= maxDim =>
          cells.map(_._2).filter(_.dim == d).sorted(using filtrationOrdering.reverse).iterator
      }

  def normalize(bars: Seq[(Int, Double, Double)]): Diagram = bars.filter((_, b, d) => b != d).sorted

  def endpoint(e: BarcodeEndpoint[Double]): Double = e match
    case ClosedEndpoint(v)  => v
    case OpenEndpoint(v)    => v
    case PositiveInfinity() => Double.PositiveInfinity
    case NegativeInfinity() => Double.NegativeInfinity

  def naive[C: Field](cells: Seq[(Double, Simplex[Int])]): Diagram =
    normalize(SimplicialHomologyContext[Int, C, Double]().persistentHomology(stratified(cells)).diagramAt(1e9))

  def chunks[C: Field](cells: Seq[(Double, Simplex[Int])]): Diagram =
    normalize(PersistenceInChunksContext[Int, C](5).persistentHomology(stratified(cells)).diagramAt(1e9))

  def cohomology[C: Field](cells: Seq[(Double, Simplex[Int])]): Diagram =
    normalize(
      CellularCohomologyContext[Simplex[Int], C, Double]()
        .persistentCohomology(stratified(cells))
        .map(b => (b.dim, endpoint(b.lower), endpoint(b.upper)))
    )

  val fixtures: Seq[Seq[(Double, Simplex[Int])]] = (1 to 8).map(fixture)

  "Torsion-free complexes with 5- and 6-vertex simplices have the same barcode over F3 as over F2" >> {
    "naive engine" >>
      forall(fixtures)(cells => naive[GF3.Fp](cells) must beEqualTo(naive[GF2.Fp](cells)))
    "chunks engine" >>
      forall(fixtures)(cells => chunks[GF3.Fp](cells) must beEqualTo(chunks[GF2.Fp](cells)))
    "cellular cohomology engine" >>
      forall(fixtures)(cells => cohomology[GF3.Fp](cells) must beEqualTo(cohomology[GF2.Fp](cells)))
  }

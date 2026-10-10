package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

import cats.Show
import scala.collection.mutable

/** A finite category: objects `0 until objectCount`, morphisms `0 until morphismCount`. Morphism `f` goes from
  * `source(f)` to `target(f)`; `andThen(f, g)` is the composite "first `f`, then `g`", defined when `target(f) ==
  * source(g)` (diagrammatic order, so a monoid's `multiply(a, b)` is `andThen(a, b)`).
  *
  * The nerve ([[CategoryNerve]]) is its classifying space. Implement the six abstract members to define a category by
  * functions, or use a constructor of the companion: `fromMonoid`, `fromPoset`, `freeOnAcyclicQuiver`,
  * `actionGroupoid`, `homotopyOrbits`. `validate()` checks the category laws.
  */
trait FiniteCategory:
  def objectCount: Int
  def morphismCount: Int
  def source(f: Int): Int
  def target(f: Int): Int

  /** The identity morphism of object `x`. */
  def identityOf(x: Int): Int

  /** `f` followed by `g`; only called with `target(f) == source(g)`. */
  def andThen(f: Int, g: Int): Int

  /** A label for morphism `f`, used in messages (default: its index). */
  def morphismName(f: Int): String = f.toString

  def isIdentity(f: Int): Boolean = identityOf(source(f)) == f

  /** The non-identity morphisms out of each object, in index order: the edges the nerve extends chains by. */
  lazy val nonIdentityFrom: IndexedSeq[IndexedSeq[Int]] =
    val out = IndexedSeq.fill(objectCount)(mutable.ArrayBuffer.empty[Int])
    for f <- 0 until morphismCount if !isIdentity(f) do out(source(f)) += f
    out.map(_.toIndexedSeq)

  /** Checks ranges, identities, that composites have the right ends, the unit laws and associativity; the empty list
    * means this really is a category. One message per kind of failure.
    */
  def validate(): Seq[String] =
    val morphisms = 0 until morphismCount
    def name(f: Int) = morphismName(f)
    val ranges =
      (morphisms.filter(f => !(0 until objectCount).contains(source(f))).map(f => s"${name(f)}: bad source") ++
        morphisms.filter(f => !(0 until objectCount).contains(target(f))).map(f => s"${name(f)}: bad target")).take(1)
    if ranges.nonEmpty then ranges
    else
      val identities = (0 until objectCount)
        .filter(x => source(identityOf(x)) != x || target(identityOf(x)) != x)
        .map(x => s"the identity of $x is not an endomorphism of $x")
        .take(1)
      val composable = for f <- morphisms; g <- morphisms if target(f) == source(g) yield (f, g)
      val ends = composable.iterator
        .filter((f, g) =>
          val h = andThen(f, g)
          h < 0 || h >= morphismCount || source(h) != source(f) || target(h) != target(g)
        )
        .map((f, g) => s"${name(f)} then ${name(g)} has the wrong source or target")
        .take(1)
        .toSeq
      if ends.nonEmpty then identities ++ ends
      else
        val units = morphisms.iterator
          .filter(f => andThen(identityOf(source(f)), f) != f || andThen(f, identityOf(target(f))) != f)
          .map(f => s"an identity is not a unit for ${name(f)}")
          .take(1)
          .toSeq
        val assoc = composable.iterator
          .flatMap((f, g) =>
            nonIdentityFrom(target(g)).iterator
              .filter(h => andThen(andThen(f, g), h) != andThen(f, andThen(g, h)))
              .map(h => s"composition is not associative at (${name(f)}, ${name(g)}, ${name(h)})")
          )
          .take(1)
          .toSeq
        identities ++ units ++ assoc

object FiniteCategory:

  /** A category given by tables: `source`, `target` per morphism, `identities` per object, and `composition(f, g)` =
    * `f` then `g` for composable pairs.
    */
  def apply(
    objectCount: Int,
    source: IndexedSeq[Int],
    target: IndexedSeq[Int],
    identities: IndexedSeq[Int],
    composition: (Int, Int) => Int,
    names: IndexedSeq[String]
  ): FiniteCategory =
    require(source.length == target.length, "source and target need one entry per morphism")
    require(identities.length == objectCount, "identities needs one entry per object")
    require(names.length == source.length, "names needs one entry per morphism")
    val (n, s, t, ids, c, ns) = (objectCount, source, target, identities, composition, names)
    new FiniteCategory:
      val objectCount = n
      val morphismCount = s.length
      def source(f: Int) = s(f)
      def target(f: Int) = t(f)
      def identityOf(x: Int) = ids(x)
      def andThen(f: Int, g: Int) = c(f, g)
      override def morphismName(f: Int) = ns(f)

  /** A monoid as a category with one object; its nerve is the same simplicial set as [[Nerve]]`(monoid)`. */
  def fromMonoid(monoid: FiniteMonoid): FiniteCategory =
    apply(
      1,
      IndexedSeq.fill(monoid.order)(0),
      IndexedSeq.fill(monoid.order)(0),
      IndexedSeq(monoid.identity),
      monoid.multiply,
      monoid.names
    )

  /** A poset as a category: object `i` is `elements(i)`, with one morphism `i -> j` whenever `lessOrEqual(elements(i),
    * elements(j))`. The nerve is the order complex. `lessOrEqual` must be reflexive and transitive (a preorder works
    * too: equivalent elements become isomorphic objects).
    */
  def fromPoset[T](elements: Seq[T], lessOrEqual: (T, T) => Boolean): FiniteCategory =
    val es = elements.toIndexedSeq
    val n = es.length
    val leq = Array.tabulate(n, n)((i, j) => lessOrEqual(es(i), es(j)))
    require((0 until n).forall(i => leq(i)(i)), "lessOrEqual must be reflexive")
    require(
      (0 until n).forall(i => (0 until n).forall(j => !leq(i)(j) || (0 until n).forall(k => !leq(j)(k) || leq(i)(k)))),
      "lessOrEqual must be transitive"
    )
    val pairs = for i <- 0 until n; j <- 0 until n if leq(i)(j) yield (i, j)
    val index = pairs.zipWithIndex.toMap
    apply(
      n,
      pairs.map(_._1),
      pairs.map(_._2),
      IndexedSeq.tabulate(n)(i => index((i, i))),
      (f, g) => index((pairs(f)._1, pairs(g)._2)),
      pairs.map((i, j) => s"$i<=$j")
    )

  /** The free category on a quiver without directed cycles: morphisms are the directed paths (the empty path at each
    * object is its identity), composed by concatenation. Arrow `k` goes from `arrows(k)._1` to `arrows(k)._2`. Its
    * nerve is homotopy equivalent to the quiver as a graph: two arrows `0 -> 1` give a circle.
    */
  def freeOnAcyclicQuiver(objectCount: Int, arrows: Seq[(Int, Int)]): FiniteCategory =
    require(
      arrows.forall((a, b) => a >= 0 && a < objectCount && b >= 0 && b < objectCount),
      s"arrows must join objects in 0 until $objectCount"
    )
    val out = (0 until objectCount).map(x => arrows.indices.filter(k => arrows(k)._1 == x))
    // Every path from x, as (start, arrow indices); a path longer than objectCount arrows revisits an object.
    def pathsFrom(x: Int): Seq[List[Int]] =
      def extend(path: List[Int], at: Int): Seq[List[Int]] =
        require(path.length <= objectCount, "the quiver has a directed cycle, so its free category is infinite")
        path +: out(at).flatMap(k => extend(path :+ k, arrows(k)._2))
      extend(Nil, x)
    val paths = (0 until objectCount).flatMap(x => pathsFrom(x).map(p => (x, p)))
    val index = paths.zipWithIndex.toMap
    def end(x: Int, p: List[Int]) = p.lastOption.fold(x)(k => arrows(k)._2)
    apply(
      objectCount,
      paths.map(_._1),
      paths.map(end),
      IndexedSeq.tabulate(objectCount)(x => index((x, Nil))),
      (f, g) => index((paths(f)._1, paths(f)._2 ++ paths(g)._2)),
      paths.map((x, p) => if p.isEmpty then s"id$x" else p.map(k => s"a$k").mkString(";"))
    )

  /** The action groupoid of `group` acting on the points `0 until points` by `act(g, x)` (a left action:
    * `act(g·h, x) = act(g, act(h, x))`): one object per point, one morphism `x -> act(g, x)` per group element `g`. Its
    * nerve is homotopy equivalent to the disjoint union, over orbits, of the classifying spaces of the stabilizers. The
    * same as [[homotopyOrbits]] on the discrete complex of the points.
    */
  def actionGroupoid(group: FiniteGroup, points: Int, act: (Int, Int) => Int): HomotopyOrbitCategory =
    homotopyOrbits(group, (0 until points).map(x => Simplex(x)), act)

  /** The category of simplices of a simplicial complex with a group action (the Grothendieck construction of `group`
    * acting on the face poset): one object per simplex `σ`, one morphism `σ -> τ` for each group element `g` with `g·σ`
    * a face of `τ`, composed by multiplying the group elements. By Thomason's theorem its nerve is homotopy equivalent
    * to the homotopy orbit space (Borel construction) `EG ×_G |K|`, so its homology is the equivariant homology of `K`.
    *
    * The complex is the one generated by `facets`; `vertexAction(g, v)` is a left action on the vertices that must map
    * simplices to simplices.
    */
  def homotopyOrbits(
    group: FiniteGroup,
    facets: Iterable[Simplex[Int]],
    vertexAction: (Int, Int) => Int
  ): HomotopyOrbitCategory = HomotopyOrbitCategory(group, facets, vertexAction)

/** The category of [[FiniteCategory.homotopyOrbits]] (and of [[FiniteCategory.actionGroupoid]]): object `i` is
  * `simplices(i)`, and morphism `f` is the pair `(groupElement(f), source(f) -> target(f))`.
  */
open class HomotopyOrbitCategory(
  val group: FiniteGroup,
  facets: Iterable[Simplex[Int]],
  val vertexAction: (Int, Int) => Int
) extends FiniteCategory:
  /** Every simplex of the complex, by dimension, then lexicographically. */
  val simplices: IndexedSeq[Simplex[Int]] =
    facets.iterator
      .flatMap(facet => (1 to facet.size).iterator.flatMap(k => facet.toList.combinations(k).map(c => Simplex.from(c))))
      .toSet
      .toIndexedSeq
      .sortBy(_.toList)(using Ordering.Implicits.seqOrdering[List, Int])
      .sortBy(_.size) // stable: lexicographic within each dimension
  private val objectIndex: Map[Simplex[Int], Int] = simplices.zipWithIndex.toMap
  private val vertices: Set[Int] = simplices.filter(_.size == 1).flatMap(_.toList).toSet

  require(
    (0 until group.order).forall(g => vertices.forall(v => vertices.contains(vertexAction(g, v)))) &&
      vertices.forall(v => vertexAction(group.identity, v) == v) &&
      (0 until group.order).forall(g =>
        (0 until group.order).forall(h =>
          vertices.forall(v => vertexAction(group.multiply(g, h), v) == vertexAction(g, vertexAction(h, v)))
        )
      ),
    "vertexAction must be a left action on the vertices: act(identity, v) = v and act(g·h, v) = act(g, act(h, v))"
  )

  /** `g·σ`, which must again be a simplex of the complex. */
  def act(g: Int, s: Int): Int =
    objectIndex.getOrElse(
      Simplex.from(simplices(s).toList.map(vertexAction(g, _))),
      throw IllegalArgumentException(s"vertexAction maps the simplex ${simplices(s).toList} out of the complex")
    )
  simplices.indices.foreach(s => (0 until group.order).foreach(g => act(g, s)))

  // The face inclusions ρ ⊆ τ, and morphism g * inclusions.length + k = (g, g⁻¹ρ_k -> τ_k).
  private val inclusions: IndexedSeq[(Int, Int)] =
    for
      t <- simplices.indices
      r <- simplices.indices if simplices(r).underlying.subsetOf(simplices(t).underlying)
    yield (r, t)
  private val inclusionIndex: Map[(Int, Int), Int] = inclusions.zipWithIndex.toMap

  val objectCount: Int = simplices.length
  val morphismCount: Int = group.order * inclusions.length

  /** The group element of morphism `f`. */
  def groupElement(f: Int): Int = f / inclusions.length

  /** The morphism `(g, σ -> τ)`; `g·σ` must be a face of `τ`. */
  def morphism(g: Int, s: Int, t: Int): Int = g * inclusions.length + inclusionIndex((act(g, s), t))

  def source(f: Int): Int = act(group.inverse(groupElement(f)), inclusions(f % inclusions.length)._1)
  def target(f: Int): Int = inclusions(f % inclusions.length)._2
  def identityOf(x: Int): Int = morphism(group.identity, x, x)
  def andThen(f: Int, g: Int): Int = morphism(group.multiply(groupElement(g), groupElement(f)), source(f), target(g))

  /** The morphisms whose group element lies in `subgroup`: the subcategory of the restricted action. */
  def morphismsOver(subgroup: Set[Int]): Set[Int] = (0 until morphismCount).filter(f => subgroup(groupElement(f))).toSet

  override def morphismName(f: Int): String =
    s"(${group.names(groupElement(f))}, ${simplices(source(f)).toList.mkString("{", ",", "}")}->${simplices(target(f)).toList
        .mkString("{", ",", "}")})"

/** A non-degenerate simplex of the nerve of a [[FiniteCategory]]: a chain `x_0 -f_1-> x_1 -> ... -f_n-> x_n` of
  * composable NON-identity morphisms (a chain containing an identity is degenerate). `start` is `x_0`, which is
  * `source(f_1)` when `n >= 1`; for `n = 0` the simplex is the object `start`.
  */
final case class CategorySimplex(start: Int, morphisms: Vector[Int]):
  def dim: Int = morphisms.length

object CategorySimplex:
  /** `(x)` for the object `x`, `[f1|f2|...]` (morphism indices) for a chain. */
  given categorySimplexShow: Show[CategorySimplex] =
    Show.show(s => if s.dim == 0 then s"(${s.start})" else s.morphisms.mkString("[", "|", "]"))

  given Ordering[CategorySimplex] =
    Ordering.by[CategorySimplex, Vector[Int]](_.morphisms)(using Ordering.Implicits.seqOrdering).orElseBy(_.start)

/** The nerve of a finite category: its classifying space, as a (usually infinite) simplicial set produced lazily.
  * `n`-simplices are chains of `n` composable morphisms; the non-degenerate ones ([[CategorySimplex]]) avoid
  * identities.
  *
  * Faces: `d_0` drops the first morphism, `d_n` the last, and `d_i` for `0 < i < n` composes morphisms `i` and `i + 1`;
  * if that composite is an identity, the face is degenerate -- `s_{i-1}` of the chain with both removed. A poset's
  * nerve is finite (its order complex); a category with a non-identity endomorphism or a cycle has generators in every
  * dimension, so take `skeleton(n)` (homology right below degree `n`) or use `persistentHomology`.
  */
open class CategoryNerve(val category: FiniteCategory) extends SimplicialSet[CategorySimplex]:
  override def ord: Ordering[CategorySimplex] = summon[Ordering[CategorySimplex]]

  override def generators(n: Int): Iterable[CategorySimplex] =
    if n < 0 then Iterable.empty
    else if n == 0 then (0 until category.objectCount).map(x => CategorySimplex(x, Vector.empty))
    else
      val firsts: Iterable[Vector[Int]] = category.nonIdentityFrom.flatten.map(Vector(_))
      (1 until n)
        .foldLeft(firsts)((chains, _) =>
          for c <- chains; f <- category.nonIdentityFrom(category.target(c.last)) yield c :+ f
        )
        .map(c => CategorySimplex(category.source(c.head), c))

  override def dimOf(s: CategorySimplex): Int = s.dim

  override val faces: CategorySimplex => IndexedSeq[SSetElement[CategorySimplex]] = s =>
    val fs = s.morphisms
    val n = fs.length
    if n == 0 then IndexedSeq.empty
    else
      IndexedSeq.tabulate(n + 1) { i =>
        if i == 0 then SSetElement(Nil, CategorySimplex(category.target(fs(0)), fs.tail))
        else if i == n then SSetElement(Nil, CategorySimplex(s.start, fs.init))
        else
          val composite = category.andThen(fs(i - 1), fs(i))
          if category.isIdentity(composite) then
            SSetElement(List(i - 1), CategorySimplex(s.start, fs.patch(i - 1, Nil, 2)))
          else SSetElement(Nil, CategorySimplex(s.start, fs.patch(i - 1, Seq(composite), 2)))
      }

  /** The filtration by a chain of subcategories, each given by its set of morphisms: a simplex enters at the index of
    * the first subcategory containing all its morphisms (an object: its identity). Each set must be closed under
    * composition and contain the identities of its morphisms' ends, and the last must contain every morphism used;
    * `persistentHomology` rejects a filtration that is not monotone.
    */
  def filtrationBy(chain: Seq[Set[Int]]): CategorySimplex => Double =
    require(chain.nonEmpty && chain.zip(chain.drop(1)).forall((a, b) => a.subsetOf(b)), "chain must be increasing")
    s =>
      val needed = if s.dim == 0 then Seq(category.identityOf(s.start)) else s.morphisms
      chain.indexWhere(c => needed.forall(c.contains)).toDouble.ensuring(_ >= 0, "chain does not cover the simplex")

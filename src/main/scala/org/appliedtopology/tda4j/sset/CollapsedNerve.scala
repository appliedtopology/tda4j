package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

import scala.collection.mutable

/** A complete rewriting system for a finite monoid, given by the normal form of every element: `normalForms(x)` is a
  * word in the letters (element indices, the keys of `levels`), the empty word for the identity. The rules are the
  * minimal words that are not normal forms, each rewritten to the normal form of its value.
  *
  * `levels` orders the letters for termination: a rule must decrease in the wreath product of shortlex orders by level
  * (the collection order of polycyclic presentations), which `validate()` checks along with the rest. Build one by
  * hand, or with [[RewritingSystem.refining]] from a chain of subgroups.
  */
final case class RewritingSystem(monoid: FiniteMonoid, normalForms: IndexedSeq[Vector[Int]], levels: Map[Int, Int]):
  private val elementOfWord: Map[Vector[Int], Int] = normalForms.zipWithIndex.toMap
  private val levelOrder: IndexedSeq[Int] = levels.values.toIndexedSeq.distinct.sorted

  /** The letters, by level and then by element index. */
  val letters: IndexedSeq[Int] = levels.keys.toIndexedSeq.sortBy(a => (levels(a), a))

  /** Whether `w` is a normal form (equivalently: irreducible). */
  def isNormal(w: Vector[Int]): Boolean = elementOfWord.contains(w)

  /** The element a word evaluates to. */
  def evaluate(w: Vector[Int]): Int = w.foldLeft(monoid.identity)(monoid.multiply)

  /** Every minimal non-normal word (its longest proper prefix and suffix are normal), with the normal form of its
    * value.
    */
  lazy val rules: Seq[(Vector[Int], Vector[Int])] =
    for
      v <- normalForms
      b <- letters
      w = v :+ b
      if !isNormal(w) && isNormal(w.tail)
    yield (w, normalForms(evaluate(w)))

  /** The collection order: compare the letters of the top level by shortlex; if equal, compare the segments between
    * them, left to right, one level down.
    */
  def compareWords(u: Vector[Int], v: Vector[Int]): Int = compareAt(u, v, levelOrder.length - 1)

  private def compareAt(u: Vector[Int], v: Vector[Int], levelIndex: Int): Int =
    if levelIndex < 0 || (u.isEmpty && v.isEmpty) then 0
    else
      val level = levelOrder(levelIndex)
      val (ut, vt) = (u.filter(levels(_) == level), v.filter(levels(_) == level))
      val top =
        if ut.length != vt.length then Integer.compare(ut.length, vt.length)
        else ut.zip(vt).iterator.map((a, b) => Integer.compare(a, b)).find(_ != 0).getOrElse(0)
      if top != 0 then top
      else
        def segments(w: Vector[Int]): Vector[Vector[Int]] =
          w.foldLeft(Vector(Vector.empty[Int]))((acc, a) =>
            if levels(a) == level then acc :+ Vector.empty else acc.init :+ (acc.last :+ a)
          )
        segments(u).zip(segments(v)).iterator.map((a, b) => compareAt(a, b, levelIndex - 1)).find(_ != 0).getOrElse(0)

  /** Empty when this is a complete rewriting system: one normal form per element, evaluating to it, the identity's
    * empty; every letter its own normal form; normal forms closed under taking factors; every rule decreasing in the
    * collection order (so rewriting terminates, and with unique normal forms it is confluent).
    */
  def validate(): Seq[String] =
    val n = monoid.order
    if normalForms.length != n then Seq(s"${normalForms.length} normal forms for $n elements")
    else
      Seq(
        Option.when(elementOfWord.size != n)("two elements share a normal form"),
        Option.when(normalForms(monoid.identity).nonEmpty || normalForms.count(_.isEmpty) != 1)(
          "the identity, and only it, must have the empty word"
        ),
        normalForms.indices
          .find(x => evaluate(normalForms(x)) != x)
          .map(x => s"the normal form of $x evaluates elsewhere"),
        letters.find(a => !normalForms.lift(a).contains(Vector(a))).map(a => s"letter $a is not its own normal form"),
        normalForms.find(_.exists(a => !levels.contains(a))).map(w => s"$w uses a letter without a level"),
        normalForms
          .find(w => (0 until w.length).exists(i => (i + 1 to w.length).exists(j => !isNormal(w.slice(i, j)))))
          .map(w => s"a factor of $w is not a normal form"),
        rules.find((w, r) => compareWords(r, w) >= 0).map((w, r) => s"rule $w -> $r does not decrease")
      ).flatten

  /** Empty when every element of every subgroup in `chain` has a normal form using only that subgroup's letters -- the
    * condition under which Brown's collapse ([[CollapsedNerve]]) never matches cells of two filtration levels.
    */
  def respects(chain: Seq[Set[Int]]): Seq[String] =
    chain.zipWithIndex.flatMap { (h, j) =>
      h.find(x => !normalForms(x).forall(h.contains))
        .map(x => s"the normal form ${normalForms(x)} of element $x of subgroup $j uses a letter outside that subgroup")
    }

object RewritingSystem:

  /** A complete rewriting system for `group` that respects `chain` (an increasing chain of subgroups): the polycyclic
    * presentation of a series `1 = K_0 < K_1 < ... < K_m = G` with each `K_{i-1}` normal in `K_i` of prime index that
    * passes through every subgroup of the chain. Level `i` has the one letter `x_i`, and the normal form of an element is
    * `x_m^(e_m) ... x_1^(e_1)` with `0 <= e_i < p_i`, top level first. Exists exactly when each subgroup of the chain (and its
    * last, in `group`) is subnormal in the next with a solvable quotient -- always for a p-group; `Left` says which
    * step fails.
    */
  def refining(group: FiniteGroup, chain: Seq[Set[Int]]): Either[String, RewritingSystem] =
    val e = group.identity
    def generated(xs: Iterable[Int]): Set[Int] = group.subgroupGeneratedBy(xs)
    def power(x: Int, k: Int): Int = (0 until k).foldLeft(e)((y, _) => group.multiply(y, x))
    def conjugate(c: Int, a: Int): Int = group.multiply(group.multiply(c, a), group.inverse(c))
    // Iterate f from `start` until it stops changing; the values, `start` first.
    def untilStable(start: Set[Int])(f: Set[Int] => Set[Int]): Vector[Set[Int]] =
      start +: Iterator.iterate(start)(f).sliding(2).takeWhile(w => w(0) != w(1)).map(_(1)).toVector
    // b ⊵ a^b ⊵ a^(a^b) ⊵ ... must reach a; returned bottom-up.
    def subnormal(a: Set[Int], b: Set[Int]): Either[String, Vector[Set[Int]]] =
      val down = untilStable(b)(cur => generated(for c <- cur; x <- a yield conjugate(c, x)))
      Either.cond(
        down.last == a,
        down.reverse,
        s"the subgroup of order ${a.size} is not subnormal in the subgroup of order ${b.size}"
      )
    // upper ⊵ [upper, upper]·lower ⊵ ... must reach lower (each quotient abelian); returned bottom-up.
    def solvable(lower: Set[Int], upper: Set[Int]): Either[String, Vector[Set[Int]]] =
      val down = untilStable(upper)(d =>
        generated(lower ++ (for a <- d; b <- d yield group.multiply(conjugate(a, b), group.inverse(b))))
      )
      Either.cond(
        down.last == lower,
        down.reverse,
        s"the subgroup of order ${upper.size} over its normal subgroup of order ${lower.size} is not solvable"
      )
    // lo ⊴ hi with hi/lo abelian: adjoin elements of prime order modulo what is built so far, one level each.
    def primeSteps(lo: Set[Int], hi: Set[Int]): Vector[(Int, Int)] =
      val out = Vector.newBuilder[(Int, Int)]
      var built = lo
      while built != hi do
        val y = hi.filterNot(built.contains).min
        val m = Iterator.from(1).find(k => built.contains(power(y, k))).get
        val p = (2 to m).find(m % _ == 0).get
        val x = power(y, m / p)
        out += ((x, p))
        built = generated(built + x)
      out.result()
    def pairs[A](xs: Vector[A]): Vector[(A, A)] = xs.zip(xs.drop(1))

    val subgroups = (Set(e) +: chain.toVector :+ (0 until group.order).toSet).distinct
    if !pairs(subgroups).forall((a, b) => a.subsetOf(b)) then Left("chain must be increasing")
    else
      val stepsOrFailure = pairs(subgroups).foldLeft[Either[String, Vector[(Int, Int)]]](Right(Vector.empty)) {
        case (done, (a, b)) =>
          for
            sofar <- done
            normalSteps <- subnormal(a, b)
            refined <- pairs(normalSteps).foldLeft[Either[String, Vector[(Int, Int)]]](Right(Vector.empty)) {
              case (acc, (lower, upper)) =>
                for d <- acc; series <- solvable(lower, upper) yield d ++ pairs(series).flatMap(primeSteps)
            }
          yield sofar ++ refined
      }
      stepsOrFailure.map { steps =>
        // K_i = <x_1, ..., x_i>; a normal form peels off one letter per level, top level first.
        val ks = steps.indices.scanLeft(Set(e))((k, i) => generated(k + steps(i)._1)).toVector
        def normalForm(g: Int): Vector[Int] =
          steps.indices.reverse
            .foldLeft((Vector.empty[Int], g)) { case ((word, rest), i) =>
              val (x, p) = steps(i)
              val k = (0 until p).find(k => ks(i).contains(group.multiply(group.inverse(power(x, k)), rest))).get
              if k == 0 then (word, rest) else (word ++ Vector.fill(k)(x), group.multiply(group.inverse(power(x, k)), rest))
            }
            ._1
        val levels = steps.zipWithIndex.map { case ((x, _), i) => x -> (i + 1) }
        RewritingSystem(group, (0 until group.order).map(normalForm), levels.toMap)
      }

/** What Brown's collapsing scheme does with a cell of the nerve: keeps it (critical), or matches it with a coface
  * (redundant) or a face (collapsible).
  */
enum CollapseRole:
  case Critical
  case Redundant(coface: NerveSimplex)
  case Collapsible(face: NerveSimplex)

/** The nerve of a monoid collapsed by Brown's collapsing scheme ("The geometry of rewriting systems", 1992): a discrete
  * Morse matching on the bar complex from a complete rewriting system, leaving one critical cell per Anick chain --
  * `n + 1` cells in dimension `n` for Z/4 where the bar complex has `3^n`. The critical cells, with the Morse boundary
  * ([[morseBoundary]]), form a chain complex with the same homology; [[lift]] maps them back to bar cycles.
  *
  * Filtered by a chain of subgroups that the rewriting system respects, the collapse keeps the barcode
  * ([[persistentHomology]]); [[RewritingSystem.refining]] builds such a system.
  */
open class CollapsedNerve(val rewriting: RewritingSystem):
  val nerve: Nerve = Nerve(rewriting.monoid)
  private def normalForm(x: Int): Vector[Int] = rewriting.normalForms(x)
  private def reducible(w: Vector[Int]): Boolean = !rewriting.isNormal(w)

  private val roles = mutable.HashMap.empty[NerveSimplex, CollapseRole]

  /** Brown's classification. `[x_1|...|x_n]` (entries read as normal forms) is essential up to `i` when `x_1` is a
    * letter and each `x_{j+1}` is the shortest word making `x_j x_{j+1}` reducible. At the first `j` where that fails:
    * if `x_j x_{j+1}` is irreducible the cell is collapsible (partner: merge them); otherwise a proper prefix `u` of
    * `x_{j+1}` already makes it reducible and the cell is redundant (partner: split `x_{j+1} = u v`). A first entry
    * longer than a letter is split after its first letter. Essential throughout: critical.
    */
  def role(s: NerveSimplex): CollapseRole = roles.getOrElseUpdate(s, classify(s))

  private def classify(s: NerveSimplex): CollapseRole =
    val xs = s.entries
    if xs.isEmpty then CollapseRole.Critical
    else if normalForm(xs(0)).length > 1 then
      val w = normalForm(xs(0))
      CollapseRole.Redundant(NerveSimplex(Vector(w.head, rewriting.evaluate(w.tail)) ++ xs.tail))
    else
      (0 until xs.length - 1).iterator
        .map { i =>
          val (left, right) = (normalForm(xs(i)), normalForm(xs(i + 1)))
          if !reducible(left ++ right) then
            Some(CollapseRole.Collapsible(NerveSimplex(xs.patch(i, Seq(rewriting.evaluate(left ++ right)), 2))))
          else
            val k = (1 to right.length).find(k => reducible(left ++ right.take(k))).get
            Option.when(k < right.length)(
              CollapseRole.Redundant(
                NerveSimplex(
                  xs.patch(i + 1, Seq(rewriting.evaluate(right.take(k)), rewriting.evaluate(right.drop(k))), 1)
                )
              )
            )
        }
        .collectFirst { case Some(r) => r }
        .getOrElse(CollapseRole.Critical)

  private val successors = mutable.HashMap.empty[Int, Vector[Int]]

  /** The entries `y` that may follow `x` in a critical cell: normal words `u`, as elements, with `x u` reducible but
    * `x u'` irreducible for every proper prefix `u'` of `u`.
    */
  private def nextEntries(x: Int): Vector[Int] = successors.getOrElseUpdate(
    x, {
      val prefix = normalForm(x)
      def grow(u: Vector[Int]): Vector[Int] =
        rewriting.letters.toVector.flatMap { a =>
          val w = u :+ a
          if !rewriting.isNormal(w) then Vector.empty
          else if reducible(prefix ++ w) then Vector(rewriting.evaluate(w))
          else grow(w)
        }
      grow(Vector.empty)
    }
  )

  /** The critical cells of dimension `n` (the Anick chains), generated directly from the rewriting system, without
    * visiting the bar complex.
    */
  def criticalCells(n: Int): IndexedSeq[NerveSimplex] =
    if n < 0 then IndexedSeq.empty
    else if n == 0 then IndexedSeq(NerveSimplex(Vector.empty))
    else
      (1 until n)
        .foldLeft(rewriting.letters.toVector.map(Vector(_)))((chains, _) =>
          for c <- chains; y <- nextEntries(c.last) yield c :+ y
        )
        .map(NerveSimplex(_))

  /** The normalized bar boundary with integer coefficients. */
  def barBoundary(s: NerveSimplex): Map[NerveSimplex, Long] =
    nerve
      .faces(s)
      .zipWithIndex
      .collect { case (SSetElement(Nil, t), i) => (t, if i % 2 == 0 then 1L else -1L) }
      .groupMapReduce(_._1)(_._2)(_ + _)
      .filter(_._2 != 0)

  // For a redundant s with partner t (incidence ±1): s = inc·∂t - inc·Σ_{f≠s} b_f f. So its critical part is
  // -inc·Σ b_f crit(f), and the bar chain carrying it there is -inc·t - inc·Σ b_f carry(f). Both are memoized over the
  // matching's DAG with an explicit stack (a long gradient path must not overflow the JVM stack) and an in-progress
  // set (a cycle means the matching is not acyclic: an error, never a hang).
  private val criticalPart = mutable.HashMap.empty[NerveSimplex, Map[NerveSimplex, Long]]
  private val carry = mutable.HashMap.empty[NerveSimplex, Map[NerveSimplex, Long]]

  private def addInto(acc: mutable.HashMap[NerveSimplex, Long], chain: Map[NerveSimplex, Long], factor: Long): Unit =
    try for (c, k) <- chain do acc(c) = Math.addExact(acc.getOrElse(c, 0L), Math.multiplyExact(factor, k))
    catch
      case _: ArithmeticException =>
        throw ArithmeticException(
          "a coefficient of the collapsed complex overflowed a Long; compute this group on the bar complex instead " +
            "(nerve.persistentHomology(ClassifyingSpace.filtrationBy(chain), maxDegree, prime))"
        )

  private def flowValue(
    root: NerveSimplex,
    memo: mutable.HashMap[NerveSimplex, Map[NerveSimplex, Long]],
    onCritical: NerveSimplex => Map[NerveSimplex, Long],
    ownTerm: (NerveSimplex, Long) => Map[NerveSimplex, Long]
  ): Map[NerveSimplex, Long] =
    def known(f: NerveSimplex): Option[Map[NerveSimplex, Long]] = role(f) match
      case CollapseRole.Critical       => Some(onCritical(f))
      case CollapseRole.Collapsible(_) => Some(Map.empty)
      case CollapseRole.Redundant(_)   => memo.get(f)
    known(root).getOrElse {
      val stack = mutable.Stack(root)
      val inProgress = mutable.HashSet(root)
      while stack.nonEmpty do
        val s = stack.top
        val t = role(s) match
          case CollapseRole.Redundant(coface) => coface
          case other                          => throw IllegalStateException(s"$s is $other, not redundant")
        val faces = barBoundary(t)
        faces.keys.find(f => f != s && known(f).isEmpty) match
          case Some(f) =>
            if inProgress.contains(f) then
              throw IllegalStateException(s"the matching is not acyclic: $f leads back to itself")
            stack.push(f)
            inProgress += f
          case None =>
            stack.pop()
            inProgress -= s
            val inc = faces.getOrElse(s, 0L)
            require(math.abs(inc) == 1, s"the incidence of $s in its partner $t is $inc, not ±1")
            val acc = mutable.HashMap.from(ownTerm(t, inc))
            for (f, b) <- faces if f != s do addInto(acc, known(f).get, -inc * b)
            memo(s) = acc.filter(_._2 != 0).toMap
      memo(root)
    }

  private val boundaries = mutable.HashMap.empty[NerveSimplex, Map[NerveSimplex, Long]]

  /** The boundary of the critical cell `c` in the collapsed complex: critical cells with integer coefficients. */
  def morseBoundary(c: NerveSimplex): Map[NerveSimplex, Long] = boundaries.getOrElseUpdate(
    c, {
      require(role(c) == CollapseRole.Critical, s"$c is not a critical cell")
      val acc = mutable.HashMap.empty[NerveSimplex, Long]
      for (s, a) <- barBoundary(c) do
        addInto(acc, flowValue(s, criticalPart, f => Map(f -> 1L), (_, _) => Map.empty), a)
      acc.filter(_._2 != 0).toMap
    }
  )

  /** The bar chain `ι(c)` of the critical cell `c`: `c` plus collapsible cells, with `∂ι(c) = ι(morseBoundary(c))`, so
    * `ι` maps cycles of the collapsed complex to cycles of the bar complex. Computed only when asked for.
    */
  def lift(c: NerveSimplex): Map[NerveSimplex, Long] =
    require(role(c) == CollapseRole.Critical, s"$c is not a critical cell")
    val acc = mutable.HashMap(c -> 1L)
    for (s, a) <- barBoundary(c) do addInto(acc, flowValue(s, carry, _ => Map.empty, (t, inc) => Map(t -> -inc)), a)
    acc.filter(_._2 != 0).toMap

  /** How many redundant cells the flow has visited so far (the memo of [[morseBoundary]]): the part of the bar complex
    * the collapse actually touches.
    */
  def redundantCellsVisited: Int = criticalPart.size

  /** The collapsed complex as cells: `dim`, and [[morseBoundary]] as the boundary. A member, like a simplicial set's
    * `cellInstance`: bring it into scope (`import collapsed.given`) to run an engine on [[stream]].
    */
  given collapsedCellInstance: (NerveSimplex is OrderedCell) = new (NerveSimplex is OrderedCell):
    override lazy val ordering = summon[Ordering[NerveSimplex]]
    extension (c: NerveSimplex)
      override def dim = c.dim
      override def boundary[F: Field as fr]: Seq[(NerveSimplex, F)] =
        morseBoundary(c).toSeq.map((s, a) => (s, CollapsedNerve.fromLong[F](a)))

  /** The critical cells of dimension `0..maxDegree + 1` inside the chain's last subgroup, filtered by `chain` (a cell
    * enters with the first subgroup containing its entries), as a stream every engine accepts with
    * [[collapsedCellInstance]]. The rewriting system must respect the chain (checked), or the collapse would match
    * cells of two levels and give a wrong barcode.
    */
  def stream(chain: Seq[Set[Int]], maxDegree: Int): StratifiedCellStream[NerveSimplex, Double] =
    require(maxDegree >= 0, s"maxDegree must be >= 0, got $maxDegree")
    require(chain.nonEmpty && chain.zip(chain.drop(1)).forall((a, b) => a.subsetOf(b)), "chain must be increasing")
    val violations = rewriting.respects(chain)
    require(
      violations.isEmpty,
      s"the rewriting system does not respect the chain (${violations.head}); build one with " +
        "RewritingSystem.refining(group, chain), or use the bar complex"
    )
    // With a chain ending below the whole group, the critical cells inside its last subgroup are that subgroup's
    // collapse (respecting the chain, the matching never leaves it).
    val top = chain.last
    val cells = IndexedSeq.tabulate(maxDegree + 2)(n => criticalCells(n).filter(_.entries.forall(top.contains)))
    val level = ClassifyingSpace.filtrationBy(chain)
    new StratifiedCellStream[NerveSimplex, Double]:
      val filtrationValue: PartialFunction[NerveSimplex, Double] = PartialFunction.fromFunction(level)
      override val filtrationOrdering: Ordering[NerveSimplex] =
        FiltrationOrdering.canonical(filtrationValue, _.dim, summon[Ordering[NerveSimplex]])
      override def iterateDimension: PartialFunction[Int, Iterator[NerveSimplex]] =
        case d if d >= 0 && d < cells.length => cells(d).sorted(using filtrationOrdering.reverse).iterator
      override def homologyDegreeLimit: Option[Int] = Some(maxDegree)
      export Filterable.DoubleIsFilterable.{largest, smallest}

  /** The persistent homology over `F_p` of the nerve filtered by `chain`, in degrees `0..maxDegree`, computed on the
    * collapsed complex: the same barcode as the bar complex's, with each representative lifted ([[lift]]) to a cycle of
    * the bar complex made of simplices present at the bar's birth.
    */
  def persistentHomology(chain: Seq[Set[Int]], maxDegree: Int, prime: Int): PersistenceDiagram[NerveSimplex] =
    require(prime > 1 && BigInt(prime).isProbablePrime(certainty = 100), s"$prime is not a prime")
    val field = new FiniteField(prime)
    import field.given
    val state =
      CellularPersistenceInChunksEngine[NerveSimplex, field.Fp](maxDegree).persistentHomology(stream(chain, maxDegree))
    val bars = state.barcodeAt(Double.PositiveInfinity).map { bar =>
      val lifted = bar.annotation.map { rep =>
        val terms = rep.terms
          .flatMap((c, a) => lift(c).toSeq.map((s, k) => (s, a * CollapsedNerve.fromLong[field.Fp](k))))
          .groupMapReduce(_._1)(_._2)(_ + _)
          .toSeq
          .filter((_, a) => !a.isZero)
        Chain.from[NerveSimplex, field.Fp](terms)
      }
      PersistenceBar(bar.dim, bar.lower, bar.upper, lifted)
    }
    PersistenceDiagram[NerveSimplex, field.Fp](bars, maxDegree, state.lastFiltrationValue)

object CollapsedNerve:
  /** The integer `a` in the field `F` (by doubling: logarithmically many additions). */
  def fromLong[F: Field as fr](a: Long): F =
    var result = fr.zero
    var base = fr.one
    var n = math.abs(a)
    while n > 0 do
      if (n & 1L) == 1L then result = result + base
      base = base + base
      n = n >> 1
    if a < 0 then -result else result

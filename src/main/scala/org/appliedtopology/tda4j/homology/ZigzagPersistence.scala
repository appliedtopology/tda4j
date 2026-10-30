package org.appliedtopology.tda4j

import scala.collection.mutable

/** One step of a simplex-wise zigzag filtration: a cell enters the complex, or a cell leaves it. */
enum ZigzagStep[CellT]:
  /** The cell enters; its facets are already in the complex. */
  case Add(cell: CellT)

  /** The cell leaves; no cell that stays has it as a facet. */
  case Remove(cell: CellT)

/** Zigzag persistent homology: the homology of a sequence of complexes in which each is included in the next or the
  * next in it, in either direction, as a barcode of intervals. A sequence `K_0, K_1, ..., K_n` is read in positions
  * `0..n`, and a bar `[b, d)` is a class that is in the homology of `K_b, ..., K_{d-1}` and in neither neighbour; the
  * `d` of a class still there at `K_n` is infinite. With every arrow forward this is the ordinary barcode of a
  * filtration with one cell, or one batch of cells, entering at each position.
  *
  * {{{
  * val steps = Seq(Add(Simplex(0)), Add(Simplex(1)), Add(Simplex(0, 1)), Remove(Simplex(0, 1)))
  * val diagram = ZigzagPersistence(steps)
  * }}}
  *
  * The input is a list of [[ZigzagStep]]s (`apply`), a list of complexes each included in the next or the next in it
  * (`ofComplexes`), with the union or the intersection of consecutive complexes put between them when they are not
  * (`Link.Union`, as for a time-varying point cloud). The result is an ordinary [[PersistenceDiagram]]: its bars have a
  * representative, a cycle in the complex at the bar's birth, and every tool for diagrams applies.
  *
  * The decomposition is made from left to right, one arrow at a time, on the homology of the complexes (Carlsson and de
  * Silva): a basis of the homology at the current position, each class with its birth, in an order that the arrows
  * decide (a class born by an arrow forward goes last, one born in the kernel of an arrow backward goes first). An
  * arrow forward reduces the images of the classes in that order and the classes that vanish end. An arrow backward
  * puts the classes of the smaller complex in the basis of the current homology, adapted to the order, and the classes
  * outside its image end. Only the dimensions an arrow touches are recomputed, by sparse elimination on the boundary
  * matrices of the larger complex of the arrow: each arrow costs a reduction of those, so the method is meant for
  * sequences of up to a few thousand cells in the dimensions that change.
  */
object ZigzagPersistence:

  /** What is put between two consecutive complexes that are not included in each other. */
  enum Link:
    /** Nothing: the complexes must be nested, in one direction or the other. */
    case None

    /** The union of the two: `K_i ⊂ K_i ∪ K_{i+1} ⊃ K_{i+1}`, one position more for each pair. */
    case Union

    /** The intersection of the two: `K_i ⊃ K_i ∩ K_{i+1} ⊂ K_{i+1}`, one position more for each pair. */
    case Intersection

  private final case class Move[CellT](cells: Seq[CellT], forward: Boolean)

  /** The zigzag of the complexes `initial`, then `initial` with the first step done, and so on; position 0 is
    * `initial`. Cells of dimension above `maxDimension + 1` are left out, because they cannot change the homology up to
    * `maxDimension`.
    */
  def apply[CellT: OrderedCell](
    steps: Seq[ZigzagStep[CellT]],
    initial: Iterable[CellT] = Nil,
    maxDimension: Int = 2,
    characteristic: Int = FiniteField.DefaultPrime,
    coefficients: Optional[Coefficients] = Optional.empty
  ): PersistenceDiagram[CellT] =
    val moves = steps.map:
      case ZigzagStep.Add(cell)    => Move(Seq(cell), forward = true)
      case ZigzagStep.Remove(cell) => Move(Seq(cell), forward = false)
    val stepped = steps.map:
      case ZigzagStep.Add(cell)    => cell
      case ZigzagStep.Remove(cell) => cell
    solve(initial.toSeq, moves, initial ++ stepped, maxDimension, characteristic, coefficients)

  /** The zigzag of a list of complexes, given by their cells, each included in the next or the next in it, or connected
    * through the [[Link]] chosen.
    */
  def ofComplexes[CellT: OrderedCell](
    complexes: Seq[Iterable[CellT]],
    link: Link = Link.None,
    maxDimension: Int = 2,
    characteristic: Int = FiniteField.DefaultPrime,
    coefficients: Optional[Coefficients] = Optional.empty
  ): PersistenceDiagram[CellT] =
    require(complexes.nonEmpty, "ZigzagPersistence.ofComplexes needs at least one complex")
    val sets = complexes.map(_.toSet)
    val universe = complexes.flatten
    // The cells of `from` or `to` that are not in the other, in the order they came in.
    def arrow(from: Set[CellT], to: Set[CellT], position: Int): Move[CellT] =
      if from.subsetOf(to) then Move(universe.filter(c => to(c) && !from(c)).distinct, forward = true)
      else if to.subsetOf(from) then Move(universe.filter(c => from(c) && !to(c)).distinct, forward = false)
      else
        throw new IllegalArgumentException(
          s"ZigzagPersistence.ofComplexes: complexes $position and ${position + 1} are not included in each other; " +
            "give link = Link.Union or Link.Intersection to put their union or intersection between them"
        )
    val moves = (0 until sets.size - 1).flatMap: i =>
      val (a, b) = (sets(i), sets(i + 1))
      link match
        case Link.None         => Seq(arrow(a, b, i))
        case Link.Union        => Seq(arrow(a, a ++ b, i), arrow(a ++ b, b, i))
        case Link.Intersection => Seq(arrow(a, a & b, i), arrow(a & b, b, i))
    solve(universe.filter(sets.head).distinct, moves, universe, maxDimension, characteristic, coefficients)

  private def solve[CellT](
    initial: Seq[CellT],
    moves: Seq[Move[CellT]],
    universe: Iterable[CellT],
    maxDimension: Int,
    characteristic: Int,
    coefficients: Optional[Coefficients]
  )(using cells: CellT is OrderedCell): PersistenceDiagram[CellT] =
    require(maxDimension >= 0, s"ZigzagPersistence: maxDimension must be 0 or more, got $maxDimension")
    val field = coefficients.toOption match
      case Some(chosen) =>
        require(
          characteristic == FiniteField.DefaultPrime || characteristic == chosen.characteristic,
          s"ZigzagPersistence: characteristic = $characteristic and coefficients = $chosen disagree; give one of them"
        )
        chosen
      case None => Coefficients(characteristic)
    import field.given
    val bars = new Run[CellT, field.C](maxDimension, universe).execute(initial, moves)
    PersistenceDiagram[CellT, field.C](
      bars,
      maxDimension,
      moves.size.toDouble,
      representatives = Some(Representatives.Cycles)
    )

  // A birth, a death (the position after the last complex with the class, none if it is there at the end) and the cycle at
  // the birth.
  private final case class Bar[F](dim: Int, birth: Int, death: Option[Int], chain: collection.Map[Int, F])

  // The state of one run. Cells get numbers, in the order of the input, and everything is a sparse vector over those numbers.
  private final class Run[CellT, F](maxDimension: Int, universe: Iterable[CellT])(using
    cells: CellT is OrderedCell,
    fr: F is Field
  ):
    private type Vec = mutable.HashMap[Int, F]

    private val ids = mutable.HashMap.empty[CellT, Int]
    private val byId = mutable.ArrayBuffer.empty[CellT]
    private val dims = mutable.ArrayBuffer.empty[Int]
    private val boundaries = mutable.HashMap.empty[Int, Array[(Int, F)]]
    universe.foreach(idOf)

    // the cells of each dimension that are in the complex now; the cells of dimension maxDimension + 1 give the boundaries
    // of the top degree, and cells above that are not tracked
    private val present = Array.fill(maxDimension + 2)(mutable.HashSet.empty[Int])

    // The generators of the homology of the complex now: a cycle that is a basis of it for each class, with the cycle it was
    // born as. They are kept in an order in which a generator may be replaced by itself plus generators before it: a class
    // born by an arrow forward goes last, a class born in the kernel of an arrow backward goes first (Carlsson and de Silva).
    // A generator's cycle changes when an arrow backward replaces it by such a combination.
    private final class Generator(val birth: Int, val born: Vec, var chain: Vec)
    private val generators = Array.fill(maxDimension + 1)(mutable.ArrayBuffer.empty[Generator])
    private val bars = mutable.ListBuffer.empty[Bar[F]]

    private def idOf(cell: CellT): Int =
      ids.getOrElseUpdate(
        cell, {
          byId += cell
          dims += cells.dim(cell)
          byId.size - 1
        }
      )

    private def tracked(id: Int): Boolean = dims(id) <= maxDimension + 1

    private def faces(id: Int): Array[(Int, F)] =
      boundaries.getOrElseUpdate(id, cells.boundary(byId(id))[F].map((face, k) => (idOf(face), k)).toArray)

    private def vec(entries: IterableOnce[(Int, F)] = Nil): Vec = mutable.HashMap.from(entries)

    // into += factor * from
    private def add(into: Vec, factor: F, from: collection.Map[Int, F]): Unit =
      for (i, k) <- from do
        val updated = fr.plus(into.getOrElse(i, fr.zero), fr.times(factor, k))
        if fr.isEqual(updated, fr.zero) then into.remove(i) else into(i) = updated

    // Rows with distinct leading entries (the largest or the smallest index), each with a payload; reducing a vector by them
    // adds up the payloads of the rows used, scaled the way the rows were.
    private final class Echelon(highest: Boolean):
      private val rows = mutable.HashMap.empty[Int, (Vec, Vec)]
      private def lead(v: Vec): Int = if highest then v.keysIterator.max else v.keysIterator.min

      // Reduces `v` in place while its leading entry has a row, adding `factor * payload` of each row used to `acc`.
      def reduce(v: Vec, acc: Vec): Unit =
        var active = v.nonEmpty
        while active do
          val l = lead(v)
          rows.get(l) match
            case Some((row, payload)) =>
              val factor = fr.divide(v(l), row(l))
              add(v, fr.negate(factor), row)
              v.remove(l)
              add(acc, factor, payload)
              active = v.nonEmpty
            case None => active = false

      def insert(v: Vec, payload: Vec): Unit = rows(lead(v)) = (v, payload)

      def payloads: Map[Int, Vec] = rows.view.mapValues(_._2).toMap

    // The rows of the boundaries of the (k+1)-cells now there: a basis of the boundaries of dimension k.
    private def boundaryEchelon(k: Int): Echelon =
      val echelon = Echelon(highest = true)
      for id <- present(k + 1).toArray.sorted do
        val v = vec(faces(id))
        echelon.reduce(v, vec())
        if v.nonEmpty then echelon.insert(v, vec())
      echelon

    // A basis of the cycles of dimension k: the kernel of the boundary, by eliminating the columns in order and keeping the
    // combination that reduces to zero.
    private def cycleBasis(k: Int): Vector[Vec] =
      val echelon = Echelon(highest = true)
      val cycles = Vector.newBuilder[Vec]
      for id <- present(k).toArray.sorted do
        val v = vec(faces(id))
        val acc = vec()
        echelon.reduce(v, acc)
        val chain = vec(Seq(id -> fr.one))
        add(chain, fr.negate(fr.one), acc)
        if v.isEmpty then cycles += chain else echelon.insert(v, chain)
      cycles.result()

    // Cycles that are a basis of the homology of dimension k, and an echelon form in which a cycle is written in them: the
    // payload of a row is the cycle it stands for in terms of the basis, so reducing a cycle gives its coordinates.
    private def homologyBasis(k: Int): (Vector[Vec], Echelon) =
      val echelon = boundaryEchelon(k)
      val basis = Vector.newBuilder[Vec]
      var size = 0
      for cycle <- cycleBasis(k) do
        val v = vec(cycle)
        val acc = vec()
        echelon.reduce(v, acc)
        if v.nonEmpty then
          val labels = vec(Seq(size -> fr.one))
          add(labels, fr.negate(fr.one), acc)
          echelon.insert(v, labels)
          basis += cycle
          size += 1
      (basis.result(), echelon)

    // The same echelon form for the basis of the homology of dimension k given by the generators: the label of a row is the
    // number of the generator.
    private def withGenerators(k: Int): Echelon =
      val echelon = boundaryEchelon(k)
      for (generator, t) <- generators(k).zipWithIndex do
        val v = vec(generator.chain)
        val acc = vec()
        echelon.reduce(v, acc)
        if v.isEmpty then
          throw new IllegalStateException(s"ZigzagPersistence: the generators of dimension $k are dependent")
        val labels = vec(Seq(t -> fr.one))
        add(labels, fr.negate(fr.one), acc)
        echelon.insert(v, labels)
      echelon

    private def coordinates(echelon: Echelon, cycle: Vec): Vec =
      val v = vec(cycle)
      val coordinates = vec()
      echelon.reduce(v, coordinates)
      if v.nonEmpty then
        throw new IllegalStateException("ZigzagPersistence: a cycle is not in the span of the homology basis")
      coordinates

    private def requireClosed(added: Iterable[Int], where: String): Unit =
      for id <- added; (face, _) <- faces(id) do
        require(
          present(dims(face)).contains(face),
          s"ZigzagPersistence: ${byId(id)} is in the complex $where but its facet ${byId(face)} is not"
        )

    private def affected(changed: Iterable[Int]): Seq[Int] =
      changed.flatMap(id => Seq(dims(id), dims(id) - 1)).filter(k => k >= 0 && k <= maxDimension).toSeq.distinct.sorted

    def execute(initial: Seq[CellT], moves: Seq[Move[CellT]]): List[PersistenceBar[Double, Chain[CellT, F]]] =
      val start = initial.map(idOf).filter(tracked).distinct
      start.foreach(id => present(dims(id)) += id)
      requireClosed(start, "at position 0")
      for k <- 0 to maxDimension do for cycle <- homologyBasis(k)._1 do generators(k) += Generator(0, cycle, cycle)
      for (move, index) <- moves.zipWithIndex do
        if move.forward then forward(move.cells, index + 1) else backward(move.cells, index + 1)
      for k <- 0 to maxDimension; generator <- generators(k) do bars += Bar(k, generator.birth, None, generator.born)
      val ordering = Ordering.by[CellT, Int](ids)
      bars.toList
        .sortBy(b => (b.dim, b.birth, b.death.getOrElse(Int.MaxValue)))
        .map: bar =>
          val upper = bar.death.fold[BarcodeEndpoint[Double]](PositiveInfinity[Double]())(d => OpenEndpoint(d.toDouble))
          PersistenceBar(
            bar.dim,
            ClosedEndpoint(bar.birth.toDouble),
            upper,
            Some(Chain.from(bar.chain.toSeq.map((i, k) => (byId(i), k)))(using ordering))
          )

    // K -> K + cells: the classes of K are mapped into the homology of the larger complex and reduced by the elder rule, the
    // ones that vanish end, and the classes of the larger complex outside the image are born.
    private def forward(added: Seq[CellT], position: Int): Unit =
      val changed = added.map(idOf).filter(tracked).distinct
      for id <- changed do
        require(
          !present(dims(id)).contains(id),
          s"ZigzagPersistence: step $position adds ${byId(id)}, which is there already"
        )
      changed.foreach(id => present(dims(id)) += id)
      requireClosed(changed, s"after step $position")
      for k <- affected(changed) do
        val (basis, echelon) = homologyBasis(k)
        val images = Echelon(highest = false)
        val kept = mutable.ArrayBuffer.empty[Generator]
        for generator <- generators(k) do
          val y = coordinates(echelon, generator.chain)
          images.reduce(y, vec())
          if y.isEmpty then bars += Bar(k, generator.birth, Some(position), generator.born)
          else
            images.insert(y, vec())
            kept += generator
        for (cycle, j) <- basis.zipWithIndex do
          val unit = vec(Seq(j -> fr.one))
          images.reduce(unit, vec())
          if unit.nonEmpty then
            images.insert(unit, vec())
            kept += Generator(position, cycle, cycle)
        generators(k).clear()
        generators(k) ++= kept

    // K + cells -> K, the map on homology going the other way: the classes of K are written in the generators of K + cells, in
    // the order of their births; the classes of the larger complex outside the image end, the ones inside continue as the
    // cycles of K that stand for them, and the classes of K that map to zero are born.
    private def backward(removed: Seq[CellT], position: Int): Unit =
      val changed = removed.map(idOf).filter(tracked).distinct
      for id <- changed do
        require(
          present(dims(id)).contains(id),
          s"ZigzagPersistence: step $position removes ${byId(id)}, which is not there"
        )
      val larger = affected(changed).map(k => k -> withGenerators(k)).toMap
      changed.foreach(id => present(dims(id)) -= id)
      for id <- changed; d = dims(id) + 1; if d <= maxDimension + 1; other <- present(d); (face, _) <- faces(other) do
        require(
          face != id,
          s"ZigzagPersistence: step $position removes ${byId(id)}, which is a facet of ${byId(other)}"
        )
      for k <- larger.keys.toSeq.sorted do
        val (basis, _) = homologyBasis(k)
        val images = Echelon(highest = true)
        val kernel = mutable.ArrayBuffer.empty[Vec]
        for cycle <- basis do
          val y = coordinates(larger(k), cycle)
          val acc = vec()
          images.reduce(y, acc)
          val preimage = vec(cycle)
          add(preimage, fr.negate(fr.one), acc)
          if y.isEmpty then kernel += preimage else images.insert(y, preimage)
        val continuing = images.payloads
        val kept = mutable.ArrayBuffer.empty[Generator]
        for (generator, t) <- generators(k).zipWithIndex do
          continuing.get(t) match
            case Some(chain) =>
              generator.chain = chain
              kept += generator
            case None => bars += Bar(k, generator.birth, Some(position), generator.born)
        // The classes born here are in front of the order, the ones born by an arrow forward are behind it: a class may be
        // replaced by itself plus classes before it, and a class born in the kernel of an arrow backward has no older class
        // to add to it (such an automorphism does not commute with the arrow), but may be added to every older one.
        generators(k).clear()
        generators(k) ++= kernel.map(cycle => Generator(position, cycle, cycle))
        generators(k) ++= kept
  end Run
end ZigzagPersistence

package org.appliedtopology.tda4j

import scala.annotation.targetName

/** Elementary cubes for cubical complexes.
  *
  * An elementary cube in ambient dimension `n` is a product `I_1 x I_2 x ... x I_n` where each factor `I_k` is
  * either a degenerate interval `[a,a]` (a single lattice point) or a unit interval `[a,a+1]`. The cube's own
  * dimension is the number of non-degenerate (unit-interval) factors, not `n` -- `n` (the ambient/embedding
  * dimension) is fixed per complex, the length of every `Cube`'s own coordinate vector.
  *
  * Represented via the standard "doubled coordinate" encoding used throughout the cubical-homology literature
  * (Kaczynski-Mischaikow-Mrozek, *Computational Homology*): axis `k` is encoded as `2*a` for the degenerate
  * interval `[a,a]`, or `2*a+1` for the unit interval `[a,a+1]`. This makes both "is this axis degenerate" (parity)
  * and "what is its lower lattice coordinate" (halve, rounding down) O(1) per axis, and gives a cube a single
  * `Vector[Int]` as its entire representation -- no separate bitmask needed.
  *
  * `opaque type` over `Vector[Int]`, not `Array`/`IArray`: `Vector` has structural `equals`/`hashCode`, which
  * `Chain`'s pivot tables (`mutable.Map[CellT, Chain[...]]`, `SortedMap[CellT, CoefficientT]`) depend on to collide
  * two structurally-identical cubes -- an array-backed opaque type would silently use reference equality instead
  * and corrupt every reduction that touches two independently-constructed copies of the same cube. See
  * `.claude/WORKLOG-cubical.md` for the derivation (caught by the advisor before any code was written, not found by
  * debugging a corrupted reduction after the fact).
  */
opaque type Cube = Vector[Int]

object Cube:
  /** The primitive constructor every other one below reduces to: direct doubled-coordinate encoding (`2*a` =
    * degenerate `{a}`, `2*a+1` = unit interval `[a,a+1]`), one entry per ambient axis.
    */
  def fromEncoded(coords: Seq[Int]): Cube = Vector.from(coords)
  @targetName("fromEncodedVarargs")
  def fromEncoded(coords: Int*): Cube = fromEncoded(coords.toSeq)

  /** The vertex (0-dimensional cube, every axis degenerate) at integer lattice point `coords`. */
  def vertex(coords: Seq[Int]): Cube = Vector.from(coords.map(k => 2 * k))
  @targetName("vertexVarargs")
  def vertex(coords: Int*): Cube = vertex(coords.toSeq)

  /** The top-dimensional cube (every axis non-degenerate) whose lower corner sits at lattice point `coords` -- e.g.
    * the cube corresponding to pixel/voxel `coords` in a dense grid.
    */
  def unitCube(coords: Seq[Int]): Cube = Vector.from(coords.map(k => 2 * k + 1))
  @targetName("unitCubeVarargs")
  def unitCube(coords: Int*): Cube = unitCube(coords.toSeq)

  /** General constructor: `lower` gives each axis's lower lattice coordinate, `nondegenerate` marks which axes are
    * unit intervals (every other axis is a degenerate point at its own `lower` value).
    */
  def apply(lower: Seq[Int], nondegenerate: Set[Int]): Cube =
    Vector.tabulate(lower.length)(i => if nondegenerate(i) then 2 * lower(i) + 1 else 2 * lower(i))

  def unapplySeq(cube: Cube): Option[Seq[Int]] = Some(cube.encoded)

// Named `encoded`/`asCube`, NOT `underlying`/`asSimplex` (which would be the more obvious mirror of
// Simplex.scala's naming) -- a same-named top-level extension for a DIFFERENT, unrelated receiver type
// (Simplex[VertexT]'s own `underlying`) already exists in this package, and Scala 3's extension-method
// resolution does not fall back to try it once a same-named candidate for a non-matching receiver type is
// found: every `Simplex[VertexT].underlying` call site elsewhere in the codebase failed to compile with
// "value underlying is not a member of Simplex[VertexT] ... Required: Cube" the first time this was named
// `underlying` here. Confirmed empirically, not just reasoned through -- see WORKLOG-cubical.md.
extension (c: Cube) def encoded: Vector[Int] = c
extension (coords: Vector[Int]) def asCube: Cube = coords

/** Ergonomic accessors, kept distinct from `dim`/`boundary` (which live on the `OrderedCell` instance below, per
  * `Simplex.scala`'s own convention of putting the `Cell`/`OrderedCell` contract methods only there).
  */
extension (c: Cube)
  def ambientDim: Int = c.encoded.length
  def isNondegenerate(axis: Int): Boolean = Math.floorMod(c.encoded(axis), 2) == 1
  def isDegenerate(axis: Int): Boolean = !c.isNondegenerate(axis)
  def lowerCoordinate(axis: Int): Int = Math.floorDiv(c.encoded(axis), 2)
  def nondegenerateAxes: Seq[Int] = c.encoded.indices.filter(c.isNondegenerate)
  /** Lattice coordinates: for a top-dimensional cube (every axis non-degenerate) this is exactly the pixel/voxel
    * grid index it corresponds to.
    */
  def latticeCoordinates: Seq[Int] = c.encoded.indices.map(c.lowerCoordinate)
  def isVertex: Boolean = c.nondegenerateAxes.isEmpty
  def isTop: Boolean = c.nondegenerateAxes.length == c.ambientDim
  // `describe`, not `show` -- same same-name-different-receiver collision as `underlying` above
  // (Simplex.scala/SimplexOps.scala already defines a top-level `show` extension).
  def describe: String =
    c.encoded.indices
      .map(i => if c.isNondegenerate(i) then s"[${c.lowerCoordinate(i)},${c.lowerCoordinate(i) + 1}]" else s"{${c.lowerCoordinate(i)}}")
      .mkString("Cube(", "x", ")")

/** Canonical (filtration-independent) total order on cubes: lexicographic on the doubled-coordinate encoding
  * itself. Only ever compares cubes of equal `ambientDim` in practice (ambient dimension is fixed per complex --
  * see the class doc above), but is a genuine total order regardless, including across mismatched lengths (shorter
  * is a prefix-less-than), matching `Ordering[Seq[Int]]`'s usual lexicographic convention.
  */
def cubeOrdering: Ordering[Cube] = new Ordering[Cube]:
  def compare(x: Cube, y: Cube): Int =
    val xu = x.encoded
    val yu = y.encoded
    val n = math.min(xu.length, yu.length)
    var i = 0
    var result = 0
    while result == 0 && i < n do
      result = Integer.compare(xu(i), yu(i))
      i += 1
    if result != 0 then result else Integer.compare(xu.length, yu.length)

/** `Cube is OrderedCell` instance -- mirrors `Simplex.scala`'s `Simplex_is_OrderedCell` parameterized-given
  * pattern exactly, so a stream can inject its own filtration-aware ordering the same way
  * `EnumeratingCofaceSimplexStream` etc. do for `Simplex[Int]`.
  *
  * Boundary formula (Kaczynski-Mischaikow-Mrozek's standard cubical boundary operator, matching exactly what was
  * asked for: "collapse each unit interval to a degenerate interval both ways, opposite signs, once per unit-
  * interval factor"): for a cube with non-degenerate axes at positions `p_1 < p_2 < ... < p_d` (0-indexed into the
  * coordinate vector, `d` = the cube's own dimension), the boundary is
  *
  * `sum_{l=1}^{d} (-1)^(l-1) * (cube with axis p_l collapsed to its UPPER endpoint - cube with axis p_l collapsed
  * to its LOWER endpoint)`
  *
  * i.e. the sign alternates over the RANK of the axis among non-degenerate axes (`l`, 0-indexed as `rank` below),
  * not over its raw position in the coordinate vector -- using the raw position instead is a real, easy-to-make
  * sign bug that breaks d(d(x)) = 0 as soon as a cube has a degenerate axis interleaved among its non-degenerate
  * ones. Flagged by the advisor before this was written (see WORKLOG-cubical.md) and verified below by
  * `CubicalSpec`'s dd=0 property test over a signed field (F3), not just F2 -- F2 cannot distinguish a correct
  * alternating sign from a constant one, since -1 = 1 there.
  */
def Cube_is_OrderedCell(setOrdering: Ordering[Cube] = cubeOrdering): Cube is OrderedCell =
  new (Cube is OrderedCell):
    override lazy val ordering = setOrdering
    extension (c: Cube)
      override def dim: Int = c.nondegenerateAxes.length
      override def boundary[CoefficientT: Field as fr]: Seq[(Cube, CoefficientT)] =
        val coords = c.encoded
        c.nondegenerateAxes.zipWithIndex.flatMap { case (axis, rank) =>
          val k = Math.floorDiv(coords(axis), 2)
          val upperSign = if rank % 2 == 0 then fr.one else fr.negate(fr.one)
          val lowerSign = fr.negate(upperSign)
          val upperCube: Cube = coords.updated(axis, 2 * k + 2)
          val lowerCube: Cube = coords.updated(axis, 2 * k)
          Seq(upperCube -> upperSign, lowerCube -> lowerSign)
        }

given default_Cube_is_OrderedCell: Cube is OrderedCell = Cube_is_OrderedCell()

package org.appliedtopology.tda4j

import scala.annotation.targetName

/** An elementary cube: a product `I_1 x ... x I_n` of intervals, each a point `[a, a]` or a unit interval `[a, a+1]`.
  * Its dimension is the number of unit intervals; `n`, the ambient dimension, is the same for every cube of a complex.
  *
  * Coordinates are doubled (Kaczynski, Mischaikow, Mrozek, ''Computational Homology''): `[a, a]` is `2a` and `[a, a+1]`
  * is `2a + 1`, so a cube is one `Vector[Int]` (with structural equality, which the reductions rely on).
  */
opaque type Cube = Vector[Int]

object Cube extends CubeInstances:
  /** The primitive constructor every other one below reduces to: direct doubled-coordinate encoding (`2*a` = degenerate
    * `{a}`, `2*a+1` = unit interval `[a,a+1]`), one entry per ambient axis.
    */
  def fromEncoded(coords: Seq[Int]): Cube = Vector.from(coords)
  @targetName("fromEncodedVarargs")
  def fromEncoded(coords: Int*): Cube = fromEncoded(coords.toSeq)

  /** The vertex (0-dimensional cube, every axis degenerate) at integer lattice point `coords`. */
  def vertex(coords: Seq[Int]): Cube = Vector.from(coords.map(k => 2 * k))
  @targetName("vertexVarargs")
  def vertex(coords: Int*): Cube = vertex(coords.toSeq)

  /** The top-dimensional cube (every axis non-degenerate) whose lower corner sits at lattice point `coords` -- e.g. the
    * cube corresponding to pixel/voxel `coords` in a dense grid.
    */
  def unitCube(coords: Seq[Int]): Cube = Vector.from(coords.map(k => 2 * k + 1))
  @targetName("unitCubeVarargs")
  def unitCube(coords: Int*): Cube = unitCube(coords.toSeq)

  /** General constructor: `lower` gives each axis's lower lattice coordinate, `nondegenerate` marks which axes are unit
    * intervals (every other axis is a degenerate point at its own `lower` value).
    */
  def apply(lower: Seq[Int], nondegenerate: Set[Int]): Cube =
    Vector.tabulate(lower.length)(i => if nondegenerate(i) then 2 * lower(i) + 1 else 2 * lower(i))

  def unapplySeq(cube: Cube): Option[Seq[Int]] = Some(cube.encoded)

  // `encoded`/`show` live inside `object Cube` (this companion), the same fix as `Simplex.underlying`/`SimplexOps`
  // (see `Simplex.scala`'s own doc for the full mechanism) -- collision-scoped by nominal receiver type instead of
  // top-level package-wide visibility, which is also why they were never renamed back to `underlying`/`show` once
  // the actual name collision that first forced this move was gone. `asCube` (below, kept top-level OUTSIDE `object
  // Cube`) is the same exception as `Simplex.asSimplex`: its receiver is `Vector[Int]`, not `Cube`, so companion-
  // object lookup (keyed by receiver type) would never find it there.
  extension (c: Cube) def encoded: Vector[Int] = c

  /** Ergonomic accessors, kept distinct from `dim`/`boundary` (which live on the `OrderedCell` instance below, per
    * `Simplex.scala`'s own convention of putting the `Cell`/`OrderedCell` contract methods only there).
    */
  extension (c: Cube)
    def ambientDim: Int = c.encoded.length
    def isNondegenerate(axis: Int): Boolean = Math.floorMod(c.encoded(axis), 2) == 1
    def isDegenerate(axis: Int): Boolean = !c.isNondegenerate(axis)
    def lowerCoordinate(axis: Int): Int = Math.floorDiv(c.encoded(axis), 2)
    def nondegenerateAxes: Seq[Int] = c.encoded.indices.filter(c.isNondegenerate)

    /** Lattice coordinates: for a top-dimensional cube (every axis non-degenerate) this is exactly the pixel/voxel grid
      * index it corresponds to.
      */
    def latticeCoordinates: Seq[Int] = c.encoded.indices.map(c.lowerCoordinate)
    def isVertex: Boolean = c.nondegenerateAxes.isEmpty
    def isTop: Boolean = c.nondegenerateAxes.length == c.ambientDim
    def show: String =
      c.encoded.indices
        .map(i =>
          if c.isNondegenerate(i) then s"[${c.lowerCoordinate(i)},${c.lowerCoordinate(i) + 1}]"
          else s"{${c.lowerCoordinate(i)}}"
        )
        .mkString("Cube(", "x", ")")

extension (coords: Vector[Int]) def asCube: Cube = coords

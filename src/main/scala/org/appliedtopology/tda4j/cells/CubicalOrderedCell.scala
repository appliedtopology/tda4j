package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}

/** `cubeOrdering`/`Cube_is_OrderedCell` -- split into their own file from `Cubical.scala` (where the opaque type `Cube`
  * and its companion object live) rather than kept alongside them, and this split is required for correctness, not just
  * organizational preference. Same underlying reason as `SimplexOrderedCell.scala`'s split from `Simplex.scala` -- see
  * that file's doc for the general explanation (opaque-type transparency, scoped to the WHOLE FILE a top-level opaque
  * type is declared in, defeats companion-object-based extension resolution for any `.someExtensionMethod` call made
  * from that same file, silently for names that coincide with a real member of the underlying representation type and
  * with a hard compile error otherwise). Confirmed empirically for `Cube` specifically, not just assumed to generalize
  * from the `Simplex` case: moving this content back into `Cubical.scala` reproduces "value encoded is not a member of
  * Cube" / "value nondegenerateAxes is not a member of Cube" at exactly the `x.encoded`/`c.nondegenerateAxes` call
  * sites below.
  *
  * One additional wrinkle specific to `Cube`, absent from the `Simplex` case: `boundary`'s original implementation
  * (written when this lived inside `Cubical.scala`) built new cube values via a raw coercion --
  * `val upperCube: Cube = coords.updated(axis, 2 * k + 2)`, relying on `Vector[Int] =:= Cube` transparency directly,
  * which -- unlike an extension-method call -- is *only* ever available inside `Cubical.scala` itself and cannot be
  * exported to a companion object at all. Moving this file out required replacing that coercion with an explicit
  * `.asCube` call (`Cubical.scala`'s own top-level extension on `Vector[Int]`, callable from anywhere): `.asCube`
  * itself still relies on the transparency, but only in the file where it's defined, exactly like every other extension
  * here.
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

/** `Cube is OrderedCell` instance -- mirrors `Simplex.scala`'s `Simplex_is_OrderedCell` parameterized-given pattern
  * exactly, so a stream can inject its own filtration-aware ordering the same way `EnumeratingCofaceSimplexStream` etc.
  * do for `Simplex[Int]`.
  *
  * Boundary formula (Kaczynski-Mischaikow-Mrozek's standard cubical boundary operator, matching exactly what was asked
  * for: "collapse each unit interval to a degenerate interval both ways, opposite signs, once per unit- interval
  * factor"): for a cube with non-degenerate axes at positions `p_1 < p_2 < ... < p_d` (0-indexed into the coordinate
  * vector, `d` = the cube's own dimension), the boundary is
  *
  * `sum_{l=1}^{d} (-1)^(l-1) * (cube with axis p_l collapsed to its UPPER endpoint - cube with axis p_l collapsed to
  * its LOWER endpoint)`
  *
  * i.e. the sign alternates over the RANK of the axis among non-degenerate axes (`l`, 0-indexed as `rank` below), not
  * over its raw position in the coordinate vector -- using the raw position instead is a real, easy-to-make sign bug
  * that breaks d(d(x)) = 0 as soon as a cube has a degenerate axis interleaved among its non-degenerate ones. Flagged
  * by the advisor before this was written (see WORKLOG-cubical.md) and verified below by `CubicalSpec`'s dd=0 property
  * test over a signed field (F3), not just F2 -- F2 cannot distinguish a correct alternating sign from a constant one,
  * since -1 = 1 there.
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
          val upperCube: Cube = coords.updated(axis, 2 * k + 2).asCube
          val lowerCube: Cube = coords.updated(axis, 2 * k).asCube
          Seq(upperCube -> upperSign, lowerCube -> lowerSign)
        }

given default_Cube_is_OrderedCell: Cube is OrderedCell = Cube_is_OrderedCell()

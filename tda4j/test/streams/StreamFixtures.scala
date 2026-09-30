package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.cells.{given, *}

/** Shared test-only stream-building helpers, used by specs in both `streams` and `homology` (the latter already depends
  * on `streams` in main code, so this direction is consistent with that layering). Lives here, not in
  * `homology.HomologyFixtures`, because `ExplicitStream`/`ExplicitStreamBuilder` themselves are `streams` types.
  */
object StreamFixtures:
  def explicitStream(cells: Seq[(Double, Simplex[Int])]): ExplicitStream[Int, Double] =
    val builder = ExplicitStreamBuilder[Int, Double]
    builder.addAll(cells)
    builder.result()

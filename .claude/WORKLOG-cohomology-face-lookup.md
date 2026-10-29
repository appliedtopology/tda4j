# Cohomology on a Vietoris-Rips stream: the face lookups and the transposition (2026-10-29, #251)

Point-in-time snapshot. Second half of #251 (the first is `WORKLOG-cohomology-stream-sort.md`: the layer sort replaced by
the generator). After that change a JFR profile of `Engine.Cohomology` on the 250-point cloud put `PackedCells.face` (the
open-addressing probe in `slotOf`) at 61% of the samples, and each lookup reads `slots` and then the vertices of the cell
it points to: two cache misses. A/B driver: `CohomologyStreamDriver generator cohomology <workload>` (test scope), variant
A = the tree of PR #261, variant B = this change, one JVM per variant, `-Xms8g -Xmx8g -XX:+UseParallelGC`.

## What was built (`CellularCohomologyEngine.PackedCells`, `PackedReduction.coboundaries`, `Cohomology.scala`)

- **A face is found by one `Long` key.** When the vertices of a level start at 0 and `width * bits(largest vertex) +
  bits(cells) <= 63`, the table holds `(vertices side by side << idBits) | (number + 1)` and a lookup compares the key in
  the slot, one miss, 8 bytes a slot instead of 4. Otherwise (vertices from another origin, or too wide) the old table of
  `Int` numbers hashed over the vertices stays. `keyOf` returns -1 for a vertex below 0 or above the level's largest: a
  face of a cell above may carry a vertex that no cell of this level has (a stream whose upper level is not closed under
  faces), and a vertex with more bits than the others would carry into its neighbour and read as another face
  (`PackedSimplexCohomologySpec`, the triangle {0, 1, 21} over edges on 0..15).
- **The coboundary is transposed in column ranges.** The scatter from "cofacet j has face f" to "column f lists cofacet j"
  was one sequential pass (3.2 to 3.6 s of the 250-point cloud). Now `parts` tasks (the threads, at most 8; one below 4096
  faces) each own a range of columns, read every face in order, count and then place only the ones in their range, so the
  writes are disjoint and each column still lists its cofacets in ascending order whatever the number of tasks.
- **Degrees above the stream's limit are not reduced.** `PackedReduction` takes the limit and stops its reduction loop at
  `min(topDim, limit)`; the top block was reduced (about 1 s on the cloud) and its bars thrown away. The result cannot
  change, only the cost, so no spec can see it.

## Measurements

`Persistence(stream, maxDimension = 2, engine = Cohomology, representatives = Cycles)`, stream built before the clock,
median of 5 trials, two JVMs per variant, two series (the first on the build that still had the phase timers and a
property to switch the keys off, the second on the tree as committed):

| input | A (PR #261) | B | bars |
|---|---|---|---|
| 150 points on S^2 | 6.5, 6.6 s; 7.2, 6.5 s | 4.96, 5.2 s; 4.8, 5.3 s | 190 |
| 250 uniform points in [0,1]^3 | 13.0, 12.4 s; 11.8, 13.0 s | 8.1, 7.8 s; 9.1, 8.7 s | 375 |

Machine noise here is about 10%: the trials within a JVM range over 1 to 2 s on the cloud, and the two series differ by
more than the two JVMs of one series. The gain is 1.2 to 1.5 times on the sphere and 1.3 to 1.6 times on the cloud.

Phases of the 250-point cloud (43.2M tetrahedra), one JVM with wall-clock timers around each phase (the timers are gone):
lookups 2.4 s to 1.2 to 1.5 s; transposition ("rest" of `coboundaries`) 3.2 to 3.6 s to 1.8 s; the top-degree reduction
about 1 s skipped. Memory: the smallest `-Xmx` under SerialGC at which the sphere finishes stays ~1.1 GB (the peak is the
`faces` and `rows` arrays of the dimension-2 block, which this change does not touch).

## Checks

- `PackedSimplexCohomologySpec` (9 examples): the packed reduction equals the unpacked one on the existing streams plus
  the same grid with its vertices relabelled from 2^20 (edges keyed, triangles hashed), 2^28 (all hashed) and -5; one
  thread against 2, 3, 5 and 8 on a 40-point cloud (the cut moves with the thread count; one count alone did not catch a
  column read twice at a boundary, because only a column that gets added to another shows its entries); a stream missing
  the edges at its last vertex (compared on cohomology only: `Involution` assumes a valid complex and throws on it); the
  carry example above.
- Mutations, each caught: no range check on a face's vertices (the carry example fails); the count pass closed at the top
  of a column range (the 4-thread-count comparison throws in `Involution`); both passes closed at the top (a bar differs
  at 8 threads); both passes open at the bottom (a column loses its cofacets: the unpacked comparison fails on the
  alpha streams and the thread comparison throws).
- Not caught, and equivalent: a duplicated cell keeping its first number instead of its last in the keyed table (a valid
  stream has no duplicate cells).

## Left

- Batched probing (hash a block of faces, then verify) is not built; the lookups are 1.2 to 1.5 s of 8 on the cloud, the
  transposition's counting pass reads every face once per task and is the next step if the thread count grows.
- `Generator.edgesWithin` boxes both vertices of each distance call (8 to 10% of the lattice profile, #251).
- Scala.js was not timed (`Parallel` runs the tasks in order there; the specs run).

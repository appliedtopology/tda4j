# The packed Ripser engine decodes a column once (2026-10-29, #194)

Point-in-time snapshot. #194 lists five edits to `PackedRipserCohomologyEngine`; the first is "decode once per column"
(half the engine's samples were in `SimplexIndexing.decodeToArray`), and a comment on it (2026-10-24, from the
emergent-pairs profile, `WORKLOG-emergent-pairs.md`) adds that `zeroPivotFacet` recomputes every facet's diameter from
scratch. This is those two. A/B: the driver `SingleEngineProfileDriver packed point-cloud <file> <maxDim> <threshold>
<trials> true true <prime>` (test scope), A = `scala` at dd7592e, B = this change, one JVM per run, `-Xms6g -Xmx6g
-XX:+UseParallelGC`, two JVMs per variant. The driver prints `barsDigest` and `cocyclesDigest` (SHA-256 of the sorted
bars, and of the bars with their cocycle terms): they are identical for A and B on every input below, over F_2, and over
F_17 on the 10-d cloud.

## Where the time went (JFR, cube1000: 1,000 uniform points in [0,1]^3, degrees 0..2, threshold 0.4, neighbour lists)

A column went through `decodeToArray` four to six times: `zeroPivotCofacet` decoded it for the diameters and the cursor
decoded it again in its constructor; `zeroPivotFacet(tie)` decoded the cofacet twice the same way; and if the cofacet's
youngest facet was not the column itself, `zeroPivotCofacet(facet)` decoded the facet twice more. `decodeToArray` was 26%
of the samples (22% on the dense 16-d cloud, after the first fix, so more before), `zeroPivotFacet` 26% inclusive (its `diameterWithout`
rebuilt each facet's vertex array and ran O(d^2) distance calls: O(d^3) a column).

## What was built

- `SimplexIndexing`/`WideSimplexIndexing`: `CofacetCursor` and `FacetCursor` take the decoded vertices (the old
  constructors remain and decode), `cofacetCursor(index, vertices, all)` and `facetCursor(index, vertices)`, and the
  facet cursor says the `position` of the vertex it removed.
- The engine's `eachCofacetOf` takes the decoded vertices and hands the added vertex to its callback. A column is decoded
  once: its oldest same-diameter cofacet comes with its vertices (the column's with one inserted at the reported
  position), and that cofacet's facets are its vertices with one removed.
- `zeroPivotFacet` finds the youngest same-diameter facet from one pass over the pairs of the cofacet's vertices: a facet
  keeps the diameter exactly when some longest edge avoids the removed vertex, or when the diameter is 0 (every facet,
  down to a single vertex, has diameter 0 then). That is O(d^2) a column, and it compares the same stored doubles as
  before (`distance(vertices(i), vertices(j)) == tau.diameter`, ascending pairs).

## Measurements (4-core sandbox, F_2, median of 5 to 7 trials in the JVM, two JVMs per variant, ms)

| input | A | B | bars | simplices |
|---|---|---|---|---|
| 1,000 uniform points in [0,1]^3, degrees 0..2, t = 0.4 (neighbour lists) | 5,716; 5,777 | 4,778; 4,618 | 1000/495/126 | 2.68M |
| 1,000 points on S^2, degrees 0..2, t = 0.6 | 1,876; 1,918 | 1,408; 1,307 | 1000/272/4 | 0.85M |
| 40 uniform points in [0,1]^10, degrees 0..6, no threshold | 2,937; 2,962 | 1,778; 1,646 | | |
| 60 uniform points in [0,1]^16, degrees 0..5, no threshold | 2,951; 2,927 | 1,915; 1,930 | | |

That is 1.2 times on the cube, 1.3 to 1.5 on the sphere, 1.5 on the 16-d cloud and 1.7 on the 10-d cloud. The input is
random and the machine noisy to about 5%, the two JVMs of a variant agree to that.

## Tried and not kept

- `Array.fill(size)(true)` (in the first version of the facet test) and `Array.tabulate` in `SparseCofacetCursor`'s
  constructor show up as `ClassTag$.apply` at 11% of a JFR profile. Removing the first (`new Array[Boolean]` with the
  meaning inverted, kept) and the second changed the wall time by nothing I could see (4.69/4.79 s against 4.8 s). JFR's
  attribution moved between runs (the 11% reappeared as `SortIndices.sort` at 15%), so only the A/B counts.
- `CofacetCursor.containsVertex` (a binary search of the simplex's vertices for every vertex `j` of the scan) replaced by
  the `above` pointer that the two-word cursor has: 14% of the dense profile, no change in wall time (1.74 and 1.92 s
  against 1.73 and 1.83 to 1.93 s). Not kept.

## Checks

- `SimplexIndexingSpec`, `WideSimplexIndexingSpec`: cursors over the decoded vertices walk what the cursors over the index
  walk (cofacets with `allCofacets` both ways, facets with their `position`), and do not touch the array.
- `EmergentPairsSpec` (new example): the packed engine's `apparentPairCount` equals the reference engine's, on the tie
  grids with coincident points. The apparent pairs change no output, so nothing else can see a wrong test for them.
- Mutations, each caught: the endpoints of a longest edge counted as avoiding it (out-of-memory in the working column,
  specs error); no special case for diameter 0 (only the new count example fails); only the first longest edge counted;
  the cofacet's facet losing vertex 0 instead of the removed one; the cofacet's vertex inserted one place to the left.
- `tda4j/testFull`: 1,905 passed, 0 failed, 8 skipped.

## Left (#194)

- Sorting each degree (`SortIndices.sort` through a closure) is 5 to 19% of the samples: the primitive key of item 2.
- `LongIntMap.update` in `report` is about 10% on the cube; `decodeToArray` is still 9 to 22% (one per column, one per
  simplex when the next degree is built).
- Item 3 (apparent pairs from the block's edge, O(k)) and item 5 (H0 by union-find) are not built.

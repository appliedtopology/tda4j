# Worklog: how a simplex's vertices are stored (2026-10-23)

Point-in-time snapshot. Question (Mikael): boxing and `Simplex` keep coming up as expensive; is there a point in a storage
model that shares most of the `SortedSet` contract, e.g. sorted arrays kept sorted by the `Simplex` methods?
Result: issue #208 (the recommendation), #209 (a bigger boxing cost found on the way), a comment on #206.
No library code changed; the prototype lives in the test-scope driver.

## State before

`opaque type Simplex[V] = SortedSet[V]`; for `Int` vertices a `TreeSet` of boxed `Integer`s (~880 B allocated per
5-vertex simplex in `WORKLOG-layered-inorder.md`). The opaque type erases to `SortedSet` in every descriptor, and
`underlying`/`toSortedSet`/`asSimplex`/`fromSortedSet` are public (35 uses in main outside `cells/`, 14 in tests and docs).
Non-`Int` vertices and a non-natural `Ordering[Int]` are pinned by `PackedSimplexCohomologySpec` (the cell fallback).
The hot paths already avoid the object: `FlatLayerStream.Layer` (#206), `PackedCells`, the packed Ripser engine's
combinatorial `Long` index (two words past 63 bits, `WORKLOG-two-word-indices.md`). What is left carrying `Simplex`
objects: alpha, Cech's adapter, explicit and user streams, the generic engines, representatives, `sset`.

## Method

`src/test/scalajvm/.../cells/SimplexStorageDriver.scala` (`SimplexStorageDriver`, `SimplexShareDriver`). One
representation per JVM (`-Xms6g -Xmx6g -XX:+UseParallelGC`), 1M random simplices over 300 vertices, widths 3/5/8, each
operation 7 trials, the first 2 dropped, median reported with the minimum. Representations: `treeset` (the library's
`Simplex.from(ArraySeq)` as `FlatLayerStream.simplexOf` calls it), `sortedset-array` (`IntArraySet extends
immutable.SortedSet[Int]`, reached through the same `SimplexOps` calls), `array` (a final class around `Array[Int]`, cached
hash), `array-nohash`, `boxed-array` (`Array[AnyRef]` + `Ordering`), `long` (combinatorial index in an open-addressing
`Long -> Int` map: the engines' floor, not a `Simplex`). `treeset`, `array` and `sortedset-array` ran twice; the tables use
the second run, which agreed with the first within ~25 % (machine noise here is that large; only ratios of 2x and up are
claimed). `IntArraySet` is checked against `TreeSet` on 2000 random sets (`hashCode` equal, `==` both ways, `incl`,
`excl`, `iteratorFrom`, `rangeFrom`, `rangeUntil`, a `HashMap` lookup across classes). JFR (`settings=profile`) for the
end-to-end shares, aggregated with an inclusive-frame script over `jfr print --events jdk.ExecutionSample --stack-depth 64`
and `jdk.ObjectAllocationSample`. Classpath built by hand from `target/out/.../{test-classes,classes}` and the coursier jars
(`scala-library-3.9.0`, `scala3-library_3-3.9.0`, cats).

## Results (per simplex, width 3 / 5 / 8)

| | `TreeSet` | `SortedSet` over `Array[Int]` | boxed `Array[AnyRef]` | `Long` index |
|---|---|---|---|---|
| retained B | 148 / 230 / 353 | 56 / 64 / 72 | 84 / 110 / 145 | 8 |
| build ns (B allocated) | 135 / 172 / 283 (484 / 798 / 1417) | 34 / 23 / 18 (32 / 40 / 48) | 64 / 42 / 58 | 3 / 5 / 12 |
| get, 1M entries, fresh key ns | 1056 / 1065 / 1239 | 357 / 294 / 356 | 331 / 406 / 509 | 44 / 63 / 69 |
| get, 20k entries ns | 250 / 459 / 654 | 112 / 110 / 72 | 92 / 113 / 181 | 18 / 22 / 20 |
| all faces ns | 133 / 355 / 709 | 148 / 133 / 152 | | 16 / 43 / 102 |
| lex sort ns per simplex | 1582 / 1721 / 1756 | 622 / 682 / 723 | | 94 / 96 / 104 |
| iterate ns | 29 / 41 / 80 | 5 / 8 / 15 | | 1 |

The array family is 3-4x on a keyed lookup, 4-16x on construction, 2.6-4.9x on memory, 7-9x on iteration, 2.5x on the
lexicographic sort; faces are equal at width 3 and 2.4-4.7x at widths 5 and 8. The `Long` is another 4-7x on lookups, which
is why the engines use it and why it is not a candidate for `Simplex` (needs the vertex count or a bit budget; `Long` is
emulated on Scala.js). The plain-`array` harness shows an unstable median in "all faces" at widths 5 and 8 (median 1.1 and
2.5 us, minimum 0.12 and 0.14 us, in both runs, while the `sortedset-array` prototype with the same data layout is steady): a
harness artifact I did not chase; the minimum is the number used for the array family there.

End to end (JFR, 4 cores, same JVM for the trials):

| run | time | `Simplex`/tree-set machinery | where the rest is |
|---|---|---|---|
| `Persistence(10,000 pts in R^3, AlphaShapes)`, degrees 0..2, 53,661 bars | 7.2 s cold, 4.2 s warm | 5.6 % of samples; `immutable.RedBlackTree`/`TreeSet` are 1.3 % of bytes | `FiniteMetricSpace.minimumEnclosingRadius` 39 % of samples, `java.lang.Double` 39 % of the 24 GB allocated (#209); the Delaunay and face tables ~41 % |
| `Persistence(80 pts in R^3, Cech)`, degrees 0..2, 265 bars | 12-13 s | 15 % of samples inclusive; ~28 % of bytes are `RedBlackTree`/`TreeSet` nodes; GC pauses 5.5 s of 38 s | the ball solve (`computeRadius`, `distanceToCenter`, `Solver`) 32 %, with `Object[]` 12.8 % and `double[]` 18 % of bytes (#206) |

`minimumEnclosingRadius` alone on the 10,000 points: 2.81 / 2.19 / 1.72 s (#209).

## Verdict

- The premise holds per object (5-10x the memory, 15-30x the allocation of a construction) and fails per run: where the
  profiles were taken, the object is 6 % (alpha) to 15 % plus GC (Cech) of the time. The boxing that costs most is not in
  `Simplex`: the all-pairs radius and the Cech ball.
- The right shape is a sorted `Int` array BEHIND the `SortedSet` contract, selected by `Ordering[V] eq Ordering.Int`
  (never by a runtime class test of the elements: Scala.js), `TreeSet` kept for other vertex types and orders. It keeps the
  opaque type, the erased descriptors (no MiMa entry), `underlying`/`asSimplex`, and the `hashCode` of `Set` so a `TreeSet`
  and the array set of the same elements are one key. Details and the list of what to override are in #208.
- Rejected, with the reason: an opaque type straight over `Array[Int]` (reference `==`/`hashCode`: a silent wrong answer as
  a key, 500+ sites); `Long` as the user-facing cell; a non-generic `Simplex`; a boxed array for all `V`.

## Not done

Scala.js numbers (cloud sessions cannot run the JS build reliably; `test-js` on CI is the first run). The implementation
itself, and a before/after on Cech and alpha (#208 holds the acceptance criteria). Locality of real streams (the keys here
are random). `CircularCoordinates`, `sset`, `plot` were read, not profiled.

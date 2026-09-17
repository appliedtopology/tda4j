# Worklog: `SimplexIndexing`'s silent `Int` overflow, and migrating combinatorial indices to `Long`

Session date: 2026-09-16/17 (continuing from WORKLOG-reference-engine-fix.md's session). Direct follow-up to
WORKLOG-dimension-ceiling.md's bug 2 (`RipserCohomologyContext` throws `ArrayIndexOutOfBoundsException` in the
sparse-threshold regime at higher build dimensions, localized but not fixed there). Picked back up on the
project lead's prompt ("we needed to pick up an indexing error in RipserCohomologyContext too, right?").

## Root cause, confirmed directly

`RipserStream.scala`'s `binomial(n: Int, k: Int): Int = binomialBigint(n, k).intValue` computes the correct value
via `BigInt` internally, then silently truncates it through `.intValue` -- no overflow check, no exception, just a
two's-complement wraparound. Measured directly: `C(229, 5) = 5,022,337,545` truncates to `727,370,249`; `C(229, 6)
= 187,500,601,680` truncates to `-1,477,959,344` (negative). `SimplexIndexing`'s entire combinatorial-number-system
machinery (`binomialTable`, `apply`/encode-decode, `cofacetIterator`, `facetIterator`) is built on this function, so
once a complex's vertex count and dimension push `C(n,k)` past `Int.MaxValue` (~2.1 billion) -- confirmed to happen
already at n=230, k=5, well within a realistic "hundreds of points, moderate dimension" use case, exactly the
sparse-threshold regime this codebase added specifically to support bigger point clouds -- every consumer
downstream gets a corrupted index. That corrupted index eventually decodes to a simplex containing a vertex outside
`[0, vertexCount)`, surfacing many calls later as `ArrayIndexOutOfBoundsException` in `EuclideanMetricSpace.distance`
(inside `insertionDiameter`, called from `zeroPivotCofacet`) -- a confusing, far-from-the-cause crash for what is
actually a silent-corruption bug much earlier in the call chain.

## Scope decision -- asked, not assumed

Two fixes were possible: (a) make `binomial` fail loudly on overflow instead of silently truncating (small, safe,
but leaves the actual size ceiling unchanged -- `RipserCohomologyContext` still couldn't compute this case, just
fails clearly instead of confusingly), or (b) migrate combinatorial indices from `Int` to `Long` throughout
`SimplexIndexing` and its consumers (actually raises the ceiling, but touches many call sites and needs its own
validation). Asked the project lead directly rather than picking for them (AskUserQuestion) -- answer: **(b), the
full migration**. The project lead separately confirmed `ripser.cpp` itself uses a 64-bit type (`long long`/
`int64_t`) for exactly this purpose, which is the same reason `Long` (not `BigInt`) is the right target: it matches
upstream Ripser's own convention, and it's dramatically cheaper than arbitrary-precision arithmetic in the hottest
code path in the engine (`Ordering[Simplex[Int]]`'s comparator, consulted on every `SortedMap`/`PriorityQueue`
operation during reduction) for no benefit at any problem size that's actually computable in practice.

## Scope map (surveyed before editing, not discovered mid-edit)

Every call site of `binomial`/`SimplexIndexing`/`.si(...)` in `../src/main`, found by grep and read directly:

- **`RipserStream.scala`**: `binomial` itself; `SimplexIndexing` class (`binomialTable`, `apply` encode/decode,
  `cofacetIterator`, `facetIterator`); and, below the file's own "Maybe @deprecate or outright everything below
  here?" marker, five more classes that ALSO call `SimplexIndexing`/`binomial` directly: `RipserStreamSparse`,
  `RipserStreamBase` (and its subclasses `RipserStream`, `SymmetricRipserStream`), `MaskedSymmetricRipserStream`.
  These are legacy/pre-`RipserCohomologyContext` code, but NOT dead in the "excluded from the build" sense --
  they're exercised by `SimplexIndexingSpec.scala`'s own `RipserStreamSpec` class (apparent-pairs tests, a
  hypercube-symmetry cross-validation), so they need to keep compiling AND keep passing, not just compile.
- **`Homology.scala`**: `RipserCohomologyContext`'s `si: SimplexIndexing` field, `compareFvThenIndex` (the shared
  tie-break comparator, currently `java.lang.Integer.compare`), `cohomologyOrdering`/`diameterSimplexOrdering`,
  `coboundaryOf`, `zeroPivotCofacet`, `zeroPivotFacet` -- everywhere `si(...)` is called to encode/decode.
- **`SimplexIndexingSpec.scala`**: direct test coverage of `SimplexIndexing.apply`/`cofacetIterator`/
  `facetIterator`, plus `RipserStreamSpec` exercising the legacy classes above.
- **Confirmed NOT in scope**: `EnumeratingCofaceSimplexStream`-family streams (`SimplexStream.scala`) use Apache
  Commons' `BinomialCoefficient.value(...).toInt` directly -- a different mechanism, not this codebase's own
  `binomial`/`SimplexIndexing` -- and those call sites are already self-limiting (`(0 until n).toSeq` needs an
  actual `Int`-sized range to iterate at all, so a genuinely astronomical `C(n,k)` there means an impractically
  slow/memory-exhausting enumeration long before an overflow would matter, not a silent-corruption risk the way
  `SimplexIndexing`'s O(1) encode/decode arithmetic is). `SymmetryGroup.scala`/`SymmetricZomorodianIncremental`:
  grepped directly, no `SimplexIndexing`/`binomial` usage at all. `totalSimplexCount` (`RipserCohomologyContext`,
  and every benchmark spec that reads it): a plain simplex counter, unrelated to combinatorial index magnitude,
  bounded by available memory long before `Int` range regardless -- left untouched.

## Judgment calls, recorded as they were made

1. **`binomial`'s parameters stay `Int`; only its return value becomes `Long`.** `n`/`k` are vertex counts and
   dimension-derived sizes, realistically bounded by the point cloud's own `Int`-sized cardinality
   (`FiniteMetricSpace.size: Int`) -- only the *combinatorial index itself* can be astronomically larger than
   either input.
2. **`binomial` gets its own overflow guard even after the `Long` migration**, not just a silent `.longValue`:
   `require(big.isValidLong, ...)`. `Long` is not infinite either (an astronomically larger `n`/`k` combination
   could in principle still overflow it), so this keeps the "fail loud, not silently wrong" property this session
   already established for `Chain.scala`'s zero-testing, rather than re-introducing the same class of silent bug
   one order of magnitude further out.
3. **The legacy `RipserStream.scala` classes below the "Maybe @deprecate" marker are updated, not left broken or
   deleted.** They share the exact same `SimplexIndexing`/`binomial` machinery being fixed, and deleting or
   ignoring them wasn't asked for -- only the indexing bug was.

## Implementation

Changed in `RipserStream.scala`:
- `binomial(n: Int, k: Int): Long` (was `Int`) -- computes via the existing `binomialBigint`, then
  `require(big.isValidLong, ...)` before `.longValue` (see judgment call 2 above).
- `SimplexIndexing`: `apply(n: Long, d: Int, ...)`  (decode, was `Int`), `apply(simplex): Long` (encode, was
  `Int`), `cofacetIterator`/`topCofacetIterator`/`facetIterator` all now index/yield `Long`.
- `RipserStreamSparse`/`RipserStreamBase`/`RipserStream`/`SymmetricRipserStream`/`MaskedSymmetricRipserStream`
  (the legacy classes below the "Maybe @deprecate" marker, still exercised by `SimplexIndexingSpec.scala`'s
  `RipserStreamSpec`): `retain`/`expand`/`zeroPivotCofacet`/`zeroPivotFacet`/`zeroApparentCofacet`/
  `zeroApparentFacet` all re-typed from `index: Int` to `index: Long`; `(0 until binomial(...))` changed to
  `(0L until binomial(...))` so the range itself is `Long`-indexed.

Changed in `Homology.scala` (`RipserCohomologyContext`): `compareFvThenIndex`'s `xIdx`/`yIdx` parameters
`Int -> Long`, its tie-break `java.lang.Integer.compare -> java.lang.Long.compare`. `cohomologyOrdering`,
`diameterSimplexOrdering`, `coboundaryOf`, `zeroPivotCofacet`, `zeroPivotFacet` needed no direct edits -- they
call `si(...)` and consume whatever it returns, so the type change propagated through inference once the
`SimplexIndexing`/`compareFvThenIndex` signatures were fixed.

Changed in `SimplexStream.scala` (`EnumeratingCofaceSimplexStream`, found only by a full compile, not by the
initial grep survey -- see below): `filtrationOrdering`'s tie-break and `sortedByFiltration`'s memoized `ix`
cache both called a lowercase `simplexIndexing` field (an *instance*, not the `SimplexIndexing` *class* name my
initial grep searched for) via `Ordering.Int.compare(simplexIndexing(x), simplexIndexing(y))` -- changed to
`Ordering.Long.compare`, and the `ixCache: mutable.HashMap[Simplex[Int], Int]` to `..., Long]`.

Changed in `RipserCohomologySpec.scala`: a dead test helper `totalSimplices(n, maxDim): Int` (defined, never
actually called -- only referenced in comments explaining why it's *not* used at two call sites) retyped to
`Long` to keep compiling; no behavior to verify since nothing calls it.

**A second, structurally-related bug found and fixed in the same pass, not part of the original plan**:
`SimplexIndexing`'s `binomialTable` was an *eagerly*-computed full `(vertexCount+1) x (vertexCount+1)` grid --
`(0 to vertexCount).map(d => (0 to vertexCount).map(s => binomial(d+s, s)))` -- built unconditionally at
construction time, regardless of which `(d, s)` pairs any real call would ever need. In every real call, `d` is
bounded by the *simplex size* being encoded/decoded (small: `apply`'s own recursion only decreases `d`, starting
from an actual simplex's vertex count), never by `vertexCount`. For `vertexCount=230`, the eager construction
still iterated `d` up to 230, and hit `binomial(207, 195)` (`d=12, s=195`) -- a pair no real cofacet/facet/
encode/decode call for this codebase's actual dimension range would ever need -- which correctly (now) throws on
overflow, but did so at `SimplexIndexing`'s *constructor*, before any actual work could happen. Under the old
silently-truncating `Int` version of `binomial`, this exact waste was already happening -- it just silently
produced garbage nobody read, instead of failing loudly, so it was invisible until this fix's overflow guard
made it visible. Fixed by replacing the eager table with `binomialCache: mutable.Map[(Int,Int), Long]`, computed
on demand and memoized per `(d, s)` pair actually queried, plus a hand-rolled binary search (`searchRow`)
replacing `scala.collection.Searching.search` (which needed a fully materialized, indexable row) -- functionally
identical to the old `Found`/`InsertionPoint - 1` logic (verified: `SimplexIndexingSpec`'s hand-checked worked
examples from the Ripser paper still pass unchanged), just evaluated lazily.

**A third consumer found only by a full compile, not the initial grep survey** (recorded as a process lesson,
not just a fix): `SimplexStream.scala`'s `simplexIndexing` field (lowercase instance name) was missed by grepping
for `SimplexIndexing` (the class name) -- only surfaced as a compile error once `Test/compile` was actually run.
This is why the scope map above was verified by compiling, not trusted from grep alone.

## Verification

- Original repro (`n=230`, `ambientDim=3`, sparse threshold `~0.165`, from WORKLOG-dimension-ceiling.md): both
  `buildDim=2` (previously working: `cells=881, bars=494`, unchanged) and `buildDim=4` (previously crashing) now
  succeed (`cells=992, bars=519`).
- `SimplexIndexingSpec` (hand-verified worked examples from the Ripser paper, encode/decode/cofacet/facet
  round-trips) and `RipserStreamSpec` (the legacy classes' own test coverage) both pass unchanged.
- Pushed `DimensionCeilingBenchmarkSpec`'s sparse-regime sweep well past the original crash point: both
  `Ripser H=3(sparse)` and `Ripser H=4(sparse)` now reach `n=608` (the sweep's own cap, not a failure) cleanly,
  with no exceptions anywhere -- roughly 2.6x past the original `n=230` crash point, not just barely past it.
- Full `sbt clean test`, twice (once before the final `scalafmtAll` pass, once after): 164 total, 160 passed, 0
  failed, 0 errors, 4 skipped, 1 pending -- identical to the pre-existing baseline, no regressions, including
  `DimensionCeilingBenchmarkSpec`'s previously-crashing cells now completing cleanly as part of the same run.
- `scalafmtCheck`/`scalafmtSbtCheck` clean after `scalafmtAll`.

## Status: resolved

Both bugs from WORKLOG-dimension-ceiling.md are now fixed: the `SimplicialHomologyContext` reference-engine
crash (WORKLOG-reference-engine-fix.md) and this `RipserCohomologyContext` indexing bug. Not committed -- per
standing project convention, the project lead commits their own work.

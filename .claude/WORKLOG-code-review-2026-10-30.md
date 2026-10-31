# WORKLOG: source review for correctness and human readability (2026-10-30)

## Scope

Reviewed the post-pull `scala` checkout, focusing on the public point-cloud metric, the packed and parallel Ripser
engines, `Persistence` dispatch, and the newly added discrete Morse and zigzag implementations. The previous whole-source
critique and its fixes were consulted to avoid repeating resolved findings. This was a static review: no source behavior
was changed and no tests or builds were run.

## Findings

1. **Ragged coordinates can silently change the metric** — `streams/FiniteMetricSpace.scala:182-192`. The common
   `PointCloud` path validates equal row lengths, but `EuclideanMetricSpace` has a public constructor that accepts raw
   arrays, and `pointSqDistance` uses `min(x.length, y.length)`. Direct construction with different coordinate counts
   silently drops the extra coordinates and produces a non-Euclidean distance. Validate once at construction or make
   the unchecked constructor inaccessible.
2. **The metric's array input is mutable and aliased** — `streams/PointCloud.scala:8-20` and
   `streams/FiniteMetricSpace.scala:182-219`. `pts` is public and retains the caller's arrays while distance and
   enclosing-radius caches are lazy. Mutating an input after either cache is first read can leave the metric object with
   stale values; with `cacheDistances = false`, later distance reads can instead see the mutation. The result therefore
   depends on cache timing. Define snapshot/ownership semantics and enforce them (for example by copying on construction).
3. **Parallel cocycle expansion is recursively unbounded** — `homology/ParallelPackedCohomology.scala:955-970`.
   `cocycle` recursively expands the reduction-log dependency DAG. Its depth has no explicit bound, so a long chain of
   column substitutions can exhaust the JVM stack. `PackedRipserCohomology.scala:538-568` already expands the same kind
   of dependency iteratively with an explicit stack; use that pattern here.
4. **The public laziness description overpromises per-bar laziness for cycles** — `homology/Persistence.scala:73-78`.
   The `computeAllRepresentatives = false` parameter says unobserved bars cost nothing, but `allCycles` at lines 299-305
   calls `ParallelRipserCycles.cycles`, which computes the cycle set in one pass on the first cycle read. The user guide
   already documents this exception; mirror that qualification in the parameter Scaladoc.
5. **An implementation helper leaks into the flat public namespace** —
   `homology/PackedRipserCohomology.scala:6-20`. `insertionDiameter` is a public top-level generic helper in the core
   package and appears under users' `import org.appliedtopology.tda4j.*`, despite being engine machinery. Move it into a
   suitable companion or restrict it to the package, consistent with the flat-namespace rule.
6. **Integer coefficients in a persistence map are converted in linear time and mishandle `Int.MinValue`** —
   `barcode/PersistenceMap.scala:190-193`. `integer(n)` adds one `abs(n)` times; a large valid coefficient makes map
   construction take billions of operations, while `math.abs(Int.MinValue)` overflows and the resulting range is empty,
   silently mapping that coefficient to zero. Use a logarithmic conversion or narrow/document the accepted coefficient
   range.
7. **Bootstrap resamples eagerly build representatives they never use** — `barcode/BootstrapBand.scala:61-69`.
   The method compares only bar endpoints, but calls `Persistence` with `computeAllRepresentatives` left at `true`.
   When `Auto` selects `ParallelRipser`, it expands every cocycle for the original and every resample. Pass
   `computeAllRepresentatives = false` for this endpoint-only workload; the other engines ignore that option.

## Readability assessment

The primitive heap and array layouts have measured reasons to exist; replacing them wholesale with collection-based
code would undo known performance work. The harder-to-maintain part is orchestration: `ParallelPackedCohomology.persistence`
(`:691-943`) owns unit construction, four reduction phases, pairing/bar collection, clearing, and next-layer generation,
while `PackedRipserCohomology.scala` also combines public cell decoding, index representation, cofacet generation,
working-column reduction, pairing, and representatives in one file. A follow-up refactor should preserve the primitive
inner loops while giving each phase a named state/result boundary and moving independent storage/indexing/representative
responsibilities into focused private helpers. The current local comments explain many invariants, but the full dependency
chain still requires readers to hold substantial mutable state in mind.

## Limits

These findings are from code reading, not reproductions. In particular, the maximum practical recursion depth of the
parallel cocycle logs was not measured. No claim is made about CI or runtime status after the pull.

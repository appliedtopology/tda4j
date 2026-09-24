# Two-step witness recipe for MATLAB/CLI: select landmarks, then compute (2026-09-23)

Follow-up to the same-day witness-complex arc (`WORKLOG-witness-complex.md`). That arc's own "deliberately not
done" list flagged a real gap: MATLAB/CLI callers had no way to get the covering radius `R` (or even the chosen
landmark set) back out of the one-shot `complex=witness` path, so the JavaPlex tutorial's own recipe -- pick
landmarks via maxmin, read `R`, pass `2R` as the threshold -- wasn't reproducible through the facade at all. The
project lead asked for a two-step split (select landmarks first, compute second) as an ADDITION alongside the
existing one-shot entry point, not a replacement for it, plus "querying" for a landmark set the caller didn't
just select (a hand-picked one).

## API shape

Two new pairs of entry points on `matlab.TDA4j`, mirroring the existing `computeFromPoints`/
`computeFromDistanceMatrix` split for input shape:

- **Step 1** -- `selectLandmarksFromPoints`/`selectLandmarksFromDistanceMatrix`: pick landmarks (`numLandmarks`
  REQUIRED, `landmarkSelector`/`landmarkSeed` optional) and return a `LandmarkSelectionResult`
  (`landmarks(): int[]`, `coveringRadius(): double`) -- no complex is built. New file
  `matlab/LandmarkSelectionResult.scala`, mirroring `PersistenceResult`'s own shape (`private[matlab]`
  constructor, plain accessor methods, no Scala types in any public signature).
- **Step 2** -- `computeFromPointsAndLandmarks`/`computeFromDistanceMatrixAndLandmarks`: build the witness
  complex and compute its barcode for an EXPLICIT, caller-supplied `int[]` landmark array (0-based ambient
  indices) -- typically `LandmarkSelectionResult.landmarks()`, but any hand-picked or reused array works, since
  this method never re-selects anything.
- **Query** -- `coveringRadiusFromPoints`/`coveringRadiusFromDistanceMatrix`: `R` for an ARBITRARY landmark set,
  for a caller who wants the `2R` recipe applied to a hand-picked set rather than one `selectLandmarksFrom*`
  chose. Backed by a new standalone `LandmarkSelector.coveringRadius(metricSpace, landmarks)`, extracted from
  `.random`'s own already-identical inline formula (`.maxmin`'s own incremental tracking is a DIFFERENT,
  already cross-validated algorithm for the same quantity -- left untouched, not routed through the new
  function, to avoid discarding a genuine efficiency property for no reason).

The one-shot `computeFromPoints`/`computeFromDistanceMatrix` with `complex=witness` is UNCHANGED in observable
behavior, with one deliberate exception: the "numLandmarks is required" error message dropped its "when
complex=witness" suffix, since `resolveLandmarkSelection` is now shared with step 1, which has no `complex`
option at all to reference -- confirmed by re-running the existing `TDA4jSpec` witness section (all 7
pre-existing examples) after every refactor step in this arc, per the project lead's own explicit instruction.
What changed internally: its
witness-specific logic was extracted into small, shared, reusable pieces (below) so step 2 gets the identical
default/refusal/coefficient-field behavior without a second copy to drift out of sync.

## Design decisions, and why (an advisor review before writing code shaped several of these)

- **`numLandmarks` stayed a STRING OPTION, not a typed method parameter**, on reflection against an initial
  instinct to make it typed (matching how `computeFromCubicalImage` takes `shape`/`flatValues` as typed
  params). The reason typed would have been wrong here: `TDA4j`'s own design principle is that IT is the one
  place that knows what "unset" means for any option (see the file's own `buildOptions` doc) -- a typed
  parameter would push "is this required" logic out to every caller (the CLI's own `--select-landmarks` mode
  would have to re-derive its own "missing --num-landmarks" error), duplicating what
  `resolveLandmarkSelection` already does once.
- **Extracted, not duplicated**: `resolveWitnessVariant`/`resolveWitnessEngine` (bundles the `witnessVariant`
  default-and-refusal logic previously inlined in `dispatch`'s own `(complex, engine) match`)/`resolveWitnessNu`/
  `resolveLandmarkSelection`, all shared between the one-shot `dispatch` and step 2's own
  `dispatchWitnessFromLandmarks`. `computeGeneric`'s own `ComplexKind.Witness` case became a one-line delegation
  to a new `computeWitnessFromLandmarks[C]` (the actual lazy/general x engine dispatch body, verbatim, just
  parameterized by an already-resolved `landmarks`/`nu` instead of reading `witnessLandmarks`/`witnessNu` from
  the enclosing scope) -- the same function step 2 calls directly, landmarks supplied instead of selected.
- **A genuine coefficient-field-dispatch helper, not two copies of the `Z`/`R` match** -- `dispatchByField[T]`,
  taking a **polymorphic context-function value** (`compute: [C] => (C => Double) => (C is Field) ?=> T`) so the
  resolved `C` and its `given C is Field` travel together through one call, rather than every caller
  hand-writing the `FiniteField`/`DoubleApproximated` branch itself. Non-obvious Scala 3 syntax point that cost
  one failed compile to learn: the context-function part (`(C is Field) ?=>`) has to be the INNERMOST/rightmost
  arrow, not wrapped around the whole lambda from the start (`[C] => (using cf: C is Field) => (toDouble...) =>
  body` is a parse error; `[C] => (toDouble: C => Double) => body`, with the context function requirement
  living in the DECLARED type only, compiles and resolves the given automatically at each call site once the
  right `given` is in scope there).
- **Strict, per-entry-point option allowlists for the two NEW pairs** (`landmarkSelectionKeys`,
  `witnessFromLandmarksKeys`), deliberately NOT the one-shot path's own permissive shared `recognizedKeys`.
  Reasoning, from the advisor review: a caller passing `numLandmarks=10` alongside an already-chosen 20-element
  landmark array in step 2 would otherwise be silently dropped -- exactly the "two flags disagree and nothing
  notices" trap this whole facade's design already exists to avoid for `--complex`+cubical-grid. Step 2 does
  still accept `"complex"` as a key, but validates it's `"witness"` if given at all (`parseWitnessComplexOption`)
  -- a CLI/MATLAB caller migrating from the one-shot form will naturally still type it out of habit, and a
  stray wrong value (e.g. copy-pasted `complex=vr`) is exactly the kind of thing worth catching rather than
  ignoring, even though this method could only ever compute a witness complex regardless.
- **Landmark validation, with an actionable message for the single most likely mistake**: `validateLandmarks`
  rejects empty arrays, duplicate indices (silently would become two zero-distance twin landmarks in
  `WitnessGeometry`, a correctness footgun not a crash), and out-of-range indices -- an index EXACTLY equal to
  `n` (the point count) gets its own message hinting at a 1-based-indexing mistake, MATLAB's own default array
  convention and the single most likely way a MATLAB caller trips over this 0-based API.

## Testing

`TDA4jSpec`'s new "the two-step witness recipe" section (13 examples): step 1 checked directly against
`LandmarkSelector.maxmin`/`.random` (both landmarks and `R`), points-vs-distance-matrix parity for both step 1
and the covering-radius query, one-shot-equals-step-1-then-step-2 for both variants. **The one genuinely
discriminating test** (an advisor-review catch, following the same lesson `WORKLOG-witness-complex.md`'s own
"measure, don't infer" episode already taught this session): landmarks `Array(5, 2, 0)` -- deliberately
NOT what `maxmin(numLandmarks=3)` would choose from this exact cloud (`{0, 4, 5}`, confirmed by hand: points 4
and 5 tie at `sqrt(4.25)`, 4 wins by lower index) -- checked against `PackedRipserCohomologyContext` built
directly over that SAME explicit, unsorted array, as sorted lists, plus a direct `cycleVertices` subset check.
Without this specific fixture, a bug where step 2 silently ignored its own `landmarks` argument and re-ran
maxmin internally would still pass every OTHER test in the suite, since they all happen to use maxmin-chosen
landmarks by construction. Also: reordering the same landmark set changes local indices/tie-breaks but not the
barcode (checked); every validation path (empty/duplicate/negative/out-of-range including exactly `n`,
forbidden keys on both new pairs, the general-variant engine refusals carried through step 2 unchanged).

`CLISpec`'s new "the two-step witness recipe" section (8 examples): a real two-invocation file round-trip
(`--select-landmarks` writing to a file, `--landmarks-file` reading it back) reproducing the one-shot barcode
exactly; the landmarks-file's own content checked against `TDA4j.selectLandmarksFromPoints` directly (both the
index list and the `# coveringRadius=...` comment line); every conflict rejected with exit code 1 without ever
passing a malformed flag to Scallop itself (`--select-landmarks`+`--landmarks-file` together, either with
cubical input, `--select-landmarks`+`--representatives`, `--select-landmarks`+non-text `--output-format`); the
landmarks-file reader's own line-numbered parse error, exercised through a real `run` call (not just the
reader function in isolation) to confirm it surfaces as a clean one-line `tda4j: ...` message, not a stack
trace.

## CLI wiring

`--select-landmarks` (Boolean, CLI-local like `--representatives` -- never forwarded into `TDA4j`'s own options
array) and `--landmarks-file` (String, likewise CLI-local). Every conflict check lives in `TDA4jCLI.run` as a
plain `throw new IllegalArgumentException`, NOT Scallop's own `conflicts`/`mutuallyExclusive` machinery -- those
route through `onError` -> `System.exit`, the same path `CLISpec`'s own doc already has to work around for
`--help`/malformed flags. `buildOptions` itself is untouched and forwarded unchanged in every mode; TDA4j's own
strict allowlists (above) do all the real option-conflict rejection, so the CLI needn't duplicate any of it.

The landmarks-file format is tda4j's own (kept in `cli`, not `io`, matching the io-module's own convention of
only implementing externally-specified formats verified against a primary source -- this one has none, it's
invented for this round trip): one 0-based landmark index per line, blank lines and `#`-prefixed comments
skipped, a leading `# coveringRadius=<value>` line written by `--select-landmarks` (informational only -- never
read back by `--landmarks-file`, so a hand-edited file without it still works). `R` is ALSO always printed to
stderr on `--select-landmarks`, since the point is a human running this interactively sees it without opening
the output file.

## Deliberately not done / left open

- **MATLAB's own marshalling of `int[]` in BOTH directions (return value from `landmarks()`, argument to
  `computeFrom*AndLandmarks`) is unverified from this environment**, same standing limitation
  `WORKLOG-matlab-api.md` already documents for `double[][]`/`String[]` (not re-editing that file -- worklogs are
  point-in-time snapshots -- recorded fresh here instead). `int[]` specifically was never exercised by the
  original facade (only `int[][]` for `cycleVertices`), so this is a genuinely new unverified surface, not an
  already-covered case.
- No MATLAB-side `.m` convenience wrapper for the two-step recipe (e.g. a helper that does both calls and
  formats `2R` for the caller) -- the project lead explicitly chose the bare-facade route for the original API
  too; no reason to diverge here.
- Landmark reuse ACROSS a points-vs-distance-matrix boundary (selecting from points, computing from a
  distance matrix, or vice versa) works today since both accept a plain `int[]` and neither validates where it
  came from -- not explicitly tested as its own scenario, since nothing about the implementation treats the two
  input shapes differently once landmarks are already in hand.

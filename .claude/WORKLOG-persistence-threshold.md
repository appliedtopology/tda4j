# Worklog: default persistence threshold (2026-10-01)

Request (project lead): CLI/MATLAB report EVERY bar, overwhelming and not field-standard. Default: a bar must
extend beyond 1% of the range 0..connectivity radius to be reported, unless the caller asks for a different
threshold or none. Most needed in CLI + MATLAB, good to have in more of the library.

## Facts found
- One choke point builds every `PersistenceResult`: `matlab.TDA4j.fromBars` (+ ~30 call sites in
  computeGeneric / computeWitnessFromLandmarks / computeDowker / computeCubicalGeneric). CLI gets bars only via
  `PersistenceResult` (`TDA4jCLI.toBars`), and mirrors option keys 1:1 with no CLI-side defaults.
- `PersistenceResult.cycleProvider(i)`/`barsOfDimension` (distance, landscape, image) index the bar arrays, so a
  filtered view needs index remapping and a decision on what distances/vectorizations see.
- ~200 existing facade/CLI test calls assume full barcodes (TDA4jSpec 118, CLISpec 55, ...).
- No git tags locally; `mimaPreviousArtifacts` derives from `v*` tags.

## Design (draft, pre-advisor)
- Post-hoc filter at `fromBars` (standard: ripser.py/GUDHI filter after the fact); engines unchanged.
- scale = (max finite H0 death) - (min H0 birth) over the UNFILTERED bars; fallback when no finite H0 bar:
  max finite endpoint - min birth over all bars; none -> 0 (nothing dropped). Equals the lead's definition for point
  clouds (births start at 0).
- Options: `minPersistenceFraction` (default 0.01), `minPersistence` (absolute; wins; both set = error). 0 = no filter.
  Essential bars always kept. Keep iff persistence > threshold (strictly "extend beyond").

## Advisor review (applied) + context
- Baseline before change: `testOnly *TDA4jSpec *CLISpec *PersistenceResultSpec *BoundaryMatrixSpec *WitnessStreamSpec`
  = 169 passed. Must be identical (same count, no edited assertions) after the default flips.
- Project lead: next version is 0.5.0 and binary compatibility with 0.4.x is NOT required -> change
  `PersistenceResult`'s private[matlab] constructor freely; no compat shims. version.sbt -> 0.5.0-SNAPSHOT.
- Advisor decisions: threshold <= 0 short-circuits to keep EVERYTHING (incl. zero-persistence bars); add keys to every
  strict allowlist (recognizedKeys, witnessFromLandmarksKeys, dowkerKeys; NOT landmarkSelectionKeys); remap cycle*
  indices; distances/landscape/image (and CLI --distance-to) use the FULL barcode, filtered view only for
  size/toArray/dimension/birth/death/cycle*/CLI diagram output; tests: route existing calls through a no-threshold
  path without editing assertions, new specs for the feature; check alpha/DTM units; leave h1Bars/circularCoordinates
  alone; CLI mirrors keys with no Scallop default + stderr note when bars hidden; update 4 doc surfaces + CLAUDE.md.

## Implementation log
- barcode/PersistenceFilter.scala (new): DefaultFraction=0.01, connectivityScale (array + bar forms), threshold,
  keptIndices, significant. Rule: keep iff essential or persistence > thr; thr<=0 keeps everything.
- PersistenceResult: now holds FULL arrays + `visible` index + threshold; size/toArray/dimension/birth/death/cycle*
  are the visible view; barsOfDimension (distance/landscape/image) use the FULL barcode; new
  persistenceThreshold(), hiddenCount(), toArrayUnfiltered(); aux ctor keeps `fromBars` unchanged.
- TDA4j: keys minPersistence/minPersistenceFraction in recognizedKeys, witnessFromLandmarksKeys, dowkerKeys (NOT
  landmarkSelectionKeys); `dispatch*` are thin wrappers (validate spec first -> dispatch*Full -> apply threshold).
- CLI: --min-persistence / --min-persistence-fraction mirrored (no Scallop default); stderr note when bars hidden;
  --distance-to uses toBarsUnfiltered; both flags rejected with --select-landmarks and --distance-to.
- Tests: test-only shims FullBarcode (matlab) / CliFull (cli) route existing call sites with the threshold OFF.
  GOTCHA: a one-line sed missed call sites split over two lines (`TDA4j` / `.computeFrom...`); 5 tests then failed
  because they legitimately got the new default -- fixed with a multi-line perl pass. After: same specs = 169 passed
  (== baseline), zero assertion edits.

## Correction (2026-10-02, project lead)
The scale was meant to be the MINIMUM ENCLOSING RADIUS (`FiniteMetricSpace.minimumEnclosingRadius`, Ripser's
enclosing radius -- already this library's default VR truncation), NOT the connectivity radius. The "connectivity
scale" section above (barcode-derived H0 range) is superseded.
New design: scale is a property of the INPUT, supplied by the facade:
- point cloud / distance matrix (every complex, incl. witness's ambient space): `metricSpace.minimumEnclosingRadius`,
  used as-is in the complex's reported units (same number the library already uses as VR's diameter cut-off and as a
  radius bound for cech/alpha); computed LAZILY, only when the fraction path is active (not for minPersistence /
  fraction 0);
- cubical image: max - min of the pixel values (sublevel=false negates values; the range is unchanged);
- Dowker relation: max - min of the finite relation values;
- non-finite or 0 scale: non-finite falls back to the barcode's finite range (max finite endpoint - min finite
  birth); 0 means nothing is hidden.
Library: `PersistenceFilter` takes the scale explicitly (`significant(bars, ..., scale = Some(mer))`) with the
barcode's finite range as the fallback when none is given.
Discriminating fixture: collinear 0, 0.015, 1, 2, 3 -- connectivity radius 1 but MER 2, so a bar of persistence
0.015 survived the old rule (>0.01) and is hidden by the new one (>0.02).

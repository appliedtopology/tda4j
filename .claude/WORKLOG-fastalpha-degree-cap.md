# fastalpha in the alpha sweep: why it lost, and the degree cap (2026-10-26)

Point-in-time snapshot. Source: Mikael's server alpha sweep (commit 563fc91, 1 seed, 160g, 3600 s) and a local check.

## What the sweep showed
`tda4j-fastalpha` was 1.3x to 2.2x slower than the default at every d >= 6 (n = 40: 4.6 s against 3.3 s at d = 7, 187 s
against 106 s at d = 10, 1790 s against 814 s at d = 12) and its old-generation peak at d = 12 was 44 GB against 10.7 GB.
Its barcodes equal GUDHI's to 3e-11.

## Cause
`FastAlphaHomologyEngine.persistentHomology` had no degree cap. In d >= 3 `computeMiddleDimensions` ran the cohomology
engine on `LimitedAlphaShapesStream(helix, d - 1)`: every face up to dimension d - 1, every degree up to d - 2. The driver
cut the bars above `dim` afterwards. The default path builds the stream up to `dim + 1` (= 3) and reduces degrees 0..2.
The dual pass also keeps a facet-to-cofaces map of all top simplices.

## Local check (cloud container, uniform points, 2 warm trials, one draw, so rough)
| ambient d, n | default path | fastalpha, uncapped (degrees <= 2 asked) |
|---|---|---|
| 2, 3000 | 0.63 s, old gen 503 MB | 0.17 s, old gen 136 MB |
| 3, 1500 | 0.43 s | 0.46 s |
| 4, 300 | 0.28 s | 0.41 s |
| 6, 40 | 0.40 s | 0.61 s |

After the cap (d = 6, n = 40, degrees <= 2): 0.43 s (default 0.36 to 0.40 s). Top degree alone (`fastalpha-top`, d = 6):
0.42 s against 0.36 s for the default path's whole barcode: at these sizes the Helix walk dominates, so the dual pass shows
its value only where the reduction costs (to be read from the `--path top` sweep, d = 8 to 12).

## Change
- `persistentHomology(helix, includeZeroLength, maxDimension)`: the view is capped at `min(maxDimension, d - 2) + 1`, the
  dual graph is walked only when `maxDimension >= d - 1`. The facade passes `requestedMaxDimension`.
- `persistentHomologyTopDegree`: the dual union-find alone.
- Sweep: `--dim top`, `--path top|four|plane`, tool `tda4j-fastalpha-top`; `agreement(..., degrees)` compares a top-only
  barcode in its degree; a numeric `--dim` is clamped to d - 1.
- Specs: capped = uncapped cut at k in 2, 3 and 4 dimensions; top degree = the top part of the full barcode, with cycles;
  facade `fast-alpha` with `maxDimension=1` in 4-D equals the cohomology engine.

## Open
- The `--path` sweeps have not been run (they need the server). Two claims to read from them: fastalpha in the plane at
  large n, and the dual pass against full reduction at d = 8 to 12.
- `rules/alpha.md` is over its size budget (12.8k against ~12k): #231.

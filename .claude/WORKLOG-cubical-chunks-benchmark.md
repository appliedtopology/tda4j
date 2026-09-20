# Cubical naive vs. chunks benchmark (2026-09-19)

Asked to benchmark `CellularHomologyContext[Cube,...]` (naive) against `CellularPersistenceInChunksContext
[Cube,...]` (chunks) on cubical complexes, now that the chunks engine has been genericized to take any
`OrderedCell` (`.claude/WORKLOG-simplicial-set-filtration.md`). Motivation: CLAUDE.md documents the naive
engine's own 3D cubical scaling as a real, not-yet-root-caused problem (per-cell cost GROWS with `n`, 190→850
us/cell from n=8 to n=32) — the natural question is whether chunks handles that case better.

## Correctness first, since this combination had never been run

`grep -rn "CellularPersistenceInChunksContext\[Cube"` across the whole `src/` tree returned nothing before this
session — chunks had only ever been exercised on `Simplex[VertexT]` (via `PersistenceInChunksContext`) and
`FiniteSimplicialSet` generators. Every prior first-contact between a generic engine and a new concrete cell
type in this codebase's history found a real bug (the three filtrationOrdering direction/tie-break bugs, the
chunks-specific multiple-tied-essential-classes bug — see CLAUDE.md's "Cross-engine benchmark" section), so
correctness was checked before trusting any timing, not assumed from genericity alone.

Consulted `advisor()` before writing anything, given the untested-combination risk. Two concrete hazards it
flagged, both addressed:

1. **Naive and chunks share `CubicalGridStream.filtrationOrdering`** when run on the same stream — a bug in that
   ordering would make both sides wrong the *same* way, so naive-vs-chunks agreement alone can't catch it.
   Addressed by also running `HomologyFixtures.totalBarsAccountForAllCells` on chunks' own output — an
   independent structural invariant that doesn't depend on naive being right.
2. **`maxDim` semantics differ between the two engines** — chunks' `maxDim` means "top homological degree
   reported" (walks `maxDim+1` internally); naive has no `maxDim` parameter and simply computes through the
   stream's actual top cube dimension. For a `dims`-dimensional grid that's `dims` itself, so every call site
   here pins `maxDim = dims` explicitly rather than leaving the two engines answering different questions.

Added to `CubicalStreamSpec.scala` (reusing its own three hand-derived, deliberately tie-heavy fixtures — the
same regime this codebase's ordering bugs have always hidden in, not the random-image generator): both engines
run on the same stream, chunks' own structural invariant checked, and `chunksBarcode must
containTheSameElementsAs(naiveBarcode)`. **All three fixtures pass, first try** — 9 examples, 337 expectations,
0 failures. This is now the first-ever validated instance of chunks running on `Cube`.

## Benchmark design

Extended `CubicalBenchmarkSpec.scala` (rather than a separate file) — same `Arguments`-driven config, same
per-`n` grid generation, now timing both engines on the *identical* generated stream per row (not two separately
seeded runs) and printing both `us/cell` columns side by side, since that per-cell-cost number is what actually
answers "does chunks change the 3D shape."

Chunks runs under a timeout (`-DtimeoutSeconds`, default 30s) on a daemon-thread executor, copying
`EngineComparisonBenchmarkSpec`'s established pattern — neither engine supports cooperative cancellation, and
`PersistenceInChunksContext` x alpha is a documented stall/OOM risk at complex sizes (102k simplices) far
smaller than a 3D cubical grid can reach (274,625 cells at n=32). Naive is left unguarded, matching the
pre-existing spec (it's slow but has never stalled or OOM'd on cubical input).

## Results

**2D** (`-DminN=8 -DmaxN=256 -DstepMultiplier=2`, default `dims=2`, a 900x range of complex sizes):

| n   | cells   | naive (ms) | naive us/cell | chunks (ms) | chunks us/cell |
|-----|---------|------------|----------------|-------------|-----------------|
| 8   | 289     | 225.8      | 781.4          | 107.6       | 372.4           |
| 16  | 1089    | 319.5      | 293.4          | 72.3        | 66.4            |
| 32  | 4225    | 319.5      | 75.6           | 179.7       | 42.5            |
| 64  | 16641   | 1493.0     | 89.7           | 747.3       | 44.9            |
| 128 | 66049   | 8079.6     | 122.3          | 3531.2      | 53.5            |
| 256 | 263169  | 38941.5    | 148.0          | 16188.1     | 61.5            |

Both engines are roughly FLAT per-cell in 2D (naive ~75-150us/cell past the small-n JIT-warmup noise, matching
CLAUDE.md's previously-documented ~60-95us/cell range; chunks ~42-66us/cell) — chunks is consistently faster,
by roughly 2x per cell, across the whole range.

**3D** (`-Ddims=3 -DminN=8 -DmaxN=32 -DstepMultiplier=2 -DtimeoutSeconds=180`; naive alone needs ~4min at n=32,
run in the background rather than blocking):

| n  | cells   | naive (ms) | naive us/cell | chunks (ms) | chunks us/cell |
|----|---------|------------|----------------|-------------|-----------------|
| 8  | 4913    | 1396.9     | 284.3          | 606.6       | 123.5           |
| 16 | 35937   | 12556.6    | 349.4          | 3229.3      | 89.9            |
| 32 | 274625  | 244777.9   | 891.3          | 31440.3     | 114.5           |

**This is the answer the whole benchmark was built to find, and it's a real, structural difference, not just a
constant-factor win**: naive's per-cell cost keeps GROWING with `n` (284 → 349 → 891 us/cell, confirming the
previously-documented 3D scaling problem), but chunks' per-cell cost stays roughly FLAT (123 → 90 → 114 us/cell,
well within noise of each other, no growth trend) across the same 56x range of complex sizes. Because naive
degrades and chunks doesn't, the SPEEDUP ratio itself grows with `n` — chunks is 2.3x faster at n=8, 3.9x at
n=16, and 7.8x at n=32 — the opposite of a fixed constant-factor advantage. Chunks' own per-cell cost in 3D
(~90-125us/cell) is also in the same range as its 2D numbers (~42-66us/cell, allowing that 3D cells are larger
objects to process) and its 3D numbers are NOT worse than 2D the way naive's are (naive's 3D per-cell cost, at
comparable n, is roughly 3-10x its 2D per-cell cost at the same n; chunks' isn't).

Chunks finished well within the 180s timeout at every size tried (max was 31.4s at n=32) — no evidence yet of
the stall/OOM risk flagged before the run, though this only reaches 274,625 cells, well below the alpha-complex
case (102k simplices) that DID stall/OOM chunks previously; a larger 3D grid (n=64, 2,146,689 cells) was not
attempted this session and remains the natural next check if that risk needs bounding further.

## Open

The question this benchmark was built to answer is now answered: chunks does NOT inherit naive's 3D cubical
scaling problem — whatever causes naive's per-cell cost to grow with `n` in 3D (still not root-caused, per
CLAUDE.md) is either absent from or bounded differently in chunks' clear-and-compress architecture. This alone
doesn't root-cause naive's problem, and doesn't rule out chunks having its own ceiling at larger sizes (n=64+
untested) or on sparser/denser images than a uniform-random one. Two natural next steps, neither attempted this
session: (1) push the 3D sweep further (n=64) to see whether chunks' flat shape holds or eventually turns, and
whether it hits the same stall/OOM risk the alpha-complex case did; (2) now that there's a second engine with a
qualitatively different scaling shape on the SAME input, comparing where the two engines' internal work diverges
(chunk-local vs. global pivot table) might be a more tractable way into naive's still-unexplained 3D growth than
profiling naive alone ever was.

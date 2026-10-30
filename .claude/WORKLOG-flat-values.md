# Dowker and Cech values on arrays (#206, 2026-10-29)

Request: #206 listed what #205 left in the flat-layer path: the Dowker value (64 % of its time, a minimum over witnesses
of a maximum over the simplex's rows, from scratch for every candidate), the Cech value (ball solve, the `Simplex` its
adapter builds, the sort, the engine's face lookups) and the Scala.js `FiltrationCache`.

## Dowker

A flat candidate is a new row on top of its parent, and `max` and `min` are exact, so
`f(v + P) = min_w max(R(v, w), M_P(w))` with `M_P(w) = max_{x in P} R(x, w)` computed once per parent gives the formula's
value bit for bit. The candidates of one parent arrive together (`flatCofaces` adds them parent by parent), the parent's
vertices after the new smallest one. State between calls is a thread problem (the values of a chunk are computed in
parallel parts when `parallelFiltrationValue` is set), so `FlatFiltration` gets `perRun`: the stream's `evaluate` calls
it once per task and `value` on the result only from that task. The default is the filtration itself.

`DowkerFlatFiltration` keeps the parent it saw last (compared by its vertices, `width - 1` ints) and its maxima. A
candidate whose parent differs recomputes them; one per parent costs `(width - 1 + c) * numWitnesses` instead of
`c * width * numWitnesses` for `c` candidates, so a parent with one candidate costs the same as before.

Checks: `DowkerStreamSpec` asks the flat value of every simplex of seven rows of seeded integer and boolean relations
(four seeds each) in the order of the layers, reversed and shuffled, against `DowkerGeometry.filtrationValue`, comparing
bits. Mutations, both caught by it and by `FlatLayersSpec`: a parent that is never refreshed, maxima that are not reset.

Layers to dimension `dim + 1` of a random relation with uniform entries (the full complex, no threshold), one JVM per
run, median of three trials, `FlatLayersTimingDriver dowker plain|parent` (`plain` is the stateless value), 4-core sandbox:

| relation, `dim` | cells | stateless value | per-parent maxima |
|---|---|---|---|
| 300 x 100, 1 | 4.5M | 8.1 s (7.9, 8.3) | 3.4 s (3.3, 3.5) |
| 200 x 400, 1 | 1.3M | 8.6 s | 2.2 s |
| 60 x 200, 2 | 0.5M | 2.1 s | 0.5 s |

Two rounds were run for the first row; the others once. Not measured: the reduction, a relation with a threshold (the
#181 table's 300 x 100 row ran 1.0 to 1.6 s end to end on its own input, which was not reproduced here), memory, Scala.js. Run-to-run noise was not
quantified; the effect is far larger than the spread between the two rounds.

## Cech

Profile of the layers of 80 points in R^3 to dimension 3 (1.67M cells, `FlatLayersTimingDriver cech`, JFR, 733 samples):
`computeRadius` was 72 % of the samples, the ball solve inside it 15 %, `LayerSorter` 10 %, red-black-tree frames (the
`Simplex` made for each facet) 29 %, `TrieMap` frames on the stack 76 % (they contain the whole `computeRadius` call, so
that figure is not the cache alone). So the part that is not the ball was more than half of the layer building.

The value is cache-dependent by design: `max` over the facets' cached values (absent counts 0), and the first vertex
strictly inside the ball makes the value its facet's cached radius (absent: the ball's own radius). A second
implementation on arrays next to the `Simplex` one would give ULP-different values for a simplex asked through the
other path (and a stream's `filtrationValue(cell)`, called after the layers, would no longer match them). So there is one
cache: `VertexSetValues`, a table keyed by the vertex arrays (open addressing in 64 locked shards, `NaN` for an empty
slot, first value kept), and `CechFiltration.Values` serves the `Simplex` function and the array hook from it. The
formula is the earlier one, term for term (`facetFloor`, the first interior vertex).

Checks: `CechValuesSpec` runs the earlier implementation (a `Simplex`-keyed map, copied into the spec) and the new one
(through `Simplex` and through the flat hook, each with its own fresh cache) through the same sequence of calls, a stream's
order and an order with the triangles of an odd smallest vertex never asked (so later simplices see missing facets), on a
grid (cospherical points, interior vertices), planar and spatial clouds and a repeated point, and compares bits.
Mutations, each caught by it and not by `FlatLayersSpec` (which compares two paths that now share the code): the interior
vertex's facet ignored, the facet floor ignored, a facet built with the wrong vertex left out. `VertexSetValues` has its
own examples (growth across widths 0 to 5, absence, first value kept, inserts from several threads).

`FlatLayersTimingDriver cech flat 80 2 3 1`, one JVM per run, median of three trials, two rounds, `-Xmx6g`:

| | layers of 80 points in R^3, 1.67M cells |
|---|---|
| earlier (`Simplex`-keyed `TrieMap`) | 15.7 s, 15.2 s |
| `VertexSetValues` | 3.8 s, 4.0 s |

About four times. Not measured: the reduction after the layers, memory (an open-addressing table at load factor at most
one half, 8 bytes and `width` ints per slot, against a `TrieMap` of `Simplex` keys: expected to be smaller, not measured), `parallelFiltrationValue` on, Scala.js timings.

## What stays open

- The general witness complex still wraps its `Simplex` function and its `FiltrationCache` (on Scala.js a `HashMap`
  hashed through `TreeSet.hashCode`). Cech no longer touches it. Its value is recursive, `max(own_k, max over facets)`;
  the same one-cache-two-doors design would apply.
- Scala.js timings of any of this were not taken (the specs run there).
- Cech against Delaunay-Cech is not like for like (every `dim + 2`-subset is a Cech cell); `AlphaShapes` gives the same
  barcode of a Euclidean point cloud. Whether `Cech` should say so in its scaladoc or dispatch to the alpha construction
  when asked for no radius is the project lead's call (left in #206).

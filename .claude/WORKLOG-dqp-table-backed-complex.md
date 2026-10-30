# DQP: table-backed complex, no witnesses in the streams (2026-10-30)

Question (Mikael, 2026-10-28/30): what swamps DQP at high ambient dimension, and does it need a degree cap to be worth
it? The server table (interior runs, JVM, 32 cores) shows DQP ahead of Helix only up to about d = 9 at n = 40 and
d = 8 at n = 60; from d = 10 Helix is faster and DQP holds 2 to 5 times the memory (n = 40 d = 10: Helix 79.7 s, 2.2 GB;
DQP 84.9 s, 4.4 GB; n = 60 d = 10: 438 s, 6.8 GB against 574 s, 21.9 GB; n = 40 d = 12: 760 s, 12.8 GB against
1124 s, 38.9 GB).

## Where DQP's time and memory went (local, n = 40, d = 9, degree 8)

- Not the QP solves and not cold starts: the per-vertex solves are independent and warm already.
- Parallel scaling was poor: build 21.5 s on 1 CPU, 10.2 s on 4. The rest is sequential bookkeeping after the solves:
  merging each vertex's found rows into `Simplex`-keyed hash maps (`weights`, `witnesses`) and the per-dimension
  buffers.
- Live data after the build was dominated by the retained `Simplex` trees (51%) and the witness arrays (26%): one
  `double[d]` per simplex that the alpha streams never read.

## Change

`AlphaComplexDQP` keeps one `FaceTable` per dimension (the builder's own, open addressing on int arrays) with the value
and witness rows beside it; lookups probe the table. `AlphaDQPSettings.keepWitnesses` (default `true`) drops the
witnesses, and `AlphaShapes` builds its streams with it off.

Cells and values bit-identical (md5 of `AlphaProfileDriver dump=` before and after). Local, d = 9, n = 40, 4 CPUs: build
11.1 s to 7.4 s, the verb 7.9 s to 6.5 s, live data after the build 540 MB to 390 MB.
`AlphaComplexDQPWitnessSpec`: triangle witnesses, power equals value, no witnesses when off (mutation-checked).

## Left

- The merge loop is still sequential and the `Simplex` objects of every dimension up to `maxDimension + 1` are still
  retained; making them lazy was judged low value because the verb reads every dimension.
- `Auto`: with a radius and d >= 6 it still picks DQP (`prefersDQP`). The table says Helix is better from d = 10 on
  time and memory when the radius is near the enclosing radius, which is what the verb uses. A cut-off needs the
  `--path four` numbers and a server re-time of d = 9 and 10 on this build; not changed here.

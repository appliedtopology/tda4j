### Added

- `blockOrder` chooses which of two simplices filed under the same edge is older, the other half of the order `edgeTies`
  sets: `Persistence(points, engine = Persistence.Engine.ParallelRipser, edgeTies = Persistence.EdgeTies.Colexicographic,
  blockOrder = Persistence.BlockOrder.SmallerIndexFirst)` (`larger-index-first`, the default and Ripser's own convention,
  or `smaller-index-first`; MATLAB and Java `blockOrder`, the command line `--block-order`, JavaScript `blockOrder`). The
  bars are the same; the apparent pairs, and so the columns left to reduce, are not. Together with `edgeTies` it reaches
  the orders of ripser.cpp's `EDGE_LEXICOGRAPHIC` build and of Ulrich Bauer's `ripser-forest` and hybrid code (the table
  in the developer's guide says which pair is which). `Auto` takes the block-parallel engine for `smaller-index-first`;
  `Engine.Ripser` and `engine=ripser` refuse it.

### Build, CI and Documentation

- `bench/run.sh count-work` (`bench/count_work.py`) counts the work of the Vietoris-Rips reductions: the columns each tool
  reduces and skips, and its column additions, per degree, for the forks of ripser.cpp and RedZeD that `run.sh setup`
  builds with counters (`<variable>_COUNT_BIN`), for ripser-forest and the hybrid's own counters, and for TDA4j's
  block-parallel engine under every `edgeTies` and `blockOrder` (`PaperBenchmarkDriver work=true`). A benchmark run never
  counts. Two tie-heavy `experiment` cases, `lattice32` and `equilateral60`, are the default inputs (left out of a run
  unless `--cases` names them), and three `experiment` rows time the engine under other orders
  (`tda4j-parallelverb-1t-ripser-edge`, `-hybrid`, `-lex-smaller`; `PaperBenchmarkDriver ties=.. order=..`) (#349).

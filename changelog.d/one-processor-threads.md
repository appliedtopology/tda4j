### Fixed

- On a machine with one processor the library counted two threads, so `Persistence` chose its parallel engine and
  shared every step with a second thread that had no processor of its own. It counts one thread there now, and
  `Engine.Auto` chooses `Ripser`, as it does with `threads = 1`. The bars are the same; a representative may differ
  where tied simplices give a choice (#339).
- `-Djava.util.concurrent.ForkJoinPool.common.parallelism=0` gives the one-thread computation the documentation
  promises. It used to choose the parallel engine and run it on the calling thread (#339).
- A one-thread computation (`threads = 1`, `--threads 1`) stays on its thread. Inside it the alpha complexes and the
  cohomology engine still handed their larger tables to as many as four threads of the common pool, so a time measured
  with `threads = 1` could be shorter than one thread's (#339).

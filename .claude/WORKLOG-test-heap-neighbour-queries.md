# The JVM test job ran out of heap on one `beEqualTo` (2026-11-25)

Point-in-time snapshot. The rule that came out of it is in `rules/testing.md` ("Compare big values").

## Symptom

Merge-queue runs on `scala` after #355 (neighbour lists by tree queries): the JVM `test` job (`sbt "clean ; tda4j/test"`,
`-Xmx2G` from `.jvmopts`, the test JVM is sbt's own) died with `OutOfMemoryError: Java heap space` in
`NeighbourQueriesEnginesSpec:90`, `TDA4jParallelSpec:86` and `WideSimplexIndexingSpec` (queue batch of #356, which holds #357
beneath it; 259 of 262 specs passed). On the pull request for #357 the same job had been red twice (an OOM in
`BowyerWatsonSpec` on a slow runner, then a silent stall in `MatlabTabsSpec`), issue #358. The base branch passed the job in
4 min 19 s, so the failures looked like a flake.

## Reproduction

In the cloud container (4 cores, 16 GB, the same shape as the runner), `sbt "clean ; tda4j/test"` with `-Xmx2G` and
`-Xlog:gc*`:

- it passes (2605 examples, 151 s), but the heap sits at 1.8-2.0 GB after every collection from 60 s to 130 s, with one full
  collection `2048M->1000M` (537 ms) and 18.7 s of GC pauses in all;
- the same run at `-Xmx1280M` fails in the same two specs as CI (`NeighbourQueriesEnginesSpec:90`, `TDA4jParallelSpec:86`);
- `NeighbourQueriesEnginesSpec` alone: `-Xmx600M` and `-Xmx1G` fail, `-Xmx1500M` passes, in 42 s, with back-to-back full
  collections that leave 1.4 GB live (800 ms each).

So the spec had a live set of about 1.4 GB, and the other specs running beside it on 4 workers did the rest. A slower runner
overlaps the heavy specs differently, which is why the failing spec changes from run to run (an OOM lands on whichever
thread allocates next). A heap held at 95% by one spec is also what a stall looks like (the JVM collecting without
progress); that was not checked for the `MatlabTabsSpec` stall.

## Cause

`jcmd <pid> GC.class_histogram` during the spec: the live objects were `org.specs2.data.InsOp` (35 million), `DelOp` (33
million) and 8,437 arrays of `EditDistanceOp`, nothing of ours. specs2's `must beEqualTo` on two listings builds the
edit-distance table of the two for its failure message, and it does so when they are equal. The example "ParallelRipser ranks
the same edges..." compared `runs.head.bars` and `runs.last.bars`, two listings of about 8,400 bars: 8,400 x 8,400 cells.
`rules/testing.md` already said not to compare big values with `beEqualTo` (#186's OOM); #355's spec broke the rule.

## Fix

`((runs.head.bars == runs.last.bars) must beTrue)` in `NeighbourQueriesEnginesSpec`. The spec now takes 8 s instead of 42 s
and passes at `-Xmx512M` with no full collection (142 MB after the last pause). The whole JVM suite passes at `-Xmx1G` (2607
examples, 136 s, 3 full collections), where it failed at 1280M before. The comparison asserts exactly what it asserted
(the two listings are equal); only the failure message is plainer. `.jvmopts` stays at 2 GB.

## Left open

- The `MatlabTabsSpec` stall on #357's pull request run is not explained (#358); the heap is a candidate, not a finding.
- #357's own queue run (the same night) never reached the tests: the launcher spent 17 minutes retrying `sbt 2.0.12` downloads
  against Maven Central and repo.scala-sbt.org (HTTP 429), then failed with `could not retrieve sbt 2.0.12`. An infrastructure
  failure; re-queueing is the remedy.

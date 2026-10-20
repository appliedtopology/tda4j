# PersistenceThresholdCLISpec and PlotCLISpec failed when run beside CLISpec: a swapped System.err

2026-10-20. Point-in-time record; found on the first CI run of PR #148 (which touches neither spec).

## Trigger

`test` failed on `PersistenceThresholdCLISpec` ("report every bar, with no stderr note"): the captured stderr held
`tda4j: --select-landmarks/--landmarks-file are not meaningful with --input-format=perseus-cubical: ...`, a line that
`CLISpec` provokes on purpose.

## Cause (reproduced)

`PersistenceThresholdCLISpec` and `PlotCLISpec` capture the CLI's stderr with `System.setErr(buffer)` and restore it
afterwards. sbt runs spec classes in parallel in one JVM, so while one swapped the stream, `CLISpec` (and the other
capturing spec) wrote its CLI errors into the buffer; the restore of one spec can also put back the other's buffer.

Reproduction on the unfixed code, `sbt "tda4j/testOnly org.appliedtopology.tda4j.cli.*"` repeated: 1 of 9 runs failed
(the chain stops at the first failure), with BOTH capturing specs failing, each holding the other's CLI message (e.g.
`PlotCLISpec` "refuse a computation flag" got `--min-persistence ... meaningless with --select-landmarks`, which only
`PersistenceThresholdCLISpec` provokes). After the change: 20 of 20 runs passed.

## What changed

`TDA4jCLI.run`, `writeLandmarkSelection`, `noteHiddenBars` and `writePlot` (all `private[cli]`) take an error stream, default
`System.err` resolved at the call; the two specs pass their own buffer and no longer touch `System.err`. No public API and no
behaviour change for the executable. The rule is in `rules/testing.md`.

## Left open

Nothing: the only `setErr`/`setOut` uses in the repository were these two specs.

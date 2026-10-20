# CI jobs failing after every step passed: setup-sbt's post-job hashFiles vs the lingering sbt server (2026-10-20)

Snapshot of one investigation; not edited afterwards. The rule it produced is in the steward skill and test.yml's header.

## Symptom

The project lead saw the Test workflow fail "at the MiMa job" twice in a row. Neither failure was MiMa, and neither was a
test:

| run (attempt 1) | job | sbt step | failing step |
|---|---|---|---|
| 39439632361 (#371, PR "NPM Publishing Automation") | `mima` | Binary Compatibility Checks: success (`mimaPreviousArtifacts is empty`) | Post Setup sbt launcher |
| 39100948890 (#367, dependabot codecov bump) | `test-js` | Run the Scala.js tests: success (1056 passed) | Post Setup sbt launcher |

Both logs, same line:

```
##[start-action display=sbt 2.x disk cache;...]
##[error]The template is not valid. sbt/setup-sbt/v1/action.yml (Line: 81, Col: 14): hashFiles('**/*.sbt, **/*.properties') failed. Fail to hash files under directory '/home/runner/work/tda4j/tda4j'
...
Terminate orphan process: pid (2281) (java)
```

A re-run of the job passed both times.

## Cause

1. `sbt/setup-sbt@v1` (composite) caches sbt 2's disk cache with a nested `actions/cache` whose key contains
   `hashFiles('**/*.sbt', '**/*.properties')` (action.yml line 81). The runner re-evaluates a nested action's inputs
   for its post step, so the hash runs again at the end of the job, over the whole workspace, `target/` included.
2. The runner's hashFiles (`actions/runner` `src/Misc/expressionFunc/hashFiles`, `@actions/glob` 0.7,
   `followSymbolicLinks: false`) `lstat`s every entry it lists, matching or not, and does not catch `ENOENT`: one file
   deleted between the directory listing and the `lstat` fails the expression, and the template error fails the job.
3. CI ran sbt through sbt 2's default thin client (`entering thin client - BEEP WHIRR`), which leaves the server
   running after the step (the orphan `java` above). Watching `target/global-logging/` with `fs.watch` while the CI's
   runner (sbt 2.0.10 zip) ran `tda4j/mimaReportBinaryIssues` in a fresh clone, three rounds: 0-15 ms after the client
   returned, the server deleted its previous `sbt-global-log<N>.log` and created a new one. In CI the post step began 50
   ms (mima) and 80 ms (test-js) after the sbt step ended: inside that window.

Mechanism of 2 reproduced on its own: a script mirroring the runner's hashFiles (same `@actions/glob` version and options)
walked a tree while a loop rotated one file in `target/global-logging/`; 2 walks of 30 failed with
`ENOENT: no such file or directory, lstat '.../target/global-logging/log1525'`. Running the mirror in a loop against the
real server (3 rounds, ~50 walks each, overlapping the rotation) never failed: the window is a few ms, which fits a
failure in a small fraction of jobs, in any job that runs sbt.

## Fix

Every sbt call in every workflow (`test.yml`, `lint.yml`, `docs.yml`, `coverage.yml`, `release.yml`, the demo build
included) runs as `sbt --server ...`: the runner's option for sbt in the foreground instead of sbtn. The step then ends
only when the sbt JVM has exited. Checked locally with the 2.0.10 runner: no JVM left (`jps`), no event under
`target/global-logging/` after the command returned, `.jvmopts`'s `-Xmx2G` on the java command line (`-v`), exit status
passed through. `SBT_OPTS=--server` does NOT work: the runner keeps SBT_OPTS out of its own argument parsing and passes
it to the JVM ("Unrecognized option: --server").

Cost: no server is shared between steps. Only the `test` job runs sbt twice (`clean ; tda4j/test`, then
`tda4j/assembly`); a warm `--server` start measured ~10 s here. That job runs beside `test-js` (~14 min), so the
workflow's wall time does not change.

Rejected without trying them: `disk-cache: false` on setup-sbt (drops the cross-run sbt 2 disk cache, a speed loss
nobody measured); a final `sbt shutdown` step (an extra step per job, `if: always()`, and it would have to wait for the
server JVM to exit, since the server may still write while it shuts down); the runner's `shutdownall` (`kill -9`).

## Not verified

That CI no longer flakes is shown only by the mechanism above, not by a run count: the failure was rare enough that a
handful of green runs proves nothing.

# The demo jar that came back from sbt's disk cache (2026-10-20)

Second failure of the trial run of `release.yml` on the hand-made tag `v0.7.0-preview` (the first, a version mismatch between
`publishLocal` and the demo build, is `WORKLOG-release-trial-tag.md`). With that fixed, the run got through the library
publish and the demo build, and the smoke test failed:

```
Error: demo/target/out/jvm/scala-3.9.0/tda4j-demo/tda4j-demo-0.7.0-preview.jar was not built
```

## What the run's log shows

- The demo step ran with `TDA4J_VERSION: 0.7.0-preview` and resolved `tda4j_3 % 0.7.0-preview` from `~/.ivy2/local`.
- It then printed `Built: .../demo/target/out/jvm/scala-3.9.0/tda4j-demo/tda4j-demo-0.6.2.jar` (`cache 66%, 12 disk cache hits`),
  a jar named for an earlier release.
- `setup-sbt` had restored `~/.cache/sbt` (sbt 2's disk cache) from a key dated 2026-10-19 just before.
- A workflow dispatch checks out the TAG (`ref: inputs.tag`), here `ebc5511`, while the workflow file is the dispatched
  branch's. So the sources of a trial run are the tag's, whatever `scala` has since: a fix in `demo/build.sbt` does not
  reach a run on an older tag, a fix in `release.yml` does.

## Reproduction here (sbt 2.0.10, JVM, `demo/`)

`~/.ivy2/local` held `tda4j_3` at `0.7.0-SNAPSHOT` and `0.7.0-preview` (the two jars differ: `LibraryVersion` is compiled in).
Each run is `TDA4J_VERSION=<v> sbt --server "++3.9.0! ; assembly"` in `demo/`.

1. `0.7.0-preview`, then `0.7.0-SNAPSHOT`: the second run printed `Built: .../tda4j-demo-0.7.0-preview.jar`. The thin jar of the
   run was named for the snapshot (`tda4j-demo_3-0.7.0-SNAPSHOT.jar`), so `version` was right; the `assembly` result was the
   earlier run's.
2. The same after `rm -rf demo/target`: still the preview jar, from `~/.cache/sbt`, so the cache is the disk cache and not
   `target/`.
3. With `set Global / localCacheDirectory := <empty dir>` the snapshot run built the snapshot jar. With
   `set Global / cacheStores := Nil` too, with the warm default cache in place.

## Fix

`Global / cacheStores := Nil` in `demo/build.sbt` (every local build of the demo, including the README's
`TDA4J_VERSION=0.6.1 sbt assembly` after a build of another version), and `set Global / cacheStores := Nil` in the
workflow's demo step (a tag cut before the build file had it). Checked with the setting in the build file: for each of the two
versions, `rm -rf target` and a build gives the jar of that version, whose `LibraryVersion` says that version, and the
workflow's smoke script on the preview jar prints `demo-smoke: bars=5 h1=1`.

## Not established

- WHY the cache key ignores the library. The assembly result for another library jar (different content, different path,
  different version) was a hit; only the demo's own classes are the same between the runs. Not traced in sbt 2 or
  sbt-assembly 2.5.0 (which has its own `AssemblyCache`); no upstream report filed.
- Whether a republished jar of the SAME version (`publishLocal` again after a library change, then `sbt assembly`) is a hit
  too. Likely by the same reasoning, not run. The new setting covers it.
- What put the `0.6.2` entry in the CI cache: presumably the release run of that tag, the cache having been saved after it
  (the key is dated 2026-10-19); not checked.
- A run on GitHub. Nothing here starts `release.yml`.
- The root build's own steps (`tda4j/assembly`, `tda4jJS/npmPack`) also run with the restored cache. They named their outputs by
  `0.7.0-preview` in this run's log (the root `assembly` jar did); their keys include the compiled `LibraryVersion`, which the
  demo's do not. The npm tarball's name is not in the log.

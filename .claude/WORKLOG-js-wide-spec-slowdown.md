# WideSimplexIndexingSpec: 8 minutes on Scala.js (2026-11-09, #324)

Weekly CI check: `test-js` median 6.0 -> 13.1 min from 2026-10-29. `WideSimplexIndexingSpec` took 4.5 to 11.4 min per
run (5 to 13 s before).

- First attribution, wrong: the in-place sort of #266, because the first slow run came right after that merge. Four
  PRs (#260, #262, #263, #266) merged within two minutes; the spec never calls a sort.
- Measured (`sbt "tda4jJS/testOnly *WideSimplexIndexingSpec -- showtimes"`): spec 8 min 1 s, of which the example
  "walk the cofacets and facets of a decoded simplex..." (added by #263, 7e8d937) is 7 min 55 s; the other six, 6 s.
- Two causes in that example: it drew `wideCase` (1500 to 2000 vertices) and a cofacet cursor visits every vertex
  (about 24 s per case on JS, where a Long is an object); and the spec's `given Parameters = ...propertyCases(n)` sat
  INSIDE each example, where specs2's Prop-to-Result conversion never sees it, so 100 cases ran on JS, not 10.
- Fix: the example takes the 300 to 400 vertex `cofacetCase` (shared with the other cofacet example); the spec mixes
  in `PlatformScalaCheck` (100 cases on the JVM, 10 on JS) and drops the four in-example givens, which never took
  effect (the JVM run stays at 100 cases). Spec on JS: 8 min -> 2.6 s; on the JVM 3.3 s.
- Rule: `rules/testing.md` (a `given Parameters` belongs at class level).

# WORKLOG: facade rename + Betti numbers helper (2026-10-02)
- Betti helper: homology.BettiNumbers (finite sets, and infinite ones via skeleton(maxDegree+1)); SSetBetti (test) and
  ClassifyingSpace.bettiNumbers(nerve) now delegate to it. Exported from TDAlab.homology. Primes only (Z deferred).
- Facade rename: `sheehy-rips` -> `sparse-rips`, `sheehyEpsilon` -> `sparseEpsilon`, CLI `--sheehy-epsilon` -> `--sparse-epsilon`
  (TDA4j, TDA4jConf, TDA4jCLI, user-guide pages, specs, CLAUDE.md; worklog file names untouched). Other option strings already match
  the new object names (`dtm-rips`, `cech`, `witnessVariant`). No compat alias; the old complex value gets a rename hint in its error.

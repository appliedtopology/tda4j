# WORKLOG: facade rename + Betti numbers helper (2026-10-02)
- Betti helper: homology.BettiNumbers (finite sets, and infinite ones via skeleton(maxDegree+1)); SSetBetti (test) and
  ClassifyingSpace.bettiNumbers(nerve) now delegate to it. Exported from TDAlab.homology. Primes only (Z deferred).

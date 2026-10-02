# WORKLOG: presentation complex, smash, join, CP^2, Hopf map, Steenrod (started 2026-10-02)

Scope agreed with the project lead: do it if ~8h. Plan/order (advisor): presentation complex, smash, join, CP^2 (+ cross-check),
Hopf map, then Steenrod squares if cheap. One commit per block. Sources: Sage `simplicial_set_examples.py` (CP^2 as a 1-vertex
simplicial set with explicit face tuples, `HopfMap` S^3 model, `PresentationComplex`) and `simplicial_complex_examples.py`
(Kuhnel-Banchoff 9-vertex CP^2, f-vector (9,36,84,90,36)), both read via raw.githubusercontent.com through WebFetch (a small
model summarizes the page, so every transcription is verified by `validate()` + Betti numbers + cup products, never trusted).
- CP^3 / CP^4: Sage builds them from Kenzo output files (not reachable here) => NOT done.
- Block 1 presentation complex (SimplicialSets.presentationComplex): 7 specs incl. torus cup, Klein, RP2 over F_2/F_3, Hurewicz round trip.

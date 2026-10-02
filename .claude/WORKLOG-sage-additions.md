# WORKLOG: presentation complex, smash, join, CP^2, Hopf map, Steenrod (started 2026-10-02)

Scope agreed with the project lead: do it if ~8h. Plan/order (advisor): presentation complex, smash, join, CP^2 (+ cross-check),
Hopf map, then Steenrod squares if cheap. One commit per block. Sources: Sage `simplicial_set_examples.py` (CP^2 as a 1-vertex
simplicial set with explicit face tuples, `HopfMap` S^3 model, `PresentationComplex`) and `simplicial_complex_examples.py`
(Kuhnel-Banchoff 9-vertex CP^2, f-vector (9,36,84,90,36)), both read via raw.githubusercontent.com through WebFetch (a small
model summarizes the page, so every transcription is verified by `validate()` + Betti numbers + cup products, never trusted).
- CP^3 / CP^4: Sage builds them from Kenzo output files (not reachable here) => NOT done.
- Block 1 presentation complex (SimplicialSets.presentationComplex): 7 specs incl. torus cup, Klein, RP2 over F_2/F_3, Hurewicz round trip.
- Block 2 smash (product / wedge via quotient): S^p^S^q ~ S^(p+q), X^S^0 ~ X (RP^2), RP^2^S^1 ~ suspension; passed first run.
- Block 3 join (OfX/OfY/Both generators): Delta^1*Delta^1 has Delta^3's f-vector (exact), pt*X contractible, S^0*RP^2 = suspension, S^p*S^q ~ S^(p+q+1); passed after a syntax fix.
- Block 4 CP^2: Sage's 1-vertex model (transcribed, validate() passes) AND Kuhnel-Banchoff 9 vertices (f-vector (9,36,84,90,36)); both Betti (1,0,1,0,1) over F_2,F_3 with x^2 != 0; S^2 v S^4 has zero square.
- Block 5 Hopf map (Sage's S^3 model transcribed; lower-cell images derived from 3-cell faces + consistency check): mapping cone has CP^2's ring (x^2 != 0, F_2 and F_3); constant map's cone (S^2 v S^4) has zero square. Added SimplicialSets.sphere(n).

# WORKLOG: Brown's collapsing scheme on the bar complex, filtered by a subgroup chain (2026-10-10)

Follow-up to `WORKLOG-lighter-classifying-spaces.md`, whose open question was "can the bar complex of a group be compressed
by a discrete Morse matching and still filter by a chain of subgroups?". Project lead asked for the Z/4 experiment. Code:
`src/test/scala/.../sset/BrownCollapseExperimentSpec.scala` (test tree only: `BrownCollapse.Rewriting`, `Collapse`,
`MorseStream`).

## Setup

- **Rewriting system.** It is given by the normal form of every element: one word per element over `letters` (element
  indices). The rules are the minimal non-normal words (longest proper prefix and suffix normal), each rewritten to its
  normal form. `validate()` checks:
  - one word per element, each evaluating to its element;
  - every letter is its own normal form;
  - the set of words is closed under taking factors;
  - every rule is shortlex-decreasing, so rewriting terminates.

  Together these make the system complete: the irreducible words are in bijection with the monoid, so it is confluent.
- **Brown's classification** of a nerve cell `[x_1|...|x_n]`, with entries read as normal-form words:
  - If `x_1` is longer than one letter, the cell is redundant; its partner splits `x_1` after its first letter.
  - Otherwise scan the pairs `(x_j, x_{j+1})` in order and stop at the first one that is not an "essential extension":
    - if `x_j x_{j+1}` is irreducible, the cell is collapsible and its partner merges the two entries (face `d_j`);
    - otherwise take the shortest prefix `u` of `x_{j+1}` that makes `x_j u` reducible. If `u` is all of `x_{j+1}`,
      continue the scan. If `u` is shorter, the cell is redundant and its partner splits `x_{j+1} = u v`.
  - A cell that passes every pair is critical.
- **Morse complex by the flow.** Take `∂c` with integer coefficients, then repeatedly remove a redundant face `σ` by
  adding `-(coefficient of σ)·[∂τ:σ]·∂τ`, where `τ` is σ's collapsible partner. Keep the critical part. The added
  `τ`s are the correction in `ι(c) = c + Σ aτ`, the chain map back to the bar complex. The incidence is checked to be
  ±1 at every step, and a step guard stops the flow if the matching is not acyclic.
- **Filtration.** A critical cell keeps its bar-complex level (`ClassifyingSpace.filtrationBy`). The Morse complex goes
  through the chunks engine as a custom `OrderedCell`.

## Systems and chains tested

| group, chain | letters | normal forms |
|---|---|---|
| Z/4, Z/2 < Z/4 (pc) | g1 = 1, g2 = 2 | g1^a g2^b |
| Z/4 (negative control) | a = 1 | a^k, k < 4: element 2 = "aa" uses a letter outside Z/2 |
| Z/8, Z/2 < Z/4 < Z/8 (pc) | 1, 2, 4 | binary digits |
| S_3, Z/3 < S_3 (pc) | g1 = (0 1), g2 = (0 1 2), h = g2² | g1^a (1 \| g2 \| h) |

The S_3 system needs `h` as its own letter. With only g1 and g2, the conjugation rule g2 g1 -> g1 g2 g2 lengthens the word,
so shortlex termination fails. That was the advisor's catch before any code was written. The 7 rules are g1g1, g2g1,
g2g2, g2h, hg1, hg2, hh.

## Results (all asserted in the spec; sbt testOnly ~25 s including compile)

**Matching.** On every cell up to dimension 5, for all four systems, the matching is an involution with incidence ±1. The
flow always terminated.

**Critical cells per dimension, 0..6, against the bar complex:**

| system | critical | bar complex |
|---|---|---|
| Z/4 pc | 1, 2, 3, 4, 5, 6, 7 (n + 1) | 1, 3, 9, 27, 81, 243, 729 |
| Z/4 one generator | 1, 1, 1, 1, 1, 1, 1 | same |
| Z/8 pc | 1, 3, 6, 10, 15, 21, 28 (C(n+2, 2)) | 1, 7, 49, ..., 117649 |
| S_3 pc | 1, 3, 7, 15, 31, 63, 127 (2^(n+1) - 1) | 1, 5, 25, ..., 15625 |

So the pc presentations compress polynomially for cyclic 2-groups. For S_3 the growth is still exponential, but with base
2 instead of 5. That base comes from the enlarged alphabet: the minimal resolution of S_3 has far fewer cells.

**Straddling.** A straddling pair is a matched pair whose two cells lie at different filtration levels. The pc systems
have none. The one-generator Z/4 system has one in each dimension 1..4: for example, [aa] at level 0 is matched with
[a|a] at level 1.

**Barcodes.** First, the bar complex's own barcodes match the values derived by hand:
- Z/4 over F_2: degree 0 [0,∞); odd degrees [0,1) and [1,∞); even degrees [0,∞).
- S_3 over F_3: degrees 1 and 2 [0,1); degrees 3 and 4 [0,∞).
- S_3 over F_2: [1,∞) in every positive degree.

Then the Morse complex, filtered by its critical cells' levels, has exactly the bar complex's barcode in degrees 0..4 in
each case tested: Z/4 over F_2 and F_3, Z/8 over F_2, S_3 over F_2 and F_3. Its boundary is monotone in every case. Every
representative it reports, lifted by `ι`, is a cycle of the bar complex made of cells present at the bar's birth.

The Morse boundaries are the expected integer relations. For example, ∂[g1|g1] = 2[g1] − [g2] is the pc relation g1² = g2,
and ∂[g2|g2] = 2[g2].

**Negative control (one-generator Z/4).** The homology is right: one essential class in each degree 0..4. The barcode is
wrong: (0,0,∞), then (n, 1, ∞) for n = 1..4. Every class is born at level 1, because every critical cell of positive
dimension has an entry (1 or 3) outside Z/2. So the condition is needed, not decorative.

## Engine finding (fixed, separate commit)

The first run crashed with `IndexOutOfBoundsException` in `CellularPersistenceInChunksEngine.unionFindDim01`. That code read
`endpoints(0)` and `endpoints(1)` of every edge. A critical 1-cell of the Morse complex has boundary 0, written as no
entries at all, so there was nothing to read. Simplices, cubes and simplicial sets write a loop's boundary as (v, 1),
(v, −1), so nothing in the library had hit this. The `Cell` contract does allow it, for example a CW complex.

Fix: such an edge is cycle-forming and is its own representative. Regression spec in `PersistenceInChunksSpec`: RP² as a
CW complex with one cell per dimension, run through the chunks and naive engines over F_2 and F_3.

## Conclusions and limits

- Brown's collapse of the bar complex is a filtered compression when the presentation refines the chain. It keeps the
  barcode and gives representatives in bar coordinates. This held in all five (system, prime) cases tested; it is a
  check on those cases, not a proof.
- Not tested:
  - chains that are not subnormal, such as the S_4 chain in `DESIGN-persistent-group-cohomology.md`, which has no pc
    presentation refining it;
  - groups bigger than S_3;
  - speed. The experiment enumerates the whole bar complex to find the critical cells and is not faster. A real
    implementation would generate the Anick chains directly and run the flow lazily.
- Cup products are not available on the Morse complex. They would need the chain equivalence and the bar complex's
  Alexander–Whitney diagonal.
- Still a test-tree experiment. Promoting it needs the following:
  - Anick-chain generation without enumerating the bar complex;
  - a rewriting-system builder (for example from a pc presentation);
  - a decision on where `Rewriting` lives.

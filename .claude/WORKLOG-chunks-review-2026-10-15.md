# Adversarial review of the #51 chunks-engine changes (2026-10-15)

Follows `WORKLOG-chunks-top-cells.md` ("The review did not happen"). Workflow `wf_4f902dd5-c15`: three reviewers (ordering
equivalence, run-once guard, test adequacy), two skeptics per candidate finding, script
`review-chunks-51.js` (the original with the spec description corrected: the diff added TWO examples, not three). 11
agents, none errored, about 28 minutes. This time the reviewers ran, so an empty list for a dimension means "looked, found
nothing". Everything below is by reading the code: no reviewer ran a spec or a mutation.

## Result

| dimension | candidates | survived the skeptics |
|---|---|---|
| run-once guard (`advanceAll`) | 0 | 0 |
| ordering equivalence (rank ordering) | 1 | 1 (2 of 2 skeptics could not refute) |
| test adequacy | 3 | 1 (2 of 2); two refuted |

### 1. The rank ordering is the stream's ordering only for a weak order (minor)

`FiltrationOrdering.canonical` falls back to dimension-then-tie-break for a cell outside the stream's `filtrationValue`
domain. For a stream that yields such a cell (a partial `filtrationValue`), the relation can be cyclic, `allCells.sorted`
then returns SOME linear order, and that order disagrees with the stream on at least one pair of the cycle. The reviewers
built a four-cell `ExplicitStream` (`∆0 -> 0.0`, `∆2 -> 1.0`, `∆(0,1) -> 2.0`, `∆1` unlisted) where the union-find in
`unionFindDim01` kills a different vertex: the diagram changes, and Chunks and Naive disagree where they agreed before.

- Reach: no library stream does this. They checked VR, Cech, Witness, Dowker, DtmRips, Sheehy, alpha, cubical, sset and
  `CollapsedNerve`; `ExplicitStream`'s public constructor with a cell missing from its map does. It breaks the streams rule
  that the ordering is total (`rules/streams.md`, rule 1); the old behaviour was already non-transitive.
- The sentence in `WORKLOG-chunks-top-cells.md` that "the answer is always the stream's answer" is too strong: it holds when
  the stream's ordering is a weak order. Not edited there (worklogs are snapshots); the rule file says it correctly.
- Not done: a spec for the scenario, or a guard (detect a cyclic sort by checking the ranks against the stream on the
  adjacent pairs, and fall back to the stream's ordering). Whether it is worth code is the project lead's call; nothing
  in the library reaches it.

### 2. The new example does not pin the fallback's sign (minor, real)

"The engine's cell ranking answers as the stream's ordering does, also for cells that are not the stream's own objects"
(`PersistenceInChunksSpec`): its `own` stream is built on `VietorisRips`, whose Enumerating implementation builds fresh
vertices and fresh cells of dimension >= 2 on every `iterateDimension`, so `own` already takes the same fallback as `copied`.
`copied.pairing.toSet == own.pairing.toSet` therefore holds for any deterministic ordering. Replacing the fallback at
`Homology.scala:408` by `-streamOrdering.compare(x, y)` is claimed to pass all four assertions.

- NOT run: the mutation is the first thing to try (`sbt "testOnly *PersistenceInChunksSpec"` with that one-line change). If
  it passes, the gap is real, and the fix is an example whose fallback decides the answer: a stream with a tie-heavy
  vertex order whose cells are fresh objects on each iteration, compared against the Naive engine.

### Refuted

- "Tie-heavy clique example checks almost nothing" (bar counts degenerate, representative check vacuous): refuted by the
  skeptics as a defect in the committed change.
- "Nothing pins that the rank fast path is taken": a coverage observation about a hypothetical future edit, no scenario.

## Open

Neither finding is an issue yet (open items are issues, `CLAUDE.md`); drafted here for the project lead:
1. "Chunks: rank ordering differs from the stream's for a stream with cells outside its filtrationValue domain" (minor).
2. "PersistenceInChunksSpec: the fallback's sign is not pinned" (minor; mutation check first).

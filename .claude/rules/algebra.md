---
paths:
  - "src/main/scala/org/appliedtopology/tda4j/algebra/**"
  - "src/main/scala/org/appliedtopology/tda4j/cells/**"
  - "src/test/scala/org/appliedtopology/tda4j/algebra/**"
---

# The algebraic core: fields, chains, cells

Loads when you work in `algebra/` or `cells/`. Project-wide rules are in `.claude/CLAUDE.md`; the rule there that is not
only for this subsystem (never test the runtime class of a generic coefficient, the generic-`given` capture gotcha) stays there.

- `RingModule`/`Field`: minimal typeclasses built with `is` syntax; everything downstream is generic over `Field`.
  `FiniteField`: `Fp` opaque type per prime `p`.
  **Never test the runtime class of a generic coefficient** (`case d: Double`): on Scala.js every boxed number is a
  `Double`, so an `Fp` matches. Ask the field (`Field.toDouble`, #80; `WORKLOG-release-prep-2026-10-14.md`).
- `Chain[CellT: Ordering, CoefficientT: Field]`: abstract and OPEN (project lead: a place for the community to
  experiment): a storage passes order and field up (kept once) and implements `entryIterator`, all else defaults over it
  (`tda4juser/ChainStorageSpec`, outside the package). Ours: `HeapChain` (a `PriorityQueue` ordered by cell), `PackedChain`
  (`rules/cubical.md`). No nulls. Equality: formal sums by `field.isEqual` (an `Fp` has several `Int` forms), order-blind.
  Defines `reduceBy`/`reduceByUntil` (with an optional `fallback` for pivots needing on-the-fly substitution) and
  a `RingModule` instance. `reduceLoop` uses a `mutable.TreeMap` accumulator (use `z.head`, not `headOption`, which
  allocates an iterator on `mutable.TreeMap`). `hashCode` agrees with `equals` (#58): it hashes each nonzero term's
  cell with `Field.hash` of its coefficient, blind to order and storage, so a chain is a fine `Set`/`Map` key.
  `Field.hash` defaults to `0` (right for any field); `Fp` hashes its residue; `DoubleApproximated` stays `0`, since
  tolerance admits no finer hash (`WORKLOG-chain-hashcode.md`). A new `Field` with exact equality overrides `hash`.
- `Cell`/`OrderedCell`/`OrderedBasis`: anything with a `boundary` and a total order plugs into homology. Concrete
  instances: `Simplex`, `Cube`, `FiniteSimplicialSet` generators.
- `DenseVector`, `DenseMatrix`, `ConjugateGradient`, `Erf`: small dense `Double` linear algebra in pure Scala (no
  commons-math3 in shared code; it is a JVM test oracle only, `DenseLinearAlgebraOracleSpec`). Vectors and matrices are
  immutable values (equality and hash on entries: they sit inside `Hyperplane`/`Hypersphere`, keys of hash sets).
  `DenseMatrix.svd` is compact one-sided Jacobi (descending; `solve` is the pseudo-inverse with the cutoff
  `max(m,n) s_1 2^-52`); `lu` is Crout with partial pivoting, bit for bit commons' solutions. `ConjugateGradient.solve`
  takes the operator as a function. `WORKLOG-scalajs-numerics-linear-algebra.md`.
- `Cocell`/`OrderedCocell` were **removed on purpose**: coboundary is extrinsic (depends on the ambient complex),
  so a per-cell `coboundary` is the wrong shape. Don't reintroduce (`DESIGN-generic-cohomology.md`).

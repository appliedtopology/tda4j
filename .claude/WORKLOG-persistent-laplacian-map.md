# The persistent Laplacian of a simplicial map (#307 item 3)

Snapshot of 2026-11-18. Mikael's decision (card, 2026-11-18): the construction meant in #307 is the persistent Laplacian of
a simplicial map, Gülen, Mémoli, Wan and Wang (SoCG 2023, arXiv 2302.03771), not the persistent Laplacian of a poset
filtration; library first, checked against the inclusion `PersistentLaplacian`, facades after.

## The construction, as implemented

`PersistentLaplacian.ofMap(source, target, dimension)(vertexMap)`, for the `k`-th Laplacian of `f: K -> L`. From the
paper (Sections 2.1, 2.2; the matrix Algorithm of Section 4 was NOT followed, it has typos, see below):

- Inner products: chains of `K` with unit weights; a simplex `tau` of `L` gets the weight `w(tau)` = the number of
  simplices of `K` that map nondegenerately onto it (1 if none), and `<[tau],[tau]> = 1 / w(tau)` (paper eq. 2, with
  Definition 1, weight preserving). Then `f_q`, restricted to the orthogonal complement of its kernel, is an isometry onto
  `Im(f_q)`, and in orthonormal coordinates `F` is a partial isometry: `F[tau, sigma] = sign / sqrt(w(tau))`.
- `Delta_down = f^ delta delta^* f^-1`, `delta` = the boundary transpose of `K` restricted to the chains of `K_{q-1}`
  whose coboundary is in `ker(f_q)^perp`. With `B = D^T` and `Q` an orthonormal basis of `ker((1 - F^T F) B)`:
  `Delta_down = (F B Q)(F B Q)^T`.
- `Delta_up = d d^*` for `d` the boundary of `L` restricted to `{c in L_{q+1} : d c in f_q(ker D)}`. With `U` an
  orthonormal basis of `F ker D`, `Q'` a basis of `ker((1 - U U^T) E)`: `Delta_up = (E Q')(E Q')^T`, `E[tau, rho] = sign
  sqrt(w(rho) / w(tau))` (the boundary of `L` in orthonormal coordinates).
- Both are `X X^T` for one factor `X` (columns of both), so the matrix, eigenvalues and kernel go through the same
  `Spectrum` code as the inclusion Laplacian (`Result` and `MapResult` share it now). The rows are the `k`-simplices of
  `L` that `f` hits: both parts vanish elsewhere.

## What was checked, and where

- **The paper's worked example** (Figure 2, Examples 13, 15, 16; the numbers were read from arXiv 2302.03771v2 by a
  subagent, not from the figure): `K` has vertices a,b,c,d and the edges ab, bc, ac, ad, bd; `L` is the filled triangle
  xyz with the weight 2 on xy; `f: a,b,c,d -> x,y,z,y`. The paper's text says `d -> b`; that is inconsistent with its own
  matrices (it would send ad outside L), `d -> y` reproduces them. Our matrix in orthonormal coordinates is the paper's
  `[[7/2,0,1],[0,5/2,0],[1/2,0,3]]` conjugated by `diag(sqrt w)` (entry `(i,j)` times `sqrt(w_j / w_i)`), eigenvalues
  `5/2, 5/2, 4`, product 25 (the paper's determinant). Without the triangle (Example 17): the kernel is `(1,1,-1)`.
  Run it first when touching the weights or the sign conventions: dropping the weights or the up part fails it.
- **An inclusion** is `PersistentLaplacian(stream, k, s, t)` entry for entry (`ofMap(rips, s, rips, t, k)(identity)`).
- **The rank of the map on homology**: for 120 random simplicial maps (Vietoris-Rips complex of random points to the
  closure of the images plus random extra simplices) the dimension of the kernel equals the rank of `H_q(K) -> H_q(L)`
  from the boundary matrices over `Z/32003` (the test's own matrices, `ZpLinearAlgebra`, sign by bubble sort).
- **A second implementation**, in numpy from the same definitions (scratch, not kept), gave the same matrices for the
  paper's example, the square folded onto a triangle (eigenvalues 0, 2, 3) and a path folded onto an edge (the 1x1
  matrix `[3]`, which the user guide does not quote).

## Traps found

- **A relative rank tolerance on a zero matrix.** `DenseMatrix.svd.rank` cuts at `max(m,n) s_1 2^-52`, relative to the
  largest singular value; for a matrix that is zero to rounding (`E - U U^T E` when the boundaries are all in the span) the
  noise is its own largest value and counts as rank, and the up Laplacian vanished. Every rank in the persistent Laplacian
  now has an absolute floor of 1e-9 (`FloorOfRank`); the boundary entries are of size one, so the floor never hides a value.
  The paper's example is what caught it.
- **The paper's Algorithm 1 has two typos** (as read): step 8 uses the down Laplacian of `L` where Proposition 14 and the
  data line say the up one, and step 12 conjugates by `R^-1 ... R` where Proposition 14 writes `R ... R^-1`. Not used.
- The `(k+1)`-simplices of `K` matter, though only for the weights they give to the simplices of `L` in dimension `k+1`
  (eigenvalues, not the kernel); `ofMap` takes them from the source and says so.

## Left

Weights on `K` other than 1 (the paper allows any positive weights on `K`; a weight function would scale `D` and `F`
by diagonal factors), and the composition of maps along a tower (compose the vertex maps). Neither is built; a weight
function would be one more parameter (#354).

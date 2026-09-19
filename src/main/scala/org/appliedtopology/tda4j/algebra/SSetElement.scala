package org.appliedtopology.tda4j
package algebra

/** A simplex of a finitely-generated simplicial set, in Eilenberg–Zilber normal form: `word` is the strictly
  * decreasing list of degeneracy indices `[w1 > w2 > ... > wk]` such that this element is `s_w1 s_w2 ... s_wk
  * (generator)`, read outermost-first (`s_w1` is the last degeneracy applied). `word = Nil` means the element
  * *is* `generator`, which must be non-degenerate. Dimension is `generator`'s own dimension plus `word.length`.
  *
  * Decreasing, not increasing: `s_i s_j = s_{j+1} s_i` for `i <= j` takes a non-decreasing adjacent pair to a
  * decreasing one, e.g. `s_0 s_0 = s_1 s_0` -- so the (unique) normal form for "apply `s_0` twice" is `[1, 0]`,
  * never `[0, 1]`.
  */
case class SSetElement[G](word: List[Int], generator: G)

/** Composes a new outermost degeneracy `s_m` onto an already-normalized word, restoring the strictly-decreasing
  * invariant via `s_i s_j = s_{j+1} s_i` (`i <= j`): if `m` already exceeds the current outermost index the word
  * is untouched (already sorted); otherwise `m` must move past that index, incrementing it, and recurse.
  */
def insertOuter(m: Int, word: List[Int]): List[Int] = word match
  case Nil                => List(m)
  case w1 :: _ if m > w1  => m :: word
  case w1 :: rest         => (w1 + 1) :: insertOuter(m, rest)

/** `d_i` on an arbitrary element `s_w1 ... s_wk (y)`, given only the primitive face data on generators
  * (`faces(y)(i)` = `d_i(y)`, for `y` non-degenerate). Bottoms out on `word = Nil`; otherwise pushes `d_i` past
  * the outermost degeneracy `s_w1` via the simplicial identities:
  *   - `i < w1`:      `d_i s_w1 = s_{w1-1} d_i`        (recurse on the same `i`, rewrap one dimension lower)
  *   - `i` in `{w1, w1+1}`: `d_{w1} s_w1 = d_{w1+1} s_w1 = id`  (cancel: drop `s_w1` entirely)
  *   - `i > w1+1`:    `d_i s_w1 = s_w1 d_{i-1}`        (recurse on `i-1`, rewrap at the same outer index)
  *
  * `faces(y)` must return, for `y` of dimension `n`, exactly `n+1` already-normalized elements of dimension
  * `n-1` (empty for `n = 0`) -- see `FiniteSimplicialSet.validate()` for a runtime check of this contract.
  */
def faceOf[G](i: Int, word: List[Int], y: G, faces: G => IndexedSeq[SSetElement[G]]): SSetElement[G] =
  word match
    case Nil => faces(y)(i)
    case w1 :: rest =>
      if i < w1 then
        val inner = faceOf(i, rest, y, faces)
        SSetElement(insertOuter(w1 - 1, inner.word), inner.generator)
      else if i == w1 || i == w1 + 1 then SSetElement(rest, y)
      else
        val inner = faceOf(i - 1, rest, y, faces)
        SSetElement(insertOuter(w1, inner.word), inner.generator)

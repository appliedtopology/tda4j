package org.appliedtopology.tda4j
package cells

import org.appliedtopology.tda4j.algebra.{given, *}

/** Hand-verified finitely-generated simplicial sets with known homology, used to validate `faceOf`/`insertOuter`
  * (`SSetElement.scala`) independently of the (degeneracy-free) `fromStream` plumbing path -- see
  * `.claude/WORKLOG-simplicial-sets.md` for the full derivation of each.
  */
object SimplicialSetFixtures:

  /** Minimal simplicial set model of `S^n`: one vertex `v`, one non-degenerate top `n`-cell `e`, whose `n+1` faces are
    * all the same maximally-degenerate `(n-1)`-simplex over `v` (for `n = 1`, that degenerate simplex is `v` itself --
    * no degeneracy needed at all). `H_0 = H_n = F`, everything else `0`.
    */
  enum SphereGenerator derives CanEqual:
    case Vertex
    case Top

  object SphereGenerator:
    given Ordering[SphereGenerator] = Ordering.by {
      case Vertex => 0
      case Top    => 1
    }

  def minimalSphere(n: Int): FiniteSimplicialSet[SphereGenerator] =
    require(n >= 1, "minimalSphere is only defined for n >= 1")
    import SphereGenerator.*
    val byDim: IndexedSeq[Set[SphereGenerator]] =
      (0 to n)
        .map(d => if d == 0 then Set(Vertex) else if d == n then Set(Top) else Set.empty[SphereGenerator])
        .toIndexedSeq
    val topFaces: IndexedSeq[SSetElement[SphereGenerator]] =
      if n == 1 then IndexedSeq.fill(2)(SSetElement(Nil, Vertex))
      else
        val word = (n - 2 to 0 by -1).toList
        IndexedSeq.fill(n + 1)(SSetElement(word, Vertex))
    new FiniteSimplicialSet(summon[Ordering[SphereGenerator]])(
      byDim,
      { case Vertex => IndexedSeq.empty; case Top => topFaces }
    )

  /** Standard reduced-bar-construction model of `B(Z/2)`, truncated to its `topDim`-skeleton: one non-degenerate
    * generator `e_n` per dimension `0..topDim`. `d_0(e_n) = d_n(e_n) = e_{n-1}` (non-degenerate); for `0 < i < n`,
    * `d_i(e_n) = s_{i-1}(e_{n-2})` (Z/2's nontrivial generator squares to the identity). `topDim = 2` is RP² (the
    * sign-discriminating fixture: `H_1 = H_2 = F2` over F2, both `0` over F3); `topDim = 3` is RP³ (the fixture that
    * reaches `faceOf`'s `i > w1+1` branch, and pins an essential bar above dimension 0: `H_3 = F` over every field,
    * `H_1 = H_2 = 0` over F3).
    */
  enum ProjectiveGenerator derives CanEqual:
    case E(n: Int)

  object ProjectiveGenerator:
    given Ordering[ProjectiveGenerator] = Ordering.by { case E(n) => n }

  def realProjectiveSpace(topDim: Int): FiniteSimplicialSet[ProjectiveGenerator] =
    require(topDim >= 0, "realProjectiveSpace needs topDim >= 0")
    import ProjectiveGenerator.*
    val byDim: IndexedSeq[Set[ProjectiveGenerator]] =
      (0 to topDim).map(n => Set[ProjectiveGenerator](E(n))).toIndexedSeq
    def facesOf(g: ProjectiveGenerator): IndexedSeq[SSetElement[ProjectiveGenerator]] = g match
      case E(0) => IndexedSeq.empty
      case E(n) =>
        val outer = SSetElement[ProjectiveGenerator](Nil, E(n - 1))
        (0 to n)
          .map(i => if i == 0 || i == n then outer else SSetElement[ProjectiveGenerator](List(i - 1), E(n - 2)))
          .toIndexedSeq
    new FiniteSimplicialSet(summon[Ordering[ProjectiveGenerator]])(byDim, facesOf)

  /** Hatcher's minimal Δ-complex model of the torus (*Algebraic Topology*, Example 2.4): one vertex `v`, three
    * loop-edges `a, b, c` (`c` the diagonal), two triangles `u, l` with the SAME face assignment `d_0=b, d_1=c, d_2=a`.
    * Nothing here is degenerate -- its value is being a genuine Δ-complex (loops/repeated edges at a single vertex)
    * that `fromStream` can never produce from an actual `Simplex[VertexT]`. `H_0=F, H_1=F², H_2=F` for every
    * coefficient field.
    */
  enum TorusGenerator derives CanEqual:
    case Vertex, A, B, C, U, L

  object TorusGenerator:
    given Ordering[TorusGenerator] = Ordering.by {
      case Vertex => 0
      case A      => 1
      case B      => 2
      case C      => 3
      case U      => 4
      case L      => 5
    }

  def torus: FiniteSimplicialSet[TorusGenerator] =
    import TorusGenerator.*
    val byDim: IndexedSeq[Set[TorusGenerator]] = IndexedSeq(Set(Vertex), Set(A, B, C), Set(U, L))
    val loopFaces = IndexedSeq.fill(2)(SSetElement[TorusGenerator](Nil, Vertex))
    val triangleFaces =
      IndexedSeq(
        SSetElement[TorusGenerator](Nil, B),
        SSetElement[TorusGenerator](Nil, C),
        SSetElement[TorusGenerator](Nil, A)
      )
    def facesOf(g: TorusGenerator): IndexedSeq[SSetElement[TorusGenerator]] = g match
      case Vertex    => IndexedSeq.empty
      case A | B | C => loopFaces
      case U | L     => triangleFaces
    new FiniteSimplicialSet(summon[Ordering[TorusGenerator]])(byDim, facesOf)

  /** A single non-degenerate edge with two distinct endpoints -- raw material for `quotient`/`identify`
    * cross-validation (`SimplicialSetHomologySpec`), not a fixture with known homology on its own (it's contractible:
    * `H_0 = F`, nothing else).
    */
  enum EdgeGenerator derives CanEqual:
    case V0, V1, E

  object EdgeGenerator:
    given Ordering[EdgeGenerator] = Ordering.by { case V0 => 0; case V1 => 1; case E => 2 }

  def edge: FiniteSimplicialSet[EdgeGenerator] =
    import EdgeGenerator.*
    val byDim: IndexedSeq[Set[EdgeGenerator]] = IndexedSeq(Set(V0, V1), Set(E))
    def facesOf(g: EdgeGenerator): IndexedSeq[SSetElement[EdgeGenerator]] = g match
      case V0 | V1 => IndexedSeq.empty
      case E       => IndexedSeq(SSetElement(Nil, V1), SSetElement(Nil, V0))
    new FiniteSimplicialSet(summon[Ordering[EdgeGenerator]])(byDim, facesOf)

  /** A single filled 2-simplex (3 vertices, 3 edges, 1 face) -- raw material for `quotient` cross-validation
    * (`SimplicialSetHomologySpec`), not a fixture with known homology on its own (it's contractible: `H_0 = F`, nothing
    * else). Standard convention: `d_i` on the 2-cell removes vertex `i`; `d_i` on an edge `[a,b]` (`a<b`) has
    * `d_0 = b`, `d_1 = a`.
    */
  enum TriangleGenerator derives CanEqual:
    case V0, V1, V2, E01, E12, E02, F

  object TriangleGenerator:
    given Ordering[TriangleGenerator] = Ordering.by {
      case V0  => 0
      case V1  => 1
      case V2  => 2
      case E01 => 3
      case E12 => 4
      case E02 => 5
      case F   => 6
    }

  def triangle: FiniteSimplicialSet[TriangleGenerator] =
    import TriangleGenerator.*
    val byDim: IndexedSeq[Set[TriangleGenerator]] =
      IndexedSeq(Set(V0, V1, V2), Set(E01, E12, E02), Set(F))
    def facesOf(g: TriangleGenerator): IndexedSeq[SSetElement[TriangleGenerator]] = g match
      case V0 | V1 | V2 => IndexedSeq.empty
      case E01          => IndexedSeq(SSetElement(Nil, V1), SSetElement(Nil, V0))
      case E12          => IndexedSeq(SSetElement(Nil, V2), SSetElement(Nil, V1))
      case E02          => IndexedSeq(SSetElement(Nil, V2), SSetElement(Nil, V0))
      case F            => IndexedSeq(SSetElement(Nil, E12), SSetElement(Nil, E02), SSetElement(Nil, E01))
    new FiniteSimplicialSet(summon[Ordering[TriangleGenerator]])(byDim, facesOf)

  /** Hatcher's own single-2-simplex Delta-complex model of RP^2 (*Algebraic Topology*, Example 2.4), built as a
    * `quotient` of `triangle` rather than hand-assembled directly like `realProjectiveSpace` -- two of the three edges
    * (`E12 = d_0(F)`, `E01 = d_2(F)`) are glued into one loop (`E12`, chosen as the representative); the third (`E02 =
    * d_1(F)`) has no peer to glue to and instead collapses to a degenerate point over the vertex `V0` -- exactly the
    * case `identify` (generator-to-generator only) cannot express, and the reason `quotient` takes a
    * `G => SSetElement[G]` map rather than `G => G`. Shared between `SimplicialSetConstructionsSpec` (structural
    * checks) and `SimplicialSetHomologySpec` (cross-validated against the independently-hand-built
    * `realProjectiveSpace(2)`) so both specs exercise literally the same gluing, not two copies that could drift apart.
    */
  def rp2QuotientMap(g: TriangleGenerator): SSetElement[TriangleGenerator] =
    import TriangleGenerator.*
    g match
      case V0 | V1 | V2 => SSetElement(Nil, V0)
      case E12 | E01    => SSetElement(Nil, E12)
      case E02          => SSetElement(List(0), V0)
      case F            => SSetElement(Nil, F)

  def realProjectiveSpaceViaQuotient: FiniteSimplicialSet[TriangleGenerator] =
    quotient(triangle, rp2QuotientMap)

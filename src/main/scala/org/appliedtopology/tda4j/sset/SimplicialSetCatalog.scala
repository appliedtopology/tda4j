package org.appliedtopology.tda4j
package sset

import org.appliedtopology.tda4j.*

/** A generator of the cone on a simplicial set `X`: the apex, a copy of each generator of `X` (the base), or the cone
  * `Cone(g)` over a generator `g` (one dimension up).
  */
enum ConeGenerator[+G]:
  case Apex
  case Base(g: G)
  case Cone(g: G)

/** A cell of the simplicial set of a group presentation (see [[SimplicialSet.presentationComplex]]). */
enum PresentationCell derives CanEqual:
  /** The single vertex. */
  case Vertex

  /** The edge for generator `i`. */
  case Gen(i: Int)

  /** The edge standing for the inverse of generator `i`. */
  case Inv(i: Int)

  /** The triangle saying `Gen(i) · Inv(i) = 1`. */
  case InvTriangle(i: Int)

  /** The diagonal `c_k` (product of the first `k` letters) in the fan triangulation of relator `r`. */
  case Diagonal(r: Int, k: Int)

  /** The `k`-th triangle of the fan triangulation of relator `r`. */
  case Triangle(r: Int, k: Int)

/** The generators of the minimal simplicial-set model of the complex projective plane (see
  * [[SimplicialSet.complexProjectivePlane]]).
  */
enum ComplexProjectivePlaneGenerator derives CanEqual:
  case V, Rho0, Rho1, Sigma0, Sigma1, Sigma2, Tau0, Tau1, Tau2

object ComplexProjectivePlaneGenerator:
  given Ordering[ComplexProjectivePlaneGenerator] = Ordering.by(_.ordinal)

/** The two generators of the minimal `n`-sphere ([[SimplicialSet.sphere]]): its vertex and its single `n`-simplex. */
enum MinimalSphereGenerator derives CanEqual:
  case Vertex, Top

object MinimalSphereGenerator:
  given Ordering[MinimalSphereGenerator] = Ordering.by(_.ordinal)

/** The generators of Sage's simplicial model of `S^3` ([[SimplicialSet.hopfMap]]'s source). */
enum HopfSphereGenerator derives CanEqual:
  case W, B11, B22, B23, B44, Beta1, Beta2, Beta3, Beta4, A12, A23, A34, A45, A56, Alpha1, Alpha2, Alpha3, Alpha4,
    Alpha5, Alpha6

object HopfSphereGenerator:
  given Ordering[HopfSphereGenerator] = Ordering.by(_.ordinal)

/** A non-degenerate simplex of the join `X ⋆ Y`: a simplex of `X` alone, of `Y` alone, or a pair `(a, b)` of
  * non-degenerate simplices of both, of dimension `dim a + dim b + 1`.
  */
enum JoinGenerator[+GX, +GY]:
  case OfX(x: GX)
  case OfY(y: GY)
  case Both(x: GX, y: GY)

object JoinGenerator:
  given joinGeneratorOrdering: [GX: Ordering as ox, GY: Ordering as oy] => Ordering[JoinGenerator[GX, GY]]:
    private def tag(j: JoinGenerator[GX, GY]): Int = j match
      case OfX(_)     => 0
      case OfY(_)     => 1
      case Both(_, _) => 2
    def compare(a: JoinGenerator[GX, GY], b: JoinGenerator[GX, GY]): Int = (a, b) match
      case (OfX(p), OfX(q))             => ox.compare(p, q)
      case (OfY(p), OfY(q))             => oy.compare(p, q)
      case (Both(p1, p2), Both(q1, q2)) =>
        val c = ox.compare(p1, q1)
        if c != 0 then c else oy.compare(p2, q2)
      case _ => Ordering.Int.compare(tag(a), tag(b))

object PresentationCell:
  private def key(c: PresentationCell): (Int, Int, Int) = c match
    case Vertex         => (0, 0, 0)
    case Gen(i)         => (1, i, 0)
    case Inv(i)         => (2, i, 0)
    case Diagonal(r, k) => (3, r, k)
    case InvTriangle(i) => (4, i, 0)
    case Triangle(r, k) => (5, r, k)

  given Ordering[PresentationCell] =
    Ordering.by[PresentationCell, (Int, Int, Int)](key)(using
      Ordering.Tuple3(using Ordering.Int, Ordering.Int, Ordering.Int)
    )

object ConeGenerator:
  given coneGeneratorOrdering: [G: Ordering as ord] => Ordering[ConeGenerator[G]]:
    private def key(c: ConeGenerator[G]): (Int, Option[G]) = c match
      case ConeGenerator.Apex    => (0, None)
      case ConeGenerator.Base(g) => (1, Some(g))
      case ConeGenerator.Cone(g) => (2, Some(g))
    private val keyOrdering: Ordering[(Int, Option[G])] =
      Ordering.Tuple2(using Ordering.Int, Ordering.Option(using ord))
    def compare(x: ConeGenerator[G], y: ConeGenerator[G]): Int = keyOrdering.compare(key(x), key(y))

/** The generators of [[SimplicialSet.kleinBottle]]: one vertex, edges `a, b, c`, triangles `T1, T2`. */
enum KleinGenerator derives CanEqual:
  case V, A, B, C, T1, T2

object KleinGenerator:
  given Ordering[KleinGenerator] = Ordering.by(_.ordinal)

/** The generators of [[SimplicialSet.torus]]: one vertex, loops `A, B` and diagonal `C`, triangles `U, L`. */
enum TorusGenerator derives CanEqual:
  case Vertex, A, B, C, U, L

object TorusGenerator:
  given Ordering[TorusGenerator] = Ordering.by(_.ordinal)

/** The generators of [[SimplicialSet.realProjectiveSpace]]: one cell `E(n)` per dimension. */
enum RealProjectiveGenerator derives CanEqual:
  case E(n: Int)

object RealProjectiveGenerator:
  given Ordering[RealProjectiveGenerator] = Ordering.by { case E(n) => n }

/** The catalog behind `object SimplicialSet` (mixed in there, so every entry is `SimplicialSet.<name>`): ready-made
  * finite simplicial sets -- the Sage `simplicial_sets.*` gap list, `.claude/DESIGN-sage-simplicial-sets-comparison.md`
  * -- plus the constructors that build one from data. Operations on an existing set (`product`, `wedge`, `cone`,
  * `quotient`, ...) are methods of [[FiniteSimplicialSet]] instead. Pullbacks and integer coefficients are deliberately
  * not here.
  *
  * Complexes given by vertices use [[Simplex]]`[Int]` as the generator type; the faces of a simplex are `d_i` = omit
  * its `i`-th vertex, so the simplicial identities hold automatically.
  */
trait SimplicialSetCatalog:

  /** A finite simplicial set given by hand: its generators (non-degenerate simplices) per dimension, and for each
    * generator `g` of dimension `n > 0` its `n + 1` faces `d_0 g, ..., d_n g`, each an `SSetElement` (a generator,
    * possibly under a degeneracy word). Check the result with `validate()`.
    */
  def apply[G: Ordering](
    generatorsByDim: IndexedSeq[Set[G]],
    faces: G => IndexedSeq[SSetElement[G]]
  ): FiniteSimplicialSet[G] = new FiniteSimplicialSet(generatorsByDim, faces)

  /** The simplicial set of every simplex a stream produces (each simplex a generator, `d_i` dropping vertex `i`) --
    * e.g. a Vietoris-Rips complex as a simplicial set. Degeneracy never arises this way.
    */
  def fromStream[VertexT: Ordering](stream: CellStream[Simplex[VertexT], ?]): FiniteSimplicialSet[Simplex[VertexT]] =
    SimplicialSetStream.fromStream(stream)

  /** The minimal torus: Hatcher's Δ-complex (*Algebraic Topology*, Example 2.4) -- one vertex, loops `a, b`, diagonal
    * `c`, triangles `U` (`a·b = c`) and `L` (`b·a = c`). `H = (F, F², F)` over every field, and the cup product of the
    * two degree-1 classes is nonzero (which tells it from `S¹ ∨ S¹ ∨ S²`, whose Betti numbers agree).
    */
  def torus: FiniteSimplicialSet[TorusGenerator] =
    import TorusGenerator.*
    def bare(g: TorusGenerator) = SSetElement[TorusGenerator](Nil, g)
    def facesOf(g: TorusGenerator): IndexedSeq[SSetElement[TorusGenerator]] = g match
      case Vertex    => IndexedSeq.empty
      case A | B | C => IndexedSeq(bare(Vertex), bare(Vertex))
      case U         => IndexedSeq(bare(B), bare(C), bare(A)) // (d_0, d_1, d_2): a·b = c
      case L         => IndexedSeq(bare(A), bare(C), bare(B)) // b·a = c
    new FiniteSimplicialSet(IndexedSeq(Set(Vertex), Set(A, B, C), Set(U, L)), facesOf)

  /** Real projective space `RP^n` as the `n`-skeleton of the minimal model of `RP^∞ = B(Z/2)`: one cell `E(k)` per
    * dimension `k = 0..n`, with `d_0 E(k) = d_k E(k) = E(k-1)` and `d_i E(k) = s_{i-1} E(k-2)` for `0 < i < k`. Over
    * `F_2` every Betti number up to `n` is 1; over `F_3` only `H_0`, and `H_n` when `n` is odd.
    */
  def realProjectiveSpace(n: Int): FiniteSimplicialSet[RealProjectiveGenerator] =
    require(n >= 0, s"realProjectiveSpace needs n >= 0, got $n")
    import RealProjectiveGenerator.*
    def facesOf(g: RealProjectiveGenerator): IndexedSeq[SSetElement[RealProjectiveGenerator]] = g match
      case E(0) => IndexedSeq.empty
      case E(k) =>
        val outer = SSetElement[RealProjectiveGenerator](Nil, E(k - 1))
        IndexedSeq.tabulate(k + 1)(i =>
          if i == 0 || i == k then outer else SSetElement[RealProjectiveGenerator](List(i - 1), E(k - 2))
        )
    new FiniteSimplicialSet(IndexedSeq.tabulate(n + 1)(k => Set[RealProjectiveGenerator](E(k))), facesOf)

  /** The classifying space `BG` of a finite group (its nerve; infinite -- take `.skeleton(n)`, or use
    * `BettiNumbers(SimplicialSet.classifyingSpace(g), maxDegree, p)`). See [[ClassifyingSpace]] for persistent group
    * homology along a subgroup chain.
    */
  def classifyingSpace(group: FiniteGroup): Nerve = ClassifyingSpace.nerve(group)

  /** The simplicial set of the abstract simplicial complex generated by `facets` (every nonempty face of a facet is a
    * generator). Vertices need not be `0..n`; their natural order orients each simplex.
    */
  def fromSimplicialComplex(facets: Iterable[Simplex[Int]]): FiniteSimplicialSet[Simplex[Int]] =
    val all: Set[Simplex[Int]] = facets.iterator.flatMap { facet =>
      val vertices = facet.toList
      (1 to vertices.size).iterator.flatMap(k => vertices.combinations(k).map(c => Simplex.from(c)))
    }.toSet
    val top = if all.isEmpty then -1 else all.map(_.dim).max
    val byDim = IndexedSeq.tabulate(top + 1)(d => all.filter(_.dim == d))
    def faces(s: Simplex[Int]): IndexedSeq[SSetElement[Simplex[Int]]] =
      if s.dim == 0 then IndexedSeq.empty
      else s.toList.indices.map(i => SSetElement(Nil, Simplex.from(s.toList.patch(i, Nil, 1)))).toIndexedSeq
    new FiniteSimplicialSet(byDim, faces)

  /** The standard `n`-simplex `Δ^n` (all faces of `{0..n}`); contractible. */
  def simplex(n: Int): FiniteSimplicialSet[Simplex[Int]] =
    require(n >= 0)
    fromSimplicialComplex(Seq(Simplex.from((0 to n).toList)))

  /** A single point. */
  def point: FiniteSimplicialSet[Simplex[Int]] = simplex(0)

  /** The empty simplicial set (no generators in any dimension). */
  def empty: FiniteSimplicialSet[Simplex[Int]] = fromSimplicialComplex(Seq.empty)

  /** The horn `Λ^n_k`: `Δ^n` without its interior and without its `k`-th facet (the one omitting vertex `k`); a
    * contractible set.
    */
  def horn(n: Int, k: Int): FiniteSimplicialSet[Simplex[Int]] =
    require(n >= 1 && k >= 0 && k <= n)
    fromSimplicialComplex((0 to n).filter(_ != k).map(i => Simplex.from((0 to n).filter(_ != i).toList)))

  /** The Klein bottle as a Δ-complex with one vertex, edges `a, b, c` and two triangles whose faces `(d_0, d_1, d_2)`
    * are `(b, c, a)` and `(a, b, c)`. Derived by hand: `π_1 = <a, b | a b a = b>` (Klein group), `∂T1 = a + b - c`,
    * `∂T2 = a - b + c` give `H_1 = Z ⊕ Z/2`, `H_2 = 0` -- so Betti numbers `(1, 2, 1)` over `F_2` and `(1, 1, 0)` over
    * `F_3`, which is what distinguishes it from the torus.
    */
  def kleinBottle: FiniteSimplicialSet[KleinGenerator] =
    import KleinGenerator.*
    def v(g: KleinGenerator) = SSetElement[KleinGenerator](Nil, g)
    def facesOf(g: KleinGenerator): IndexedSeq[SSetElement[KleinGenerator]] = g match
      case V         => IndexedSeq.empty
      case A | B | C => IndexedSeq(v(V), v(V))
      case T1        => IndexedSeq(v(B), v(C), v(A))
      case T2        => IndexedSeq(v(A), v(B), v(C))
    new FiniteSimplicialSet(IndexedSeq(Set(V), Set(A, B, C), Set(T1, T2)), facesOf)

  /** The presentation complex of `<g_0..g_{n-1} | relations>`: one vertex; an edge per generator plus one for its
    * inverse (tied to it by a triangle `g · g^{-1} = 1`); and each relator, a word of letters `(generator, +1 or -1)`,
    * filled by a fan of triangles over its polygon, whose last triangle's third edge is the degenerate (identity) edge.
    * Its `π_1` is the presented group, and it is the inverse of [[FundamentalGroup.presentation]] up to homotopy.
    */
  def presentationComplex(
    numGenerators: Int,
    relations: Seq[List[(Int, Int)]]
  ): FiniteSimplicialSet[PresentationCell] =
    import PresentationCell.*
    relations.foreach(
      _.foreach((i, e) => require(i >= 0 && i < numGenerators && (e == 1 || e == -1), s"bad letter ($i,$e)"))
    )
    val identityEdge = SSetElement[PresentationCell](List(0), Vertex)
    def bare(c: PresentationCell) = SSetElement[PresentationCell](Nil, c)
    def letter(l: (Int, Int)): PresentationCell = if l._2 > 0 then Gen(l._1) else Inv(l._1)

    val faceTable =
      scala.collection.mutable.LinkedHashMap.empty[PresentationCell, IndexedSeq[SSetElement[PresentationCell]]]
    faceTable(Vertex) = IndexedSeq.empty
    for i <- 0 until numGenerators do
      faceTable(Gen(i)) = IndexedSeq(bare(Vertex), bare(Vertex))
      faceTable(Inv(i)) = IndexedSeq(bare(Vertex), bare(Vertex))
      faceTable(InvTriangle(i)) = IndexedSeq(bare(Inv(i)), identityEdge, bare(Gen(i)))
    for (word, r) <- relations.zipWithIndex do
      word.length match
        case 0 => faceTable(Triangle(r, 1)) = IndexedSeq(identityEdge, identityEdge, identityEdge)
        case 1 => faceTable(Triangle(r, 1)) = IndexedSeq(bare(letter(word.head)), identityEdge, identityEdge)
        case l =>
          // c_1 = e_1, c_k = c_{k-1} · e_k; the last diagonal is the identity.
          def diagonal(k: Int): SSetElement[PresentationCell] =
            if k == 1 then bare(letter(word.head)) else if k == l then identityEdge else bare(Diagonal(r, k))
          for k <- 2 until l do faceTable(Diagonal(r, k)) = IndexedSeq(bare(Vertex), bare(Vertex))
          for k <- 2 to l do
            faceTable(Triangle(r, k)) = IndexedSeq(bare(letter(word(k - 1))), diagonal(k), diagonal(k - 1))
    def dim(c: PresentationCell): Int = c match
      case Vertex                           => 0
      case Gen(_) | Inv(_) | Diagonal(_, _) => 1
      case InvTriangle(_) | Triangle(_, _)  => 2
    val byDim = IndexedSeq.tabulate(3)(d => faceTable.keySet.filter(dim(_) == d).toSet)
    new FiniteSimplicialSet(byDim.reverse.dropWhile(_.isEmpty).reverse, faceTable)

  /** The presentation complex of a [[GroupPresentation]] (e.g. one read off with [[FundamentalGroup.presentation]]). */
  def presentationComplex[G](p: GroupPresentation[G]): FiniteSimplicialSet[PresentationCell] =
    presentationComplex(p.generators.length, p.relations)

  /** A minimal simplicial-set model of `CP^2`: one vertex, two 2-simplices, three 3-simplices and three 4-simplices.
    * Transcribed from Sage's `simplicial_sets.ComplexProjectiveSpace(2)` (`simplicial_set_examples.py`), whose face
    * tuples are listed `d_0, d_1, ...`. Checked here by `validate()` (simplicial identities), by Betti numbers
    * `(1, 0, 1, 0, 1)` over every field, and by `x ∪ x ≠ 0` for the degree-2 class (which tells it from `S^2 ∨ S^4`);
    * not trusted on the transcription alone. `CP^3`/`CP^4` are not available: Sage builds them from Kenzo data files.
    */
  def complexProjectivePlane: FiniteSimplicialSet[ComplexProjectivePlaneGenerator] =
    import ComplexProjectivePlaneGenerator.*
    type G = ComplexProjectivePlaneGenerator
    def bare(g: G) = SSetElement[G](Nil, g)
    def degenerate(g: G, word: Int*) = SSetElement[G](word.toList, g)
    val sv = degenerate(V, 0)
    val ssv = degenerate(V, 1, 0)
    def facesOf(g: G): IndexedSeq[SSetElement[G]] = g match
      case V           => IndexedSeq.empty
      case Rho0 | Rho1 => IndexedSeq(sv, sv, sv)
      case Sigma0      => IndexedSeq(bare(Rho0), bare(Rho1), bare(Rho0), ssv)
      case Sigma1      => IndexedSeq(bare(Rho0), bare(Rho0), bare(Rho0), bare(Rho0))
      case Sigma2      => IndexedSeq(ssv, bare(Rho0), bare(Rho1), bare(Rho0))
      case Tau0        =>
        IndexedSeq(degenerate(Rho0, 0), degenerate(Rho0, 0), bare(Sigma1), degenerate(Rho0, 2), degenerate(Rho0, 2))
      case Tau1 => IndexedSeq(degenerate(Rho0, 1), bare(Sigma2), bare(Sigma1), bare(Sigma0), degenerate(Rho0, 1))
      case Tau2 => IndexedSeq(degenerate(Rho0, 2), bare(Sigma2), degenerate(Rho0, 1), bare(Sigma0), degenerate(Rho0, 0))
    new FiniteSimplicialSet(
      IndexedSeq(Set(V), Set.empty[G], Set(Rho0, Rho1), Set(Sigma0, Sigma1, Sigma2), Set(Tau0, Tau1, Tau2)),
      facesOf
    )

  /** The 9-vertex triangulation of `CP^2` of Kühnel and Banchoff (1983): 36 four-simplices, f-vector
    * `(9, 36, 84, 90, 36)`. Facet list from Sage's `simplicial_complexes.ComplexProjectivePlane()`; an independent
    * model to cross-check [[complexProjectivePlane]] against.
    */
  def complexProjectivePlaneKuhnel: FiniteSimplicialSet[Simplex[Int]] =
    val facets = Seq(
      Seq(1, 2, 4, 5, 6),
      Seq(2, 3, 5, 6, 4),
      Seq(3, 1, 6, 4, 5),
      Seq(1, 2, 4, 5, 9),
      Seq(2, 3, 5, 6, 7),
      Seq(3, 1, 6, 4, 8),
      Seq(2, 3, 6, 4, 9),
      Seq(3, 1, 4, 5, 7),
      Seq(1, 2, 5, 6, 8),
      Seq(3, 1, 5, 6, 9),
      Seq(1, 2, 6, 4, 7),
      Seq(2, 3, 4, 5, 8),
      Seq(4, 5, 7, 8, 9),
      Seq(5, 6, 8, 9, 7),
      Seq(6, 4, 9, 7, 8),
      Seq(4, 5, 7, 8, 3),
      Seq(5, 6, 8, 9, 1),
      Seq(6, 4, 9, 7, 2),
      Seq(5, 6, 9, 7, 3),
      Seq(6, 4, 7, 8, 1),
      Seq(4, 5, 8, 9, 2),
      Seq(6, 4, 8, 9, 3),
      Seq(4, 5, 9, 7, 1),
      Seq(5, 6, 7, 8, 2),
      Seq(7, 8, 1, 2, 3),
      Seq(8, 9, 2, 3, 1),
      Seq(9, 7, 3, 1, 2),
      Seq(7, 8, 1, 2, 6),
      Seq(8, 9, 2, 3, 4),
      Seq(9, 7, 3, 1, 5),
      Seq(8, 9, 3, 1, 6),
      Seq(9, 7, 1, 2, 4),
      Seq(7, 8, 2, 3, 5),
      Seq(9, 7, 2, 3, 6),
      Seq(7, 8, 3, 1, 4),
      Seq(8, 9, 1, 2, 5)
    )
    fromSimplicialComplex(facets.map(f => Simplex.from(f.toList)))

  /** The minimal `n`-sphere (`n >= 1`): one vertex and one non-degenerate `n`-simplex, all of whose faces are
    * degenerate.
    */
  def sphere(n: Int): FiniteSimplicialSet[MinimalSphereGenerator] =
    require(n >= 1, "sphere is only defined for n >= 1")
    import MinimalSphereGenerator.*
    val degenerateVertex = SSetElement[MinimalSphereGenerator](((n - 2) to 0 by -1).toList, Vertex)
    def facesOf(g: MinimalSphereGenerator): IndexedSeq[SSetElement[MinimalSphereGenerator]] = g match
      case Vertex => IndexedSeq.empty
      case Top    =>
        if n == 1 then IndexedSeq.fill(2)(SSetElement(Nil, Vertex)) else IndexedSeq.fill(n + 1)(degenerateVertex)
    new FiniteSimplicialSet(
      IndexedSeq(Set(Vertex)) ++ IndexedSeq.fill(n - 1)(Set.empty[MinimalSphereGenerator]) :+ Set(Top),
      facesOf
    )

  /** A simplicial model of the Hopf map `S^3 -> S^2`: Sage's S^3 (one vertex, four edges, nine triangles, six
    * 3-simplices) and its map to the minimal 2-sphere, which sends the 3-simplices to degeneracies of the 2-simplex.
    * Transcribed from Sage's `simplicial_sets.HopfMap()`; the images of the lower-dimensional cells are not listed
    * there and are derived from the faces of the 3-simplices (and checked consistent). The check that it really is the
    * Hopf map: its mapping cone has the cohomology RING of `CP^2` (`x ∪ x ≠ 0`, Hopf invariant ±1).
    */
  def hopfMap: SSetMap[HopfSphereGenerator, MinimalSphereGenerator] =
    import HopfSphereGenerator.*
    type G = HopfSphereGenerator
    def bare(g: G) = SSetElement[G](Nil, g)
    def deg(g: G, word: Int*) = SSetElement[G](word.toList, g)
    val w1 = deg(W, 0)
    val w2 = deg(W, 1, 0)
    val table: Map[G, IndexedSeq[SSetElement[G]]] = Map(
      W -> IndexedSeq.empty,
      B11 -> IndexedSeq(bare(W), bare(W)),
      B22 -> IndexedSeq(bare(W), bare(W)),
      B23 -> IndexedSeq(bare(W), bare(W)),
      B44 -> IndexedSeq(bare(W), bare(W)),
      Beta1 -> IndexedSeq(w1, bare(B11), w1),
      Beta2 -> IndexedSeq(w1, bare(B22), bare(B23)),
      Beta3 -> IndexedSeq(w1, bare(B23), w1),
      Beta4 -> IndexedSeq(w1, bare(B44), w1),
      A12 -> IndexedSeq(bare(B11), bare(B23), w1),
      A23 -> IndexedSeq(bare(B11), bare(B22), w1),
      A34 -> IndexedSeq(bare(B11), bare(B22), bare(B44)),
      A45 -> IndexedSeq(w1, bare(B23), bare(B44)),
      A56 -> IndexedSeq(w1, bare(B23), w1),
      Alpha1 -> IndexedSeq(bare(Beta1), bare(Beta3), bare(A12), w2),
      Alpha2 -> IndexedSeq(deg(B11, 1), bare(Beta2), bare(A23), bare(A12)),
      Alpha3 -> IndexedSeq(deg(B11, 0), bare(A34), bare(A23), bare(Beta4)),
      Alpha4 -> IndexedSeq(bare(Beta1), bare(Beta2), bare(A34), bare(A45)),
      Alpha5 -> IndexedSeq(w2, bare(A45), bare(A56), bare(Beta4)),
      Alpha6 -> IndexedSeq(w2, bare(Beta3), bare(A56), w2)
    )
    val byDim = IndexedSeq(
      Set[G](W),
      Set[G](B11, B22, B23, B44),
      Set[G](Beta1, Beta2, Beta3, Beta4, A12, A23, A34, A45, A56),
      Set[G](Alpha1, Alpha2, Alpha3, Alpha4, Alpha5, Alpha6)
    )
    val source = new FiniteSimplicialSet(byDim, table)
    val target = sphere(2)
    import MinimalSphereGenerator.{Top, Vertex as V}
    def tgt(word: Int*)(g: MinimalSphereGenerator) = SSetElement[MinimalSphereGenerator](word.toList, g)
    val top: Map[G, SSetElement[MinimalSphereGenerator]] = Map(
      Alpha1 -> tgt(0)(Top),
      Alpha2 -> tgt(1)(Top),
      Alpha3 -> tgt(2)(Top),
      Alpha4 -> tgt(0)(Top),
      Alpha5 -> tgt(2)(Top),
      Alpha6 -> tgt(1)(Top)
    )
    // Triangles: the image of a bare face of a 3-simplex is the matching face of that 3-simplex's image.
    val triangleImages = scala.collection.mutable.Map.empty[G, SSetElement[MinimalSphereGenerator]]
    for (a, image) <- top; (face, k) <- table(a).zipWithIndex if face.word.isEmpty do
      val derived = target.face(k, image)
      require(
        triangleImages.getOrElseUpdate(face.generator, derived) == derived,
        s"inconsistent image for ${face.generator}"
      )
    def image(g: G): SSetElement[MinimalSphereGenerator] =
      if byDim(0).contains(g) then SSetElement(Nil, V)
      else if byDim(1).contains(g) then tgt(0)(V)
      else if byDim(2).contains(g) then triangleImages(g)
      else top(g)
    SSetMap(source, target, image)

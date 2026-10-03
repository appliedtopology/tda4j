package org.appliedtopology.tda4j

/** A finite presentation `<generators | relations>`; a relation is a word, each letter `(generator index, +1 or -1)`.
  */
final case class GroupPresentation[G](generators: IndexedSeq[G], relations: IndexedSeq[List[(Int, Int)]]):

  /** The exponent-sum matrix of the relations: one row per relation, one column per generator. */
  def exponentSums: IndexedSeq[IndexedSeq[Int]] =
    relations.map { word =>
      val row = Array.fill(generators.length)(0)
      for (i, e) <- word do row(i) += e
      row.toIndexedSeq
    }

  /** `dim_F` of the abelianization tensored with `F_p`: `#generators - rank_p(exponent sums)`. By Hurewicz this equals
    * `dim H_1(X; F_p)` for the simplicial set the presentation came from.
    */
  def abelianRank(prime: Int): Int =
    val field = new FiniteField(prime)
    import field.given
    val rows: Seq[Seq[field.Fp]] = exponentSums.map(_.map(field.Fp(_)))
    generators.length - LinearAlgebra.rank(rows)

  /** Evaluate every relation in any structure with a product and an inverse, given the value of each generator. */
  def relationsHold[E](value: Int => E, identity: E, multiply: (E, E) => E, inverse: E => E): Boolean =
    relations.forall { word =>
      word.foldLeft(identity) { case (acc, (i, e)) =>
        multiply(acc, if e > 0 then value(i) else inverse(value(i)))
      } == identity
    }

/** The fundamental group of a connected finite simplicial set, as a presentation read off a one-vertex model: `reduce`
  * collapses a spanning tree of the 1-skeleton to the base vertex, then every remaining non-degenerate edge is a
  * generator and every 2-simplex `σ` gives the relation `d_2 σ · d_0 σ = d_1 σ` (a degenerate edge is the identity).
  */
object FundamentalGroup:

  /** The quotient of a connected simplicial set by a spanning tree: one vertex, no tree edges. Tree edges become the
    * degenerate edge `s_0(root)`, so this is exactly `FiniteSimplicialSet.quotient` with a one-step map.
    */
  def reduce[G](x: FiniteSimplicialSet[G]): FiniteSimplicialSet[G] =
    require(SimplicialSets.isConnected(x), "reduce: the simplicial set must be connected")
    val vertices = x.generatorsAt(0).toVector.sorted(using x.ord)
    val root = vertices.head
    val incident = scala.collection.mutable.Map.empty[G, List[(G, G)]].withDefaultValue(Nil)
    for e <- x.generatorsAt(1) do
      val ends = x.faces(e).map(_.generator)
      incident(ends(0)) = (e, ends(1)) :: incident(ends(0))
      incident(ends(1)) = (e, ends(0)) :: incident(ends(1))
    val seen = scala.collection.mutable.Set[G](root)
    val treeEdges = scala.collection.mutable.Set.empty[G]
    val queue = scala.collection.mutable.Queue[G](root)
    while queue.nonEmpty do
      val v = queue.dequeue()
      for (e, w) <- incident(v).sortBy(_._1)(using x.ord) if !seen(w) do
        seen += w
        treeEdges += e
        queue.enqueue(w)
    def collapse(g: G): SSetElement[G] =
      if x.dimOf(g) == 0 then SSetElement(Nil, root)
      else if treeEdges(g) then SSetElement(List(0), root)
      else SSetElement(Nil, g)
    FiniteSimplicialSet.quotient(x, collapse)(using x.ord)

  /** The presentation of `π_1` (of a connected simplicial set) from the one-vertex model. */
  def presentation[G](x: FiniteSimplicialSet[G]): GroupPresentation[G] =
    val reduced = reduce(x)
    val generators = reduced.generatorsAt(1).toVector.sorted(using reduced.ord)
    val index = generators.zipWithIndex.toMap
    def letter(e: SSetElement[G], exponent: Int): List[(Int, Int)] =
      if e.word.nonEmpty then Nil else List((index(e.generator), exponent))
    val relations = reduced.generatorsAt(2).toVector.sorted(using reduced.ord).map { triangle =>
      val f = reduced.faces(triangle) // d_0, d_1, d_2
      letter(f(2), 1) ++ letter(f(0), 1) ++ letter(f(1), -1)
    }
    GroupPresentation(generators, relations)

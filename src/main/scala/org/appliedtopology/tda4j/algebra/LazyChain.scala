package org.appliedtopology.tda4j

/** A chain computed when first read: `compute` runs on the first access of its terms (once, whichever thread gets there
  * first), and the chain it returns is the one read from then on. For representatives that cost something to build and
  * are rarely all looked at. `force()` computes it now.
  */
// Restricted: a public top-level `LazyChain` would join every user's `import org.appliedtopology.tda4j.*`; it is only
// ever seen as a `Chain`.
private[tda4j] final class LazyChain[CellT, CoefficientT](
  private var compute: (() => Chain[CellT, CoefficientT]) | Null
)(order: Ordering[CellT], field: CoefficientT is Field)
    extends Chain[CellT, CoefficientT](using order, field):

  // Drops `compute` (and what it holds) once the chain exists.
  private lazy val chain: Chain[CellT, CoefficientT] =
    val made = compute.nn()
    compute = null
    made

  def force(): Unit = chain

  override def entryIterator: Iterator[(CellT, CoefficientT)] = chain.entryIterator
  override def collapseHead(): Unit = chain.collapseHead()
  override def collapseAll()(using fr: CoefficientT is Field): Unit = chain.collapseAll()
  override def terms: Seq[(CellT, CoefficientT)] = chain.terms
  override def rawEntries: Seq[(CellT, CoefficientT)] = chain.rawEntries
  override def isZero(): Boolean = chain.isZero()
  override def leadingTerm: (Option[CellT], CoefficientT) = chain.leadingTerm

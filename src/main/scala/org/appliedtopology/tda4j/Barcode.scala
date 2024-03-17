package org.appliedtopology.tda4j

/** The semantics of the `PersistenceBar` trait declares the bar to be unbounded
  * if the corresponding endpoint is `None`. That way, FiltrationT` does not
  * need an explicit infinity value.`
  * @tparam FiltrationT
  */
case class PersistenceBar[FiltrationT: Ordering, AnnotationT](
  val dim: Int,
  val birth: Option[FiltrationT] = None,
  val death: Option[FiltrationT] = None,
  val annotation: Option[AnnotationT] = None
) {
  override def toString: String =
    s"""$dim : (${birth.map(_.toString).getOrElse("-∞")} , ${death
      .map(_.toString)
      .getOrElse("∞")})${if annotation.isDefined then " : "}${annotation.map(_.toString).getOrElse("")}"""
}
object PersistenceBar {
  def apply(dim: Int) = new PersistenceBar(dim)
  def apply[FiltrationT: Ordering](dim: Int, birth: FiltrationT) = new PersistenceBar(dim, Some(birth))
  def apply[FiltrationT: Ordering](dim: Int, birth: FiltrationT, death: FiltrationT) =
    new PersistenceBar(dim, Some(birth), Some(death))
}
type Barcode[FiltrationT] = List[PersistenceBar[FiltrationT, Nothing]]

type BarcodeGenerators[FiltrationT, CellT <: Cell[CellT], CoefficientT] =
  List[PersistenceBar[FiltrationT, Chain[CellT, CoefficientT]]]

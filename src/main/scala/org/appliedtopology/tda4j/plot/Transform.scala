package org.appliedtopology.tda4j.plot

/** How data values become positions along an axis (or steps along a color ramp). The plots hold data in its own units;
  * a transform is applied only when drawing, so the same figure can be shown on a linear or a log axis.
  *
  * A value with no place under the transform (zero or a negative number on a log axis) comes out as `NaN`: the renderer
  * leaves such marks out and says how many it left out.
  */
enum Transform:
  /** The data as it is. */
  case Identity

  /** Base-10 logarithm: equal steps are equal ratios. Needs positive values, so an `H0` bar born at `0` has no place.
    */
  case Log10

  /** Square root: spreads out small values and keeps zero. Needs non-negative values. */
  case Sqrt

  /** Logarithmic away from zero, linear within `linear` of it: `sign(v) * log10(1 + |v| / linear)`. Keeps zero and
    * negative values on a log-like axis. `linear` is the size of the linear stretch around zero, in data units.
    */
  case SymLog(linear: Double)

  /** The position of `v`, or `NaN` when `v` has none. */
  def apply(v: Double): Double = this match
    case Identity  => v
    case Log10     => if v > 0 then math.log10(v) else Double.NaN
    case Sqrt      => if v >= 0 then math.sqrt(v) else Double.NaN
    case SymLog(c) => math.signum(v) * math.log10(1 + math.abs(v) / c)

  /** The value at position `u`: `inverse(apply(v)) == v` wherever `apply(v)` is a number. */
  def inverse(u: Double): Double = this match
    case Identity  => u
    case Log10     => math.pow(10, u)
    case Sqrt      => math.max(u, 0.0) * math.max(u, 0.0)
    case SymLog(c) => math.signum(u) * c * (math.pow(10, math.abs(u)) - 1)

  /** The lowest position the transform can give (nothing is below zero on a square-root axis): an axis that widens its
    * range to keep an aspect ratio stops a margin below it.
    */
  def floor: Double = this match
    case Sqrt => 0.0
    case _    => Double.NegativeInfinity

  /** A word for the axis note: "log", "square-root". */
  def label: String = this match
    case Identity  => "linear"
    case Log10     => "log"
    case Sqrt      => "square-root"
    case SymLog(_) => "symmetric log"

  /** About `target` round tick values inside `[lo, hi]` (data units): 1, 2, 5 times a power of ten for a linear or
    * square-root axis, powers of ten (with 2 and 5 between when there are few decades) for a log axis, zero and signed
    * powers of ten beyond the linear stretch for a symmetric log axis.
    */
  def ticks(lo: Double, hi: Double, target: Int = 5): IndexedSeq[Double] = this match
    case Identity | Sqrt => Svg.niceTicks(lo, hi, target)
    case Log10           =>
      val positive = lo > 0 && hi > lo && hi.isFinite
      if !positive then IndexedSeq.empty
      else
        val kLo = math.floor(math.log10(lo)).toInt
        val kHi = math.ceil(math.log10(hi)).toInt
        val decades = kHi - kLo
        val stride = math.max(1, math.ceil(decades / 6.0).toInt)
        val multiples = if decades >= 4 then Seq(1) else Seq(1, 2, 5)
        val found = for
          k <- kLo to kHi by stride
          m <- multiples
          t = m * math.pow(10, k)
          if t >= lo * (1 - 1e-9) && t <= hi * (1 + 1e-9)
        yield t
        if found.size >= 2 then found.toIndexedSeq else Svg.niceTicks(lo, hi, target)
    case SymLog(c) =>
      val reach = math.max(math.abs(lo), math.abs(hi))
      if reach <= c then Svg.niceTicks(lo, hi, target)
      else
        val kMax = math.ceil(math.log10(reach / c)).toInt
        val stride = math.max(1, math.ceil((kMax + 1) / 3.0).toInt)
        val magnitudes = (0 to kMax by stride).map(k => c * math.pow(10, k))
        val all = (magnitudes.map(-_).reverse :+ 0.0) ++ magnitudes
        all.filter(t => t >= lo && t <= hi).toIndexedSeq

  /** The label of a tick at `v`: plain numbers, except on a log axis where `1e-4` and `2e6` read better than a long
    * decimal or `1.00e-04`.
    */
  def tickLabel(v: Double): String = this match
    case Log10 if v > 0 && (v < 1e-3 || v >= 1e5) =>
      val e = math.floor(math.log10(v) + 1e-9).toInt
      val m = v / math.pow(10, e)
      if math.abs(m - math.rint(m)) < 1e-9 then s"${math.rint(m).toInt}e$e" else Svg.number(v)
    case _ => Svg.number(v)

object Transform:
  /** The transform called `name`, in any case: `linear`, `log`, `sqrt` or `symlog` (a symmetric log axis, linear within
    * 1 of zero; `Transform.SymLog(c)` sets another stretch). For callers that have a word, not a value: the command
    * line and MATLAB.
    */
  def named(name: String): Transform = name.trim.toLowerCase(java.util.Locale.ROOT) match
    case "linear" | "identity" => Identity
    case "log" | "log10"       => Log10
    case "sqrt"                => Sqrt
    case "symlog"              => SymLog(1.0)
    case other                 =>
      throw IllegalArgumentException(s"unknown scale '$other'; expected one of linear, log, sqrt, symlog")

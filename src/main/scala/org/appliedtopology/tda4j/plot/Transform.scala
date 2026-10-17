package org.appliedtopology.tda4j.plot

import RootText.lowerRoot

/** How data values become positions along an axis (or steps along a color ramp). The plots hold data in its own units;
  * a transform is applied only when drawing, so the same figure can be shown on a linear or a log axis.
  *
  * `apply` gives `NaN` for a value with no position of its own: zero or a negative number under `Log10`. The renderer
  * does not leave such a value out: what is at 0 on a log axis sits on a `-∞` line below the lowest number, and a log
  * axis with a negative value is drawn as a symmetric log ([[Transform.SymLog]]), which it says under the plot. Only
  * what is not a number at all is left out, and counted.
  */
enum Transform:
  /** The data as it is. */
  case Identity

  /** Base-10 logarithm: equal steps are equal ratios. On an axis, what is at 0 (an `H0` bar's birth) sits on a `-∞`
    * line below the lowest number, and an axis with a negative value is drawn as a symmetric log.
    */
  case Log10

  /** Square root of the magnitude, with the sign kept (`sign(v) * sqrt(|v|)`): spreads out small values, keeps zero,
    * and shows a negative value mirrored.
    */
  case Sqrt

  /** Logarithmic away from zero, linear within `linear` of it: `sign(v) * log10(1 + |v| / linear)`. Keeps zero and
    * negative values on a log-like axis. `linear` is the size of the linear stretch around zero, in data units.
    */
  case SymLog(linear: Double)

  /** A transform of your own: `forward` gives the position of a value (`NaN` for a value with none), `backward` its
    * inverse; ticks are round numbers of the data, as on a square-root axis. Two axes are on one scale when they share
    * the same functions (so a `val` keeps one): `Transform.Custom("cube root", math.cbrt, v => v * v * v)`.
    */
  case Custom(name: String, forward: Double => Double, backward: Double => Double)

  /** The position of `v`, or `NaN` when `v` has none. */
  def apply(v: Double): Double = this match
    case Identity              => v
    case Log10                 => if v > 0 then math.log10(v) else Double.NaN
    case Sqrt                  => math.signum(v) * math.sqrt(math.abs(v))
    case SymLog(c)             => math.signum(v) * math.log10(1 + math.abs(v) / c)
    case Custom(_, forward, _) => forward(v)

  /** The value at position `u`: `inverse(apply(v)) == v` wherever `apply(v)` is a number. */
  def inverse(u: Double): Double = this match
    case Identity               => u
    case Log10                  => math.pow(10, u)
    case Sqrt                   => math.signum(u) * u * u
    case SymLog(c)              => math.signum(u) * c * (math.pow(10, math.abs(u)) - 1)
    case Custom(_, _, backward) => backward(u)

  /** The lowest position for data that are not negative (zero on a square-root axis): an axis that widens its range to
    * keep an aspect ratio stops a margin below it, unless it has a negative value.
    */
  def floor: Double = this match
    case Sqrt => 0.0
    case _    => Double.NegativeInfinity

  /** A word for the axis note: "log", "square-root". */
  def label: String = this match
    case Identity           => "linear"
    case Log10              => "log"
    case Sqrt               => "square-root"
    case SymLog(_)          => "symmetric log"
    case Custom(name, _, _) => name

  /** About `target` round tick values inside `[lo, hi]` (data units): 1, 2, 5 times a power of ten for a linear or
    * square-root axis, powers of ten (with 2 and 5 between when there are few decades) for a log axis, and for a
    * symmetric log axis zero and those on both sides (round numbers throughout when the range is within a decade of the
    * linear stretch).
    */
  def ticks(lo: Double, hi: Double, target: Int = 5): IndexedSeq[Double] = this match
    case Identity | Sqrt | Custom(_, _, _) => Svg.niceTicks(lo, hi, target)
    case Log10                             =>
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
      // within a decade of the linear stretch the axis is nearly linear: round numbers; beyond it, round powers of ten
      // away from zero on both sides
      if reach <= 10 * c then Svg.niceTicks(lo, hi, target)
      else
        val magnitudes = Log10.ticks(c, reach, target)
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
  def named(name: String): Transform = name.trim.lowerRoot match
    case "linear" | "identity" => Identity
    case "log" | "log10"       => Log10
    case "sqrt"                => Sqrt
    case "symlog"              => SymLog(1.0)
    case other                 =>
      throw IllegalArgumentException(s"unknown scale '$other'; expected one of linear, log, sqrt, symlog")

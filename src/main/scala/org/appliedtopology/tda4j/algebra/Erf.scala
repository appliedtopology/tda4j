package org.appliedtopology.tda4j

/** The error function `erf(x) = 2/sqrt(pi) * integral_0^x exp(-t^2) dt` and its complement `erfc(x) = 1 - erf(x)`.
  *
  * Computed by W. J. Cody's rational Chebyshev approximations ("Rational Chebyshev approximations for the error
  * function", Mathematics of Computation 23, 1969): one in `x` on `|x| <= 0.46875`, one in `exp(-x^2)` times a rational
  * function of `x` up to 4, and one in `1/x^2` beyond. Measured against the C library's `erf` on random arguments in
  * `[-6.5, 6.5]`, `erf` is within `3.4e-16` absolute (a few units in the last place near `0.5`, where the value is half
  * of what its error scales with), `erfc` within a relative `1e-15` out to its underflow near 26.5. `erf` is odd,
  * exactly, and is exactly `+-1` from `|x| = 6`.
  */
object Erf:

  // Cody's coefficients: A, B for |x| <= 0.46875; C, D for 0.46875 < |x| <= 4; P, Q for |x| > 4.
  private val A = Array(
    3.16112374387056560e00, 1.13864154151050156e02, 3.77485237685302021e02, 3.20937758913846947e03,
    1.85777706184603153e-1
  )
  private val B = Array(2.36012909523441209e01, 2.44024637934444173e02, 1.28261652607737228e03, 2.84423683343917062e03)
  private val C = Array(
    5.64188496988670089e-1, 8.88314979438837594e00, 6.61191906371416295e01, 2.98635138197400131e02,
    8.81952221241769090e02, 1.71204761263407058e03, 2.05107837782607147e03, 1.23033935479799725e03,
    2.15311535474403846e-8
  )
  private val D = Array(
    1.57449261107098347e01, 1.17693950891312499e02, 5.37181101862009858e02, 1.62138957456669019e03,
    3.29079923573345963e03, 4.36261909014324716e03, 3.43936767414372164e03, 1.23033935480374942e03
  )
  private val P = Array(
    3.05326634961232344e-1, 3.60344899949804439e-1, 1.25781726111229246e-1, 1.60837851487422766e-2,
    6.58749161529837803e-4, 1.63153871373020978e-2
  )
  private val Q = Array(
    2.56852019228982242e00, 1.87295284992346725e00, 5.27905102951428412e-1, 6.05183413124413191e-2,
    2.33520497626869185e-3
  )

  private val Threshold = 0.46875
  // 1/sqrt(pi)
  private val InverseSqrtPi = 5.6418958354775628695e-1
  // From here 1 - erfc(y) is 1 in double precision (erfc(6) = 2.2e-17 is below half the spacing below 1).
  private val ErfIsOne = 6.0
  // Cody's XBIG: erfc is below the smallest normal double from here, and is returned as 0.
  private val ErfcIsZero = 26.543
  private val TinyArgument = 1.11e-16

  /** The error function. `erf(NaN)` is NaN, `erf(+-Infinity)` is `+-1`. */
  def erf(x: Double): Double =
    val y = math.abs(x)
    if y.isNaN then Double.NaN
    else if y <= Threshold then erfSmall(x, y)
    else if y >= ErfIsOne then if x < 0.0 then -1.0 else 1.0
    else
      val r = 1.0 - erfcPositive(y)
      if x < 0.0 then -r else r

  /** The complementary error function `1 - erf(x)`, accurate in relative terms in the tail (where `1 - erf(x)` would
    * lose every digit). `erfc(NaN)` is NaN, `erfc(Infinity)` is 0, `erfc(-Infinity)` is 2.
    */
  def erfc(x: Double): Double =
    val y = math.abs(x)
    if y.isNaN then Double.NaN
    else if y <= Threshold then 1.0 - erfSmall(x, y)
    else
      val r = if y >= ErfcIsZero then 0.0 else erfcPositive(y)
      if x < 0.0 then 2.0 - r else r

  /** erf on `|x| <= 0.46875`: `x R(x^2)` for a rational function `R`. */
  private def erfSmall(x: Double, y: Double): Double =
    val ysq = if y > TinyArgument then y * y else 0.0
    var numerator = A(4) * ysq
    var denominator = ysq
    var i = 0
    while i < 3 do
      numerator = (numerator + A(i)) * ysq
      denominator = (denominator + B(i)) * ysq
      i += 1
    x * (numerator + A(3)) / (denominator + B(3))

  /** erfc on `y > 0.46875`: `exp(-y^2) R(y)`, with `exp(-y^2)` split as `exp(-s^2) exp(-(y-s)(y+s))` for `s = y`
    * truncated to a multiple of 1/16, which keeps the exponent's rounding error from growing with `y^2`.
    */
  private def erfcPositive(y: Double): Double =
    val r =
      if y <= 4.0 then
        var numerator = C(8) * y
        var denominator = y
        var i = 0
        while i < 7 do
          numerator = (numerator + C(i)) * y
          denominator = (denominator + D(i)) * y
          i += 1
        (numerator + C(7)) / (denominator + D(7))
      else
        val ysq = 1.0 / (y * y)
        var numerator = P(5) * ysq
        var denominator = ysq
        var i = 0
        while i < 4 do
          numerator = (numerator + P(i)) * ysq
          denominator = (denominator + Q(i)) * ysq
          i += 1
        val tail = ysq * (numerator + P(4)) / (denominator + Q(4))
        (InverseSqrtPi - tail) / y
    val s = math.floor(y * 16.0) / 16.0
    val delta = (y - s) * (y + s)
    math.exp(-s * s) * math.exp(-delta) * r

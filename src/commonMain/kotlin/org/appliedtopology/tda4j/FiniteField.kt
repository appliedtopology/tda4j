package org.appliedtopology.tda4j

public open class Fp(public val x: Int) {
    public override fun toString(): String = "fp($x)"
}

public interface FpFactory {
    public fun fp(x: Number): Fp
}

public open class FiniteFieldContext(public val p: Int) : FieldContext<Fp>, FpFactory {
    // Make finite field elements
    final override fun fp(x: Number): Fp = Fp(x.toInt() % p)

    override fun number(value: Number): Fp = Fp(value.toInt() % p)

    override val zero: Fp = fp(0)
    override val one: Fp = fp(1)

    // Finite field arithmetic
    override fun Fp.unaryMinus(): Fp {
        return Fp(p - this.x)
    }

    override fun add(
        left: Fp,
        right: Fp,
    ): Fp = Fp((left.x + right.x) % p)

    override fun multiply(
        left: Fp,
        right: Fp,
    ): Fp = Fp((left.x.toLong() * right.x.toLong() % p.toLong()).toInt())

    protected fun inverse(a: Int): Int {
        var u = a % p
        var v = p
        var x1 = 1
        var x2 = 0
        var q: Int
        var r: Int
        var x: Int
        while (u != 1) {
            q = v.floorDiv(u)
            r = v - q * u
            x = x2 - q * x1
            v = u
            u = r
            x2 = x1
            x1 = x
        }
        return (x1 % p)
    }

    // Using a Map here takes up unnecessarily large amounts of space and runtime complexity
    // for what should be a simple Array lookup.
    // Performance note: This runs every time the context is instantiated. Reuse context when possible.
    protected val inverseTable: IntArray = IntArray(p) { if (it == 0) 0 else inverse(it) }

    override fun divide(
        left: Fp,
        right: Fp,
    ): Fp {
        require((right.x % p) != 0) { "Cannot divide by zero." }
        // This is ugly bc I don't think inverseTable[norm(b)]
        // should ever return a zero so long as we have the require statement above(?)
        return multiply(left, Fp(inverseTable[(norm(right))]))
    }

    // Normalize, convert, print
    public fun norm(a: Fp): Int =
        if (a.x % p < 0) {
            (a.x % p) + p
        } else {
            a.x % p
        }

    public fun Fp.normal(): Fp = Fp(norm(this))

    public fun canonical(a: Fp): Int = norm(a) - (p - 1) / 2

    public fun Fp.toInt(): Int = canonical(this@toInt)

    public override infix fun Fp.eq(other: Any?): Boolean =
        if (other !is Fp) {
            false
        } else {
            ((this@eq.x - other.x) % p) == 0
        }
}

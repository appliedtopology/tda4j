package org.appliedtopology.tda4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.equals.shouldBeEqual

class ApiSpec : StringSpec({
    "The Api can build a context with field arithmetic and simplices" {
        val out: Int =
            Api.tdaWith(DoubleContext, ChainContext<Int, Double>(DoubleContext)) {
                val simplex = s(1, 2, 3)
                val z = 1.0 * simplex - 1.0 * s(2, 3, 4)
                val dz = z.boundary
                dz.shouldBeEqual(
                    1.0 * s(1, 2) - 1.0 * s(1, 3) + 1.0 * s(2, 4) - 1.0 * s(3, 4),
                )
                12
            }
        out
    }
})

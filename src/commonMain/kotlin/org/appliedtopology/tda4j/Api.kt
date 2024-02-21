@file:JvmName("Api")

package org.appliedtopology.tda4j

import kotlin.jvm.JvmName

object Api {
    fun <VertexT : Comparable<VertexT>, CoefficientT, R> tdaWith(
        fieldContext: FieldContext<CoefficientT>,
        chainContext: ChainContext<VertexT, CoefficientT>,
        block: ChainContext<VertexT, CoefficientT>.() -> R,
    ): R {
        with(fieldContext) {
            return chainContext.block()
        }
    }
}

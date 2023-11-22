package org.appliedtopology.tda4j

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeSorted
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.assume
import io.kotest.property.checkAll

class SetSpec : StringSpec({
    "MutableSortedSet should not return duplicates" {
        val set = MutableSortedSet<Int>()

        set.add(0)
        set.add(0)
        set.shouldContainExactly(0)
    }

    "MutableSortedSet can be created" {
        val set = MutableSortedSet<Int>()
        set.isEmpty().shouldBeTrue()
        checkAll<UShort> {
            val set = MutableSortedSet<Int>(it.toInt())
            set.isEmpty().shouldBeTrue()
        }
    }

    "MutableSortedSet capabilities" {
        checkAll(Arb.list(Arb.int(-1024, 1024), 0..500)) {
            val set = MutableSortedSet<Int>(it.size)
            withClue("MutableSortedSet has everything we insert") {
                set.addAll(it)
                set.containsAll(it).shouldBeTrue()
            }
            withClue("MutableSortedSet.iterator returns in sorted order") {
                set.shouldBeSorted()
            }
            withClue("MutableSortedSet can have one element removed, and then it's gone") {
                assume(it.isNotEmpty())
                set.remove(it[0])
                set.contains(it[0]).shouldBeFalse()
            }
        }
    }

    "!Timing test for HashSet<Int>" {
        val sets: MutableSet<HashSet<Int>> = HashSet()
        checkAll<List<Int>>(1_000) {
            val set = HashSet<Int>()
            set.addAll(it)
            sets.add(set)
            sets.contains(set)
            sets.forEach { AbstractSimplex.compare(simplexOf(it), simplexOf(set)) }
        }
    }

    "!Timing test for MutableSortedSet<Int>" {
        val sets: MutableSet<MutableSortedSet<Int>> = HashSet()
        checkAll<List<Int>>(1_000) {
            val set = MutableSortedSet<Int>()
            set.addAll(it)
            sets.add(set)
            sets.contains(set)
            sets.forEach { AbstractSimplex.compare(simplexOf(it), simplexOf(set)) }
        }
    }
})

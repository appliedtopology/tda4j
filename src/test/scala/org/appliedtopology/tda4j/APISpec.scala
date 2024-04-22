package org.appliedtopology.tda4j

import org.specs2.mutable

class APISpec extends mutable.Specification {
  """Test case class for developing the non-Scala facing API functionality
    |and the non-expert API functionality""".stripMargin

  given ctx: TDAContext[Char, Double]()
  import ctx.{given, *}

  "we should be able to create and compute with chains" >> {
    1.0 ⊠ s(1, 2) - s(2, 3) should beEqualTo(
      Chain(Simplex(1, 2) -> 1.0, Simplex(2, 3) -> -1.0)
    )
  }
}

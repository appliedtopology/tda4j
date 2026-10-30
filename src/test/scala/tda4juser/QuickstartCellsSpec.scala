package tda4juser

import org.specs2.mutable.Specification

/** The cells snippet of the quickstart, from a user's side of the package boundary. */
class QuickstartCellsSpec extends Specification:
  import org.appliedtopology.tda4j.*

  "the quickstart's loop" should {
    "name an edge as its birth cell and a triangle as its death cell" in {
      val points = Array.tabulate(30)(i => Array(math.cos(i * 0.21), math.sin(i * 0.21)))
      val loop = Persistence(points).dim(1).longest.get
      (loop.birthCell.map(_.size) must beSome(2)) and (loop.deathCell.map(_.size) must beSome(3))
    }
  }

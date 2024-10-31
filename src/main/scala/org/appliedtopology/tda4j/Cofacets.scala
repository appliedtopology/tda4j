package org.appliedtopology.tda4j

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.{Failure, Success, Try}
import scala.util.chaining.scalaUtilChainingOps


/********* Optimized Vietoris-Rips cofacet generation for fast coboundary computation ********/

case class CofacetIterator[VertexT : Ordering](val simplex : Simplex[VertexT], sparseMetricSpace: SparseMetricSpace[VertexT]) extends Iterator[VertexT] {
  val vertices : Vector[VertexT] = Vector.from(simplex.toSeq.sorted)
  // data structure to hold and buffer outputs until it's time to send them out
  val outputQueue : mutable.ArrayDeque[VertexT] = mutable.ArrayDeque.empty
  var canProcess: Boolean = true

  override def hasNext: Boolean = outputQueue.nonEmpty || { fillQueue(); outputQueue.nonEmpty }
  override def next(): VertexT =
    if(hasNext)
      if(outputQueue.nonEmpty) outputQueue.removeHead()
      else {
        fillQueue()
        if (outputQueue.nonEmpty)
          outputQueue.removeHead()
        else
          throw new NoSuchElementException("Iterator exhausted")
      }
    else
      throw new NoSuchElementException("Iterator exhausted")

  // initiation means running through the neighborhoods for the vertices that will be emitted
  // at the current diameter.
  val filtrationValue = FiniteMetricSpace.MaximumDistanceFiltrationValue[VertexT](sparseMetricSpace)
  val alpha = filtrationValue(simplex)

  val vertexL1 : mutable.PriorityQueue[VertexT] = mutable.PriorityQueue.empty
  val vertexCache : mutable.Map[VertexT, Int] = mutable.Map.empty
  val pointers : mutable.Map[VertexT, Int] = mutable.Map.from(vertices.map(_ -> 0))

  // run through to find lexicographically last vertex
  // lex. last among all is also lex. last in each neighborhood it appears
  // ... noooo, that's not right ... each vertex could have a neighbor that is later but isn't in the intersection
  // still, we can gather up everything in an array, and walk it backwards filling out the vertexCache as we go
  // and returning the moment we find something in the intersection
  {
    var vi : Int = 0
    val vPointers : Array[Int] = Array.ofDim(vertices.size)
    var outerflag : Boolean = true
    while(outerflag && (vi < vertices.size)) {
      val neighbors = sparseMetricSpace.neighborhoods(vertices(vi))
      var wi : Int = 0
      var innerflag : Boolean = true
      while(innerflag && (wi < neighbors.size)) {
        if(!simplex.contains(neighbors(wi)._1)) {
          if (neighbors(wi)._2 <= alpha) {
            vertexL1.addOne(neighbors(wi)._1)
          } else {
            innerflag = false
            vPointers(vi) = wi
          }
        }
        wi += 1
      }
      if(innerflag) // we bailed out because we walked through all neighbors of vi
        vPointers(vi) = wi
      vi += 1
    }
  }

  // at this point, vPointers holds the positions of the pointers
  // and vertexL1 holds everything that should be inserted in the vertexCache
  // now let's start inserting things, and the moment we find a neighbor we pause and return to caller.
  //
  // lower down, process() should be adapted to do the rest of the init work when it first gets called

  var foundApparentFlag : Boolean = false
  var apparentVertex : Option[VertexT] = None
  while(!foundApparentFlag && vertexL1.nonEmpty) {
    val w = vertexL1.dequeue()
    val seen = vertexCache.getOrElse(w, 0)+1
    if(seen == vertices.length) {
      foundApparentFlag = true
      apparentVertex = Some(w)
    }
    vertexCache(w) = seen
  }
  var finishedInit : Boolean = false

  def finishInit(): Unit = {
    vertexL1.dequeueAll.foreach { (w) =>
      vertexCache(w) = vertexCache.getOrElse(w, 0) + 1
    }

    val immediateOutput = (for
      w <- vertexCache.keys
      if (vertexCache(w) == vertices.size)
    yield {
      vertexCache.remove(w)
      w
    })

    outputQueue.appendAll(immediateOutput)
  }

  def nearestNeighbor: (VertexT, (VertexT, Double)) = {
    vertices.map(v => v -> sparseMetricSpace.neighborhoods(v)(pointers(v))).minBy(_._2._2)
  }

  // Method that moves forward one step in the iteration
  def process() : Unit =
    if(!finishedInit) {
      finishInit()
      finishedInit = true
    } else {
      if (canProcess)
        Try {
          nearestNeighbor
        } match {
          case Success((v, (w, d))) => {
            if(!simplex.contains(w)) {
              vertexCache(w) = vertexCache.getOrElse(w, 0) + 1
              if (vertexCache(w) == vertices.size) {
                outputQueue.append(w)
                vertexCache.remove(w)
              }
            }
            pointers(v) += 1
          }
          case Failure(exc: IndexOutOfBoundsException) =>
            // we tried to access past the end of one of our neighborhoods. Time to clean up and finish things.
            vertexCache
              .keySet
              .map[(VertexT,Double)] { (w) =>
                w ->
                  vertices
                    .map((v) => sparseMetricSpace.distance(v, w))
                    .max
              }
              .toSeq
              .filter(_._2.isFinite)
              .sortBy(_._2)
              .map(_._1)
              .pipe(outputQueue.appendAll)
            canProcess = false
          case Failure(exc) => throw exc
        }
    }

  @tailrec
  final def fillQueue() : Unit = {
    if (canProcess) {
      process()
      if (outputQueue.nonEmpty) ()
      else fillQueue()
    }
    else
      ()
  }
}


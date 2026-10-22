package org.appliedtopology.tda4j

import java.nio.{ByteBuffer, ByteOrder}

/** Shared little-endian binary primitives for the binary file formats this package reads/writes (Ripser's packed
  * distance-matrix format, DIPHA's binary container format). Every such format is little-endian on disk regardless of
  * host byte order -- confirmed directly against both projects' own source (Ripser's `read<T>` reverses bytes only
  * `if (is_big_endian)`, i.e. the file itself is always little-endian; DIPHA's own README states "little-endian binary
  * format" outright) -- so `ByteBuffer.order(LITTLE_ENDIAN)` is used unconditionally here rather than detecting host
  * order, matching both.
  */
object BinaryIO:
  def readAllLE(path: String): ByteBuffer = wrapLE(FileAccess.readBytes(path))

  def wrapLE(bytes: Array[Byte]): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

  def newBufferLE(byteSize: Int): ByteBuffer =
    ByteBuffer.allocate(byteSize).order(ByteOrder.LITTLE_ENDIAN)

  def writeLE(path: String, buffer: ByteBuffer): Unit =
    FileAccess.writeBytes(path, buffer.array())

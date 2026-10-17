package org.appliedtopology.tda4j

import java.io.{BufferedWriter, OutputStreamWriter, PrintWriter, Writer}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.io.Source

/** The few things the file formats, the plots and the command line need from a file system, on the JVM through
  * `java.nio.file`. The Scala.js build has the same object (`src/main/scalajs`) on top of Node.js' `fs` module. Paths
  * are strings and text is UTF-8 on both.
  */
// Restricted: a public top-level `FileAccess` would join every user's `import org.appliedtopology.tda4j.*`, and its
// shape is what the two platforms have in common, not an API to build on.
private[tda4j] object FileAccess:
  def readText(path: String): String = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)

  /** The lines of the file at `path`, as `scala.io.Source`: the caller closes it. */
  def source(path: String): Source = Source.fromString(readText(path))

  def readBytes(path: String): Array[Byte] = Files.readAllBytes(Paths.get(path))

  /** The first `n` bytes of the file at `path`, or all of them when the file is shorter. */
  def readHead(path: String, n: Int): Array[Byte] =
    val in = Files.newInputStream(Paths.get(path))
    try in.readNBytes(n)
    finally in.close()

  def writeText(path: String, text: String): Unit =
    Files.write(Paths.get(path), text.getBytes(StandardCharsets.UTF_8))

  def writeBytes(path: String, bytes: Array[Byte]): Unit = Files.write(Paths.get(path), bytes)

  /** A writer to the file at `path`; the file is complete once the caller has closed the writer. A failed write throws
    * when the writer is flushed or closed.
    */
  def writer(path: String): Writer =
    new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(Paths.get(path)), StandardCharsets.UTF_8), 1 << 16)

  /** [[writer]] as a `PrintWriter`, which hides a failed write until `checkError`. */
  def printWriter(path: String): PrintWriter = new PrintWriter(writer(path))

  /** What reading a missing file throws: a `FileNotFoundException`. (Scala.js has none: there it is an `IOException`.)
    */
  def noSuchFile(path: String): java.io.IOException = new java.io.FileNotFoundException(s"$path: no such file")

  def isFile(path: String): Boolean = Files.isRegularFile(Paths.get(path))

  /** `path` made absolute against the working directory. */
  def absolute(path: String): String = Paths.get(path).toAbsolutePath.toString

  /** The `file:` URI of `path`, for opening a written page in a browser. */
  def fileUri(path: String): String = Paths.get(path).toAbsolutePath.toUri.toString

  /** Creates the directories above `path` that do not exist yet. */
  def createParentDirectories(path: String): Unit =
    Option(Paths.get(path).toAbsolutePath.getParent).foreach(Files.createDirectories(_))

  /** A new, empty file in the temporary directory. */
  def tempFile(prefix: String, suffix: String): String = Files.createTempFile(prefix, suffix).toString

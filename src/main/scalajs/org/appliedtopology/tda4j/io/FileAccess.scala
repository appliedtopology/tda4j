package org.appliedtopology.tda4j

import java.io.{PrintWriter, StringWriter, Writer}
import scala.io.Source
import scala.scalajs.js
import scala.scalajs.js.typedarray.{Int8Array, Uint8Array}

/** The few things the file formats, the plots and the command line need from a file system, here on Node.js' `fs`
  * module. The JVM build has the same object (`src/main/scalajvm`) on top of `java.nio.file`. Paths are strings and
  * text is UTF-8 on both.
  *
  * A browser has no file system: every method throws an `UnsupportedOperationException` that says so. Nothing is
  * imported at load time (`fs` is looked up when first used, through `process.getBuiltinModule` or `require`), so a
  * bundle that includes this object still loads in a browser.
  */
// Restricted: a public top-level `FileAccess` would join every user's `import org.appliedtopology.tda4j.*`, and its
// shape is what the two platforms have in common, not an API to build on.
private[tda4j] object FileAccess:
  private def nodeModule(name: String): js.Dynamic =
    if js.typeOf(js.Dynamic.global.process) != "undefined" &&
      js.typeOf(js.Dynamic.global.process.getBuiltinModule) == "function"
    then js.Dynamic.global.process.getBuiltinModule("node:" + name)
    else if js.typeOf(js.Dynamic.global.require) == "function" then js.Dynamic.global.require(name)
    else
      throw new UnsupportedOperationException(
        "Reading and writing files needs Node.js 20.16 or newer; a browser has no file system. " +
          "Pass the data in memory instead."
      )

  /** A failed `fs` call is a `js.JavaScriptException`; callers (and the JVM build) see `java.io.IOException`s. */
  private def io[A](path: String)(body: => A): A =
    try body
    catch
      case e @ js.JavaScriptException(error) =>
        val dynamic = error.asInstanceOf[js.Dynamic]
        val code = if js.typeOf(dynamic.code) == "string" then dynamic.code.toString else ""
        if code == "ENOENT" then throw new java.io.FileNotFoundException(s"$path: no such file")
        else throw new java.io.IOException(s"$path: ${e.getMessage}", e)

  private lazy val fs = nodeModule("fs")
  private lazy val nodePath = nodeModule("path")
  private lazy val os = nodeModule("os")

  def readText(path: String): String = io(path)(fs.readFileSync(path, "utf8").asInstanceOf[String])

  /** The lines of the file at `path`, as `scala.io.Source`: the caller closes it. */
  def source(path: String): Source = Source.fromString(readText(path))

  def readBytes(path: String): Array[Byte] = io(path)(toScala(fs.readFileSync(path)))

  /** The first `n` bytes of the file at `path`, or all of them when the file is shorter. */
  def readHead(path: String, n: Int): Array[Byte] = io(path) {
    val fd = fs.openSync(path, "r")
    try
      val buffer = js.Dynamic.global.Uint8Array.newInstance(n)
      val read = fs.readSync(fd, buffer, 0, n, 0).asInstanceOf[Int]
      toScala(buffer.subarray(0, read))
    finally fs.closeSync(fd)
  }

  def writeText(path: String, text: String): Unit = io(path)(fs.writeFileSync(path, text, "utf8"))

  def writeBytes(path: String, bytes: Array[Byte]): Unit =
    val view = new Int8Array(bytes.length)
    var i = 0
    while i < bytes.length do
      view(i) = bytes(i)
      i += 1
    io(path)(fs.writeFileSync(path, new Uint8Array(view.buffer)))

  /** A writer to the file at `path`; the file is complete once the caller has closed the writer. A failed write throws
    * when the writer is closed.
    */
  def writer(path: String): Writer = new FileWriter(path)

  /** [[writer]] as a `PrintWriter`, which hides a failed write until `checkError`. */
  def printWriter(path: String): PrintWriter = new PrintWriter(writer(path))

  def isFile(path: String): Boolean =
    val stat = fs.statSync(path, js.Dynamic.literal(throwIfNoEntry = false))
    !js.isUndefined(stat) && stat.isFile().asInstanceOf[Boolean]

  /** `path` made absolute against the working directory. */
  def absolute(path: String): String = nodePath.resolve(path).asInstanceOf[String]

  /** The `file:` URI of `path`, for opening a written page in a browser. */
  def fileUri(path: String): String =
    nodeModule("url").pathToFileURL(absolute(path)).href.asInstanceOf[String]

  /** Creates the directories above `path` that do not exist yet. */
  def createParentDirectories(path: String): Unit =
    io(path)(fs.mkdirSync(nodePath.dirname(absolute(path)), js.Dynamic.literal(recursive = true)))

  /** A new, empty file in the temporary directory. */
  def tempFile(prefix: String, suffix: String): String =
    val directory = fs.mkdtempSync(nodePath.join(os.tmpdir(), prefix)).asInstanceOf[String]
    val file = nodePath.join(directory, "file" + suffix).asInstanceOf[String]
    writeText(file, "")
    file

  private def toScala(buffer: js.Dynamic): Array[Byte] =
    val length = buffer.length.asInstanceOf[Int]
    val view =
      new Int8Array(buffer.buffer.asInstanceOf[js.typedarray.ArrayBuffer], buffer.byteOffset.asInstanceOf[Int], length)
    val bytes = new Array[Byte](length)
    var i = 0
    while i < length do
      bytes(i) = view(i)
      i += 1
    bytes

  /** Collects what is written in memory and writes the file when closed: Node's `fs` has no blocking stream that a
    * `java.io.Writer` could sit on.
    */
  private final class FileWriter(path: String) extends StringWriter:
    private var closed = false
    override def close(): Unit =
      if !closed then
        closed = true
        writeText(path, toString)
        super.close()

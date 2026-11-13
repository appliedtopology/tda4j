package org.appliedtopology.tda4j.demo

import scala.tools.asm.tree.{AbstractInsnNode, ClassNode, IincInsnNode, InsnNode, JumpInsnNode, LabelNode}
import scala.tools.asm.tree.{LdcInsnNode, MethodInsnNode, MethodNode, VarInsnNode}
import scala.tools.asm.{ClassReader, ClassWriter, Opcodes, Type}

import java.io.{File, FileOutputStream}
import java.util.jar.{JarEntry, JarOutputStream}
import java.util.zip.ZipFile
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** The classes of the demo jar that CheerpJ, the Java runtime the web page uses (`web/index.html`), cannot run as they
  * are, rewritten so that it can. Writes them as a small jar that goes AHEAD of the demo jar on the page's class path:
  *
  * {{{
  * java -cp tda4j-demo.jar org.appliedtopology.tda4j.demo.WebPatch tda4j-demo-web-patch.jar
  * }}}
  *
  * Both rewrites keep what a class does on any JVM, and `-Xverify:all` accepts the result.
  *
  *   - '''Local slots above 255.''' A method with more than 256 local slots reaches the high ones with the `wide` forms
  *     of the load and store instructions. CheerpJ 5.0 runs the forms for `int`s and references and stops the page at
  *     the ones for `float`, `long` and `double`. Every method of the jar that has one is rewritten: a value stored and
  *     loaded again by the very next instruction, from a slot used nowhere else, stays on the stack; then the slots are
  *     dealt out again, `long`s and `double`s first, `float`s next, so that only `int`s and references are left above
  *     255. In the Scala compiler that is `dotty.tools.dotc.core.Flags$` (its flag sets are `long`s; nothing compiles
  *     without it), in the library the plot renderer.
  *   - '''`dotty.tools.repl.StackTraceOps$`''' prints the stack trace of an exception the user's line threw, and fails
  *     on a frame with no file name. Every frame in CheerpJ is one. The file name becomes `Unknown Source` there, the
  *     text `StackTraceElement.toString` itself uses.
  *
  * The rewrites are found by shape, not by position, and each checks that it did its job: a jar whose classes no longer
  * fit stops this program with a message, and never yields a patch that silently changes nothing.
  */
object WebPatch:
  private val stackTraces = "dotty/tools/repl/StackTraceOps$"

  def main(args: Array[String]): Unit =
    val target = args.headOption.getOrElse(sys.error("usage: WebPatch <jar to write>"))
    val source = File(getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
    if !source.isFile then sys.error(s"run this from the demo jar (java -cp tda4j-demo.jar ...): $source is not a jar")
    val zip = ZipFile(source)
    val rewritten =
      try
        // One version of each class: the jar is flat (an assembly), and `META-INF/versions` copies are for other JVMs.
        val names = zip.entries.asScala.map(_.getName).filter(n => n.endsWith(".class") && !n.startsWith("META-INF/"))
        names.flatMap { name =>
          val bytes = zip.getInputStream(zip.getEntry(name)).readAllBytes()
          val className = name.stripSuffix(".class")
          if className == stackTraces then
            Some(
              name -> rewrite(
                bytes,
                node =>
                  lowerWideSlots(node); nameless(node)
              )
            )
          else if needsLowering(bytes) then Some(name -> rewrite(bytes, lowerWideSlots))
          else None
        }.toList
      finally zip.close()
    if !rewritten.exists(_._1 == s"$stackTraces.class") then sys.error(s"$stackTraces is not in $source")
    val jar = JarOutputStream(FileOutputStream(target))
    try
      for (name, bytes) <- rewritten.sortBy(_._1) do
        jar.putNextEntry(JarEntry(name))
        jar.write(bytes)
        jar.closeEntry()
    finally jar.close()
    println(s"wrote $target, ${rewritten.size} classes:")
    rewritten.map(_._1.stripSuffix(".class").replace('/', '.')).sorted.foreach(name => println(s"  $name"))

  // The kinds of local variable, by the instructions that read and write them.
  private enum Kind:
    case Wide, Single, Whole, Reference // long and double (two slots); float; int; anything else

  private def kindOf(opcode: Int): Kind = opcode match
    case Opcodes.LLOAD | Opcodes.LSTORE | Opcodes.DLOAD | Opcodes.DSTORE => Kind.Wide
    case Opcodes.FLOAD | Opcodes.FSTORE                                  => Kind.Single
    case Opcodes.ILOAD | Opcodes.ISTORE                                  => Kind.Whole
    case _                                                               => Kind.Reference

  private val loadFor: Map[Int, Int] = Map(
    Opcodes.ISTORE -> Opcodes.ILOAD,
    Opcodes.LSTORE -> Opcodes.LLOAD,
    Opcodes.FSTORE -> Opcodes.FLOAD,
    Opcodes.DSTORE -> Opcodes.DLOAD,
    Opcodes.ASTORE -> Opcodes.ALOAD
  )

  /** Whether CheerpJ would stop in `method`: a `float`, `long` or `double` in a slot above 255. */
  private def stopsTheRuntime(method: MethodNode): Boolean = method.instructions.iterator.asScala.exists {
    case v: VarInsnNode => v.`var` > 255 && (kindOf(v.getOpcode) == Kind.Wide || kindOf(v.getOpcode) == Kind.Single)
    case _              => false
  }

  private def needsLowering(bytes: Array[Byte]): Boolean =
    val node = ClassNode()
    ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
    node.methods.asScala.exists(stopsTheRuntime)

  private def rewrite(bytes: Array[Byte], change: ClassNode => Unit): Array[Byte] =
    val node = ClassNode()
    ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES)
    change(node)
    // The frames are computed again: the old ones list the old slots. Merging two types asks for their classes, which
    // are in the jar this program runs from.
    val writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES):
      override def getClassLoader: ClassLoader = WebPatch.getClass.getClassLoader
    node.accept(writer)
    writer.toByteArray

  private def nextInstruction(from: AbstractInsnNode): Option[AbstractInsnNode] =
    Iterator.iterate(from.getNext)(_.getNext).takeWhile(_ != null).find(_.getOpcode >= 0)

  /** Every method of `node` that would stop the runtime, with its `float`s, `long`s and `double`s in slots below 256.
    */
  private def lowerWideSlots(node: ClassNode): Unit =
    for method <- node.methods.asScala if stopsTheRuntime(method) do
      // 1. A temporary: stored, loaded at once, and its slot used for nothing else. It stays on the stack.
      val uses = method.instructions.iterator.asScala
        .collect {
          case v: VarInsnNode  => v.`var`
          case i: IincInsnNode => i.`var`
        }
        .toSeq
        .groupMapReduce(identity)(_ => 1)(_ + _)
      for instruction <- method.instructions.toArray do
        instruction match
          case store: VarInsnNode if loadFor.contains(store.getOpcode) && uses(store.`var`) == 2 =>
            nextInstruction(store) match
              case Some(load: VarInsnNode) if load.getOpcode == loadFor(store.getOpcode) && load.`var` == store.`var` =>
                method.instructions.remove(store)
                method.instructions.remove(load)
              case _ => ()
          case _ => ()
      // 2. The slots dealt out again. A variable is a slot together with the kind of value read from and written to it
      // (verified code never reads one kind where it wrote another, so a slot the compiler used for two kinds in
      // turn is two variables). The receiver and the parameters keep their slots: the caller put them there.
      val firstFree =
        (if (method.access & Opcodes.ACC_STATIC) != 0 then 0 else 1) + Type
          .getArgumentTypes(method.desc)
          .map(_.getSize)
          .sum
      val variables = mutable.LinkedHashSet.empty[(Int, Kind)]
      method.instructions.iterator.asScala.foreach {
        case v: VarInsnNode if v.`var` >= firstFree  => variables += ((v.`var`, kindOf(v.getOpcode)))
        case i: IincInsnNode if i.`var` >= firstFree => variables += ((i.`var`, Kind.Whole))
        case _                                       => ()
      }
      var free = firstFree
      val slotOf: Map[(Int, Kind), Int] = variables.toSeq
        .sortBy(_._2.ordinal) // stable: within a kind, the order of first use
        .map { variable =>
          val slot = free
          free += (if variable._2 == Kind.Wide then 2 else 1)
          variable -> slot
        }
        .toMap
      method.instructions.iterator.asScala.foreach {
        case v: VarInsnNode if v.`var` >= firstFree  => v.`var` = slotOf((v.`var`, kindOf(v.getOpcode)))
        case i: IincInsnNode if i.`var` >= firstFree => i.`var` = slotOf((i.`var`, Kind.Whole))
        case _                                       => ()
      }
      // Debug names of the old slots would point at the wrong ones.
      method.localVariables = null
      method.visibleLocalVariableAnnotations = null
      method.invisibleLocalVariableAnnotations = null
      if stopsTheRuntime(method) then
        sys.error(
          s"${node.name}.${method.name}: more floats, longs and doubles than fit below slot 256, even after the " +
            "rewrite. The page's Java runtime cannot run this method; split it in the source."
        )

  /** Every `StackTraceElement.getFileName()` in the class answers `Unknown Source` where it would answer `null`. */
  private def nameless(node: ClassNode): Unit =
    var changed = 0
    for method <- node.methods.asScala; instruction <- method.instructions.toArray do
      instruction match
        case call: MethodInsnNode if call.owner == "java/lang/StackTraceElement" && call.name == "getFileName" =>
          val named = LabelNode()
          val fallback = List(
            InsnNode(Opcodes.DUP),
            JumpInsnNode(Opcodes.IFNONNULL, named),
            InsnNode(Opcodes.POP),
            LdcInsnNode("Unknown Source"),
            named
          )
          fallback.foldLeft(call: AbstractInsnNode) { (after, added) =>
            method.instructions.insert(after, added)
            added
          }
          changed += 1
        case _ => ()
    if changed == 0 then
      sys.error(
        s"${node.name}: no call of StackTraceElement.getFileName to guard. This compiler prints stack traces another " +
          "way; check in the web page that an exception thrown by a line still prints its trace."
      )

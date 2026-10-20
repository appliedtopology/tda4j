---
layout: main
title: Installation
---

## Requirements

| to use tda4j from | you need |
|---|---|
| Scala | Java 21 or newer, Scala 3.9 |
| Java, MATLAB, the command line | Java 21 or newer (MATLAB: a release whose bundled or configured JVM is 21 or newer) |
| JavaScript, TypeScript | a browser, or Node.js 20.16 or newer |

Everything runs on the JVM or in JavaScript; there is nothing to compile and no native library to install.

The current version is on [Maven Central](https://central.sonatype.com/artifact/org.appliedtopology/tda4j_3) and in the
[releases](https://github.com/appliedtopology/tda4j/releases); below it is written `VERSION`. A version that ends in
`-SNAPSHOT` is a development build: [build it from source](#from-source).

## Scala

```scala
// sbt
libraryDependencies += "org.appliedtopology" %% "tda4j" % "VERSION"
// sbt, Scala.js project
libraryDependencies += "org.appliedtopology" %%% "tda4j" % "VERSION"
```

```scala
// Mill
mvn"org.appliedtopology::tda4j:VERSION"
// Scala CLI, at the top of the file
//> using scala 3.9.0
//> using dep org.appliedtopology::tda4j:VERSION
```

```
// Gradle (Kotlin DSL)
implementation("org.appliedtopology:tda4j_3:VERSION")
```

Every Scala file that uses the library starts with `import scala.language.experimental.modularity` (see the
[user guide](index.md)). To check the installation:

```scala 3
import scala.language.experimental.modularity
import org.appliedtopology.tda4j.*

val square = Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0))
Persistence(square, maxDimension = 1).bettiNumbers   // Vector(1, 0)
```

## Java and MATLAB

Download `tda4j-VERSION-assembly.jar` from the [releases](https://github.com/appliedtopology/tda4j/releases): one jar
with the library and its dependencies. Put it on the class path, or in MATLAB:

```matlab
javaaddpath('/path/to/tda4j-VERSION-assembly.jar');
import org.appliedtopology.tda4j.matlab.*;
TDA4j.computeFromPoints([0 0; 1 0; 0.5 0.8], {'maxDimension', '1'})
```

In a Maven build, depend on `org.appliedtopology:tda4j_3:VERSION`; Maven brings the Scala library along. The
[MATLAB and Java page](matlab.md) lists the methods.

## Command line

```
java -jar tda4j-VERSION-assembly.jar --help
java -jar tda4j-VERSION-assembly.jar --max-dimension 1 points.csv
```

See the [command-line page](cli.md).

## JavaScript and TypeScript

```
npm install @appliedtopology/tda4j
```

See the [JavaScript page](javascript.md).

## Try it without installing anything to your project

`tda4j-demo-VERSION.jar` from the releases is a Scala REPL with the library and its imports in place:
`java -jar tda4j-demo-VERSION.jar`.

## From source

```
git clone https://github.com/appliedtopology/tda4j
cd tda4j
sbt tda4j/publishLocal     # then depend on the -SNAPSHOT version printed at the end
sbt tda4j/assembly         # the fat jar, under target/out/jvm/scala-3.9.0/tda4j/
```

This needs Java 21 and [sbt](https://www.scala-sbt.org/) 2. [CONTRIBUTING.md](https://github.com/appliedtopology/tda4j/blob/scala/CONTRIBUTING.md)
has the rest.

If something does not work, see [troubleshooting](troubleshooting.md).

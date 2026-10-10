Contributing
============

## Requirements

You will need the following tools:

- [Git](https://git-scm.com/)
- Java 21 (any distribution, e.g. [Temurin](https://adoptium.net/))
- [sbt](https://www.scala-sbt.org/) 2

## Workflow

Create your own fork of the repository and work in a local branch based on `scala`. Binary compatible
fixes should be based on the corresponding branch version (e.g., `1.x`).

## Design principles

TDA4j is meant to be a platform for experiments and research as much as a toolbox, so two principles hold for every
contribution:

- **Representatives and coefficients.** Every persistent homology computation is generic over the coefficient `Field`
  and returns a representative chain with every bar.
- **Open by default.** Classes are public, concrete classes are `open`, and the machinery behind the convenient entry
  points (the streams behind `VietorisRips`, the engines behind `Persistence`, the pairings, union-finds and geometric
  predicates) can be called, combined and subclassed. Which class to reach for first is said in the documentation,
  not enforced by access modifiers. A restriction needs a reason, written as a comment where it is declared:
  - mutable state whose consistency the class relies on, or a constructor that trusts its arguments (offer a read-only
    view or a checked factory instead);
  - a library-wide guarantee (nothing public returns bars without representatives);
  - a generic helper name (`Level`, `LongIntMap`) that would sit in every user's `import org.appliedtopology.tda4j.*`
    and shadow their own definitions (nest it in a companion object before making it public);
  - the internals of the MATLAB and command-line facades, whose contract is their string options.

  `sealed` is for closed mathematical classifications (the four kinds of barcode endpoint); `final` is for value types
  (case classes) and for the chain storages the reductions match on. "It keeps the API small" or "users should not
  need this" are not reasons.

## Code Style

We use [scalafmt](https://scalameta.org/scalafmt/) to format the source code, and recommend you to setup
your editor to “format on save”, as documented [here](https://scalameta.org/scalafmt/docs/installation.html).

## Run Tests

~~~
sbt testFull
~~~

## Publish a Release

Push a Git tag:

~~~ bash
$ git tag v2.0.0
$ git push origin v2.0.0
~~~

After releasing a new major version, create a new Git branch (e.g., `2.x`) that will contain the binary
compatible evolutions of that version. In this branch, set the `mimaPreviousArtifacts` setting (in file
`build.sbt`) to the following value:

~~~ diff
-mimaPreviousArtifacts := Set.empty
+mimaPreviousArtifacts := previousStableVersion.value.map(organization.value %% name.value % _).toSet
~~~
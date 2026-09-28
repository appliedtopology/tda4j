[![test](https://github.com/appliedtopology/tda4j/actions/workflows/test.yml/badge.svg?branch=scala)](https://github.com/appliedtopology/tda4j/actions)
[![lint](https://github.com/appliedtopology/tda4j/actions/workflows/lint.yml/badge.svg?branch=scala)](https://github.com/appliedtopology/tda4j/actions)
[![docs](https://github.com/appliedtopology/tda4j/actions/workflows/docs.yml/badge.svg?branch=scala)](https://github.com/appliedtopology/tda4j/actions)


# TDA4j

## Persistent Homology and Topological Data Analysis Library 


The `TDA4j` library implements persistent homology and related techniques from computational and applied topology, in a library designed for ease of use, ease of access from Matlab and java-based systems, and ease of extensions for further research projects and approaches.

The library is based on previous libraries from the Computational Topology workgroup at Stanford University.

For persistent homology and its capabilities, we recommend the survey article [Topology and Data](http://www.ams.org/journals/bull/2009-46-02/S0273-0979-09-01249-X/S0273-0979-09-01249-X.pdf) by Gunnar Carlsson.

# Note on writing documentation

We can run TDA4j on [Scastie](https://scastie.scala-lang.org/). To set up a snippet that automatically loads the library on Scastie, use:

```
https://scastie.scala-lang.org/?inputs=[URL-ENCODED JSON]
```

where the `URL-ENCODED JSON` is something like this (replace the string pointed at by `"code"` to fit the example):

```json
{
  "_isWorksheetMode" : true,
  "code" : "import org.appliedtopology.tda4j.*\n∆(1,2,3)\n",
  "target" : {
    "scalaVersion" : "3.9.0",
    "tpe" : "Scala3"
  },
  "libraries" : [ ],
  "librariesFromList" : [ ],
  "sbtConfigExtra":"\nscalacOptions ++= Seq(\n  \"-deprecation\",\n  \"-encoding\", \"UTF-8\",\n  \"-feature\",\n  \"-unchecked\",\n  \"-source:future\", \n  \"-language:experimental.modularity\"\n)\nlibraryDependencies += \"org.appliedtopology\" %% \"tda4j\" % \"0.4.0\"\n",
  "sbtPluginsConfigExtra" : "",
  "isShowingInUserProfile" : true
}
```

As an example, here is a snippet that creates a single simplex:
[Scastie demo](https://scastie.scala-lang.org/?inputs=%7B%0A%20%20%22_isWorksheetMode%22%20%3A%20true%2C%0A%20%20%22code%22%20%3A%20%22import%20org.appliedtopology.tda4j.*%5Cn%E2%88%86(1%2C2%2C3)%5Cn%22%2C%0A%20%20%22target%22%20%3A%20%7B%0A%20%20%20%20%22scalaVersion%22%20%3A%20%223.9.0%22%2C%0A%20%20%20%20%22tpe%22%20%3A%20%22Scala3%22%0A%20%20%7D%2C%0A%20%20%22libraries%22%20%3A%20%5B%20%5D%2C%0A%20%20%22librariesFromList%22%20%3A%20%5B%20%5D%2C%0A%20%20%22sbtConfigExtra%22%3A%22%5CnscalacOptions%20%2B%2B%3D%20Seq(%5Cn%20%20%5C%22-deprecation%5C%22%2C%5Cn%20%20%5C%22-encoding%5C%22%2C%20%5C%22UTF-8%5C%22%2C%5Cn%20%20%5C%22-feature%5C%22%2C%5Cn%20%20%5C%22-unchecked%5C%22%2C%5Cn%20%20%5C%22-source%3Afuture%5C%22%2C%20%5Cn%20%20%5C%22-language%3Aexperimental.modularity%5C%22%5Cn)%5CnlibraryDependencies%20%2B%3D%20%5C%22org.appliedtopology%5C%22%20%25%25%20%5C%22tda4j%5C%22%20%25%20%5C%220.4.0%5C%22%5Cn%22%2C%0A%20%20%22sbtPluginsConfigExtra%22%20%3A%20%22%22%2C%0A%20%20%22isShowingInUserProfile%22%20%3A%20true%0A%7D%0A)

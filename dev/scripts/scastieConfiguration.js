scastieConfiguration = "
  scalacOptions ++= Seq(
    \"-deprecation\",
    \"-encoding\", \"UTF-8\",
    \"-feature\", \"-unchecked\",
    \"-source:future\", \"-language:experimental.modularity\",
    \"-language:implicitConversions\", \"-language:adhocExtensions\"
  )
  libraryDependencies += \"org.appliedtopology\" %% \"tda4j\" % \"0.4.0\"
"
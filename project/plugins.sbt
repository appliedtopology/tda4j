addSbtPlugin("org.scalameta"  % "sbt-scalafmt"    % "2.6.1")
// MiMa >= 1.2.1: older TASTy readers abort on Scala 3.9 modifier tags (`into class Chain` is tag 49 = INTO).
addSbtPlugin("com.typesafe"   % "sbt-mima-plugin" % "1.2.1")
addSbtPlugin("com.github.sbt" % "sbt-release"     % "1.5.0")
addSbtPlugin("com.github.sbt" % "sbt-pgp"         % "2.3.1")
addSbtPlugin("com.eed3si9n"   % "sbt-assembly"    % "2.3.1")

#!/usr/bin/env bash
# Puts the fat jar and the license where the wheel build finds them: python/src/tda4j/jars/ and python/LICENSE.md.
# Build the jar first (`sbt tda4j/assembly`); with no argument the newest one under target/ is taken.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
root="$(cd "$here/.." && pwd)"
jar="${1:-$(ls -t "$root"/target/out/jvm/scala-*/tda4j/tda4j-*-assembly.jar 2>/dev/null | head -1)}"
test -f "$jar" || { echo "no assembly jar: run 'sbt tda4j/assembly' or pass the jar's path" >&2; exit 1; }
rm -f "$here"/src/tda4j/jars/*.jar
mkdir -p "$here/src/tda4j/jars"
cp "$jar" "$here/src/tda4j/jars/"
cp "$root/LICENSE.md" "$here/LICENSE.md"
echo "bundled $(basename "$jar")"

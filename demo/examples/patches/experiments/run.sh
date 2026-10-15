#!/usr/bin/env bash
# usage: run.sh EXPERIMENT [extra java options]
# Runs lib.scala + EXPERIMENT.scala in the demo jar's REPL (one JVM per experiment), prints the plain output.
# JAR (required) is the demo jar, DATA (default ./data) the directory with the CSVs, HEAP (default 6g) the -Xmx.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
exp=$1; shift
JAR=${JAR:?set JAR to the demo jar, e.g. ../../../target/out/jvm/scala-3.9.0/tda4j-demo/tda4j-demo-0.6.1-SNAPSHOT.jar}
DATA=${DATA:-$here/data}
cat "$here/lib.scala" "$here/$exp.scala" > "$here/run-$exp.scala"
start=$(date +%s)
echo ":load $here/run-$exp.scala" | JAVA_TOOL_OPTIONS= java -Xmx"${HEAP:-6g}" -Dklein.dir="$DATA" \
  -Dtda4j.plot.browser=none "$@" -jar "$JAR" 2>&1 | sed -u 's/\x1b\[[0-9;]*m//g' | grep --line-buffered -v -E '^(val |  *(Array|\.\.\.))'
echo "[wall] $exp: $(( $(date +%s) - start )) s (JVM start to exit, -Xmx${HEAP:-6g})"

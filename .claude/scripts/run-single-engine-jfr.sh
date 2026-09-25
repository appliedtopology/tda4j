#!/usr/bin/env bash
# Runs SingleEngineProfileDriver directly (one engine, one JVM process, no sbt/timeout/daemon-thread
# machinery -- see that class's own doc comment) under JDK Flight Recorder, for a targeted profiling pass
# on one case. Built specifically to chase two anomalies found in a same-machine compute-server run against
# real ripser.cpp (see .claude/WORKLOG-packed-ripser-engine.md/-ripser-profiling.md for the established
# baseline): o3_1024's packed-vs-SortedSet advantage collapsing to ~1.7x (every other case: 15-45x), and
# fractal-r timing out for the packed engine while RipserCohomologyContext itself finishes (in ~89 minutes).
#
# Unlike RipserPaperBenchmarkSpec (sbt-hosted, shared JVM across every case, a per-cell timeout with NO
# cooperative cancellation), this gives each run its own clean process with no time budget at all -- let it
# run to completion, however long that takes, and get a profile of the WHOLE run, not just whatever a
# 240s/1800s window happened to catch.
#
# Usage:
#   .claude/scripts/run-single-engine-jfr.sh <engine> <format> <dataFile> <maxDim> [threshold] [heap]
#
#   engine:    sortedset | packed
#   format:    point-cloud | distance   (fractal-r's own file is a full distance matrix -> "distance")
#   dataFile:  path to the data file (same files run-ripser-paper-benchmark.sh already downloaded, under
#              its own WORKDIR/data -- e.g. $WORKDIR/data/fractal_9_5_2_distmat.txt)
#   maxDim:    top homological degree (2 for fractal-r, 3 for o3_1024 -- see RipserPaperBenchmarkSpec's own
#              `cases` Seq for the exact value used for every paper case)
#   threshold: "none" (default) or a literal Double (1.8 for o3_1024, matching the paper's own --threshold)
#   heap:      -Xmx value (default 16G -- bump this if the run OOMs; the compute-server run had up to 160G
#              available, so there's real headroom to raise this well past the default if needed)
#
# Examples (paths assume run-ripser-paper-benchmark.sh already downloaded the data to ./ripser-bench-scratch):
#   .claude/scripts/run-single-engine-jfr.sh packed distance \
#     ripser-bench-scratch/data/fractal_9_5_2_distmat.txt 2 none 32G
#   .claude/scripts/run-single-engine-jfr.sh packed point-cloud \
#     ripser-bench-scratch/data/o3_1024.txt 3 1.8 32G
#   .claude/scripts/run-single-engine-jfr.sh sortedset point-cloud \
#     ripser-bench-scratch/data/o3_1024.txt 3 1.8 32G
#
# Output: prints the driver's own timing/substitutionCount/totalSimplexCount line to stdout (tee'd to
# <dataFile-basename>-<engine>.log alongside the .jfr file), and writes a JFR recording to
# <dataFile-basename>-<engine>-<timestamp>.jfr in the current directory.
#
# What to send back for analysis: the printed driver output (small, paste directly) PLUS a `jfr print` text
# extract rather than the raw .jfr file (which can be large for a long run and isn't directly readable) --
# this script prints the exact `jfr print` commands to run once it's done, covering both CPU execution
# samples and allocation samples (the two views every profiling session in this arc has used), each capped
# to a stack depth of 30 (a shallow default previously conflated unrelated call sites that happened to share
# a class-name prefix -- see WORKLOG-ripser-profiling.md's "second follow-up session" for why 30, not the
# default, matters here).

set -euo pipefail

ENGINE="${1:?engine: sortedset or packed}"
FORMAT="${2:?format: point-cloud or distance}"
DATA_FILE="${3:?path to data file}"
MAX_DIM="${4:?maxDim}"
THRESHOLD="${5:-none}"
HEAP="${6:-16G}"

BASE="$(basename "$DATA_FILE")-$ENGINE"
TS="$(date +%Y%m%d-%H%M%S)"
JFR_FILE="${BASE}-${TS}.jfr"
LOG_FILE="${BASE}-${TS}.log"

echo "=== Building test classpath (first run only; sbt caches this) ==="
CP_FILE="$(mktemp)"
sbt -Dsbt.supershell=false -batch "export Test/fullClasspath" 2>/dev/null | tail -1 > "$CP_FILE"
CP="$(cat "$CP_FILE"):target/scala-3.9.0/test-classes:target/scala-3.9.0/classes"
rm -f "$CP_FILE"

echo "=== Running SingleEngineProfileDriver under JFR ==="
echo "engine=$ENGINE format=$FORMAT dataFile=$DATA_FILE maxDim=$MAX_DIM threshold=$THRESHOLD heap=$HEAP"
echo "JFR recording -> $JFR_FILE"
echo "stdout log    -> $LOG_FILE"
echo

java -Xmx"$HEAP" \
  -XX:+FlightRecorder \
  -XX:StartFlightRecording=settings=profile,filename="$JFR_FILE" \
  -cp "$CP" \
  org.appliedtopology.tda4j.homology.SingleEngineProfileDriver \
  "$ENGINE" "$FORMAT" "$DATA_FILE" "$MAX_DIM" "$THRESHOLD" 1 \
  2>&1 | tee "$LOG_FILE"

echo
echo "=== Done. To extract text summaries to send back for analysis: ==="
echo "jfr print --events jdk.ExecutionSample --stack-depth 30 '$JFR_FILE' > '${BASE}-${TS}-cpu.txt'"
echo "jfr print --events jdk.ObjectAllocationSample --stack-depth 30 '$JFR_FILE' > '${BASE}-${TS}-alloc.txt'"
echo "Send '$LOG_FILE' plus both extracted .txt files (not the raw .jfr -- large, binary, not directly readable)."

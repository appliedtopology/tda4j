#!/usr/bin/env bash
# Builds real ripser.cpp, downloads the Ripser paper's own Table-1 data sets, and runs
# RipserPaperBenchmarkSpec against BOTH -- live-measuring real ripser.cpp itself via the
# spec's own -DripserBin support (see RipserPaperBenchmarkSpec.scala's class doc and
# .claude/WORKLOG-ripser-profiling.md), rather than trusting the hardcoded 2026-09-17 M1 Pro
# reference snapshot baked into that spec as a fallback.
#
# Meant to be copied onto and run directly on a machine that doesn't already have either
# (e.g. a compute server) -- idempotent: re-running skips the clone/build/download steps if
# their outputs already exist, so it's safe to re-run after a failure or to add more cases.
#
# Usage:
#   .claude/scripts/run-ripser-paper-benchmark.sh [case1,case2,...]
#
# Env vars (all optional, sensible defaults below):
#   WORKDIR         where to clone ripser.cpp and download data (default: ./ripser-bench-scratch)
#   HEAP            sbt/JVM heap for the benchmark JVM (default: 6G)
#   TIMEOUT_SECONDS per-case timeout in the benchmark spec (default: 300)
#   RIPSER_TRIALS   how many times to re-run real ripser.cpp per case for a median (default: 5;
#                   lower this for the largest cases, e.g. RIPSER_TRIALS=1, if a 30s case x 5
#                   trials is more time than you want to spend on the ripser side alone)
#   PACKED_ONLY     set to "true" to skip RipserCohomologyContext (SortedSet) entirely and time
#                   only PackedRipserCohomologyContext -- recommended for the larger cases
#                   (dragon, o3_1024, fractal-r, random16, o3_4096), where SortedSet is known to
#                   be far slower and, per this spec's own doc, contaminates the packed engine's
#                   timing on every case after it times out (no cooperative cancellation).
#
# Example:
#   # the three sphere3 sizes, dual-engine, defaults otherwise
#   .claude/scripts/run-ripser-paper-benchmark.sh sphere3_48,sphere3_96,sphere3_192
#
#   # the harder cases, packed only, one ripser trial each (they're slow enough that 5 isn't worth it)
#   PACKED_ONLY=true RIPSER_TRIALS=1 TIMEOUT_SECONDS=1800 \
#     .claude/scripts/run-ripser-paper-benchmark.sh dragon,o3_1024,fractal-r,random16,o3_4096

set -euo pipefail

CASE_NAMES="${1:-}"
WORKDIR="${WORKDIR:-$(pwd)/ripser-bench-scratch}"
HEAP="${HEAP:-6G}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-300}"
RIPSER_TRIALS="${RIPSER_TRIALS:-5}"
PACKED_ONLY="${PACKED_ONLY:-false}"

RIPSER_SRC_DIR="$WORKDIR/ripser-src"
DATA_DIR="$WORKDIR/data"
RIPSER_BIN="$RIPSER_SRC_DIR/ripser"

mkdir -p "$WORKDIR" "$DATA_DIR"

echo "=== Step 1: real ripser.cpp (vanilla github.com/Ripser/ripser, plain make) ==="
if [ -x "$RIPSER_BIN" ]; then
  echo "Already built at $RIPSER_BIN, skipping clone+build. Delete it to force a rebuild."
else
  if [ ! -d "$RIPSER_SRC_DIR" ]; then
    git clone --depth 1 https://github.com/Ripser/ripser.git "$RIPSER_SRC_DIR"
  fi
  (cd "$RIPSER_SRC_DIR" && make)
fi
"$RIPSER_BIN" --help >/dev/null || { echo "ripser binary at $RIPSER_BIN doesn't run -- aborting"; exit 1; }
echo "ripser binary OK: $RIPSER_BIN"

echo
echo "=== Step 2: paper data sets (see RipserPaperBenchmarkSpec.scala's own class doc for these exact URLs) ==="
BASE=https://raw.githubusercontent.com/Ripser/ripser-benchmark/master
ROADMAP=https://github.com/n-otter/PH-roadmap/raw/master/data_sets

fetch() {
  local url="$1" dest="$2"
  if [ -s "$dest" ]; then
    echo "  already have $dest, skipping"
  else
    echo "  fetching $dest"
    curl -sL -o "$dest" "$url"
  fi
}

fetch "$BASE/sphere_3_192_points.dat" "$DATA_DIR/sphere_3_192_points.dat"
fetch "$BASE/o3_1024.txt" "$DATA_DIR/o3_1024.txt"
fetch "$BASE/o3_4096.txt" "$DATA_DIR/o3_4096.txt"
fetch "$ROADMAP/roadmap_datasets_point_cloud/random_point_cloud_50_16_.txt" "$DATA_DIR/random_point_cloud_50_16_.txt"
fetch "$ROADMAP/roadmap_datasets_distmat/fractal_9_5_2_random_edge_list.txt_0.19795_distmat.txt" "$DATA_DIR/fractal_9_5_2_distmat.txt"
fetch "$ROADMAP/roadmap_datasets_point_cloud/dragon_vrip.ply.txt_2000_.txt" "$DATA_DIR/dragon_vrip_2000.txt"

if [ ! -s "$DATA_DIR/sphere3_48.dat" ]; then
  head -48 "$DATA_DIR/sphere_3_192_points.dat" > "$DATA_DIR/sphere3_48.dat"
fi
if [ ! -s "$DATA_DIR/sphere3_96.dat" ]; then
  head -96 "$DATA_DIR/sphere_3_192_points.dat" > "$DATA_DIR/sphere3_96.dat"
fi
echo "Data ready in $DATA_DIR"

echo
echo "=== Step 3: run the benchmark ==="
# RipserPaperBenchmarkSpec.scala (like every *BenchmarkSpec/ProfilingSpec in this package -- see CLAUDE.md's
# "Commands" section) is skipped by default via `if !args.commandLine.boolOr("runBenchmarks", false) then
# skipAll`, not a hardcoded `skipAll` -- -DrunBenchmarks=true below re-enables it directly, no source editing
# or restore-on-exit trap needed (this script used to sed-toggle a literal `skipAll` line for exactly that
# reason; that line no longer exists, so the toggle is gone too, not patched to match).
CASE_ARG=""
if [ -n "$CASE_NAMES" ]; then
  CASE_ARG="-DcaseNames=$CASE_NAMES"
fi

sbt -J-Xmx"$HEAP" \
  -DrunBenchmarks=true \
  -DdataDir="$DATA_DIR" \
  -DripserBin="$RIPSER_BIN" \
  -DtimeoutSeconds="$TIMEOUT_SECONDS" \
  -DripserTrials="$RIPSER_TRIALS" \
  -DpackedOnly="$PACKED_ONLY" \
  $CASE_ARG \
  "testOnly org.appliedtopology.tda4j.homology.RipserPaperBenchmarkSpec"

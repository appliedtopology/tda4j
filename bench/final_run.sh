#!/usr/bin/env bash
# The paper's final benchmark run, in stages, on one frozen commit of TDA4j.
#
# The week a full run takes is the other tools (DIPHA, Dionysus, GUDHI, JavaPlex, ...), whose rows do not change with
# TDA4j's commit. So the final run is not a second full run: it measures TDA4j's own rows (the JVM build on every case,
# the Scala.js build on a subset) on the frozen commit, checks that the machine still runs the other tools as it did,
# and merges both into one set of tables. The earlier full run can still be running its last tasks while this one
# starts, but run the `merge` stage only once it has finished: the reference barcodes it compares with are its rows.
#
#   BASE=/scratch/bench-work/results/20261020-101500 bench/final_run.sh all
#
#   plan      print every command the stages would run (this is `DRY_RUN=1 ... all`); changes nothing
#   freeze    require a clean checkout, record its commit in $FINAL/commit, build TDA4j (JVM classes and the npm package)
#   smoke     the gate: three small cases at the frozen commit, TDA4j's rows against ripser.cpp; stops on any failure
#   anchors   rerun a few of BASE's rows (ripser.cpp, GUDHI, giotto-ph) and compare their times with BASE's
#   tda4j     every TDA4j JVM row, every task, in $FINAL/tda4j (resumable: rerun the stage after an interruption)
#   js        the Scala.js twins on a subset of the cases, in $FINAL/js (resumable)
#   merge     BASE's rows of the other tools + $FINAL/tda4j + $FINAL/js -> $FINAL/merged, summarized and checked
#   sweeps    the threads, cubical and alpha sweeps, each in its own directory under $FINAL/sweeps
#   status    the commit, the anchors verdict and the number of rows of each stage
#   all       freeze smoke anchors tda4j js merge sweeps
#
# Every stage after `freeze` refuses to run when HEAD is not the commit `freeze` recorded: a new commit is a new FINAL.
#
# Environment (all optional but BASE; the defaults in brackets):
#   BASE         the earlier full run's results directory, where the other tools' rows are (anchors, merge, defaults)
#   WORKDIR      as for bench/run.sh [./bench-work]; FINAL: where this run's stages write [$WORKDIR/final]
#   FIELDS, TIMEOUT, WARM_CAP, WARMUP, TRIALS, JVM_HEAP, JVM_OPTS, NODE_HEAP   the measurement protocol [BASE's own,
#                else 2,17 / 3600 / 60 / 2 / 5 / 32g]: rows of one table are measured alike. The heap must hold the
#                bars-only experiment's table of distances (8 n^2 bytes: 20 GB for clifford50000, 29 GB at its limit)
#   TDA4J_TOOLS  [every TDA4j JVM row, and the two bars-only experiment rows tda4j-parallel-1t and -mt]
#   JS_TOOLS, JS_CASES, JS_FIELDS, JS_TIMEOUT   the Scala.js twins [tda4j-js*; a subset of the cases: not o3_4096 (two
#                hours a row), not clifford50000; the last field of FIELDS; TIMEOUT]
#   SMOKE_CASES, SMOKE_FIELD    [sphere3_192,o3_1024,dragon2000; 17]
#   ANCHOR_TOOLS, ANCHOR_CASES, ANCHOR_FIELDS, ANCHOR_TOLERANCE   [ripser.cpp,gudhi,giotto-ph-1t; sphere3_192,o3_1024,
#                random2d_1024; the last field of FIELDS; 0.2]. Only the rows BASE also has are compared
#   ALLOW_DRIFT=1   merge although the anchors differ from BASE's by more than the tolerance (say so in the paper)
#   SWEEPS       the sweeps to run [threads cubical alpha]; THREADS_SWEEP_ARGS, CUBICAL_SWEEP_ARGS, ALPHA_SWEEP_ARGS
#                are passed on as they are (the earlier sweep's env.json has the arguments it was given). The sweep
#                directories do not merge: each sweep reruns its external tools too
#   DRY_RUN=1    print the commands, run nothing
#
# bench/run.sh does the work; this script only chooses what to run and in which order.
set -euo pipefail

REPO="${REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
BENCH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN="${RUN:-$BENCH/run.sh}"
WORKDIR="${WORKDIR:-$PWD/bench-work}"
mkdir -p "$WORKDIR"
WORKDIR="$(cd "$WORKDIR" && pwd)"
export WORKDIR
PY="${PY:-$WORKDIR/venv/bin/python}"
FINAL="${FINAL:-$WORKDIR/final}"
BASE="${BASE:-}"

die() { printf 'final_run.sh: %s\n' "$*" >&2; exit 1; }
say() { printf '\n=== %s ===\n' "$*"; }
# Print the command; run it unless this is a dry run.
quote() { case "$1" in ''|*[!A-Za-z0-9_./:=@%+,-]*) printf "'%s'" "$1" ;; *) printf '%s' "$1" ;; esac; }
step() {
  local arg
  printf '+'
  for arg in "$@"; do printf ' '; quote "$arg"; done
  printf '\n'
  if [ -z "${DRY_RUN:-}" ]; then "$@"; fi
}

# A value the earlier run was given (its env.json), else the default.
base_arg() {
  local value=""
  if [ -n "$BASE" ] && [ -f "$BASE/env.json" ] && [ -x "$PY" ]; then
    value="$("$PY" -c 'import json, sys
v = json.load(open(sys.argv[1])).get("args", {}).get(sys.argv[2])
print("" if v is None else v)' "$BASE/env.json" "$2" 2>/dev/null || true)"
  fi
  printf '%s' "${value:-$1}"
}

FIELDS="${FIELDS:-$(base_arg 2,17 fields)}"
TIMEOUT="${TIMEOUT:-$(base_arg 3600 timeout)}"
WARM_CAP="${WARM_CAP:-$(base_arg 60 warm_cap)}"
WARMUP="${WARMUP:-$(base_arg 2 warmup)}"
TRIALS="${TRIALS:-$(base_arg 5 trials)}"
JVM_HEAP="${JVM_HEAP:-$(base_arg 32g jvm_heap)}"
JVM_OPTS="${JVM_OPTS:-$(base_arg '' jvm_opts)}"
NODE_HEAP="${NODE_HEAP:-$(base_arg '' node_heap)}"
LAST_FIELD="${FIELDS##*,}"

TDA4J_TOOLS="${TDA4J_TOOLS:-tda4j*,!tda4j-js*,tda4j-parallel-1t,tda4j-parallel-mt}"
JS_TOOLS="${JS_TOOLS:-tda4j-js*}"
JS_CASES="${JS_CASES:-sphere3_48,sphere3_96,sphere3_192,dragon2000,random16,o3_1024,random2d_512,blob2d_512,random2d_1024,random3d_32,random3d_64,blob3d_64,unif2d_1000,unif3d_1000,unif5d_500,dragon2000_alpha_gp,sphere3_192_alpha_gp}"
JS_FIELDS="${JS_FIELDS:-$LAST_FIELD}"
JS_TIMEOUT="${JS_TIMEOUT:-$TIMEOUT}"
SMOKE_CASES="${SMOKE_CASES:-sphere3_192,o3_1024,dragon2000}"
SMOKE_FIELD="${SMOKE_FIELD:-17}"
ANCHOR_TOOLS="${ANCHOR_TOOLS:-ripser.cpp,gudhi,giotto-ph-1t}"
ANCHOR_CASES="${ANCHOR_CASES:-sphere3_192,o3_1024,random2d_1024}"
ANCHOR_FIELDS="${ANCHOR_FIELDS:-$LAST_FIELD}"
ANCHOR_TOLERANCE="${ANCHOR_TOLERANCE:-0.2}"
SWEEPS="${SWEEPS:-threads cubical alpha}"

# The protocol every stage shares; a stage adds its own --fields, --timeout, --tools and --cases.
protocol=(--warm-cap "$WARM_CAP" --warmup "$WARMUP" --trials "$TRIALS" --jvm-heap "$JVM_HEAP" --resume)
if [ -n "$JVM_OPTS" ]; then protocol+=(--jvm-opts "$JVM_OPTS"); fi
if [ -n "$NODE_HEAP" ]; then protocol+=(--node-heap "$NODE_HEAP"); fi

bench_py() { step "$PY" "$BENCH/bench.py" "$@"; }

# A clean checkout (tracked files; the harness's provenance counts no others) at the commit freeze recorded.
check_clean() {
  if [ -n "${DRY_RUN:-}" ]; then return; fi
  if ! git -C "$REPO" diff --quiet HEAD; then
    die "tracked files differ from HEAD ($(git -C "$REPO" diff --name-only HEAD | head -3 | tr '\n' ' ')): commit them first, or the run would record a commit it did not measure"
  fi
}

require_frozen() {
  if [ -n "${DRY_RUN:-}" ]; then return; fi
  [ -s "$FINAL/commit" ] || die "no $FINAL/commit: run the freeze stage first"
  check_clean
  local now
  now="$(git -C "$REPO" rev-parse HEAD)"
  [ "$now" = "$(cat "$FINAL/commit")" ] \
    || die "HEAD is $now but $FINAL was frozen at $(cat "$FINAL/commit"): the stages of one final run share a commit; use a new FINAL"
}

require_base() { [ -n "$BASE" ] && { [ -f "$BASE/runs.csv" ] || [ -n "${DRY_RUN:-}" ]; } || die "BASE must be the earlier run's results directory (it has no runs.csv)"; }

stage_freeze() {
  say "freeze: the commit and the build"
  check_clean
  local now
  now="$(git -C "$REPO" rev-parse HEAD)"
  if [ -s "$FINAL/commit" ] && [ "$(cat "$FINAL/commit")" != "$now" ] && [ -z "${DRY_RUN:-}" ]; then
    die "$FINAL was frozen at $(cat "$FINAL/commit"), HEAD is $now: use a new FINAL"
  fi
  printf 'commit %s\n' "$now"
  if [ -z "${DRY_RUN:-}" ]; then mkdir -p "$FINAL" && printf '%s\n' "$now" > "$FINAL/commit"; fi
  if [ -n "$BASE" ] && [ -x "$PY" ] && [ -z "${DRY_RUN:-}" ]; then
    # The Python tools must be the versions BASE measured; this only reports, since pip cannot downgrade a venv
    # without reinstalling it (bench/run.sh setup python honours PIP_CONSTRAINTS in a new WORKDIR).
    "$RUN" constraints "$BASE" | sort > "$FINAL/constraints.txt"
    "$PY" -m pip freeze 2>/dev/null | sort | diff - "$FINAL/constraints.txt" > "$FINAL/pip-differences.txt" \
      && echo "python packages: the same as BASE's" \
      || { echo "python packages differ from BASE's (< installed, > BASE): $FINAL/pip-differences.txt"; head -10 "$FINAL/pip-differences.txt"; }
  fi
  step "$RUN" setup tda4j tda4j-js
}

stage_smoke() {
  say "smoke: $SMOKE_CASES at p=$SMOKE_FIELD"
  require_frozen
  step env RESULTS_DIR="$FINAL/smoke" "$RUN" run --cases "$SMOKE_CASES" --fields "$SMOKE_FIELD" \
    --timeout "$TIMEOUT" --tools "$TDA4J_TOOLS,ripser.cpp" "${protocol[@]}"
  bench_py check "$FINAL/smoke" --tools 'tda4j*'
}

stage_anchors() {
  say "anchors: $ANCHOR_TOOLS on $ANCHOR_CASES at p=$ANCHOR_FIELDS, against BASE"
  require_frozen
  require_base
  step env RESULTS_DIR="$FINAL/anchors" "$RUN" run --cases "$ANCHOR_CASES" --fields "$ANCHOR_FIELDS" \
    --timeout "$TIMEOUT" --tools "$ANCHOR_TOOLS" "${protocol[@]}"
  local verdict=0
  bench_py anchors "$FINAL/anchors" "$BASE" --tools "$ANCHOR_TOOLS" --tolerance "$ANCHOR_TOLERANCE" || verdict=$?
  if [ -z "${DRY_RUN:-}" ]; then
    case "$verdict" in 0) echo ok ;; 1) echo drift ;; *) echo none ;; esac > "$FINAL/anchors.verdict"
    echo "anchors verdict: $(cat "$FINAL/anchors.verdict")"
  fi
}

# A JVM heap size in MB (16g, 512m, 1t, or plain MB).
heap_mb() {
  case "$1" in
    *[gG]) echo $(( ${1%[gG]} * 1024 )) ;;
    *[tT]) echo $(( ${1%[tT]} * 1024 * 1024 )) ;;
    *[mM]) echo "${1%[mM]}" ;;
    *) echo "$1" ;;
  esac
}

stage_tda4j() {
  say "tda4j: $TDA4J_TOOLS"
  require_frozen
  case ",$TDA4J_TOOLS," in
    *,tda4j-parallel-1t,*|*,tda4j-parallel-mt,*)
      if [ "$(heap_mb "$JVM_HEAP")" -lt 30720 ]; then
        echo "WARNING: JVM_HEAP=$JVM_HEAP: the bars-only experiment tabulates all distances (20 GB for clifford50000, 29 GB at its limit of 60,000 points) and will run out of memory there"
      fi ;;
  esac
  step env RESULTS_DIR="$FINAL/tda4j" "$RUN" run --tools "$TDA4J_TOOLS" --fields "$FIELDS" --timeout "$TIMEOUT" \
    "${protocol[@]}"
  # A failure of a TDA4j row is a problem; a timeout or a lack of memory is a result, read in the tables. The barcodes
  # are compared with the reference's in the merged run: the reference's bars are BASE's.
  bench_py check "$FINAL/tda4j" --tools 'tda4j*' --allow unsupported,missing,timeout,oom,limit
}

stage_js() {
  say "js: $JS_TOOLS on $JS_CASES at p=$JS_FIELDS"
  require_frozen
  step env RESULTS_DIR="$FINAL/js" "$RUN" run --tools "$JS_TOOLS" --cases "$JS_CASES" --fields "$JS_FIELDS" \
    --timeout "$JS_TIMEOUT" "${protocol[@]}"
  bench_py check "$FINAL/js" --tools 'tda4j*' --allow unsupported,missing,timeout,oom,limit
}

stage_merge() {
  say "merge: BASE's other tools + this run's TDA4j rows"
  require_frozen
  require_base
  if [ -z "${DRY_RUN:-}" ]; then
    [ ! -e "$FINAL/merged" ] || die "$FINAL/merged exists: remove it to merge again"
    case "$(cat "$FINAL/anchors.verdict" 2>/dev/null || echo absent)" in
      ok) ;;
      drift) [ -n "${ALLOW_DRIFT:-}" ] || die "the anchors differ from BASE's by more than $ANCHOR_TOLERANCE ($FINAL/anchors/anchors.md): the machine is not the one that measured BASE; ALLOW_DRIFT=1 merges anyway" ;;
      none) echo "WARNING: no anchor row is common to this run and BASE: nothing shows the machine ran as it did" ;;
      *) echo "WARNING: the anchors stage has not run: nothing shows the machine ran as it did" ;;
    esac
  fi
  # BASE's own TDA4j rows (including the JS twins) are from an older commit: only the other tools' rows are taken.
  local sources=("$BASE:!tda4j*" "$FINAL/tda4j")
  if [ -f "$FINAL/js/runs.csv" ] || [ -n "${DRY_RUN:-}" ]; then sources+=("$FINAL/js"); fi
  step "$RUN" merge "$FINAL/merged" "${sources[@]}"
  bench_py check "$FINAL/merged" --tools 'tda4j*' --allow unsupported,missing,timeout,oom,limit
}

stage_sweeps() {
  say "sweeps: $SWEEPS"
  require_frozen
  local sweep
  for sweep in $SWEEPS; do
    case "$sweep" in
      threads) step "$RUN" sweep-threads --out "$FINAL/sweeps/threads" --timeout "$TIMEOUT" --jvm-heap "$JVM_HEAP" ${THREADS_SWEEP_ARGS:-} ;;
      cubical) step "$RUN" sweep-cubical --out "$FINAL/sweeps/cubical" --timeout "$TIMEOUT" --jvm-heap "$JVM_HEAP" ${CUBICAL_SWEEP_ARGS:-} ;;
      alpha) step "$RUN" sweep-alpha --out "$FINAL/sweeps/alpha" --timeout "$TIMEOUT" --jvm-heap "$JVM_HEAP" ${ALPHA_SWEEP_ARGS:-} ;;
      *) die "unknown sweep '$sweep' (threads cubical alpha)" ;;
    esac
  done
}

stage_status() {
  echo "FINAL=$FINAL"
  echo "frozen at: $(cat "$FINAL/commit" 2>/dev/null || echo 'not yet')   HEAD: $(git -C "$REPO" rev-parse HEAD)"
  echo "anchors verdict: $(cat "$FINAL/anchors.verdict" 2>/dev/null || echo 'not yet')"
  local dir
  for dir in smoke anchors tda4j js merged sweeps/threads sweeps/cubical sweeps/alpha; do
    for csv in runs.csv sweep.csv; do
      if [ -f "$FINAL/$dir/$csv" ]; then
        printf '%-16s %6d rows  (%s)\n' "$dir" "$(($(wc -l < "$FINAL/$dir/$csv") - 1))" "$csv"
      fi
    done
  done
}

stage="${1:-}"
shift || true
case "$stage" in
  plan) DRY_RUN=1; export DRY_RUN
        for s in freeze smoke anchors tda4j js merge sweeps; do "stage_$s"; done ;;
  freeze|smoke|anchors|tda4j|js|merge|sweeps|status) "stage_$stage" ;;
  all) for s in freeze smoke anchors tda4j js merge sweeps; do "stage_$s"; done ;;
  *) sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'; [ -z "$stage" ] || exit 2 ;;
esac

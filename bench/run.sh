#!/usr/bin/env bash
# Cross-platform benchmark of TDA4j against the persistent-homology libraries at the frontier.
# See bench/README.md. Idempotent: re-running skips what is already built or downloaded.
#
#   bench/run.sh setup              build/install every tool into $WORKDIR
#   bench/run.sh prepare            download and generate the data sets into $WORKDIR/data
#   bench/run.sh run [bench.py run options...]   e.g. --tasks vr --fields 2,17 --timeout 3600
#   bench/run.sh all [options...]   setup + prepare + run
#   bench/run.sh summarize RESULTS_DIR
#
# Environment (all optional):
#   WORKDIR        scratch space for tools, data and results (default: ./bench-work)
#   PYTHON         python3 to build the venv from (default: python3)
#   JULIA          julia binary; Ripserer.jl is skipped if unset and `julia` is not on PATH
#   JAVAPLEX_JAR   a JavaPlex 4.x jar; JavaPlex is skipped if unset
#   SKIP_TDA4J_BUILD=1   reuse the classpath file from a previous setup
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BENCH="$REPO/bench"
WORKDIR="${WORKDIR:-$PWD/bench-work}"
mkdir -p "$WORKDIR"
WORKDIR="$(cd "$WORKDIR" && pwd)"
VENV="$WORKDIR/venv"
TOOLS="$WORKDIR/tools"
DATA="$WORKDIR/data"
mkdir -p "$TOOLS" "$DATA"

log() { printf '\n=== %s ===\n' "$*"; }

setup_python() {
  log "Python tools (ripser.py, giotto-ph, GUDHI, CubicalRipser) in $VENV"
  if [ ! -x "$VENV/bin/python" ]; then "${PYTHON:-python3}" -m venv "$VENV"; fi
  "$VENV/bin/python" -m pip install --upgrade pip >/dev/null
  # Unpinned on purpose: the versions actually installed are recorded in every run's env.json.
  # For the paper's final run, pin them here (pip install pkg==x.y.z).
  for pkg in numpy scipy ripser giotto-ph gudhi cripser; do
    "$VENV/bin/python" -m pip install "$pkg" || echo "WARNING: could not install $pkg; it will be reported as missing"
  done
}

setup_ripser() {
  log "ripser.cpp (vanilla Z/2 build and the USE_COEFFICIENTS build)"
  if [ ! -d "$TOOLS/ripser" ]; then git clone --depth 1 https://github.com/Ripser/ripser.git "$TOOLS/ripser"; fi
  (cd "$TOOLS/ripser" && make ripser ripser-coeff)
  "$TOOLS/ripser/ripser" --help >/dev/null 2>&1 || true
}

setup_julia() {
  local julia="${JULIA:-$(command -v julia || true)}"
  if [ -z "$julia" ]; then echo "julia not found: Ripserer.jl will be reported as missing"; return; fi
  log "Ripserer.jl ($julia)"
  mkdir -p "$TOOLS/julia"
  if "$julia" --project="$TOOLS/julia" -e 'using Pkg; Pkg.add(["Ripserer", "DelimitedFiles"]); Pkg.precompile()'; then
    echo "$julia" > "$TOOLS/julia.path"
  else
    echo "WARNING: Ripserer.jl setup failed; it will be reported as missing"; rm -f "$TOOLS/julia.path"
  fi
}

setup_javaplex() {
  if [ -z "${JAVAPLEX_JAR:-}" ]; then echo "JAVAPLEX_JAR not set: JavaPlex will be reported as missing"; return; fi
  log "JavaPlex worker against $JAVAPLEX_JAR"
  mkdir -p "$TOOLS/javaplex-build"
  # The worker was written against JavaPlex 4.x's documented API but never compiled against a real jar: a signature
  # mismatch is a warning (fix bench/workers/JavaPlexWorker.java), not a reason to stop the other tools.
  if javac -cp "$JAVAPLEX_JAR" -d "$TOOLS/javaplex-build" "$BENCH/workers/JavaPlexWorker.java"; then
    echo "$JAVAPLEX_JAR" > "$TOOLS/javaplex.path"
  else
    echo "WARNING: JavaPlexWorker.java did not compile against $JAVAPLEX_JAR; JavaPlex will be reported as missing"
    rm -f "$TOOLS/javaplex.path"
  fi
}

setup_tda4j() {
  if [ "${SKIP_TDA4J_BUILD:-0}" = "1" ] && [ -s "$TOOLS/tda4j.classpath" ]; then return; fi
  log "TDA4j (test classpath with PaperBenchmarkDriver)"
  if ! command -v sbt >/dev/null; then
    echo "sbt not on PATH; .claude/scripts/install-sbt.sh installs it"; exit 1
  fi
  # Never run two sbt processes on one checkout at once (stale class files look like real bugs).
  (cd "$REPO" && sbt "Test/compile" "export Test/fullClasspath") > "$TOOLS/sbt-export.log" 2>&1 \
    || { tail -40 "$TOOLS/sbt-export.log"; exit 1; }
  # sbt 2 prints the classpath with ${OUT}/${CSR_CACHE} placeholders and content hashes; resolve them, and use
  # the class directories rather than the packaged jars.
  "$VENV/bin/python" - "$REPO" "$TOOLS/sbt-export.log" "$TOOLS/tda4j.classpath" <<'PY'
import os, re, sys, glob
repo, log, dest = sys.argv[1:4]
text = open(log).read()
m = re.findall(r'List\((.*)\)', text)
if not m:
    lines = [l for l in text.splitlines() if 'scala3-library' in l]
    if not lines: sys.exit("could not find the exported classpath in " + log)
    entries = re.split(r'[:,]', lines[-1].split(']')[-1])
else:
    entries = m[-1].split(',')
csr = os.environ.get("COURSIER_CACHE") or next(
    (p for p in [os.path.expanduser("~/.cache/coursier/v1"), os.path.expanduser("~/Library/Caches/Coursier/v1")]
     if os.path.isdir(p)), os.path.expanduser("~/.cache/coursier/v1"))
out = os.path.join(repo, "target", "out")
scala_dirs = sorted(glob.glob(os.path.join(out, "jvm", "scala-*", "tda4j")))
cp = []
for e in entries:
    e = e.strip()
    e = re.sub(r'>sha256-.*$', '', e)
    e = e.replace("${CSR_CACHE}", csr).replace("${OUT}", out)
    if not e or "tda4j_3-" in os.path.basename(e):
        continue
    cp.append(e)
target = [d for d in scala_dirs if os.path.isdir(os.path.join(d, "test-classes"))]
if not target: sys.exit("no compiled classes under " + out)
cp = [os.path.join(target[-1], "classes"), os.path.join(target[-1], "test-classes")] + cp
missing = [p for p in cp if not os.path.exists(p)]
if missing: sys.exit("classpath entries not found: " + ", ".join(missing[:5]))
open(dest, "w").write(os.pathsep.join(cp))
print(f"classpath: {len(cp)} entries -> {dest}")
PY
  java -cp "$(cat "$TOOLS/tda4j.classpath")" org.appliedtopology.tda4j.PaperBenchmarkDriver \
    task=vr input="$BENCH/smoke-points.txt" dim=1 warmup=0 trials=1 >/dev/null \
    || { echo "TDA4j driver smoke test failed"; exit 1; }
}

export_env() {
  export TDA4J_CP="$TOOLS/tda4j.classpath"
  export RIPSER_BIN="$TOOLS/ripser/ripser"
  export RIPSER_COEFF_BIN="$TOOLS/ripser/ripser-coeff"
  [ -x "$RIPSER_BIN" ] || unset RIPSER_BIN
  [ -x "$RIPSER_COEFF_BIN" ] || unset RIPSER_COEFF_BIN
  if [ -s "$TOOLS/julia.path" ]; then
    export JULIA="$(cat "$TOOLS/julia.path")" RIPSERER_PROJECT="$TOOLS/julia"
  fi
  if [ -s "$TOOLS/javaplex.path" ]; then
    export JAVAPLEX_JAR="$(cat "$TOOLS/javaplex.path")" JAVAPLEX_BUILD="$TOOLS/javaplex-build"
  fi
}

cmd="${1:-all}"
shift || true
case "$cmd" in
  # TDA4j first: the optional tools after it only warn when they fail.
  setup) setup_python; setup_tda4j; setup_ripser; setup_julia; setup_javaplex ;;
  prepare) "$VENV/bin/python" "$BENCH/bench.py" prepare --data "$DATA" ;;
  run)
    export_env
    RESULTS="$WORKDIR/results/$(date +%Y%m%d-%H%M%S)"
    echo "results -> $RESULTS"
    "$VENV/bin/python" "$BENCH/bench.py" run --data "$DATA" --out "$RESULTS" --python "$VENV/bin/python" "$@"
    ;;
  summarize) "$VENV/bin/python" "$BENCH/bench.py" summarize "$@" ;;
  all)
    "$0" setup
    "$0" prepare
    "$0" run "$@"
    ;;
  *) echo "usage: $0 setup|prepare|run|all|summarize [options]"; exit 2 ;;
esac

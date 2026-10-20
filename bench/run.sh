#!/usr/bin/env bash
# Cross-platform benchmark of TDA4j against the persistent-homology libraries at the frontier.
# See bench/README.md. Idempotent: re-running skips what is already built or downloaded.
#
#   bench/run.sh setup [tool...]    build/install every tool into $WORKDIR, or only the named ones:
#                                   python tda4j tda4j-js ripser julia javaplex dipha phat eirene
#   bench/run.sh prepare            download and generate the data sets into $WORKDIR/data
#   bench/run.sh run [bench.py run options...]   e.g. --tasks vr --fields 2,17 --timeout 3600
#   bench/run.sh all [options...]   setup + prepare + run
#   bench/run.sh summarize RESULTS_DIR
#   bench/run.sh sweep-cubical [--dims 2,3] [...]   size sweep of TDA4j's image engines (sweep_cubical.py)
#   bench/run.sh sweep-alpha [--seeds 1] [...]       dimension and point-count sweep of the alpha complex backends
#                                   (sweep_alpha.py): cube and sphere samples, the Helix thesis's workloads
#   bench/run.sh count-cells [--reference] [--cases a,b] [--verify] [...]   the number of cells of every case's complex,
#                                   by dimension (count_cells.py); --reference writes bench/cells-reference.csv, which
#                                   `summarize` joins: a benchmark run never counts
#
# Environment (all optional):
#   WORKDIR        scratch space for tools, data and results (default: ./bench-work)
#   PYTHON         python3 to build the venv from (default: python3)
#   JULIA          julia binary; Ripserer.jl and Eirene.jl are skipped if unset and `julia` is not on PATH
#   MPIEXEC, MPIEXEC_FLAGS   how `dipha-mt` is launched (default: mpiexec, and for Open MPI --oversubscribe, plus
#                  --allow-run-as-root as root); DIPHA needs an MPI implementation with mpicxx and cmake
#   JAVAPLEX_JAR   a JavaPlex 4.x jar; JavaPlex is skipped if unset
#   NODE           node binary for the Scala.js build of TDA4j (20.16 or newer; default: node on PATH); tda4j-js is
#                  skipped if there is none
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
  log "Python tools (ripser.py, giotto-ph, GUDHI, CubicalRipser, Dionysus 2, OAT) in $VENV"
  if [ ! -x "$VENV/bin/python" ]; then "${PYTHON:-python3}" -m venv "$VENV"; fi
  "$VENV/bin/python" -m pip install --upgrade pip >/dev/null
  # Unpinned on purpose: the versions actually installed are recorded in every run's env.json.
  # For the paper's final run, pin them here (pip install pkg==x.y.z).
  for pkg in numpy scipy ripser giotto-ph gudhi cripser dionysus oat_python; do
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

setup_dipha() {
  log "DIPHA (MPI) and make_phat, which writes DIPHA's Vietoris-Rips complex as a PHAT boundary matrix"
  if ! command -v mpicxx >/dev/null || ! command -v cmake >/dev/null; then
    echo "WARNING: DIPHA needs mpicxx (apt install libopenmpi-dev openmpi-bin) and cmake: dipha, dipha-mt, phat and phat-dual will be reported as missing"
    return
  fi
  if [ ! -d "$TOOLS/dipha" ]; then git clone https://github.com/DIPHA/dipha.git "$TOOLS/dipha"; fi
  (cd "$TOOLS/dipha" && git checkout --quiet 0b874769fbd092c07a12cebc2459adb02117c2fd)
  mkdir -p "$TOOLS/dipha/build"
  (cd "$TOOLS/dipha/build" && cmake .. && make -j"$(nproc)" dipha) \
    || { echo "WARNING: DIPHA did not build; it will be reported as missing"; return; }
  mpicxx -O3 -DNDEBUG -std=c++11 -I"$TOOLS/dipha/include" -I"$TOOLS/dipha/externals" \
    -o "$TOOLS/make_phat" "$BENCH/workers/make_phat.cpp" \
    || echo "WARNING: make_phat did not build; phat will be reported as missing"
}

setup_phat() {
  log "PHAT (reduction of a prebuilt boundary matrix)"
  if ! command -v cmake >/dev/null; then echo "WARNING: PHAT needs cmake: phat and phat-dual will be reported as missing"; return; fi
  if [ ! -d "$TOOLS/phat" ]; then git clone https://bitbucket.org/phat-code/phat.git "$TOOLS/phat"; fi
  (cd "$TOOLS/phat" && git checkout --quiet a74705e4628e447084d47dbf13a16dfb6d2ebd9a)
  mkdir -p "$TOOLS/phat/build"
  (cd "$TOOLS/phat/build" && cmake .. && make -j"$(nproc)" phat) \
    || echo "WARNING: PHAT did not build; it will be reported as missing"
}

setup_eirene() {
  local julia="${JULIA:-$(command -v julia || true)}"
  if [ -z "$julia" ]; then echo "julia not found: Eirene will be reported as missing"; return; fi
  log "Eirene.jl ($julia): GitHub master at a pinned commit (1.4.0)"
  # The registered Eirene (1.0.0 to 1.3.6) cannot be resolved on Julia 1.10: its CSV and DataFrames bounds exclude each
  # other's current versions. The repository's master (1.4.0, compat julia = "1") installs and loads.
  mkdir -p "$TOOLS/eirene"
  if "$julia" --project="$TOOLS/eirene" -e 'using Pkg; Pkg.add(url="https://github.com/Eetion/Eirene.jl", rev="16622d0aa1d7237895919e7fbdc1e8a6196425ab"); Pkg.precompile(); using Eirene' \
     && "$julia" --project="$TOOLS/eirene" "$BENCH/workers/eirene_worker.jl" \
          task=vr input="$BENCH/smoke-points.txt" dim=1 p=2 warmup=0 trials=1 >/dev/null; then
    echo "$julia" > "$TOOLS/eirene.path"
  else
    echo "WARNING: Eirene setup failed; it will be reported as missing"; rm -f "$TOOLS/eirene.path"
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
  # `tda4j` is the JVM build (the project matrix also has `tda4jJS`; the root only aggregates).
  (cd "$REPO" && sbt "tda4j/Test/compile" "export tda4j/Test/fullClasspath") > "$TOOLS/sbt-export.log" 2>&1 \
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

setup_tda4j_js() {
  local node="${NODE:-$(command -v node || true)}"
  if [ -z "$node" ]; then echo "node not found: tda4j-js will be reported as missing"; rm -f "$TOOLS/tda4j-js.path"; return; fi
  if ! "$node" -e 'const v = process.versions.node.split(".").map(Number); process.exit(v[0] > 20 || (v[0] === 20 && v[1] >= 16) ? 0 : 1)'; then
    echo "WARNING: $("$node" --version) is older than 20.16, which the file access needs: tda4j-js will be reported as missing"
    rm -f "$TOOLS/tda4j-js.path"; return
  fi
  log "TDA4j for JavaScript: the npm package (optimized Scala.js link), installed under $TOOLS/tda4j-js"
  if ! command -v sbt >/dev/null; then echo "sbt not on PATH; .claude/scripts/install-sbt.sh installs it"; exit 1; fi
  # Never run two sbt processes on one checkout at once: this runs after setup_tda4j's sbt has finished.
  if ! (cd "$REPO" && sbt "tda4jJS/npmPack") > "$TOOLS/sbt-npm.log" 2>&1; then
    tail -40 "$TOOLS/sbt-npm.log"
    echo "WARNING: sbt tda4jJS/npmPack failed; tda4j-js will be reported as missing"; rm -f "$TOOLS/tda4j-js.path"; return
  fi
  local tarball
  tarball="$(ls -t "$REPO"/target/npm/*.tgz | head -1)"
  rm -rf "$TOOLS/tda4j-js"; mkdir -p "$TOOLS/tda4j-js"
  (cd "$TOOLS/tda4j-js" && npm init -y >/dev/null && npm install --no-audit --no-fund "$tarball" >/dev/null)
  local main="$TOOLS/tda4j-js/node_modules/@appliedtopology/tda4j/main.mjs"
  if ! TDA4J_JS="$main" "$node" "$BENCH/workers/tda4j_js_worker.mjs" \
      task=vr input="$BENCH/smoke-points.txt" dim=1 warmup=0 trials=1 >/dev/null; then
    echo "WARNING: the JavaScript worker's smoke test failed; tda4j-js will be reported as missing"
    rm -f "$TOOLS/tda4j-js.path"; return
  fi
  echo "$main" > "$TOOLS/tda4j-js.path"
  echo "tda4j-js: $tarball -> $main"
}

export_env() {
  export TDA4J_CP="$TOOLS/tda4j.classpath"
  # The classpath points at sbt's class directories: after an `sbt clean` (or a docs build) they are gone.
  if [ -s "$TDA4J_CP" ] && ! java -cp "$(cat "$TDA4J_CP")" org.appliedtopology.tda4j.PaperBenchmarkDriver \
      task=vr input="$BENCH/smoke-points.txt" dim=1 warmup=0 trials=1 >/dev/null 2>&1; then
    echo "TDA4j's compiled classes are missing (sbt clean?): rerun 'bench/run.sh setup' first"; exit 1
  fi
  # The Scala.js build is the npm package that setup installed; if it is gone (a new WORKDIR), the tool is skipped.
  if [ -s "$TOOLS/tda4j-js.path" ] && [ -f "$(cat "$TOOLS/tda4j-js.path")" ]; then
    export TDA4J_JS="$(cat "$TOOLS/tda4j-js.path")"
    export NODE="${NODE:-$(command -v node || true)}"
    [ -n "$NODE" ] || unset NODE TDA4J_JS
  fi
  export RIPSER_BIN="$TOOLS/ripser/ripser"
  export RIPSER_COEFF_BIN="$TOOLS/ripser/ripser-coeff"
  [ -x "$RIPSER_BIN" ] || unset RIPSER_BIN
  [ -x "$RIPSER_COEFF_BIN" ] || unset RIPSER_COEFF_BIN
  if [ -s "$TOOLS/julia.path" ]; then
    export JULIA="$(cat "$TOOLS/julia.path")" RIPSERER_PROJECT="$TOOLS/julia"
  fi
  if [ -s "$TOOLS/eirene.path" ]; then
    export JULIA="${JULIA:-$(cat "$TOOLS/eirene.path")}" EIRENE_PROJECT="$TOOLS/eirene"
  fi
  [ -x "$TOOLS/dipha/build/dipha" ] && export DIPHA_BIN="$TOOLS/dipha/build/dipha"
  [ -x "$TOOLS/make_phat" ] && export MAKE_PHAT_BIN="$TOOLS/make_phat"
  [ -x "$TOOLS/phat/build/phat" ] && export PHAT_BIN="$TOOLS/phat/build/phat"
  if [ -s "$TOOLS/javaplex.path" ]; then
    export JAVAPLEX_JAR="$(cat "$TOOLS/javaplex.path")" JAVAPLEX_BUILD="$TOOLS/javaplex-build"
  fi
}

cmd="${1:-all}"
shift || true
case "$cmd" in
  # TDA4j first: the optional tools after it only warn when they fail.
  setup)
    components=("$@")
    if [ ${#components[@]} -eq 0 ]; then components=(python tda4j tda4j-js ripser julia javaplex dipha phat eirene); fi
    for component in "${components[@]}"; do
      case "$component" in
        python) setup_python ;;
        tda4j) setup_tda4j ;;
        tda4j-js) setup_tda4j_js ;;
        ripser) setup_ripser ;;
        julia) setup_julia ;;
        javaplex) setup_javaplex ;;
        dipha) setup_dipha ;;
        phat) setup_phat ;;
        eirene) setup_eirene ;;
        *) echo "unknown component '$component' (python tda4j tda4j-js ripser julia javaplex dipha phat eirene)"; exit 2 ;;
      esac
    done
    ;;
  prepare) "$VENV/bin/python" "$BENCH/bench.py" prepare --data "$DATA" ;;
  run)
    export_env
    RESULTS="$WORKDIR/results/$(date +%Y%m%d-%H%M%S)"
    echo "results -> $RESULTS"
    "$VENV/bin/python" "$BENCH/bench.py" run --data "$DATA" --out "$RESULTS" --python "$VENV/bin/python" "$@"
    ;;
  summarize) "$VENV/bin/python" "$BENCH/bench.py" summarize "$@" ;;
  sweep-cubical)
    export_env
    "$VENV/bin/python" "$BENCH/sweep_cubical.py" --data "$DATA" --python "$VENV/bin/python" "$@"
    ;;
  sweep-alpha)
    export_env
    "$VENV/bin/python" "$BENCH/sweep_alpha.py" --data "$DATA" --python "$VENV/bin/python" "$@"
    ;;
  count-cells)
    export_env
    RESULTS="$WORKDIR/results/cells-$(date +%Y%m%d-%H%M%S)"
    echo "results -> $RESULTS"
    "$VENV/bin/python" "$BENCH/count_cells.py" --data "$DATA" --out "$RESULTS" --python "$VENV/bin/python" "$@"
    ;;
  all)
    "$0" setup
    "$0" prepare
    "$0" run "$@"
    ;;
  *) echo "usage: $0 setup [tool...]|prepare|run|all|summarize|sweep-cubical|sweep-alpha|count-cells [options]"; exit 2 ;;
esac

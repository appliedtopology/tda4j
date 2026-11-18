#!/usr/bin/env bash
# Cross-platform benchmark of TDA4j against the persistent-homology libraries at the frontier.
# See bench/README.md. Idempotent: re-running skips what is already built or downloaded.
#
#   bench/run.sh setup [tool...]    build/install every tool into $WORKDIR, or only the named ones:
#                                   python flashcubical tda4j tda4j-js ripser redzed julia javaplex dipha phat eirene dowker
#   bench/run.sh prepare            download and generate the data sets into $WORKDIR/data
#   bench/run.sh run [bench.py run options...]   e.g. --tasks vr --fields 2,17 --timeout 3600
#   bench/run.sh all [options...]   setup + prepare + run
#   bench/run.sh summarize RESULTS_DIR
#   bench/run.sh merge OUT DIR[:tool,tool]...   one results directory from several, the last source with a row winning
#                                   (bench.py merge); `anchors NEW OLD` checks that two runs ran at the same speed;
#                                   `check DIR` lists the failed rows and disagreeing barcodes; `constraints DIR` writes
#                                   a pip constraints file from a run's env.json
#   bench/final_run.sh              the paper's final run in stages (see its header); this script's commands are the stages
#   bench/run.sh sweep-cubical [--dims 2,3] [...]   size sweep of TDA4j's image engines (sweep_cubical.py)
#   bench/run.sh sweep-alpha [--seeds 1] [...]       dimension and point-count sweep of the alpha complex backends
#                                   (sweep_alpha.py): cube and sphere samples, the Helix thesis's workloads
#   bench/run.sh sweep-threads [--cases a,b] [--threads 1,2,4] [...]   the multithreaded Vietoris-Rips tools at 1, 2, 4,
#                                   ... threads, pinned to as many CPUs (sweep_threads.py)
#   bench/run.sh sweep-merge OUT DIR[:tool,tool]...   one sweep directory (threads, cubical or alpha) from several: a rerun
#                                   of some tools replaces their series in an earlier sweep, the last source winning
#                                   (sweep_merge.py)
#   bench/run.sh count-cells [--reference] [--cases a,b] [--verify] [...]   the number of cells of every case's complex,
#                                   by dimension (count_cells.py); --reference writes bench/cells-reference.csv, which
#                                   `summarize` joins: a benchmark run never counts
#   bench/run.sh count-work [--cases a,b] [--tools t,u] [--ties ..] [--orders ..] [...]   the columns each tool reduces and
#                                   skips, and its column additions, from the counting builds `setup` makes of the forks and
#                                   from TDA4j's work mode (count_work.py); never timed, never part of a run
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
#   RIPSER_PATCHED_REPO, RIPSER_PATCHED_REF, RIPSER_PATCHED_VARIANTS, RIPSER_PATCHED[_<NAME>]_SRC, _FLAGS, _INPUT, _COEFF,
#   REDZED_PATCHED_REPO, REDZED_PATCHED_REF, REDZED_PATCHED_VARIANTS, REDZED_PATCHED[_<NAME>]_SRC, _FLAGS; and for the
#   counting builds `count-work` runs: RIPSER_PATCHED[_<NAME>]_COUNT_SRC, _COUNT_FLAGS, _COUNT_PREP (likewise REDZED_PATCHED)
#                  a fork or patched branch of ripser.cpp or RedZeD (a URL or local path; a branch, tag or commit): `setup
#                  ripser redzed` builds it too, and the rows `ripser.cpp-patched` and `redzed-patched` run beside the stock
#                  ones (README, "Forked builds"). Unset: no such rows.
#   RESULTS_DIR    where `run` writes (default: $WORKDIR/results/<timestamp>); bench/final_run.sh names its stages
#   PIP_CONSTRAINTS   a pip constraints file for `setup python` (`bench.py constraints DIR` writes one from a run's
#                  env.json), to install the versions an earlier run measured
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BENCH="$REPO/bench"
WORKDIR="${WORKDIR:-$PWD/bench-work}"
mkdir -p "$WORKDIR"
WORKDIR="$(cd "$WORKDIR" && pwd)"
export WORKDIR  # the sweep scripts put their output in $WORKDIR/sweeps; unexported they wrote to the current directory
VENV="$WORKDIR/venv"
TOOLS="$WORKDIR/tools"
DATA="$WORKDIR/data"
mkdir -p "$TOOLS" "$DATA"

log() { printf '\n=== %s ===\n' "$*"; }

setup_python() {
  log "Python tools (ripser.py, giotto-ph, GUDHI, CubicalRipser, Dionysus 2, OAT) in $VENV"
  if [ ! -x "$VENV/bin/python" ]; then "${PYTHON:-python3}" -m venv "$VENV"; fi
  "$VENV/bin/python" -m pip install --upgrade pip >/dev/null
  # Unpinned unless PIP_CONSTRAINTS names a constraints file: the versions actually installed are recorded in every
  # run's env.json (`bench.py constraints DIR` turns them back into such a file for the paper's final run).
  local constraints=()
  if [ -n "${PIP_CONSTRAINTS:-}" ]; then constraints=(-c "$PIP_CONSTRAINTS"); fi
  for pkg in numpy scipy networkx pynverse matplotlib ripser giotto-ph gudhi cripser dionysus oat_python; do
    "$VENV/bin/python" -m pip install ${constraints[@]+"${constraints[@]}"} "$pkg" \
      || echo "WARNING: could not install $pkg; it will be reported as missing"
  done
}

setup_flashcubical() {
  log "FlashCubical (T-prog123; a C++17 pybind11 extension for 2-D and 3-D arrays) at a pinned commit, into $VENV"
  if [ ! -x "$VENV/bin/python" ]; then echo "WARNING: FlashCubical installs into the Python venv (run setup python first): flashcubical will be reported as missing"; return; fi
  # scikit-build-core builds it with CMake (3.20 or newer) and a C++17 compiler; pip fetches both build requirements.
  # The commit's tarball, so that no git is needed; "Update README.md v2", 2026-06-04.
  "$VENV/bin/python" -m pip install "https://github.com/T-prog123/FlashCubical/archive/eaff91f54a126eed48170cdf1d92fef20537bec9.tar.gz" \
    && "$VENV/bin/python" -c 'import numpy, flash_cubical; print("flash_cubical:", len(flash_cubical.compute(numpy.arange(16.0).reshape(4, 4)).values), "pairs on a 4x4 ramp")' \
    || echo "WARNING: FlashCubical did not build; it will be reported as missing"
}

setup_ripser() {
  log "ripser.cpp (vanilla Z/2 build and the USE_COEFFICIENTS build)"
  if [ ! -d "$TOOLS/ripser" ]; then git clone --depth 1 https://github.com/Ripser/ripser.git "$TOOLS/ripser"; fi
  (cd "$TOOLS/ripser" && make ripser ripser-coeff)
  "$TOOLS/ripser/ripser" --help >/dev/null 2>&1 || true
  setup_forked ripser-patched "${RIPSER_PATCHED_REPO:-}" "${RIPSER_PATCHED_REF:-}"
}

# A fork or patched branch of ripser.cpp or RedZeD, as rows of its own beside the stock one (README, "Forked builds").
#   setup_forked ripser-patched|redzed-patched REPO [REF]
# REPO is a URL or a local path, REF a branch, tag or commit (default: the repository's HEAD). It is cloned into
# $TOOLS/<name>, fetched again at each setup (so a branch that moved is rebuilt), and built exactly as the stock tool is:
# one binary per variant, in $TOOLS/<name>/.bench (the fork's own files are never overwritten). Variants: the names in
# RIPSER_PATCHED_VARIANTS / REDZED_PATCHED_VARIANTS ("forest edge"), each with its own RIPSER_PATCHED_<NAME>_SRC, _FLAGS,
# _INPUT and _COEFF; with no list, one unnamed variant with RIPSER_PATCHED_SRC and so on.
# Each variant also gets a COUNTING build where its source has the counters (`COUNT_WORK`, or the hybrid's own
# instrumentation): `<name>-count`, for `count-work`, never timed. COUNT_SRC (default: SRC), COUNT_FLAGS (default: FLAGS; the
# build adds -D COUNT_WORK) and COUNT_PREP, a shell command run in the repository first that makes the counting source
# (Bauer's hybrid: `python3 -I hybrid/profile.py`, with COUNT_SRC=hybrid/build/pruned-profile.cpp).
# Nothing happens where REPO is empty. A failure leaves no binary: the row is reported as missing, the stock rows stand.
fork_var() { local k="$1_$2"; printf '%s' "${!k:-${3:-}}"; }  # fork_var PREFIX_SUFFIX KEY [DEFAULT]: the variable's value

setup_forked() {
  local name="$1" repo="$2" ref="${3:-}"
  if [ -z "$repo" ]; then return; fi
  local dir="$TOOLS/$name" tool="${1%-patched}"
  local prefix; prefix="$(printf '%s' "$tool" | tr a-z A-Z)_PATCHED"
  log "$name: $repo @ ${ref:-HEAD}"
  rm -rf "$dir/.bench"
  { [ -d "$dir/.git" ] || git clone --quiet "$repo" "$dir"; } \
    && git -C "$dir" fetch --quiet origin "${ref:-HEAD}" \
    && git -C "$dir" checkout --quiet --detach FETCH_HEAD \
    || { echo "WARNING: $name could not be fetched; it will be reported as missing"; return 0; }
  echo "$name: built from $(git -C "$dir" rev-parse HEAD)"
  mkdir -p "$dir/.bench"
  local list="${prefix}_VARIANTS" variant
  for variant in ${!list:--}; do
    local s="" f=""  # s: the variable names' suffix, f: the binary's
    if [ "$variant" != - ]; then s="_$(printf '%s' "$variant" | tr a-z- A-Z_)"; f="-$variant"; fi
    local src flags input
    flags="$(fork_var "$prefix$s" FLAGS)"
    case "$tool" in
      ripser)
        # Compiled as the stock Makefile does (c++ -std=c++11 -Wall -O3 -D NDEBUG), whatever the fork's own Makefile says: the
        # fork names its source file and flags, relative to the repository. INPUT=binary is for a code that reads only the
        # binary lower triangle (--format binary, as ripser-forest does); COEFF=0 for one that is Z/2 only.
        local cxx="${CXX:-c++}" out="$dir/.bench/ripser$f"
        src="$(fork_var "$prefix$s" SRC ripser.cpp)"; input="$(fork_var "$prefix$s" INPUT text)"
        (cd "$dir" && $cxx -std=c++11 -Wall -O3 -D NDEBUG $flags "$src" -o "$out") \
          || { echo "WARNING: $name${f} did not build; it will be reported as missing"; continue; }
        if [ "$(fork_var "$prefix$s" COEFF 1)" != 0 ]; then
          (cd "$dir" && $cxx -std=c++11 -Wall -O3 -D NDEBUG -D USE_COEFFICIENTS $flags "$src" -o "$out-coeff") \
            || echo "WARNING: $name${f} has no coefficient build: its row runs over Z/2 only"
        fi
        local smoke=(--dim 1 --format point-cloud) smoke_input="$BENCH/smoke-points.txt"
        if [ "$input" = binary ]; then
          smoke_input="$(mktemp)"
          python3 -I - "$BENCH/smoke-points.txt" "$smoke_input" <<'PY'
import math, struct, sys
pts = [[float(x) for x in line.split()] for line in open(sys.argv[1]) if line.strip() and not line.startswith("#")]
with open(sys.argv[2], "wb") as out:
    for i in range(len(pts)):
        for j in range(i):
            out.write(struct.pack("<f", math.dist(pts[i], pts[j])))
PY
          smoke=(--dim 1 --format binary)
        fi
        if "$out" "$smoke_input" "${smoke[@]}" >/dev/null; then
          echo "$variant $input" >> "$dir/.bench/variants"
          # the counting builds: the same compile with -D COUNT_WORK, where the source counts; the smoke test is the same
          local csrc cflags cprep
          csrc="$(fork_var "$prefix$s" COUNT_SRC "$src")"; cflags="$(fork_var "$prefix$s" COUNT_FLAGS "$flags")"
          cprep="$(fork_var "$prefix$s" COUNT_PREP)"
          if [ -n "$cprep" ]; then (cd "$dir" && bash -c "$cprep") || echo "WARNING: $name${f}: COUNT_PREP failed"; fi
          if [ -f "$dir/$csrc" ] && grep -q 'COUNT_WORK' "$dir/$csrc"; then
            (cd "$dir" && $cxx -std=c++11 -Wall -O3 -D NDEBUG $cflags -D COUNT_WORK "$csrc" -o "$out-count") \
              || echo "WARNING: $name${f} has no counting build"
            if [ -x "$out-count" ] && ! "$out-count" "$smoke_input" "${smoke[@]}" >/dev/null 2>&1; then
              echo "WARNING: $name${f}: the counting build failed its smoke test"; rm -f "$out-count"
            fi
            if [ -x "$out-count" ] && [ -x "$out-coeff" ]; then
              (cd "$dir" && $cxx -std=c++11 -Wall -O3 -D NDEBUG -D USE_COEFFICIENTS $cflags -D COUNT_WORK "$csrc" -o "$out-coeff-count") \
                || echo "WARNING: $name${f} has no coefficient counting build"
            fi
          else
            echo "$name${f}: $csrc has no COUNT_WORK counters: no counting build (set ${prefix}${s}_COUNT_SRC for another source)"
          fi
        else
          echo "WARNING: $name${f} failed its smoke test (input $input); it will be reported as missing"
          rm -f "$out" "$out-coeff"
        fi
        if [ "$input" = binary ]; then rm -f "$smoke_input"; fi
        ;;
      redzed)
        # as the stock one is built; the source file and extra flags (a later -std wins) are the fork's, relative to the repository
        src="$(fork_var "$prefix$s" SRC python/cpp/redzed.cpp)"
        if (cd "$dir" && g++ -O3 -std=c++17 -DNDEBUG $flags -o "$dir/.bench/redzed$f" "$src"); then
          echo "$variant -" >> "$dir/.bench/variants"
          # the counting build: a source that counts (COUNT_SRC, made by COUNT_PREP; default SRC when it has the counters)
          local csrc cflags cprep
          csrc="$(fork_var "$prefix$s" COUNT_SRC "$src")"; cflags="$(fork_var "$prefix$s" COUNT_FLAGS "$flags")"
          cprep="$(fork_var "$prefix$s" COUNT_PREP)"
          if [ -n "$cprep" ]; then (cd "$dir" && bash -c "$cprep") || echo "WARNING: $name${f}: COUNT_PREP failed"; fi
          if [ -f "$dir/$csrc" ] && grep -q 'COUNT_WORK\|struct WorkCounters' "$dir/$csrc"; then
            (cd "$dir" && g++ -O3 -std=c++17 -DNDEBUG $cflags -D COUNT_WORK -o "$dir/.bench/redzed$f-count" "$csrc") \
              || echo "WARNING: $name${f} has no counting build"
          else
            echo "$name${f}: $csrc has no counters: no counting build (set ${prefix}${s}_COUNT_SRC for another source)"
          fi
        else
          echo "WARNING: $name${f} did not build; it will be reported as missing"
        fi
        ;;
    esac
  done
  return 0  # a fork that failed is reported as missing; it never stops the setup of the others
}

# The variables the harness reads for the forks `setup_forked` built: <PREFIX>[_<NAME>]_BIN, _COEFF_BIN, _COUNT_BIN,
# _COEFF_COUNT_BIN, _INPUT, and the names.
export_forks() {
  local tool="$1" dir="$TOOLS/$1-patched" names="" v input s f
  local prefix; prefix="$(printf '%s' "$tool" | tr a-z A-Z)_PATCHED"
  unset "${prefix}_VARIANTS"
  [ -s "$dir/.bench/variants" ] || return 0
  export "${prefix}_DIR=$dir"
  while read -r v input; do
    s=""; f=""
    if [ "$v" != - ]; then s="_$(printf '%s' "$v" | tr a-z- A-Z_)"; f="-$v"; names="$names $v"; fi
    if [ -x "$dir/.bench/$tool$f" ]; then export "${prefix}${s}_BIN=$dir/.bench/$tool$f"; fi
    if [ -x "$dir/.bench/$tool$f-coeff" ]; then export "${prefix}${s}_COEFF_BIN=$dir/.bench/$tool$f-coeff"; fi
    if [ -x "$dir/.bench/$tool$f-count" ]; then export "${prefix}${s}_COUNT_BIN=$dir/.bench/$tool$f-count"; fi
    if [ -x "$dir/.bench/$tool$f-coeff-count" ]; then export "${prefix}${s}_COEFF_COUNT_BIN=$dir/.bench/$tool$f-coeff-count"; fi
    if [ "$input" != - ]; then export "${prefix}${s}_INPUT=$input"; fi
  done < "$dir/.bench/variants"
  if [ -n "$names" ]; then export "${prefix}_VARIANTS=${names# }"; fi
}

setup_redzed() {
  log "RedZeD (Kapulkin and Kershaw; C++17, Z/2) at a pinned commit"
  if ! command -v g++ >/dev/null; then echo "WARNING: RedZeD needs a C++17 compiler: redzed will be reported as missing"; return; fi
  if [ ! -d "$TOOLS/redzed" ]; then git clone https://github.com/nkershaw01/RedZeD.git "$TOOLS/redzed"; fi
  # "Updated readme", 2026-10-30; the repository has no tags or releases. The flags are the README's, with no -march=native,
  # as for ripser.cpp's Makefile.
  (cd "$TOOLS/redzed" && git checkout --quiet 22ef1bf8696bccad02b041a83863d12d4bd92766) \
    && g++ -O3 -std=c++17 -DNDEBUG -o "$TOOLS/redzed/redzed" "$TOOLS/redzed/python/cpp/redzed.cpp" \
    || { echo "WARNING: RedZeD did not build; it will be reported as missing"; return; }
  setup_forked redzed-patched "${REDZED_PATCHED_REPO:-}" "${REDZED_PATCHED_REF:-}"
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

setup_dowker() {
  log "Dowker implementations: Blaser-Brun's dowker_homology and Yoon's Dowker persistence, cloned at pinned commits"
  # dowker_homology does not build with a current setuptools (its setup.py asks for install_layout), so it is used from
  # the clone; py_worker.dowker_nerves_tool shims what current Python removed. Its networkx/pynverse/matplotlib come
  # from setup_python above.
  if [ ! -d "$TOOLS/Sparse-Dowker-Nerves" ]; then
    git clone https://github.com/mbr085/Sparse-Dowker-Nerves "$TOOLS/Sparse-Dowker-Nerves" \
      && git -C "$TOOLS/Sparse-Dowker-Nerves" checkout 6dcb5f5a5563922966bc6d9af68e14bc8c3d33b5 \
      || echo "WARNING: dowker_homology could not be cloned; it will be reported as missing"
  fi
  if [ ! -d "$TOOLS/Dowker_persistence" ]; then
    git clone https://github.com/irishryoon/Dowker_persistence "$TOOLS/Dowker_persistence" \
      && git -C "$TOOLS/Dowker_persistence" checkout 35297429115c97cf06040d9af5edd47a2cc9a9c0 \
      || echo "WARNING: Dowker_persistence could not be cloned; it will be reported as missing"
  fi
  local julia="${JULIA:-$(command -v julia || true)}"
  if [ -n "$julia" ] && [ -d "$TOOLS/Dowker_persistence" ]; then
    # Its Manifest.toml is from Julia 1.7.2 and cannot be instantiated on a current Julia (MbedTLS_jll is no longer a
    # stdlib: "Missing source file"). The Project.toml's compat is open, so the tracked manifest is deleted once and
    # Pkg resolves afresh (Plots, IJulia and Interact come with it, a few minutes of precompilation); a manifest
    # Pkg wrote since differs from the tracked one and stays. If the resolve fails the row is reported as missing and
    # the Python rows are unaffected.
    if git -C "$TOOLS/Dowker_persistence" diff --quiet -- Manifest.toml 2>/dev/null; then
      rm -f "$TOOLS/Dowker_persistence/Manifest.toml"
    fi
    if "$julia" --project="$TOOLS/Dowker_persistence" -e 'import Pkg; Pkg.resolve(); Pkg.precompile()' \
       && DOWKER_YOON_PROJECT="$TOOLS/Dowker_persistence" "$julia" --project="$TOOLS/Dowker_persistence" "$BENCH/workers/dowker_yoon_worker.jl" \
            task=dowker input="$DATA/relation_60x200.txt" dim=1 p=2 threshold=0.5 warmup=0 trials=1 >/dev/null; then
      echo "$julia" > "$TOOLS/dowker_yoon.path"
    else
      echo "WARNING: Dowker_persistence setup failed; it will be reported as missing"; rm -f "$TOOLS/dowker_yoon.path"
    fi
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
  rm -f "$TOOLS/tda4j-js.git"
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
  # The commit the package was built from (first line), then the tracked files that differed from it: env.json records
  # it as tda4j_js_git, and the summaries name it where it is not the JVM classes' commit.
  { git -C "$REPO" rev-parse HEAD && git -C "$REPO" diff --name-only HEAD; } > "$TOOLS/tda4j-js.git" 2>/dev/null \
    || rm -f "$TOOLS/tda4j-js.git"
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
    if [ -n "${TDA4J_JS:-}" ] && [ -s "$TOOLS/tda4j-js.git" ]; then export TDA4J_JS_GIT="$TOOLS/tda4j-js.git"; fi
  fi
  export RIPSER_BIN="$TOOLS/ripser/ripser"
  export RIPSER_COEFF_BIN="$TOOLS/ripser/ripser-coeff"
  [ -x "$RIPSER_BIN" ] || unset RIPSER_BIN
  [ -x "$RIPSER_COEFF_BIN" ] || unset RIPSER_COEFF_BIN
  export REDZED_BIN="$TOOLS/redzed/redzed"
  [ -x "$REDZED_BIN" ] || unset REDZED_BIN
  # the forked builds exist only where `setup` was given RIPSER_PATCHED_REPO / REDZED_PATCHED_REPO; their rows are "missing" otherwise
  export_forks ripser
  export_forks redzed
  if [ -s "$TOOLS/julia.path" ]; then
    export JULIA="$(cat "$TOOLS/julia.path")" RIPSERER_PROJECT="$TOOLS/julia"
  fi
  if [ -s "$TOOLS/eirene.path" ]; then
    export JULIA="${JULIA:-$(cat "$TOOLS/eirene.path")}" EIRENE_PROJECT="$TOOLS/eirene"
  fi
  [ -d "$TOOLS/Sparse-Dowker-Nerves" ] && export DOWKER_HOMOLOGY_PATH="$TOOLS/Sparse-Dowker-Nerves"
  if [ -s "$TOOLS/dowker_yoon.path" ]; then
    export JULIA="${JULIA:-$(cat "$TOOLS/dowker_yoon.path")}" DOWKER_YOON_PROJECT="$TOOLS/Dowker_persistence"
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
    if [ ${#components[@]} -eq 0 ]; then components=(python flashcubical tda4j tda4j-js ripser redzed julia javaplex dipha phat eirene); fi
    for component in "${components[@]}"; do
      case "$component" in
        python) setup_python ;;
        flashcubical) setup_flashcubical ;;
        tda4j) setup_tda4j ;;
        tda4j-js) setup_tda4j_js ;;
        ripser) setup_ripser ;;
        redzed) setup_redzed ;;
        julia) setup_julia ;;
        javaplex) setup_javaplex ;;
        dipha) setup_dipha ;;
        phat) setup_phat ;;
        eirene) setup_eirene ;;
        dowker) setup_dowker ;;
        *) echo "unknown component '$component' (python flashcubical tda4j tda4j-js ripser redzed julia javaplex dipha phat eirene dowker)"; exit 2 ;;
      esac
    done
    ;;
  prepare) "$VENV/bin/python" "$BENCH/bench.py" prepare --data "$DATA" ;;
  run)
    export_env
    RESULTS="${RESULTS_DIR:-$WORKDIR/results/$(date +%Y%m%d-%H%M%S)}"
    echo "results -> $RESULTS"
    "$VENV/bin/python" "$BENCH/bench.py" run --data "$DATA" --out "$RESULTS" --python "$VENV/bin/python" "$@"
    ;;
  summarize) "$VENV/bin/python" "$BENCH/bench.py" summarize "$@" ;;
  merge) "$VENV/bin/python" "$BENCH/bench.py" merge "$@" ;;
  anchors) "$VENV/bin/python" "$BENCH/bench.py" anchors "$@" ;;
  constraints) "$VENV/bin/python" "$BENCH/bench.py" constraints "$@" ;;
  check) "$VENV/bin/python" "$BENCH/bench.py" check "$@" ;;
  sweep-cubical)
    export_env
    "$VENV/bin/python" "$BENCH/sweep_cubical.py" --data "$DATA" --python "$VENV/bin/python" "$@"
    ;;
  sweep-alpha)
    export_env
    "$VENV/bin/python" "$BENCH/sweep_alpha.py" --data "$DATA" --python "$VENV/bin/python" "$@"
    ;;
  sweep-threads)
    export_env
    "$VENV/bin/python" "$BENCH/sweep_threads.py" --data "$DATA" --python "$VENV/bin/python" "$@"
    ;;
  sweep-merge) "$VENV/bin/python" "$BENCH/sweep_merge.py" "$@" ;;
  count-cells)
    export_env
    RESULTS="$WORKDIR/results/cells-$(date +%Y%m%d-%H%M%S)"
    echo "results -> $RESULTS"
    "$VENV/bin/python" "$BENCH/count_cells.py" --data "$DATA" --out "$RESULTS" --python "$VENV/bin/python" "$@"
    ;;
  count-work)
    export_env
    RESULTS="$WORKDIR/results/work-$(date +%Y%m%d-%H%M%S)"
    echo "results -> $RESULTS"
    "$VENV/bin/python" "$BENCH/count_work.py" --data "$DATA" --out "$RESULTS" --python "$VENV/bin/python" "$@"
    ;;
  all)
    "$0" setup
    "$0" prepare
    "$0" run "$@"
    ;;
  *) echo "usage: $0 setup [tool...]|prepare|run|all|summarize|merge|anchors|check|constraints|sweep-cubical|sweep-alpha|sweep-threads|sweep-merge|count-cells|count-work [options]"; exit 2 ;;
esac

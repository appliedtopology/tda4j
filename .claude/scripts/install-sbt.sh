#!/usr/bin/env bash
# Bootstraps a working `sbt` in a container that has neither the launcher nor a warm
# dependency cache -- this is the exact situation the 2026-09-26 toroidal-coordinates session
# hit (`.claude/WORKLOG-toroidal-coordinates.md`'s own environment note): no `sbt` on PATH at
# all, and Maven Central (through this environment's proxy) throttles the resulting cold-cache
# burst of parallel dependency fetches with `429`s aggressively enough that a single plain `sbt
# compile` reliably fails. A solo `curl` of an individual repeatedly-`429`d artifact succeeds
# immediately -- this is burst/concurrency throttling, not a real block -- so the fix is patient,
# paced, externally-driven retries whose successes accumulate in the local cache across attempts,
# not a smarter single invocation.
#
# Meant to be pasted into (or sourced from) this environment's own Setup script (cloud
# environment menu -> Edit -> Setup script) so future sessions never pay this cost cold.
# Idempotent: re-running skips the launcher download if it's already present, and each retry
# picks up from whatever the previous one already cached, so it's safe to re-run after a
# partial/interrupted run.
#
# Usage:
#   .claude/scripts/install-sbt.sh [repo-dir]
#
# repo-dir defaults to the current directory; it only matters for the final warm-up step
# (`sbt compile`), which needs to run from a real sbt project to actually exercise (and warm)
# this project's own library-dependency resolution, not just the sbt launcher itself.
#
# Env vars (optional):
#   SBT_VERSION      sbt version to install (default: 1.12.11 -- must match project/build.properties)
#   INSTALL_DIR      where sbt-launch.jar lives (default: /opt/sbt-launcher)
#   WRAPPER_PATH     where the `sbt` wrapper script is installed (default: /usr/local/bin/sbt)
#   MAX_ATTEMPTS     retry attempts for the final warm-up compile (default: 20)
#   RETRY_SLEEP      seconds to sleep between warm-up attempts (default: 20 -- shorter risks
#                     re-hitting the same rate-limit window before it resets)
#   ATTEMPT_TIMEOUT  per-attempt timeout in seconds (default: 90)

set -euo pipefail

SBT_VERSION="${SBT_VERSION:-1.12.11}"
INSTALL_DIR="${INSTALL_DIR:-/opt/sbt-launcher}"
WRAPPER_PATH="${WRAPPER_PATH:-/usr/local/bin/sbt}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-20}"
RETRY_SLEEP="${RETRY_SLEEP:-20}"
ATTEMPT_TIMEOUT="${ATTEMPT_TIMEOUT:-90}"
REPO_DIR="${1:-$(pwd)}"

echo "install-sbt.sh: installing sbt ${SBT_VERSION} launcher to ${INSTALL_DIR}"
mkdir -p "${INSTALL_DIR}"

if [ ! -s "${INSTALL_DIR}/sbt-launch.jar" ]; then
  curl -fsSL -o "${INSTALL_DIR}/sbt-launch.jar" \
    "https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch/${SBT_VERSION}/sbt-launch-${SBT_VERSION}.jar"
else
  echo "install-sbt.sh: sbt-launch.jar already present, skipping download"
fi

cat > "${WRAPPER_PATH}" <<EOF
#!/bin/bash
exec java -Xmx2G -jar ${INSTALL_DIR}/sbt-launch.jar "\$@"
EOF
chmod +x "${WRAPPER_PATH}"
echo "install-sbt.sh: wrapper installed at ${WRAPPER_PATH}"

# Warm-up: bootstraps the sbt launcher itself (project/plugins.sbt's own dependency graph --
# Laika alone pulls in a large flexmark/jackson/netty tree) AND this project's own
# libraryDependencies (commons-math3, specs2, jvptree, scallop, ...) in one pass, so a later
# `sbt test`/`sbt compile` in the actual session starts from a fully warm cache. Deliberately an
# EXTERNAL retry loop, not sbt's own interactive "(r)etry/(q)uit" project-loading prompt -- this
# script has no TTY to answer that prompt from, and a closed/empty stdin is not guaranteed to
# behave like pressing enter (the prompt's own default). Each attempt's successes are cached by
# coursier/ivy regardless of whether the attempt as a whole succeeds, so this converges.
cd "${REPO_DIR}"
echo "install-sbt.sh: warming sbt + this project's dependency cache from ${REPO_DIR} (up to ${MAX_ATTEMPTS} attempts)"
for attempt in $(seq 1 "${MAX_ATTEMPTS}"); do
  echo "install-sbt.sh: attempt ${attempt}/${MAX_ATTEMPTS}"
  if timeout "${ATTEMPT_TIMEOUT}" "${WRAPPER_PATH}" compile < /dev/null; then
    echo "install-sbt.sh: sbt compile succeeded -- cache is warm"
    exit 0
  fi
  sleep "${RETRY_SLEEP}"
done

echo "install-sbt.sh: gave up after ${MAX_ATTEMPTS} attempts -- sbt itself is installed at" \
  "${WRAPPER_PATH}, but the dependency cache may still be cold; a session's own first" \
  "'sbt compile'/'sbt test' will keep converging using the same paced-retry approach." >&2
exit 1

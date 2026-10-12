#!/bin/bash
# The cloud environment's Setup script for tda4j (cloud environment menu in the session's title bar -> Edit ->
# Setup script): paste this file's contents there. New sessions then start with sbt's dependency cache warm, instead
# of spending their first hour on Maven Central's 429 rate limits (.claude/WORKLOG-nan-mask.md, environment notes).
#
# Unverified (2026-10-12): whether the repository is already cloned when the setup script runs. If it is not, this
# prints the "not checked out yet" line below and does nothing; sbt then warms on first use, as before.
REPO=/home/user/tda4j
if [ -x "$REPO/.claude/scripts/install-sbt.sh" ]; then
  # The sbt launcher, sbt 2 itself, the plugins and the main dependencies (a paced retry loop around `sbt compile`).
  "$REPO/.claude/scripts/install-sbt.sh" "$REPO" || true
  # install-sbt.sh warms `compile` only: also the test-scope dependencies (specs2, scalacheck) and the scalafmt the sbt
  # plugin downloads on first use.
  cd "$REPO" && for i in 1 2 3 4 5; do
    timeout 300 sbt "Test/update" scalafmtSbtCheck < /dev/null && break
    sleep 20
  done || true
else
  echo "setup: $REPO not checked out yet; sbt will warm on first use" >&2
fi

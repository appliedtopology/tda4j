#!/usr/bin/env bash
# SessionStart hook (.claude/settings.json). In a cloud session (CLAUDE_CODE_REMOTE set): install the native scalafmt and
# turn on the versioned pre-commit format check (.githooks/pre-commit), so Claude's commits are checked like CI's lint
# job. A local session leaves the clone's git config alone: each developer opts in themselves (CONTRIBUTING.md).
[ -n "${CLAUDE_CODE_REMOTE:-}" ] || exit 0
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
if ! .claude/scripts/install-scalafmt.sh > /dev/null 2>&1; then
  echo "session-start: the native scalafmt could not be installed, so .githooks/pre-commit will not check formatting." >&2
fi
git config core.hooksPath .githooks
echo "Commits in this clone run .githooks/pre-commit (a scalafmt check of the staged files; it never starts sbt)."
exit 0

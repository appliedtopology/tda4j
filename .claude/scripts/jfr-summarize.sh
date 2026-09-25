#!/usr/bin/env bash
# Reduces a JFR recording to a small, pasteable text summary -- never writes the raw jdk.ExecutionSample/
# jdk.ObjectAllocationSample event stream to disk at all (piped straight from `jfr print` into an awk
# aggregator), so there's no large intermediate file to accidentally `git add`, and nothing here belongs in
# the repo regardless of size -- these are local analysis artifacts, keep the .jfr and this summary wherever
# is convenient on the machine that ran the profile, outside the git working tree if you want to be safe, and
# paste ONLY this script's own output back into the conversation.
#
# Produces the same shape of table every profiling session in .claude/WORKLOG-ripser-profiling.md built by
# hand: top CPU leaf frames by sample count (the method actually executing, not every caller waiting on it --
# see that worklog's own "leaf-frame numbers are what 'time spent actually computing this' means" note), and
# top allocation sources both by class and by allocating call site, weighted by sampled bytes (not event
# count -- an allocation profile weighted by count alone hides that one class's instances might be 10x the
# size of another's).
#
# Usage:
#   .claude/scripts/jfr-summarize.sh <recording.jfr> [topN]
#
#   topN: how many rows to print per table (default 40)
#
# Output: plain text to stdout, typically well under 100 lines total regardless of how long the recording is
# or how many samples it contains -- redirect to a file if you want, but it's meant to be pasted directly.

set -euo pipefail

JFR_FILE="${1:?path to .jfr recording}"
TOP_N="${2:-40}"

if ! command -v jfr >/dev/null 2>&1; then
  echo "jfr not found on PATH -- it ships with the JDK (\$JAVA_HOME/bin/jfr); add that to PATH or invoke it directly." >&2
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "=== jfr summary (event counts, recording duration) ==="
jfr summary "$JFR_FILE" | grep -E "^ (Version|Chunks|Start|Duration):" || true
echo

# Single pass over BOTH event types via one `jfr print` call (cheaper than two passes over what can be a very
# large recording). Emits tagged, tab-separated rows to a scratch file -- never the raw per-event text --
# which the shell then sorts/heads per category below.
jfr print --events jdk.ExecutionSample,jdk.ObjectAllocationSample --stack-depth 1 "$JFR_FILE" | awk -v OFS='\t' '
  /^jdk\.ExecutionSample/       { ev = "cpu";   cls = ""; wt = 0; state = 1; next }
  /^jdk\.ObjectAllocationSample/{ ev = "alloc"; cls = ""; wt = 0; state = 1; next }
  state == 1 && /objectClass = / {
    line = $0
    sub(/^[ \t]*objectClass = /, "", line)
    sub(/[ \t]*\(classLoader.*/, "", line)
    cls = line
    next
  }
  state == 1 && /weight = / {
    line = $0
    sub(/^[ \t]*weight = /, "", line)
    gsub(/[^0-9]/, "", line)
    wt = line + 0
    next
  }
  state == 1 && /stackTrace = \[/ { state = 2; next }
  state == 2 {
    line = $0
    gsub(/^[ \t]+|[ \t]+$/, "", line)
    if (line != "" && line !~ /^\]/) {
      if (ev == "cpu") print "CPU_LEAF", 1, line
      else {
        print "ALLOC_SITE", wt, line
        if (cls != "") print "ALLOC_CLASS", wt, cls
      }
      state = 0
    }
    next
  }
' > "$WORK/tagged.tsv"

report() {
  local tag="$1" label="$2" unit="$3"
  local total
  total=$(awk -F'\t' -v t="$tag" '$1==t {s+=$2} END {print s+0}' "$WORK/tagged.tsv")
  echo "=== $label (top $TOP_N, total $unit = $total) ==="
  if [ "$total" -eq 0 ]; then
    echo "(no $tag events found -- check the recording actually captured this event type)"
    echo
    return
  fi
  awk -F'\t' -v t="$tag" '$1==t {a[$3]+=$2} END {for (k in a) print a[k]"\t"k}' "$WORK/tagged.tsv" \
    | sort -rn \
    | head -n "$TOP_N" \
    | awk -F'\t' -v tot="$total" '{printf "%6.2f%%  %10d  %s\n", ($1/tot)*100, $1, $2}'
  echo
}

report CPU_LEAF   "CPU leaf frames"                 "samples"
report ALLOC_SITE "Allocation by call site (leaf)"   "bytes"
report ALLOC_CLASS "Allocation by class"             "bytes"

echo "=== Done. This whole output is what to send back -- not the .jfr file itself. ==="

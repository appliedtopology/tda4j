#!/usr/bin/env bash
# The fixed grid, one JVM per core, three at a time; a log per core, line-buffered, 900 s timeout each.
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$here/grid"
for k in 1 2 3 5 8; do for T in 5 10 20; do echo "$k $T"; done; done | \
  xargs -P3 -L1 bash -c 'timeout 900 '"$here"'/run.sh klein_one -Dk=$0 -DT=$1 > '"$here"'/grid/k$0_T$1.log 2>&1'
echo GRID DONE > "$here/grid/DONE"

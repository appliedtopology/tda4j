#!/usr/bin/env bash
# usage: sub_sweep.sh LABEL kind file nsub param cap top p [kind file nsub param cap top p ...]  -- one JVM per case, 240 s timeout
here=$(cd "$(dirname "$0")" && pwd)
label=$1; shift
log="$here/subsweep-$label.log"; : > "$log"
while [ $# -ge 7 ]; do
  kind=$1 file=$2 nsub=$3 param=$4 cap=$5 top=$6 p=$7; shift 7
  echo "##### $kind $file nsub=$nsub param=$param cap=$cap top=$top p=$p" >> "$log"
  start=$(date +%s)
  timeout 240 "${here}/run.sh" rips_sub -Dkind=$kind -Dfile=$file -Dnsub=$nsub -Dparam=$param -Dcap=$cap -Dtop=$top -Dp=$p -Xmx${HEAP:-6g} 2>&1 \
    | grep --line-buffered -v -E '^(Picked|TDA4j|  scala\.lang|Try|Welcome|Type in|def |\(|List|Double\)|upTo|divisor|scala>|\[wall\])' >> "$log"
  echo "##### exit=${PIPESTATUS[0]} wall=$(( $(date +%s) - start )) s" >> "$log"
done
echo "SWEEP DONE" >> "$log"

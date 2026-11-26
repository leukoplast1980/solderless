#!/bin/bash
# Runs PerfProbeTest and records a simpleperf profile of the app during some
# of its phases (the test logs "START <phase>" when a phase begins).
set -u
PKG=io.github.leukoplast1980.solderless.debug
adb shell am instrument -w -e class io.github.leukoplast1980.solderless.PerfProbeTest $PKG.test/androidx.test.runner.AndroidJUnitRunner > probe/instrument.txt 2>&1 &
IPID=$!
for phase in idle-console busy-editor busy-board busy-chip-Registers gpio-console print-console; do
  for i in $(seq 1 400); do
    adb logcat -d -s PerfProbe:I | grep -q "START $phase\$" && break
    kill -0 $IPID 2>/dev/null || break
    sleep 1
  done
  adb shell simpleperf record --app $PKG -g --duration 6 -o /data/local/tmp/$phase.data > probe/sp-$phase.log 2>&1
  adb shell simpleperf report -i /data/local/tmp/$phase.data --sort comm,symbol --percent-limit 0.7 > probe/sp-$phase.txt 2>&1
  adb shell simpleperf report -i /data/local/tmp/$phase.data --children --sort symbol --percent-limit 3 > probe/sp-$phase-children.txt 2>&1
done
wait $IPID
cat probe/instrument.txt

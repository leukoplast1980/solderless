#!/bin/bash
# Runs PerfProbeTest in the emulator (see the Perf probe workflow). With
# PROFILE=1 it also records a simpleperf profile of the app during some of
# its phases (the test logs "START <phase>" when a phase begins). The
# profiler slows the app down: compare numbers only between runs made the
# same way.
set -u
PKG=io.github.leukoplast1980.solderless.debug
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p probe
adb shell am instrument -w -e class io.github.leukoplast1980.solderless.PerfProbeTest $RUNNER > probe/instrument.txt 2>&1 &
IPID=$!
if [ "${PROFILE:-0}" = 1 ]; then
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
fi
wait $IPID
cat probe/instrument.txt
grep -q "^OK (" probe/instrument.txt

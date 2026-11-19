#!/bin/sh
# Runs each tests/stellaris/*.fs through the emulator (deterministic mode)
# and compares the transcript with the .expected file next to it.
#   run.sh SLEMU FIRMWARE [--update]
set -u
slemu=$1 fw=$2 update=${3:-}
dir=$(dirname "$0")
fail=0
for t in "$dir"/*.fs; do
    name=$(basename "$t" .fs)
    out=$("$slemu" -d -q -k -s "$t" "$fw" 2>&1)
    if [ "$update" = "--update" ]; then
        printf '%s\n' "$out" > "$dir/$name.expected"
        echo "updated $name"
    elif printf '%s\n' "$out" | diff -u "$dir/$name.expected" - > /tmp/sl_$name.diff; then
        echo "ok      $name"
    else
        echo "FAILED  $name"
        cat /tmp/sl_$name.diff
        fail=1
    fi
done
exit $fail

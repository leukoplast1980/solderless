#!/bin/sh
# Builds the emulator for the host and runs every test.
#   tests/run_host_tests.sh [build-dir]
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
build=${1:-$root/build/host}
cmake -S "$root/emu" -B "$build" -G Ninja -DCMAKE_BUILD_TYPE=Release >/dev/null
ninja -C "$build"
fw=$root/firmware/stellaris/mecrisp-stellaris-stm32g431kb.bin

echo "== exception model"
"$build/exc_test" "$build/exc_test.bin"

echo "== instruction set against Unicorn"
if python3 -I -c "import unicorn" 2>/dev/null; then
    python3 -I "$root/tests/cpu/diff_test.py" "$build/libcmtest.so" --cases 5000 --seed "${SEED:-1}"
elif [ -n "$REQUIRE_UNICORN" ]; then
    echo "python3 module 'unicorn' not installed" >&2
    exit 1
else
    echo "skipped: python3 module 'unicorn' not installed"
fi

echo "== Mecrisp-Stellaris transcripts"
"$root/tests/stellaris/run.sh" "$build/slemu" "$fw"

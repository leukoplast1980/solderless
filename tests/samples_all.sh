#!/bin/sh
# Runs the sample code of every part, in every setting, on every pin the app
# offers (written by SamplesTest.everyPinHasASample to app/build/samples/all)
# on the emulator with the parts attached, and prints what each did:
#   ./gradlew testDebugUnitTest --tests '*SamplesTest*'
#   tests/samples_all.sh build/host/slemu firmware/stellaris/mecrisp-stellaris-stm32g431kb.bin
# Lines starting with PROBLEM are errors or chip messages. The rest, counted
# per setting, show the pins after the sample's "Try it" line.
set -u
slemu=$1 fw=$2 dir=${3:-app/build/samples/all}
out=$(mktemp -d)
one() {
    f=$1; b=$(basename "$f" .fs); v=${b%%_*}
    pins=$(echo "$b" | sed 's/^v[0-9]*_[a-z0-9]*_//' | tr '-' ' ')
    try=$(grep '^\\ Try it: ' "$f" | sed 's/^\\ Try it: //; s/   or   .*//; s/ (.*//')
    {
        i=1
        for p in $pins; do
            case $v in
                v0|v2|v4) echo "#!attach $i led $p r=270 vf=1900";;
                v1|v3|v5) echo "#!attach $i led $p r=270 vf=1900 vcc";;
                v6|v7) echo "#!attach $i switch $p";;
                v8) echo "#!attach $i switch $p vcc"; echo "#!attach 9 pull $p";;
                v9) echo "#!attach $i pot $p"; echo "#!input $i 300";;
                v13) [ $i = 1 ] && echo "#!attach 1 oled $(echo $pins | tr ' ' '\n' | head -1) $(echo $pins | tr ' ' '\n' | tail -1)";;
                v14) [ $i = 1 ] && echo "#!attach 1 flash $pins";;
                v15) [ $i = 1 ] && echo "#!attach 1 matrix $pins chips=4";;
                v16) [ $i = 1 ] && echo "#!attach 1 tft $pins";;
                v17) [ $i = 1 ] && echo "#!attach 1 bmp280 $pins";;
                v18) [ $i = 1 ] && echo "#!attach 1 scd41 $pins";;
                *) echo "#!attach $i audio $p";;
            esac
            i=$((i + 1))
        done
        cat "$f"; echo "$try"; echo "10 ms"; echo "#!pins"; echo "10 ms"; echo "#!pins"
        [ $v = v13 ] && echo "#!oled 1"
    } > "$out/$b.in"
    timeout 60 "$slemu" -d -k -t 20000 -s "$out/$b.in" "$fw" > "$out/$b.txt" 2>&1
    bad=$(grep -E "not found|^line [0-9]+:|Stack|\[chip\]|timeout|locked" "$out/$b.txt" | grep -v "MAX7219: the module runs from 5 V" | head -3)
    [ -n "$bad" ] && echo "PROBLEM $b: $bad"
    res=$(grep -F "$try" "$out/$b.txt" | tail -1 | sed 's/.* \([-0-9]*\)  ok\.$/\1/; s/.*  ok\.$//')
    st=$(awk '/^#!pins/{n++} n==2' "$out/$b.txt" | grep -E "^P[A-G][0-9]" |
        sed -E 's/ +/ /g; s/^P[A-G][0-9]+ //; s/LED [0-9]: [0-9.]+ mA//' | sort -u | tr '\n' ';')
    echo "$v|$res|$st"
}
for f in "$dir"/*.fs; do one "$f"; done | sort | uniq -c
rm -rf "$out"

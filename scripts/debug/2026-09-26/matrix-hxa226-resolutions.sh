#!/usr/bin/env bash
# HXA-226 domain 4/5 — resolution x font-scale matrix on the real device.
#
# Captures, per combination: the session list, the navigation drawer, and the empty
# conversation composer. 412dp is the emulator's native width (1080px @ 420dpi);
# 320/360dp are produced with a `wm size` override at the same density.
#
# Usage: matrix.sh <out-dir>
set -uo pipefail
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
S=emulator-5554
PKG=com.helix.agent
OUT="${1:?out dir required}"
mkdir -p "$OUT"

dump() { adb -s $S shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb -s $S exec-out cat /sdcard/ui.xml; }

tap() {
  local target="$1" b
  b=$(dump | python3 -c "
import sys,re
x=sys.stdin.read(); target=sys.argv[1]
for m in re.finditer(r'<node[^>]*>', x):
    t=m.group(0)
    txt=re.search(r'text=\"([^\"]*)\"',t); desc=re.search(r'content-desc=\"([^\"]*)\"',t)
    label=(txt.group(1) if txt else '') or (desc.group(1) if desc else '')
    if target.lower() in label.lower():
        bb=re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',t)
        if bb:
            x1,y1,x2,y2=map(int,bb.groups()); print((x1+x2)//2,(y1+y2)//2); break
" "$target")
  if [ -z "$b" ]; then echo "   NOT FOUND: $target" >&2; return 1; fi
  adb -s $S shell input tap $b
}

shot() { adb -s $S exec-out screencap -p > "$1"; printf '   %-46s %s bytes\n' "$(basename "$1")" "$(wc -c <"$1" | tr -d ' ')"; }

# width(dp) x height(px); density stays 420 so 1dp == 2.625px
combos=("320 840 1800" "360 945 2000" "412 1080 2400")
fonts=("1.0" "1.3" "2.0")

for combo in "${combos[@]}"; do
  set -- $combo; W=$1; PX=$2; PY=$3
  for fs in "${fonts[@]}"; do
    tag="w${W}-f${fs}"
    echo "== $tag"
    adb -s $S shell wm size "${PX}x${PY}" >/dev/null
    adb -s $S shell wm density 420 >/dev/null
    adb -s $S shell settings put system font_scale "$fs" >/dev/null
    adb -s $S shell am force-stop $PKG >/dev/null
    sleep 1
    adb -s $S shell am start -n $PKG/com.helix.app.MainActivity >/dev/null
    sleep 3
    tap "I understand" >/dev/null 2>&1   # first-launch notice only on the first combo
    sleep 1
    shot "$OUT/$tag-1-sessions.png"
    tap "App navigation" >/dev/null 2>&1
    sleep 1
    shot "$OUT/$tag-2-drawer.png"
    # Restart instead of pressing BACK: at narrow widths the drawer is full-bleed, and a
    # BACK that arrives while the drawer is still animating exits the activity outright.
    adb -s $S shell am force-stop $PKG >/dev/null
    sleep 1
    adb -s $S shell am start -n $PKG/com.helix.app.MainActivity >/dev/null
    sleep 3
    tap "New session" >/dev/null 2>&1
    sleep 2
    shot "$OUT/$tag-3-composer.png"
  done
done

# restore the device
adb -s $S shell wm size reset >/dev/null
adb -s $S shell wm density reset >/dev/null
adb -s $S shell settings put system font_scale 1.0 >/dev/null
echo "restored: $(adb -s $S shell wm size | tr -d '\r') / $(adb -s $S shell wm density | tr -d '\r') / font=$(adb -s $S shell settings get system font_scale | tr -d '\r')"

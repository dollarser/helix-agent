#!/bin/bash
set -euo pipefail
serial=${1:?serial}
pid=$(adb -s "$serial" shell pidof com.helix.agent.developer | tr -d '\r')
[[ "$pid" =~ ^[0-9]+$ ]]
adb -s "$serial" shell "su -c 'kill -3 $pid'"
adb -s "$serial" logcat -d -t 1500 > build/hxa094-test-process-logcat.txt

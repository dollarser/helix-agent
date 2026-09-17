#!/bin/bash
# 2026-09-16 真机基线测试 — OnePlus PLC110 (OP60EDL1)
# 历史设备：API 35 / Android 15 / arm64-v8a / 4096-byte page；当前设备由参数指定。
# 用途：HXA 基线验收前的真机设备门禁（developer flavor 全设备套件 + storage 迁移链）
# 输出：build/real-device-baseline-20260916/（git 忽略目录）
# 注意：会安装/覆盖本机 com.helix developer 调试包；只操作本 serial。
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SDK="$HOME/Library/Android/sdk"
ADB="$SDK/platform-tools/adb"
SERIAL="${1:?Usage: real-device-baseline.sh serial}"
OUT="$REPO/build/real-device-baseline-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
cd "$REPO"
# JAVA_HOME is supplied by the caller.
export ANDROID_SERIAL="$SERIAL"

# 设备事实留痕
{
  echo "run_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "serial: $SERIAL"
  for p in ro.product.model ro.product.manufacturer ro.build.version.sdk ro.build.version.release ro.build.version.incremental ro.product.cpu.abi ro.build.type; do
    echo "$p: $($ADB -s "$SERIAL" shell getprop "$p" | tr -d '\r')"
  done
  echo "pagesize: $($ADB -s "$SERIAL" shell getconf PAGESIZE | tr -d '\r')"
  echo "git_head: $(git rev-parse HEAD)"
  echo "git_dirty_paths: $(git status --porcelain | wc -l | tr -d ' ')"
} > "$OUT/device-facts.txt"

# 1) app 设备全套件（developer debug）
./gradlew :app:connectedDeveloperDebugAndroidTest --console=plain > "$OUT/app-device.log" 2>&1
app_rc=$?

# 2) core:storage 设备套件（Room 迁移链）
./gradlew :core:storage:connectedDebugAndroidTest --console=plain > "$OUT/storage-device.log" 2>&1
sto_rc=$?

# 3) 结果归档
cp -R app/build/reports/androidTests/connected "$OUT/app-reports" 2>/dev/null || true
cp -R core/storage/build/reports/androidTests/connected "$OUT/storage-reports" 2>/dev/null || true
find app/build/outputs/androidTest-results core/storage/build/outputs/androidTest-results -name "*.xml" 2>/dev/null | while read -r f; do
  cp "$f" "$OUT/" 2>/dev/null || true
done

echo "app_rc=$app_rc storage_rc=$sto_rc" | tee "$OUT/summary.txt"
exit $(( app_rc + sto_rc ))

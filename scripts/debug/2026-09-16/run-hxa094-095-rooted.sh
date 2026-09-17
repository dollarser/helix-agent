#!/usr/bin/env bash
set -euo pipefail

# HXA-094/095 rooted physical-device acceptance runner (2026-09-16).
#
# Usage: run-hxa094-095-rooted.sh <serial> <rootless|granted|background|tools-app|recreate-setup|recreate-verify|revoked>
#
# Initial granted tools suite uses Gradle; subsequent phases use am instrument without uninstall/reinstall across
# recreation. Build app + test APKs first; granted installs them with -r.
#   rootless         :tools:root full suite, hxa094ExpectedRoot=rootless (no external conditions;
#                    run while the Magisk policy denies the test package)
#   granted          :tools:root full suite (granted) + app RootLifecycleDeviceTest background test
#                    with a host-side su-process timeline (see below)
#   recreate-setup   app RootLifecycleDeviceTest phase=setup; the run ends by killing the app
#                    process (expected non-zero rc; the marker must exist afterwards)
#   recreate-verify  host-side orphan/process-group check from the setup timeline, then the
#                    verify phase (fresh app process, no stale authority)
#   revoked          :tools:root suite, hxa094ExpectedRoot=revoked (live revocation scenario)
#
# OS-level shell evidence: the app SELinux domain cannot read the su domain's /proc entries,
# so while an app-level phase runs this script polls from the host's own root:
#     ps -A -o PID,PPID,PGID,NAME (name == su) + the phase file the test writes.
# The app's own shell is the su process with PPID == the app pid; host-side `adb shell su`
# probes are filtered out by PPID. Evidence lands in build/hxa094-095-<phase>-<ts>/.
#
# Operator (device owner) steps:
#   granted / recreate-* : pre-approving the app package (com.helix.agent.developer) and the
#                          Root test package (com.helix.tools.root.test) in the Magisk policy
#                          list makes the run unattended; otherwise tap Allow when a prompt
#                          appears. Never approve other apps or change global policy.
#   revoked              when the tools:root log shows the grant reaching CONNECTED, open Magisk
#                          and revoke the test package's su permission while the test waits
#                          (30-minute confirmation window; write the marker only after the owner confirms).
# After the owner confirms Deny, in another terminal:
#   adb -s <serial> shell run-as com.helix.tools.root.test touch files/hxa094-revoke-confirmed
# Return to the Root test page from Recents if the OEM parks the background test while Mask
# is foreground. The acceptance run used this equivalent host navigation only:
#   adb -s <serial> shell su -c 'am start --activity-reorder-to-front -n com.helix.tools.root.test/com.helix.tools.root.RootTestActivity'
# That command merely restores the test Activity; grants and operations still use App libsu.
#
# Evidence: build/hxa094-095-<phase>-<timestamp>/ (gradle log, result XMLs, su timeline).

readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"

if [[ $# -ne 2 ]]; then
    printf 'Usage: %s <physical-device-serial> <rootless|granted|background|tools-app|recreate-setup|recreate-verify|revoked>\n' "$0" >&2
    exit 2
fi

readonly device_serial="$1"
readonly phase="$2"
readonly app_package="com.helix.agent.developer"
readonly marker_path="/data/data/${app_package}/files/hxa094-recreation.txt"
readonly phase_path="/data/data/${app_package}/files/hxa094-phase.txt"
readonly recreate_state="$project_root/build/hxa094-095-recreate-state.txt"

case "$phase" in
    rootless|granted|background|tools-app|recreate-setup|recreate-verify|revoked) ;;
    *)
        printf 'Unknown phase: %s\n' "$phase" >&2
        exit 2
        ;;
esac

if ! adb devices | awk -v serial="$device_serial" '$1 == serial && $2 == "device" { found = 1 } END { exit !found }'; then
    printf 'Physical test device is not online: %s\n' "$device_serial" >&2
    exit 2
fi

readonly is_emulator="$(adb -s "$device_serial" shell getprop ro.kernel.qemu | tr -d '\r')"
if [[ "$is_emulator" == "1" || "$device_serial" == emulator-* ]]; then
    printf 'HXA-094/095 rooted acceptance refuses emulator evidence: %s\n' "$device_serial" >&2
    exit 2
fi

readonly api_level="$(adb -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')"
readonly primary_abi="$(adb -s "$device_serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
if [[ ! "$api_level" =~ ^[0-9]+$ ]] || ((api_level < 34)); then
    printf 'HXA-094/095 requires API 34+, got: %s\n' "$api_level" >&2
    exit 2
fi
if [[ "$primary_abi" != "arm64-v8a" ]]; then
    printf 'HXA-094/095 requires an arm64-v8a primary ABI, got: %s\n' "$primary_abi" >&2
    exit 2
fi

readonly evidence_dir="$project_root/build/hxa094-095-${phase}-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$evidence_dir"
printf 'HXA-094/095 phase=%s serial=%s api=%s abi=%s evidence=%s\n' \
    "$phase" "$device_serial" "$api_level" "$primary_abi" "$evidence_dir"

cd "$project_root"
readonly device_lock="$project_root/build/hxa094-device-$device_serial.lock"
if ! mkdir "$device_lock" 2>/dev/null; then
    printf 'Device already reserved: %s\n' "$device_lock" >&2
    exit 2
fi
printf '%s\n' "$$" > "$device_lock/owner"
touch "$evidence_dir/started"
run_gradle() {
    local rc=0
    ANDROID_SERIAL="$device_serial" ./gradlew --no-daemon --max-workers=2 "$@" \
        2>&1 | tee "$evidence_dir/gradle-${phase}.log" || rc=$?
    return "$rc"
}

run_app_test() {
    local method="$1" recreation="${2:-}" rc=0
    local args=(-e class "com.helix.app.root.RootLifecycleDeviceTest#$method" -e hxa094ExpectedRoot granted)
    if [[ -n "$recreation" ]]; then args+=(-e hxa094RecreationPhase "$recreation"); fi
    adb -s "$device_serial" shell am instrument -w -r "${args[@]}" \
        "$app_package.test/com.helix.app.HelixAndroidJUnitRunner" \
        > "$evidence_dir/app-instrumentation.log" 2>&1 || rc=$?
    cat "$evidence_dir/app-instrumentation.log"
    if [[ "$recreation" == setup ]]; then
        grep -qi 'Process crashed' "$evidence_dir/app-instrumentation.log"
    else
        [[ "$rc" == 0 ]] && grep -Eq 'OK \(1 test\)' "$evidence_dir/app-instrumentation.log" &&
            ! grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$evidence_dir/app-instrumentation.log"
    fi
}

install_app_tests() {
    adb -s "$device_serial" install -r app/build/outputs/apk/developer/debug/app-developer-debug.apk
    adb -s "$device_serial" install -r app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk
}

archive_results() {
    mkdir -p "$evidence_dir/results"
    local xml
    while IFS= read -r -d '' xml; do
        cp "$xml" "$evidence_dir/results/"
    done < <(find app/build/outputs/androidTest-results tools/root/build/outputs/androidTest-results \
        -name "TEST-*.xml" -newer "$evidence_dir/started" -print0 2>/dev/null)
    cp -R app/build/reports/androidTests "$evidence_dir/app-connected-reports" 2>/dev/null || true
    cp -R tools/root/build/reports/androidTests "$evidence_dir/root-connected-reports" 2>/dev/null || true
    # Device-side context for post-mortem: package install/uninstall events, adbd activity.
    adb -s "$device_serial" logcat -d -t 4000 2>/dev/null > "$evidence_dir/device-logcat.txt" || true
}

# Poll from the host root while an app-level phase runs: a full system process snapshot per
# cycle, plus the app's own pid and the test phase file. Timeline line formats:
#   CYCLE|<ts>|<appPid>|<phase>
#   ROW|<pid>|<ppid>|<pgid>|<name>
# The full snapshot captures the app's su (PPID == app pid) and its descendants. The
# RootService remote process is a libsu-internal child of the su shell whose process name is
# not stable, so descendant tracking must not assume name == "su".
start_su_timeline() {
    local timeline="$1"
    local flag="$2"
    # A previously interrupted instrumentation can leave a stale phase marker.
    adb -s "$device_serial" shell run-as "$app_package" rm -f files/hxa094-phase.txt
    (
        # An installed-then-missing transition is monitored with a short debounce; only an
        # installed-then-missing transition is an uninstall. (`phase` is readonly in the main
        # shell, so the per-cycle value uses a different name here.)
        seen_installed=0
        missing_streak=0
        missing_since=0
        while true; do
            # No `local` here: bash forbids it in a bare subshell and would kill the poller.
            ts="$(python3 -c 'import time; print(int(time.time()*1000))')"
            raw="$(adb -s "$device_serial" shell su -c "cat $phase_path 2>/dev/null; echo; ps -A -o PID,PPID,PGID,NAME" 2>/dev/null | tr -d '\r')" || raw=""
            app_pid="$(adb -s "$device_serial" shell pidof $app_package 2>/dev/null | tr -d '\r' | awk '{print $1}')" || app_pid=""
            # Watchdog: the app package must stay installed once Gradle has installed it. An
            # uninstall mid-run invalidates evidence; debounce the package-manager query
            # before declaring a sustained disappearance.
            pkg="$(adb -s "$device_serial" shell pm list packages "$app_package" 2>/dev/null | tr -d '\r')" || pkg=""
            if [[ "$pkg" == "package:$app_package" ]]; then
                seen_installed=1
                missing_streak=0
                missing_since=0
            elif [[ "$seen_installed" == "1" && ! -f "$flag" ]]; then
                [[ "$missing_streak" != 0 ]] || missing_since="$ts"
                missing_streak=$((missing_streak + 1))
                if (( missing_streak >= 5 && ts - missing_since >= 6000 )); then
                printf '%s app package %s uninstalled during the run; abort evidence is invalid\n' "$ts" "$app_package" > "$flag"
                fi
            fi
            phase_val="${raw%%$'\n'*}"
            {
                printf 'CYCLE|%s|%s|%s\n' "$ts" "$app_pid" "$phase_val"
                while IFS= read -r row; do
                    [[ -n "$row" ]] && printf 'ROW|%s\n' "$row"
                done <<< "${raw#*$'\n'}"
            } >> "$timeline"
            sleep 0.3
        done
    ) >> "$timeline.debug" 2>&1 &
    SU_TIMELINE_PID=$!
}

stop_su_timeline() {
    kill "$SU_TIMELINE_PID" 2>/dev/null || true
    wait "$SU_TIMELINE_PID" 2>/dev/null || true
    SU_TIMELINE_PID=""
}

# App phases use am instrument and retain the installation. A confirmed package removal
# is always invalid evidence; there is no "last ten seconds" exemption.
check_uninstall_flag() {
    local flag="$1"
    [[ -f "$flag" ]] || return 0
    printf 'FATAL: %s\n' "$(cat "$flag")" >&2
    return 3
}

# Assert the background-transition timeline: the app's own su (PPID == app pid) must appear
# while the phase is "connected", and the entire observed app process tree (su + libsu
# descendants) must be gone from "backgrounded" onward with no rebind.
check_background_timeline() {
    python3 "$(dirname "${BASH_SOURCE[0]}")/hxa094-095-timeline.py" check "$1"
}

# From the setup timeline, extract the app's su (pid + pgrp) and its observed children
# (the RootService remote process). Prints "suPid:pgrp:remotePid[,remotePid...]".
setup_su_identity() {
    python3 "$(dirname "${BASH_SOURCE[0]}")/hxa094-095-timeline.py" setup "$1"
}

# Verify-run window: the fresh process never requests Root; any app-owned su is a regression.
check_verify_timeline() {
    python3 "$(dirname "${BASH_SOURCE[0]}")/hxa094-095-timeline.py" verify "$1"
}

SU_TIMELINE_PID=""
trap '[[ -n "${SU_TIMELINE_PID:-}" ]] && stop_su_timeline; rm -f "$device_lock/owner"; rmdir "$device_lock"' EXIT

case "$phase" in
    rootless)
        printf 'rootless phase: no Root prompts are expected; the Magisk policy must deny the test package.\n'
        adb -s "$device_serial" shell am instrument -w -r \
            -e hxa094ExpectedRoot rootless -e hxa095ExpectedRoot rootless \
            -e class com.helix.tools.root.LibsuRootAccessDeviceTest,com.helix.tools.root.RootHighLevelToolsDeviceTest,com.helix.tools.root.RootToolsRootlessDeviceTest \
            com.helix.tools.root.test/androidx.test.runner.AndroidJUnitRunner \
            > "$evidence_dir/root-instrumentation.log" 2>&1
        cat "$evidence_dir/root-instrumentation.log"
        grep -Eq 'OK \(8 tests\)' "$evidence_dir/root-instrumentation.log"
        ! grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$evidence_dir/root-instrumentation.log"
        printf 'HXA-094/095 rootless phase passed on %s.\n' "$device_serial"
        ;;

    granted|background)
        printf 'granted phase: pre-approved Magisk policy entries make this unattended.\n'
        if [[ "$phase" == granted ]]; then
        rc=0
        run_gradle \
            "-Pandroid.testInstrumentationRunnerArguments.hxa094ExpectedRoot=granted" \
            "-Pandroid.testInstrumentationRunnerArguments.hxa095ExpectedRoot=granted" \
            :tools:root:connectedDebugAndroidTest || rc=$?
        archive_results
        if (( rc != 0 )); then exit "$rc"; fi
        fi
        rc=0
        install_app_tests
        start_su_timeline "$evidence_dir/su-timeline.txt" "$evidence_dir/package-uninstalled.flag"
        run_app_test backgroundTransitionFailsClosedAndLeavesNoRootProcess || rc=$?
        stop_su_timeline
        archive_results
        check_uninstall_flag "$evidence_dir/package-uninstalled.flag" "$evidence_dir/su-timeline.txt"
        check_background_timeline "$evidence_dir/su-timeline.txt"
        if (( rc != 0 )); then exit "$rc"; fi
        printf 'HXA-094/095 granted phase passed on %s.\n' "$device_serial"
        ;;

    tools-app)
        install_app_tests
        run_app_test realAppDispatcherHonorsRootScopeAndToolDisable
        printf 'HXA-095 App dispatcher phase passed.\n'
        ;;

    recreate-setup)
        printf 'recreate-setup phase: pre-approved Magisk policy entries make this unattended;\n'
        printf 'the run is expected to end by killing the app process (non-zero rc is normal).\n'
        rc=0
        start_su_timeline "$evidence_dir/su-timeline.txt" "$evidence_dir/package-uninstalled.flag"
        run_app_test processRecreationStartsCleanWithoutStaleAuthority setup || rc=$?
        stop_su_timeline
        archive_results
        check_uninstall_flag "$evidence_dir/package-uninstalled.flag" "$evidence_dir/su-timeline.txt"
        if (( rc != 0 )); then exit "$rc"; fi
        marker_content="$(adb -s "$device_serial" shell su -c "cat $marker_path" | tr -d '\r')"
        if [[ -z "$marker_content" || ! "$marker_content" =~ ^[0-9]+$ ]]; then
            printf 'FATAL: recreation marker missing or malformed (gradle rc=%s): %q\n' "$rc" "$marker_content" >&2
            exit 1
        fi
        su_identity="$(setup_su_identity "$evidence_dir/su-timeline.txt")"
        printf '%s %s\n' "$marker_content" "$su_identity" > "$recreate_state"
        printf 'recreate-setup phase complete: app pid %s died with Root tree %s (gradle rc=%s, expected).\n' \
            "$marker_content" "$su_identity" "$rc"
        printf 'Next: run the recreate-verify phase on the same device.\n'
        ;;

    recreate-verify)
        if [[ ! -f "$recreate_state" ]]; then
            printf 'FATAL: run recreate-setup first (no state at %s)\n' "$recreate_state" >&2
            exit 1
        fi
        read -r old_app_pid su_identity < "$recreate_state"
        old_su_pid="${su_identity%%:*}"
        rest="${su_identity#*:}"
        old_pgrp="${rest%%:*}"
        old_remotes="${rest#*:}"
        sleep 5
        su_probe="$(adb -s "$device_serial" shell su -c "test -d /proc/$old_su_pid; echo -n \$?" | tr -d '\r')"
        if [[ "$su_probe" != "1" ]]; then
            printf 'FATAL: orphaned Root shell %s survived the app process death (probe=%s).\n' "$old_su_pid" "$su_probe" >&2
            exit 1
        fi
        IFS=',' read -r -a remote_pids <<< "$old_remotes"
        for remote_pid in "${remote_pids[@]}"; do
            [[ -z "$remote_pid" ]] && continue
            remote_probe="$(adb -s "$device_serial" shell su -c "test -d /proc/$remote_pid; echo -n \$?" | tr -d '\r')"
            if [[ "$remote_probe" != "1" ]]; then
                printf 'FATAL: RootService remote process %s survived the app process death (probe=%s).\n' "$remote_pid" "$remote_probe" >&2
                exit 1
            fi
        done
        printf 'Observed app shell %s and RootService %s have exited; no unrelated process is killed.\n' "$old_su_pid" "$old_remotes"
        printf 'recreate-verify phase: no Magisk prompt may appear (the verify test never requests\n'
        printf 'Root; a prompt here would indicate a product regression).\n'
        rc=0
        start_su_timeline "$evidence_dir/su-timeline.txt" "$evidence_dir/package-uninstalled.flag"
        run_app_test processRecreationStartsCleanWithoutStaleAuthority verify || rc=$?
        stop_su_timeline
        archive_results
        check_uninstall_flag "$evidence_dir/package-uninstalled.flag" "$evidence_dir/su-timeline.txt"
        check_verify_timeline "$evidence_dir/su-timeline.txt"
        if (( rc != 0 )); then exit "$rc"; fi
        rm -f "$recreate_state"
        printf 'HXA-094/095 recreate-verify phase passed on %s.\n' "$device_serial"
        ;;

    revoked)
        printf 'revoked: test package must initially be allowed; wait for files/hxa094-revoke-ready,\n'
        printf 'then deny ONLY com.helix.tools.root.test in the manager and write the confirmation marker.\n'
        adb -s "$device_serial" install -r tools/root/build/outputs/apk/androidTest/debug/root-debug-androidTest.apk
        adb -s "$device_serial" shell am instrument -w -r \
            -e hxa094ExpectedRoot revoked \
            -e class 'com.helix.tools.root.LibsuRootAccessDeviceTest#b_explicitRequestMatchesTheDeclaredDeviceProfile' \
            com.helix.tools.root.test/androidx.test.runner.AndroidJUnitRunner \
            > "$evidence_dir/root-instrumentation.log" 2>&1
        cat "$evidence_dir/root-instrumentation.log"
        grep -Eq 'OK \(1 test\)' "$evidence_dir/root-instrumentation.log"
        ! grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$evidence_dir/root-instrumentation.log"
        printf 'HXA-094 revoked phase passed.\n'
        ;;
esac

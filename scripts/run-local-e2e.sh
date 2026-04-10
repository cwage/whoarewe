#!/usr/bin/env bash
#
# Local convenience wrapper for scripts/e2e-pairing.sh.
#
# Boots two headless emulators on ports 5554 and 5556, waits for both to
# finish booting, then runs the pairing harness against them. Emulators that
# are already running on those ports are reused as-is.
#
# Usage:
#   scripts/run-local-e2e.sh                      # uses first two AVDs
#   scripts/run-local-e2e.sh Pixel_9 Pixel_9b     # pick specific AVDs
#   scripts/run-local-e2e.sh --kill-on-exit ...   # kill both when done
#   scripts/run-local-e2e.sh --show-window ...    # visible emulator windows
#
# NOTE: only touches emulator-5554 and emulator-5556. Physical devices
# attached via USB are never targeted.

set -euo pipefail

KILL_ON_EXIT=0
SHOW_WINDOW=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --kill-on-exit) KILL_ON_EXIT=1; shift ;;
        --show-window)  SHOW_WINDOW=1;  shift ;;
        -h|--help)
            sed -n '3,17p' "$0" | sed 's/^#//'
            exit 0
            ;;
        *) break ;;
    esac
done

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
EMULATOR="$SDK/emulator/emulator"
ADB="${ADB:-adb}"

if [[ ! -x "$EMULATOR" ]]; then
    echo "ERROR: emulator not found at $EMULATOR" >&2
    echo "       set ANDROID_SDK_ROOT or ANDROID_HOME" >&2
    exit 1
fi

mapfile -t avds < <("$EMULATOR" -list-avds)
if [[ ${#avds[@]} -lt 2 ]]; then
    echo "ERROR: need at least 2 AVDs; found ${#avds[@]}" >&2
    echo "       available: ${avds[*]:-(none)}" >&2
    exit 1
fi

AVD_A="${1:-${avds[0]}}"
AVD_B="${2:-${avds[1]}}"

if [[ "$AVD_A" == "$AVD_B" ]]; then
    echo "ERROR: need two different AVDs (got '$AVD_A' twice)" >&2
    exit 1
fi

OPTS=(-no-snapshot-save -gpu swiftshader_indirect -noaudio -no-boot-anim)
if [[ $SHOW_WINDOW -eq 0 ]]; then
    OPTS+=(-no-window)
fi

declare -a STARTED_PIDS=()

cleanup() {
    local rc=$?
    if [[ $KILL_ON_EXIT -eq 1 ]]; then
        echo "[local-e2e] killing emulators..."
        "$ADB" -s emulator-5554 emu kill >/dev/null 2>&1 || true
        "$ADB" -s emulator-5556 emu kill >/dev/null 2>&1 || true
    fi
    exit $rc
}
trap cleanup EXIT INT TERM

is_device_online() {
    local serial=$1
    "$ADB" devices 2>/dev/null | awk -v s="$serial" '$1==s && $2=="device" {found=1} END {exit !found}'
}

launch_if_needed() {
    local port=$1 avd=$2
    local serial="emulator-$port"
    if is_device_online "$serial"; then
        echo "[local-e2e] $serial already running, reusing"
        return 0
    fi
    echo "[local-e2e] booting $avd on port $port..."
    nohup "$EMULATOR" -avd "$avd" -port "$port" "${OPTS[@]}" \
        > "/tmp/emu-$port.log" 2>&1 &
    STARTED_PIDS+=( $! )
}

wait_boot() {
    local serial=$1 i=0
    "$ADB" -s "$serial" wait-for-device
    while [[ $i -lt 240 ]]; do
        local val
        val=$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
        if [[ "$val" == "1" ]]; then
            echo "[local-e2e] $serial booted"
            return 0
        fi
        sleep 1
        i=$((i+1))
    done
    echo "ERROR: $serial failed to boot within 240s (log: /tmp/emu-${serial#emulator-}.log)" >&2
    return 1
}

launch_if_needed 5554 "$AVD_A"
launch_if_needed 5556 "$AVD_B"

wait_boot emulator-5554
wait_boot emulator-5556

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

if [[ ! -f "$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk" ]]; then
    echo "[local-e2e] debug APK missing; building..."
    (cd "$REPO_ROOT" && ./gradlew :app:assembleDebug --no-daemon)
fi

echo "[local-e2e] running pairing harness..."
# Run (don't exec) so our EXIT trap still fires for --kill-on-exit cleanup.
"$REPO_ROOT/scripts/e2e-pairing.sh" emulator-5554 emulator-5556

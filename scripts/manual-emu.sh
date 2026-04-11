#!/usr/bin/env bash
#
# Spin up a single emulator for manual pairing tests: fresh AVD state, PIN set,
# debug APK installed, unlocked, ready for the user to drive by hand. Sibling
# to `run-local-e2e.sh` but meant for hands-on work, not automated harnesses.
#
# What this does, in order:
#   1. Builds the debug APK if missing
#   2. Confirms `hw.keyboard = yes` in the AVD's config.ini (warns if not)
#   3. Kills whatever is running on the target port (so we don't stack on top)
#   4. Boots the AVD with -wipe-data -no-snapshot-load -no-snapshot-save
#      (any of these alone is not enough — -no-snapshot-save keeps future
#      shutdowns from writing state, but a previously-saved snapshot can still
#      reload; -wipe-data nukes userdata for full-clean every run.)
#   5. Waits for `sys.boot_completed = 1`
#   6. Sets the device PIN via `locksettings set-pin`
#   7. Installs app-debug.apk
#   8. Unlocks the keyguard once so the launcher is visible
#   9. Launches `com.whoarewe.app/.MainActivity`
#   10. Prints final state + the PIN so you know what to punch in when the
#       app's biometric bouncer pops
#
# Usage:
#   scripts/manual-emu.sh                          # Pixel_9, PIN 1234, port 5554
#   scripts/manual-emu.sh --avd Pixel_9b           # different AVD
#   scripts/manual-emu.sh --pin 9999               # different PIN
#   scripts/manual-emu.sh --port 5556              # different port (pair with --avd)
#   scripts/manual-emu.sh --headless               # -no-window (default is visible)
#
# Requires:
#   - $ANDROID_SDK_ROOT / $ANDROID_HOME / ~/Android/Sdk pointing at the SDK
#   - An AVD with `hw.keyboard = yes` in config.ini if you want to type into
#     the app directly from your host keyboard. The script checks and warns.
#
# This only ever touches emulator-$PORT. Physical USB devices are never
# targeted, even if adb is ambiguous, because we pass -s explicitly on every
# adb call.

set -euo pipefail

AVD=""
PIN="1234"
PORT="5554"
HEADLESS=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --avd)      AVD="$2";  shift 2 ;;
        --pin)      PIN="$2";  shift 2 ;;
        --port)     PORT="$2"; shift 2 ;;
        --headless) HEADLESS=1; shift ;;
        -h|--help)
            sed -n '3,35p' "$0" | sed 's/^#//; s/^ //'
            exit 0
            ;;
        *)
            echo "ERROR: unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
EMULATOR="$SDK/emulator/emulator"
ADB="${ADB:-adb}"
SERIAL="emulator-$PORT"

if [[ ! -x "$EMULATOR" ]]; then
    echo "ERROR: emulator not found at $EMULATOR" >&2
    echo "       set ANDROID_SDK_ROOT or ANDROID_HOME" >&2
    exit 1
fi

# Pick the first AVD if the caller didn't specify one.
if [[ -z "$AVD" ]]; then
    AVD=$("$EMULATOR" -list-avds | head -n1 || true)
    if [[ -z "$AVD" ]]; then
        echo "ERROR: no AVDs found (emulator -list-avds returned nothing)" >&2
        exit 1
    fi
fi

CONFIG_INI="$HOME/.android/avd/${AVD}.avd/config.ini"
if [[ -f "$CONFIG_INI" ]]; then
    if ! grep -q '^hw.keyboard[[:space:]]*=[[:space:]]*yes' "$CONFIG_INI"; then
        echo "WARN: $CONFIG_INI does not have 'hw.keyboard = yes'." >&2
        echo "      Host keyboard input won't reach the app. Edit the file"
        echo "      and rerun, or accept that you'll only be able to type via"
        echo "      'adb -s $SERIAL shell input text ...'."
    fi
else
    echo "WARN: $CONFIG_INI not found — can't verify hw.keyboard setting." >&2
fi

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"

if [[ ! -f "$APK" ]]; then
    echo "[manual-emu] debug APK missing; building..."
    (cd "$REPO_ROOT" && ./gradlew :app:assembleDebug --no-daemon)
fi

# Kill anything on the target port so we boot from a clean slate. `emu kill`
# is a no-op if there's nothing there — we just ignore its error.
echo "[manual-emu] killing any existing emulator on $SERIAL..."
"$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
sleep 1

OPTS=(
    -avd "$AVD"
    -port "$PORT"
    -wipe-data
    -no-snapshot-load
    -no-snapshot-save
    -gpu swiftshader_indirect
    -noaudio
    -no-boot-anim
)
if [[ $HEADLESS -eq 1 ]]; then
    OPTS+=(-no-window)
fi

echo "[manual-emu] booting $AVD on $SERIAL (this takes ~20s)..."
nohup "$EMULATOR" "${OPTS[@]}" > "/tmp/emu-$PORT.log" 2>&1 &

echo "[manual-emu] waiting for boot_completed..."
"$ADB" -s "$SERIAL" wait-for-device
i=0
while [[ $i -lt 240 ]]; do
    val=$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    if [[ "$val" == "1" ]]; then
        break
    fi
    sleep 1
    i=$((i+1))
done
if [[ "${val:-}" != "1" ]]; then
    echo "ERROR: $SERIAL failed to boot within 240s (log: /tmp/emu-$PORT.log)" >&2
    exit 1
fi
echo "[manual-emu] $SERIAL booted"

echo "[manual-emu] setting device PIN to $PIN..."
"$ADB" -s "$SERIAL" shell locksettings set-pin "$PIN"

echo "[manual-emu] installing $APK..."
"$ADB" -s "$SERIAL" install "$APK" >/dev/null

# Unlock the keyguard so the launcher is visible. This is a swipe-up to
# reveal the PIN pad, then the PIN, then enter. Matches what the e2e harness
# does to get past the lockscreen after boot.
echo "[manual-emu] unlocking keyguard..."
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_MENU
sleep 0.5
"$ADB" -s "$SERIAL" shell input text "$PIN"
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_ENTER

echo "[manual-emu] launching com.whoarewe.app..."
"$ADB" -s "$SERIAL" shell am start -n com.whoarewe.app/.MainActivity >/dev/null

cat <<EOF

============================================================
Emulator ready:

    serial:   $SERIAL
    AVD:      $AVD
    PIN:      $PIN

When the app's biometric/PIN prompt appears, type $PIN and Enter.
To type into the EditText, click the emulator window and type on
your host keyboard (requires hw.keyboard = yes in config.ini).
============================================================
EOF

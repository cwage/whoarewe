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

# PIN is typed as individual digit keyevents below rather than `input text`.
# The secure keyguard bouncer on many Android versions is button-based and
# silently drops `input text`, so we validate up-front that the PIN is
# all-digits — anything else can't possibly unlock via this path.
if [[ ! "$PIN" =~ ^[0-9]+$ ]]; then
    echo "ERROR: --pin must be all digits (got '$PIN'), otherwise the" >&2
    echo "       keyguard unlock path below cannot dismiss the bouncer." >&2
    exit 1
fi

echo "[manual-emu] setting device PIN to $PIN..."
"$ADB" -s "$SERIAL" shell locksettings set-pin "$PIN"

echo "[manual-emu] installing $APK..."
"$ADB" -s "$SERIAL" install "$APK" >/dev/null

# Keep the screen awake once we've unlocked so the keyguard doesn't re-arm
# the moment the human looks away. This matches what scripts/e2e-pairing.sh
# does on boot (`svc power stayon true`) and is the difference between
# "the emulator is usable for the next 15 minutes" and "oh no the lockscreen
# came back and now I have to re-unlock halfway through a pair wizard step".
"$ADB" -s "$SERIAL" shell svc power stayon true >/dev/null

# Dismiss the keyguard. Wake the screen, then send the PIN digits as
# individual KEYCODE_N keyevents plus ENTER. We do NOT use `input text`
# here because the secure PIN bouncer on several Android system images
# only accepts button-style input and silently drops a text event — a
# bug scripts/e2e-pairing.sh was already fighting with, so we match its
# approach (see unlock_keyguard() in that script).
echo "[manual-emu] unlocking keyguard..."
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
sleep 0.3
digit_events=()
for ((i = 0; i < ${#PIN}; i++)); do
    digit_events+=( "KEYCODE_${PIN:i:1}" )
done
digit_events+=( KEYCODE_ENTER )
"$ADB" -s "$SERIAL" shell input keyevent "${digit_events[@]}"
sleep 0.5

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

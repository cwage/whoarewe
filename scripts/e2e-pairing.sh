#!/usr/bin/env bash
#
# End-to-end pairing test driver. Drives two Android devices/emulators through
# the full WhoAreWe pairing flow via adb + uiautomator + the debug-only intent
# seam in MainActivity.handleE2eIntent (e2e_dump_qr / e2e_inject_qr).
#
# Steps:
#   1. Wait for both devices to boot
#   2. Set device PIN to 1234, disable animations, install debug APK
#   3. Drive Setup screen → enter name → "Create Identity" → enter PIN
#   4. Dump each device's QR payload via the debug intent (read from logcat)
#   5. Inject the opposing QR into each device → biometric → enter PIN
#   6. Read each contact's TOTP code from the contact list and assert they match
#
# Usage:
#   scripts/e2e-pairing.sh [device-a-serial] [device-b-serial]
#
# If serials are omitted, uses the first two devices reported by `adb devices`.
# Requires: adb, python3.

set -euo pipefail

DEVICE_A="${1:-}"
DEVICE_B="${2:-}"
APK="${E2E_APK:-app/build/outputs/apk/debug/app-debug.apk}"
PIN="${E2E_PIN:-1234}"
NAME_A="${E2E_NAME_A:-Alice}"
NAME_B="${E2E_NAME_B:-Bob}"
PKG="com.whoarewe.app"
ACT="$PKG/$PKG.MainActivity"
LOG_TAG="WhoAreWe-E2E"
WAIT_BOOT_SECS=180
WAIT_UI_SECS=30

if [[ -z "$DEVICE_A" || -z "$DEVICE_B" ]]; then
    mapfile -t serials < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
    DEVICE_A="${DEVICE_A:-${serials[0]:-}}"
    DEVICE_B="${DEVICE_B:-${serials[1]:-}}"
fi

if [[ -z "$DEVICE_A" || -z "$DEVICE_B" ]]; then
    echo "ERROR: need two connected devices/emulators" >&2
    exit 1
fi

if [[ ! -f "$APK" ]]; then
    echo "ERROR: APK not found at $APK; run ./gradlew assembleDebug" >&2
    exit 1
fi

if ! command -v python3 >/dev/null; then
    echo "ERROR: python3 required for ui dump parsing" >&2
    exit 1
fi

echo "[e2e] device-a=$DEVICE_A device-b=$DEVICE_B apk=$APK"

# ── adb plumbing ─────────────────────────────────────────────────────────────

wait_boot() {
    local s=$1 i=0
    echo "[e2e] $s: waiting for boot..."
    adb -s "$s" wait-for-device
    while [[ $i -lt $WAIT_BOOT_SECS ]]; do
        local val
        val=$(adb -s "$s" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
        if [[ "$val" == "1" ]]; then
            return 0
        fi
        sleep 1
        i=$((i+1))
    done
    echo "ERROR: $s never finished booting" >&2
    return 1
}

setup_device() {
    local s=$1
    echo "[e2e] $s: configuring device..."
    # Keep the screen awake so the keyguard stops re-arming after we dismiss it.
    adb -s "$s" shell svc power stayon true >/dev/null
    # Wake the screen and dismiss any swipe-only keyguard.
    adb -s "$s" shell input keyevent 224 >/dev/null 2>&1 || true
    adb -s "$s" shell input keyevent 82 >/dev/null 2>&1 || true
    # Animations off so taps land on stable bounds
    adb -s "$s" shell settings put global window_animation_scale 0 >/dev/null
    adb -s "$s" shell settings put global transition_animation_scale 0 >/dev/null
    adb -s "$s" shell settings put global animator_duration_scale 0 >/dev/null
    # Set device PIN. Clear any existing one first (idempotent across reruns).
    adb -s "$s" shell locksettings clear --old "$PIN" >/dev/null 2>&1 || true
    adb -s "$s" shell locksettings set-pin "$PIN" >/dev/null
    # Setting a PIN re-arms the secure keyguard. Dismiss the bouncer once now;
    # stayon=true keeps it dismissed for the rest of the run.
    unlock_keyguard "$s"
    # Reinstall to guarantee fresh state
    adb -s "$s" uninstall "$PKG" >/dev/null 2>&1 || true
    adb -s "$s" install -r "$APK" >/dev/null
}

# Send PIN digits as keyevents to dismiss the secure keyguard bouncer. The
# bouncer's PIN pad is button-based and doesn't accept `input text`, so we map
# each digit to KEYCODE_0..9 (KEYCODE_0=7, so digit + 7).
unlock_keyguard() {
    local s=$1 i=0
    adb -s "$s" shell input keyevent 224 >/dev/null 2>&1 || true
    sleep 0.3
    # If the bouncer isn't visible we still send the digits — they're harmless
    # noops if no view consumes them.
    local digits=()
    for ((i = 0; i < ${#PIN}; i++)); do
        digits+=( $(( ${PIN:i:1} + 7 )) )
    done
    digits+=( 66 )  # KEYCODE_ENTER
    adb -s "$s" shell input keyevent "${digits[@]}" >/dev/null 2>&1 || true
    sleep 0.5
}

# Dump current uiautomator hierarchy to stdout. We dump to /sdcard then cat
# because uiautomator's stdout/exec-out path is polluted with a "UI hierchary
# dumped to:" trailer that breaks XML parsing.
ui_dump() {
    local s=$1
    adb -s "$s" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
    adb -s "$s" exec-out cat /sdcard/ui.xml
}

# Find center x,y of a node by text= or content-desc=. Echos "x y" or empty.
find_xy() {
    local s=$1 needle=$2
    ui_dump "$s" | python3 -c '
import sys, re, xml.etree.ElementTree as ET
needle = sys.argv[1]
data = sys.stdin.read()
try:
    root = ET.fromstring(data)
except ET.ParseError:
    sys.exit(2)
for node in root.iter("node"):
    if node.get("text") == needle or node.get("content-desc") == needle:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print((x1+x2)//2, (y1+y2)//2)
            sys.exit(0)
sys.exit(1)
' "$needle" 2>/dev/null || true
}

# Find center of first node whose class matches the pattern
find_xy_class() {
    local s=$1 pattern=$2
    ui_dump "$s" | python3 -c '
import sys, re, xml.etree.ElementTree as ET
pattern = sys.argv[1]
data = sys.stdin.read()
try:
    root = ET.fromstring(data)
except ET.ParseError:
    sys.exit(2)
for node in root.iter("node"):
    if pattern in (node.get("class") or ""):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print((x1+x2)//2, (y1+y2)//2)
            sys.exit(0)
sys.exit(1)
' "$pattern" 2>/dev/null || true
}

tap_xy() {
    local s=$1 xy=$2
    [[ -n "$xy" ]] || return 1
    # shellcheck disable=SC2086
    adb -s "$s" shell input tap $xy
}

tap_text() {
    local s=$1 needle=$2
    local xy
    xy=$(find_xy "$s" "$needle")
    if [[ -z "$xy" ]]; then
        echo "ERROR: text '$needle' not found on $s" >&2
        return 1
    fi
    # shellcheck disable=SC2086
    adb -s "$s" shell input tap $xy
}

wait_for_text() {
    local s=$1 needle=$2 timeout=${3:-$WAIT_UI_SECS} i=0
    while [[ $i -lt $timeout ]]; do
        if [[ -n "$(find_xy "$s" "$needle")" ]]; then
            return 0
        fi
        sleep 1
        i=$((i+1))
    done
    echo "ERROR: text '$needle' did not appear on $s within ${timeout}s" >&2
    adb -s "$s" exec-out screencap -p > "/tmp/e2e-fail-${s//[^a-zA-Z0-9]/_}.png" || true
    return 1
}

# ── identity creation ────────────────────────────────────────────────────────

enter_pin() {
    local s=$1 i=0
    echo "[e2e] $s: waiting for biometric/PIN prompt..."
    while [[ $i -lt $WAIT_UI_SECS ]]; do
        local dump
        dump=$(ui_dump "$s")
        # System-credential PIN screen lives in com.android.systemui under
        # auth_credential_* views (or shows the word "PIN" on the title).
        if echo "$dump" | grep -q 'auth_credential' || echo "$dump" | grep -q 'lockPattern'; then
            break
        fi
        sleep 1
        i=$((i+1))
    done
    # Tap the password field if we can find one, then type the PIN + Enter.
    local pin_xy
    pin_xy=$(ui_dump "$s" | python3 -c '
import sys, re, xml.etree.ElementTree as ET
data = sys.stdin.read()
try:
    root = ET.fromstring(data)
except ET.ParseError:
    sys.exit(2)
for node in root.iter("node"):
    rid = node.get("resource-id") or ""
    cls = node.get("class") or ""
    pwd = node.get("password") == "true"
    if "auth_credential" in rid or pwd or "PinView" in cls:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print((x1+x2)//2, (y1+y2)//2)
            sys.exit(0)
sys.exit(1)
' 2>/dev/null || true)
    if [[ -n "$pin_xy" ]]; then
        tap_xy "$s" "$pin_xy" || true
        sleep 0.3
    fi
    adb -s "$s" shell input text "$PIN"
    sleep 0.3
    adb -s "$s" shell input keyevent 66  # KEYCODE_ENTER
    sleep 1
}

create_identity() {
    local s=$1 name=$2
    echo "[e2e] $s: creating identity '$name'..."
    adb -s "$s" shell am force-stop "$PKG" >/dev/null 2>&1 || true
    adb -s "$s" shell am start -n "$ACT" >/dev/null
    wait_for_text "$s" "Create Identity" 30

    local field_xy
    field_xy=$(find_xy_class "$s" "EditText")
    if [[ -z "$field_xy" ]]; then
        echo "ERROR: $s: no EditText found on Setup screen" >&2
        return 1
    fi
    tap_xy "$s" "$field_xy"
    sleep 0.3
    adb -s "$s" shell input text "$name"
    sleep 0.3
    # KEYCODE_BACK dismisses the IME without navigating away. The Pixel emulator's
    # Gboard otherwise covers the "Create Identity" button (so taps fall on the
    # text field instead) and may also auto-correct names like "Alice" → "Alive".
    adb -s "$s" shell input keyevent 4
    sleep 0.5

    tap_text "$s" "Create Identity"
    enter_pin "$s"
    wait_for_text "$s" "Your Identity" 30
    echo "[e2e] $s: identity created"
}

# ── QR seam ──────────────────────────────────────────────────────────────────

dump_qr() {
    local s=$1 i=0
    adb -s "$s" logcat -c >/dev/null 2>&1 || true
    adb -s "$s" shell am start -n "$ACT" --ez e2e_dump_qr true -f 0x30000000 >/dev/null
    while [[ $i -lt 20 ]]; do
        local line
        line=$(adb -s "$s" logcat -d -s "$LOG_TAG":I 2>/dev/null \
            | grep -F 'QR_DUMP:' | tail -1 || true)
        if [[ -n "$line" ]]; then
            echo "${line#*QR_DUMP: }"
            return 0
        fi
        sleep 0.5
        i=$((i+1))
    done
    echo "ERROR: $s never logged QR_DUMP" >&2
    return 1
}

inject_qr() {
    local s=$1 qr=$2
    adb -s "$s" shell am start -n "$ACT" --es e2e_inject_qr "$qr" -f 0x30000000 >/dev/null
}

# ── verification ─────────────────────────────────────────────────────────────

read_totp_for() {
    local s=$1 contact=$2
    ui_dump "$s" | python3 -c '
import sys, re, xml.etree.ElementTree as ET
contact = sys.argv[1]
data = sys.stdin.read()
try:
    root = ET.fromstring(data)
except ET.ParseError:
    sys.exit(2)
nodes = list(root.iter("node"))
# Find the contact name node, then walk forward for the next "DDD DDD" code.
for i, n in enumerate(nodes):
    if n.get("text") == contact:
        for m in nodes[i+1:i+12]:
            t = m.get("text") or ""
            if re.fullmatch(r"\d{3} \d{3}", t):
                print(t)
                sys.exit(0)
sys.exit(1)
' "$contact" 2>/dev/null || true
}

# ── main flow ────────────────────────────────────────────────────────────────

wait_boot "$DEVICE_A"
wait_boot "$DEVICE_B"

setup_device "$DEVICE_A"
setup_device "$DEVICE_B"

create_identity "$DEVICE_A" "$NAME_A"
create_identity "$DEVICE_B" "$NAME_B"

QR_A=$(dump_qr "$DEVICE_A")
QR_B=$(dump_qr "$DEVICE_B")
echo "[e2e] QR_A=$QR_A"
echo "[e2e] QR_B=$QR_B"

if [[ -z "$QR_A" || -z "$QR_B" ]]; then
    echo "ERROR: failed to read QR payload from one or both devices" >&2
    exit 1
fi

# Cross-inject. Each call triggers biometric/PIN, advances pair wizard to
# ShowAfterScan, after which we tap "Done" to return to ContactList.
echo "[e2e] injecting Bob's QR into Alice..."
inject_qr "$DEVICE_A" "$QR_B"
enter_pin "$DEVICE_A"
wait_for_text "$DEVICE_A" "Done" 30
tap_text "$DEVICE_A" "Done"
wait_for_text "$DEVICE_A" "Your Identity" 15

echo "[e2e] injecting Alice's QR into Bob..."
inject_qr "$DEVICE_B" "$QR_A"
enter_pin "$DEVICE_B"
wait_for_text "$DEVICE_B" "Done" 30
tap_text "$DEVICE_B" "Done"
wait_for_text "$DEVICE_B" "Your Identity" 15

CODE_A=$(read_totp_for "$DEVICE_A" "$NAME_B")
CODE_B=$(read_totp_for "$DEVICE_B" "$NAME_A")

echo "[e2e] $NAME_A sees $NAME_B: '${CODE_A:-(none)}'"
echo "[e2e] $NAME_B sees $NAME_A: '${CODE_B:-(none)}'"

if [[ -z "$CODE_A" || -z "$CODE_B" ]]; then
    echo "FAIL: could not read TOTP code from one or both devices" >&2
    exit 1
fi

if [[ "$CODE_A" != "$CODE_B" ]]; then
    echo "FAIL: codes do not match ($CODE_A != $CODE_B)" >&2
    exit 1
fi

echo "PASS: matching TOTP code on both devices: $CODE_A"

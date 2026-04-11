#!/usr/bin/env bash
#
# Full manual pairing test wizard. Walks a human from "fresh shell, phone
# plugged in" to "both devices paired, TOTP codes match", handling every
# step that doesn't require biometric/PIN entry by hand.
#
# Companion to `scripts/manual-emu.sh`. Intended for the issue-#3 "verify
# on real hardware" loop — the human exercises the biometric prompt and
# the import-from-image path, the script handles device detection, APK
# build, installs, emulator boot, QR screenshot ferrying, and the final
# TOTP match check.
#
# Device roles:
#   A = "show first" side — creates the first QR (default: the phone)
#   B = "scan first" side — imports A's QR, then shows its own (default: emu)
#
# Default flow:
#   0. Detect the single USB-connected phone; error if zero or >1
#   1. Build the debug APK if missing
#   2. Uninstall + reinstall com.whoarewe.app on the phone for clean state
#   3. Boot a fresh emulator via manual-emu.sh (wipes AVD, sets PIN,
#      installs APK, unlocks) — or reuse an already-running one on $PORT
#   4. Human: create identity on phone (biometric/PIN is yours)
#   5. Human: create identity on emulator (PIN bouncer uses --pin)
#   6. Pair wizard walkthrough:
#        a. Phone: Pair → Show my code first
#        b. Script ferries phone's QR into the emulator's gallery
#        c. Emulator: Pair → Scan their code first → Import QR from image,
#           pick newest, authenticate with PIN
#        d. Script ferries emulator's QR back into the phone's gallery
#        e. Phone: Next → Import QR from image, pick newest, authenticate
#   7. Script scrapes both devices' contact lists via uiautomator dump,
#      extracts the 6-digit TOTP code, asserts they match
#
# Usage:
#   scripts/manual-pair.sh                      # default: one phone + boot emu
#   scripts/manual-pair.sh --phone SERIAL       # force a specific phone
#   scripts/manual-pair.sh --emu emulator-5554  # reuse running emulator
#   scripts/manual-pair.sh --avd Pixel_9b       # pick a different AVD
#   scripts/manual-pair.sh --pin 9999           # non-default emulator PIN
#   scripts/manual-pair.sh --port 5556          # emulator on different port
#   scripts/manual-pair.sh --skip-install       # don't re-install on either
#   scripts/manual-pair.sh --swap-roles         # emulator is A (show first)
#
# Between each pause, press ENTER when the indicated action is done, or
# Ctrl+C to abort. The script only ever touches the two devices it has
# resolved as A and B; no other connected serial is ever targeted.

set -euo pipefail

ADB="${ADB:-adb}"
SHOT_DIR="/sdcard/Pictures/Screenshots"

PHONE=""
EMU=""
AVD=""
PIN="1234"
PORT="5554"
SKIP_INSTALL=0
SWAP_ROLES=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --phone)        PHONE="$2";       shift 2 ;;
        --emu)          EMU="$2";         shift 2 ;;
        --avd)          AVD="$2";         shift 2 ;;
        --pin)          PIN="$2";         shift 2 ;;
        --port)         PORT="$2";        shift 2 ;;
        --skip-install) SKIP_INSTALL=1;   shift ;;
        --swap-roles)   SWAP_ROLES=1;     shift ;;
        -h|--help)
            sed -n '3,44p' "$0" | sed 's/^#//; s/^ //'
            exit 0
            ;;
        *)
            echo "ERROR: unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"

pause() {
    read -r -p ">>> $1 Press ENTER to continue (Ctrl+C to abort)... " _
}

device_online() {
    local serial=$1
    "$ADB" devices 2>/dev/null \
        | awk -v s="$serial" 'NR>1 && $1==s && $2=="device" {found=1} END {exit !found}'
}

# ─────────────────────────────────────────────────────────────────────────
# 1. Resolve the phone serial
# ─────────────────────────────────────────────────────────────────────────
if [[ -z "$PHONE" ]]; then
    mapfile -t candidates < <(
        "$ADB" devices \
            | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1}'
    )
    if [[ ${#candidates[@]} -eq 0 ]]; then
        echo "ERROR: no USB phone detected. Plug in a device (with USB" >&2
        echo "       debugging authorised) or pass --phone SERIAL."       >&2
        exit 1
    elif [[ ${#candidates[@]} -gt 1 ]]; then
        echo "ERROR: multiple USB devices attached: ${candidates[*]}" >&2
        echo "       pass --phone SERIAL to pick one."                >&2
        exit 1
    fi
    PHONE="${candidates[0]}"
fi
echo "[manual-pair] phone: $PHONE"

# ─────────────────────────────────────────────────────────────────────────
# 2. Build APK if missing
# ─────────────────────────────────────────────────────────────────────────
if [[ ! -f "$APK" ]]; then
    echo "[manual-pair] debug APK missing; building..."
    (cd "$REPO_ROOT" && ./gradlew :app:assembleDebug --no-daemon)
fi

# ─────────────────────────────────────────────────────────────────────────
# 3. Install (clean) on phone
# ─────────────────────────────────────────────────────────────────────────
if [[ $SKIP_INSTALL -eq 0 ]]; then
    echo "[manual-pair] reinstalling com.whoarewe.app on $PHONE for clean state..."
    "$ADB" -s "$PHONE" uninstall com.whoarewe.app >/dev/null 2>&1 || true
    "$ADB" -s "$PHONE" install "$APK" >/dev/null
    echo "[manual-pair] installed on $PHONE"
fi

# ─────────────────────────────────────────────────────────────────────────
# 4. Resolve / boot the emulator
# ─────────────────────────────────────────────────────────────────────────
if [[ -z "$EMU" ]]; then
    EMU="emulator-$PORT"
    if device_online "$EMU"; then
        echo "[manual-pair] $EMU already running, reusing"
        if [[ $SKIP_INSTALL -eq 0 ]]; then
            echo "[manual-pair] reinstalling com.whoarewe.app on $EMU..."
            "$ADB" -s "$EMU" uninstall com.whoarewe.app >/dev/null 2>&1 || true
            "$ADB" -s "$EMU" install "$APK" >/dev/null
        fi
    else
        echo "[manual-pair] no emulator on port $PORT — booting via manual-emu.sh"
        EMU_ARGS=(--pin "$PIN" --port "$PORT")
        [[ -n "$AVD" ]] && EMU_ARGS+=(--avd "$AVD")
        "$SCRIPT_DIR/manual-emu.sh" "${EMU_ARGS[@]}"
    fi
else
    if ! device_online "$EMU"; then
        echo "ERROR: --emu $EMU is not online in adb devices" >&2
        exit 1
    fi
    if [[ $SKIP_INSTALL -eq 0 ]]; then
        echo "[manual-pair] reinstalling com.whoarewe.app on $EMU..."
        "$ADB" -s "$EMU" uninstall com.whoarewe.app >/dev/null 2>&1 || true
        "$ADB" -s "$EMU" install "$APK" >/dev/null
    fi
fi
echo "[manual-pair] emulator: $EMU"

# Launch the app on both sides so the human sees the Setup screen.
"$ADB" -s "$PHONE" shell am start -n com.whoarewe.app/.MainActivity >/dev/null
"$ADB" -s "$EMU"   shell am start -n com.whoarewe.app/.MainActivity >/dev/null

# ─────────────────────────────────────────────────────────────────────────
# 5. Role assignment
# ─────────────────────────────────────────────────────────────────────────
if [[ $SWAP_ROLES -eq 1 ]]; then
    A="$EMU"
    B="$PHONE"
else
    A="$PHONE"
    B="$EMU"
fi
echo
echo "Device A (show first): $A"
echo "Device B (scan first): $B"
echo

# ─────────────────────────────────────────────────────────────────────────
# 6. Identity creation
# ─────────────────────────────────────────────────────────────────────────
echo "── IDENTITY CREATION ──"
echo
echo "On $PHONE (your phone):"
echo "  1. The app should be on the Setup screen."
echo "  2. Type a display name (anything you like)."
echo "  3. Tap 'Create Identity' and authenticate with your biometric or PIN."
echo "  4. You should land on the Contacts list."
pause "Phone identity created?"

echo
echo "On $EMU (emulator):"
echo "  1. Click the emulator window to focus it."
echo "  2. Click the 'Your name' field and type a display name (e.g. Bob)."
echo "  3. Tap 'Create Identity'."
echo "  4. When the system PIN bouncer appears, type $PIN and press Enter."
echo "  5. You should land on the Contacts list."
pause "Emulator identity created?"

# ─────────────────────────────────────────────────────────────────────────
# 7. Pair wizard walkthrough
# ─────────────────────────────────────────────────────────────────────────
transfer_qr() {
    local from=$1 to=$2 label=$3
    local host_file="/tmp/manual-pair-${label}.png"
    local remote_file="$SHOT_DIR/manual-pair-${label}.png"
    echo "[manual-pair] screencapping $from"
    "$ADB" -s "$from" exec-out screencap -p > "$host_file"
    echo "[manual-pair] pushing $(basename "$remote_file") to $to"
    "$ADB" -s "$to" shell mkdir -p "$SHOT_DIR" 2>/dev/null || true
    "$ADB" -s "$to" push "$host_file" "$remote_file" > /dev/null
    "$ADB" -s "$to" shell am broadcast \
        -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
        -d "file://$remote_file" > /dev/null
    echo "[manual-pair] $to's gallery now has the new QR as its newest screenshot"
    echo
}

echo
echo "── PAIR WIZARD ──"
echo
echo "── Step 1: $A shows its code first ──"
echo "On $A:"
echo "  1. Tap the Pair icon (top right)"
echo "  2. Tap 'Show my code first'"
echo "  3. You should land on 'Show your code' with your QR visible"
pause "$A is showing its QR?"

transfer_qr "$A" "$B" "a-to-b"

echo "── Step 2: $B imports A's QR and shows its own ──"
echo "On $B:"
echo "  1. Tap the Pair icon (top right)"
echo "  2. Tap 'Scan their code first'"
echo "  3. Tap 'Import QR from image'"
echo "  4. Pick the newest screenshot (filename manual-pair-a-to-b.png)"
echo "  5. Authenticate at the biometric/PIN prompt"
if [[ "$B" == "$EMU" ]]; then
    echo "     (on the emulator, the PIN is $PIN)"
fi
echo "  6. $B should advance to 'Almost done' showing its own QR"
pause "$B is on 'Almost done'?"

transfer_qr "$B" "$A" "b-to-a"

echo "── Step 3: $A imports B's QR ──"
echo "On $A:"
echo "  1. Tap 'Next: Scan their code'"
echo "  2. Tap 'Import QR from image'"
echo "  3. Pick the newest screenshot (filename manual-pair-b-to-a.png)"
echo "  4. Authenticate at the biometric/PIN prompt"
if [[ "$A" == "$EMU" ]]; then
    echo "     (on the emulator, the PIN is $PIN)"
fi
echo "  5. $A should land on 'Paired!' — tap Done to return to Contacts"
pause "$A is paired and back on Contacts?"

echo "── Step 4: finish $B ──"
echo "On $B:"
echo "  1. Tap 'Done' on the 'Almost done' screen"
echo "  2. $B should return to Contacts"
pause "$B is back on Contacts?"

# ─────────────────────────────────────────────────────────────────────────
# 8. TOTP verification
# ─────────────────────────────────────────────────────────────────────────
echo
echo "── VERIFICATION: TOTP codes ──"

read_code() {
    local serial=$1
    "$ADB" -s "$serial" shell uiautomator dump /sdcard/dump.xml > /dev/null
    "$ADB" -s "$serial" shell cat /sdcard/dump.xml \
        | grep -oE 'text="[0-9]{3} [0-9]{3}"' \
        | head -n1 \
        | sed 's/^text="//; s/"$//'
}

code_a=$(read_code "$A")
code_b=$(read_code "$B")

echo "  $A: '${code_a:-<not found>}'"
echo "  $B: '${code_b:-<not found>}'"
echo

if [[ -z "$code_a" || -z "$code_b" ]]; then
    echo "RESULT: could not scrape a 6-digit code from one side."
    echo "        Check the contact list manually — both devices should show"
    echo "        a rotating 6-digit code, and the two should match."
    exit 2
fi

if [[ "$code_a" == "$code_b" ]]; then
    echo "RESULT: MATCH — both devices show '$code_a'"
    exit 0
else
    echo "RESULT: MISMATCH — $A shows '$code_a', $B shows '$code_b'"
    echo "        Codes are time-based, so a tiny race across a TOTP window"
    echo "        boundary is possible. Re-run the read in a second if you"
    echo "        think that's what happened; otherwise this is a real bug."
    exit 1
fi

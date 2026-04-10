#!/usr/bin/env bash
#
# Local maestro test loop, designed to run inside the `androidtest` compose
# service (cwage/whoarewe#13). Boots a headless API 28 emulator inside the
# container, installs the freshly-built debug APK, runs whatever maestro
# flow(s) you point it at, and tears down on exit.
#
# Usage (from the host):
#   docker compose run --rm androidtest scripts/local-maestro.sh
#   docker compose run --rm androidtest scripts/local-maestro.sh .maestro/setup-identity.yaml
#   docker compose run --rm androidtest scripts/local-maestro.sh .maestro/pair-wizard-navigation.yaml
#
# With no arguments, runs all flows in the .maestro/ directory in
# alphabetical order (the same default as `maestro test .maestro/`).
#
# Assumes:
#   - Container has /dev/kvm passthrough (compose handles this)
#   - The `android-avd` named volume is mounted at /data/avd (compose handles this)
#   - The project is bind-mounted at /project (compose handles this)
#
# Notes on what this script *does not* do:
#   - It does not bootstrap the bootstrapped-identity state for the
#     pair-wizard-navigation flow. That's the wrapper script's job, not
#     this one's. If you're running pair-wizard-navigation directly, you
#     need to fire the `e2e_create_identity` intent yourself first via:
#       adb shell am start -n com.whoarewe.app/.MainActivity --es e2e_create_identity TestUser
#     For now, run `setup-identity.yaml` standalone or pass both flows
#     in the right order with the bootstrap interleaved (mirrors what
#     .github/workflows/integration-tests.yml does in CI).

set -euo pipefail

AVD_NAME="${AVD_NAME:-whoarewe_test}"
SYSTEM_IMAGE="system-images;android-28;default;x86_64"
DEVICE_PROFILE="${DEVICE_PROFILE:-Nexus 6}"
APK_PATH="${APK_PATH:-app/build/outputs/apk/debug/app-debug.apk}"
EMULATOR_PORT=5554
EMULATOR_BOOT_TIMEOUT=180
PIN="${PIN:-1234}"
PKG="com.whoarewe.app"

log() { printf '[local-maestro] %s\n' "$*"; }

# ── sanity checks ───────────────────────────────────────────────────────────

if [[ ! -e /dev/kvm ]]; then
    echo "ERROR: /dev/kvm is not present in the container." >&2
    echo "       Did docker compose drop the devices: passthrough?" >&2
    echo "       Run via: docker compose run --rm androidtest scripts/local-maestro.sh ..." >&2
    exit 1
fi
if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
    echo "ERROR: /dev/kvm is not readable/writable by this user." >&2
    echo "       Check that compose has group_add: ['109'] (host kvm group)." >&2
    ls -la /dev/kvm >&2
    id >&2
    exit 1
fi

if ! command -v emulator >/dev/null; then
    echo "ERROR: emulator binary not on PATH. Are you running the wrong service?" >&2
    echo "       This script is meant to run inside the androidtest compose stage." >&2
    exit 1
fi
if ! command -v maestro >/dev/null; then
    echo "ERROR: maestro CLI not on PATH. Same as above." >&2
    exit 1
fi

if [[ ! -f "$APK_PATH" ]]; then
    log "APK not found at $APK_PATH — building it now via gradle"
    ./gradlew :app:assembleDebug --no-daemon
fi

# ── AVD bootstrap ───────────────────────────────────────────────────────────

if ! avdmanager list avd 2>/dev/null | grep -q "Name: ${AVD_NAME}"; then
    log "Creating AVD ${AVD_NAME} (${SYSTEM_IMAGE}, ${DEVICE_PROFILE})"
    echo "no" | avdmanager create avd \
        --force \
        -n "${AVD_NAME}" \
        --package "${SYSTEM_IMAGE}" \
        --device "${DEVICE_PROFILE}"
else
    log "Reusing existing AVD ${AVD_NAME}"
fi

# ── boot emulator ───────────────────────────────────────────────────────────

# Kill any stray emulator on the same port from a previous run.
adb kill-server >/dev/null 2>&1 || true
adb start-server >/dev/null 2>&1 || true

log "Booting emulator @${AVD_NAME} on port ${EMULATOR_PORT}"
nohup emulator \
    -avd "${AVD_NAME}" \
    -port "${EMULATOR_PORT}" \
    -no-snapshot-save \
    -no-window \
    -gpu swiftshader_indirect \
    -noaudio \
    -no-boot-anim \
    > /tmp/emulator.log 2>&1 &
EMULATOR_PID=$!

cleanup() {
    log "Shutting down emulator (pid ${EMULATOR_PID})"
    adb -s "emulator-${EMULATOR_PORT}" emu kill >/dev/null 2>&1 || true
    kill "${EMULATOR_PID}" 2>/dev/null || true
    wait "${EMULATOR_PID}" 2>/dev/null || true
}
trap cleanup EXIT

# Wait for boot. wait-for-device returns as soon as adb sees the device,
# but the device may still be booting userspace — poll sys.boot_completed.
adb -s "emulator-${EMULATOR_PORT}" wait-for-device
elapsed=0
while [[ "${elapsed}" -lt "${EMULATOR_BOOT_TIMEOUT}" ]]; do
    boot=$(adb -s "emulator-${EMULATOR_PORT}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    if [[ "${boot}" == "1" ]]; then
        log "Emulator booted after ${elapsed}s"
        break
    fi
    sleep 2
    elapsed=$((elapsed + 2))
done
if [[ "${boot:-0}" != "1" ]]; then
    echo "ERROR: emulator did not finish booting within ${EMULATOR_BOOT_TIMEOUT}s" >&2
    tail -50 /tmp/emulator.log >&2
    exit 1
fi

# ── device prep + APK install ───────────────────────────────────────────────

log "Setting device PIN to ${PIN}"
adb -s "emulator-${EMULATOR_PORT}" shell locksettings set-pin "${PIN}" >/dev/null

log "Installing ${APK_PATH}"
adb -s "emulator-${EMULATOR_PORT}" install -r "${APK_PATH}" >/dev/null

# ── run maestro ─────────────────────────────────────────────────────────────

if [[ $# -eq 0 ]]; then
    set -- .maestro/
fi

log "Running maestro test $*"
maestro test "$@"
log "Maestro flow(s) passed."

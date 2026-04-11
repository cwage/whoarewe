#!/usr/bin/env bash
#
# Local instrumented-test loop, designed to run inside the `androidtest`
# compose service. Sibling to scripts/local-maestro.sh: boots a headless
# API 28 emulator inside the container, then runs
# `./gradlew :app:connectedDebugAndroidTest`, which is the same task the
# `integration-tests.yml` GitHub Actions workflow runs remotely. Use this
# to verify instrumented tests (anything under `app/src/androidTest/`)
# without round-tripping through CI.
#
# Usage (from the host):
#   export HOST_UID=$(id -u) HOST_GID=$(id -g)
#   export KVM_GID=$(getent group kvm | cut -d: -f3)
#   docker compose run --rm androidtest scripts/local-instrumented.sh
#
# Optional: pass a fully-qualified class filter as arg 1 to scope the run,
# e.g.
#   docker compose run --rm androidtest scripts/local-instrumented.sh \
#       com.whoarewe.app.WhoAreWeViewModelCollisionTest
# Anything after the first arg is forwarded to gradle verbatim.
#
# The boot sequence is deliberately duplicated from local-maestro.sh rather
# than sourced — sourcing a bash script that invokes `exit`/`trap` for its
# own lifecycle from inside another trap-owning script is a debugging
# nightmare, and the ~40 lines of boot glue are stable enough that a copy
# is cheaper than the abstraction. If the two ever drift in a way that
# matters, factor then — not before.

set -euo pipefail

AVD_NAME="${AVD_NAME:-whoarewe_test}"
SYSTEM_IMAGE="system-images;android-28;default;x86_64"
DEVICE_PROFILE="${DEVICE_PROFILE:-Nexus 6}"
EMULATOR_PORT=5554
EMULATOR_BOOT_TIMEOUT=180
PIN="${PIN:-1234}"

log() { printf '[local-instrumented] %s\n' "$*"; }

# ── sanity checks ───────────────────────────────────────────────────────────

if [[ ! -e /dev/kvm ]]; then
    echo "ERROR: /dev/kvm is not present in the container." >&2
    echo "       Run via: docker compose run --rm androidtest scripts/local-instrumented.sh" >&2
    exit 1
fi
if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
    echo "ERROR: /dev/kvm is not readable/writable by this user." >&2
    echo "       Set KVM_GID=\$(getent group kvm | cut -d: -f3) before compose." >&2
    ls -la /dev/kvm >&2
    id >&2
    exit 1
fi

if ! command -v emulator >/dev/null; then
    echo "ERROR: emulator binary not on PATH. Wrong compose service?" >&2
    exit 1
fi

# ── AVD bootstrap ───────────────────────────────────────────────────────────

if ! avdmanager list avd 2>/dev/null | grep -qxF "    Name: ${AVD_NAME}"; then
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

EMULATOR_SERIAL="emulator-${EMULATOR_PORT}"
STARTED_EMULATOR=0
EMULATOR_PID=""

adb kill-server >/dev/null 2>&1 || true
adb start-server >/dev/null 2>&1 || true

if adb devices | awk -v s="${EMULATOR_SERIAL}" '$1 == s && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }'; then
    log "Reusing existing emulator on ${EMULATOR_SERIAL}"
else
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
    STARTED_EMULATOR=1
fi

cleanup() {
    if [[ "${STARTED_EMULATOR}" -eq 1 ]]; then
        log "Shutting down emulator (pid ${EMULATOR_PID})"
        adb -s "${EMULATOR_SERIAL}" emu kill >/dev/null 2>&1 || true
        kill "${EMULATOR_PID}" 2>/dev/null || true
        wait "${EMULATOR_PID}" 2>/dev/null || true
    else
        log "Leaving existing emulator ${EMULATOR_SERIAL} running"
    fi
}
trap cleanup EXIT

adb -s "${EMULATOR_SERIAL}" wait-for-device
elapsed=0
boot=""
while [[ "${elapsed}" -lt "${EMULATOR_BOOT_TIMEOUT}" ]]; do
    boot=$(adb -s "${EMULATOR_SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
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

# ── device prep ─────────────────────────────────────────────────────────────

log "Setting device PIN to ${PIN}"
adb -s "${EMULATOR_SERIAL}" shell locksettings set-pin "${PIN}" >/dev/null

# ── gradle connectedDebugAndroidTest ────────────────────────────────────────

# `connectedDebugAndroidTest` handles APK install + test APK install + test
# execution itself, so we don't duplicate that here. A class filter can be
# passed as the first argument; everything after is forwarded verbatim so
# callers can add e.g. `--info` or `--stacktrace`.

gradle_args=()
if [[ $# -gt 0 ]]; then
    gradle_args+=("-Pandroid.testInstrumentationRunnerArguments.class=$1")
    shift
    gradle_args+=("$@")
fi

log "Running ./gradlew :app:connectedDebugAndroidTest ${gradle_args[*]:-}"
./gradlew :app:connectedDebugAndroidTest --no-daemon "${gradle_args[@]}"

log "connectedDebugAndroidTest passed."

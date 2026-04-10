#!/usr/bin/env bash
#
# CI wrapper for scripts/e2e-pairing.sh.
#
# Runs inside the reactivecircus/android-emulator-runner action's `script:`
# block, which spawns each YAML line as its own `sh -c` (so variables don't
# persist). Putting everything in one .sh file dodges that, and also lets us
# stay in bash instead of dash.
#
# The runner has already started emulator-5554 by the time this is invoked. We
# launch a second emulator on port 5556 using the same AVD definition, wait
# for it to boot, then hand both serials off to the e2e harness.

set -euxo pipefail

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
EMULATOR="$SDK/emulator/emulator"
ADB="$SDK/platform-tools/adb"

AVD=$("$EMULATOR" -list-avds | head -1)
if [[ -z "$AVD" ]]; then
    echo "ERROR: no AVD found; the runner should have created one" >&2
    exit 1
fi
echo "[ci-e2e] using AVD: $AVD"

nohup "$EMULATOR" -avd "$AVD" \
    -port 5556 \
    -no-snapshot-save -no-window -gpu swiftshader_indirect \
    -noaudio -no-boot-anim -read-only \
    > /tmp/emulator-5556.log 2>&1 &

"$ADB" -s emulator-5556 wait-for-device
for i in $(seq 1 240); do
    val=$("$ADB" -s emulator-5556 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    if [[ "$val" == "1" ]]; then
        echo "[ci-e2e] emulator-5556 booted after ${i}s"
        break
    fi
    sleep 1
done

"$ADB" devices

exec bash ./scripts/e2e-pairing.sh emulator-5554 emulator-5556

#!/usr/bin/env bash
#
# Build a signed release APK locally.
#
# Expects two environment variables:
#   KEYSTORE_PATH      — path to the release .jks keystore
#   KEYSTORE_PASSWORD  — password for the keystore and key
#
# The key alias is "whoarewe" (hardcoded in build.gradle.kts).
#
# Usage:
#   KEYSTORE_PATH=~/keys/whoarewe.jks KEYSTORE_PASSWORD=secret \
#       scripts/build-release.sh
#
# The signed APK is copied to the repo root as whoarewe-<version>.apk.

set -euo pipefail

if [[ -z "${KEYSTORE_PATH:-}" ]]; then
    echo >&2 "ERROR: KEYSTORE_PATH is not set"
    echo >&2 "  export KEYSTORE_PATH=/path/to/whoarewe-release.jks"
    exit 1
fi

if [[ -z "${KEYSTORE_PASSWORD:-}" ]]; then
    echo >&2 "ERROR: KEYSTORE_PASSWORD is not set"
    exit 1
fi

if [[ ! -f "$KEYSTORE_PATH" ]]; then
    echo >&2 "ERROR: keystore not found at $KEYSTORE_PATH"
    exit 1
fi

export KEYSTORE_PATH KEYSTORE_PASSWORD

./gradlew :app:assembleRelease --no-daemon

APK_SRC="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK_SRC" ]]; then
    echo >&2 "ERROR: expected APK not found at $APK_SRC"
    exit 1
fi

# VERSION override > CI ref name > exact git tag > "dev".
VERSION="${VERSION:-${GITHUB_REF_NAME:-$(git describe --tags --exact-match 2>/dev/null || echo "dev")}}"
DEST="whoarewe-${VERSION}.apk"

cp "$APK_SRC" "$DEST"
echo "Built: $DEST"

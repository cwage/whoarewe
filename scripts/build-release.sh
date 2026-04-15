#!/usr/bin/env bash
#
# Build a release APK locally.
#
# By default, builds a signed APK and expects:
#   KEYSTORE_PATH      — path to the release .jks keystore
#   KEYSTORE_PASSWORD  — password for the keystore and key
#
# The key alias is "whoarewe" (hardcoded in build.gradle.kts).
#
# Usage:
#   KEYSTORE_PATH=~/keys/whoarewe.jks KEYSTORE_PASSWORD=secret \
#       scripts/build-release.sh
#
#   scripts/build-release.sh --unsigned   # skip signing, for sideloading
#
# The APK is copied to the repo root as whoarewe-<version>.apk.

set -euo pipefail

UNSIGNED=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --unsigned) UNSIGNED=1; shift ;;
        -h|--help)
            sed -n '3,17p' "$0" | sed 's/^#//'
            exit 0
            ;;
        *)
            echo >&2 "ERROR: unknown argument: $1"
            exit 1
            ;;
    esac
done

if [[ "$UNSIGNED" -eq 0 ]]; then
    if [[ -z "${KEYSTORE_PATH:-}" ]]; then
        echo >&2 "ERROR: KEYSTORE_PATH is not set (use --unsigned to skip signing)"
        echo >&2 "  export KEYSTORE_PATH=/path/to/whoarewe-release.jks"
        exit 1
    fi

    if [[ -z "${KEYSTORE_PASSWORD:-}" ]]; then
        echo >&2 "ERROR: KEYSTORE_PASSWORD is not set (use --unsigned to skip signing)"
        exit 1
    fi

    if [[ ! -f "$KEYSTORE_PATH" ]]; then
        echo >&2 "ERROR: keystore not found at $KEYSTORE_PATH"
        exit 1
    fi

    export KEYSTORE_PATH KEYSTORE_PASSWORD
else
    unset KEYSTORE_PATH KEYSTORE_PASSWORD 2>/dev/null || true
fi

./gradlew :app:assembleRelease --no-daemon

if [[ "$UNSIGNED" -eq 0 ]]; then
    APK_SRC="app/build/outputs/apk/release/app-release.apk"
else
    APK_SRC="app/build/outputs/apk/release/app-release-unsigned.apk"
fi

if [[ ! -f "$APK_SRC" ]]; then
    echo >&2 "ERROR: expected APK not found at $APK_SRC"
    exit 1
fi

# VERSION override > CI ref name > exact git tag > "dev".
VERSION="${VERSION:-${GITHUB_REF_NAME:-$(git describe --tags --exact-match 2>/dev/null || echo "dev")}}"
DEST="whoarewe-${VERSION}.apk"

cp "$APK_SRC" "$DEST"
echo "Built: $DEST"

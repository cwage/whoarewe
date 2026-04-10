# Multi-stage build for the WhoAreWe project's local dev container.
#
# Stage `build` is the slim base — JDK 21, Android cmdline-tools, platform 35,
# build-tools 35.0.0. Sufficient for `./gradlew :app:assembleDebug` and
# `:app:testDebugUnitTest`. This is what `docker compose run --rm build` uses.
#
# Stage `androidtest` extends `build` with what's needed to drive a live
# emulator: the `emulator` package, the API 28 system image (matching the
# CI matrix entry that exercises the legacy biometric path), and the maestro
# CLI. Used by `scripts/local-maestro.sh` for the local maestro iteration loop
# (cwage/whoarewe#13).
#
# The androidtest stage is intentionally fat (~2-3GB more than `build`) and
# only built/pulled when you actually want to run a flow against a real device.
# Most local iterations are just gradle and should keep using `build`.

# ── Stage 1: build ──────────────────────────────────────────────────────────

FROM eclipse-temurin:21-jdk-jammy AS build

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=${ANDROID_HOME}
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/35.0.0:${PATH}"

# System deps
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    unzip \
    git \
    && rm -rf /var/lib/apt/lists/*

# Android command-line tools
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools && \
    mv /tmp/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools

# Accept licenses and install SDK components
RUN yes | sdkmanager --licenses > /dev/null 2>&1 && \
    sdkmanager \
    "platform-tools" \
    "platforms;android-35" \
    "build-tools;35.0.0" \
    "extras;google;m2repository" \
    "extras;android;m2repository"

WORKDIR /project

# Gradle will cache deps in a volume. Pre-create the dir with world-write
# so when the named volume mounts here on first run, Docker propagates
# those permissions to the volume — otherwise the volume comes up owned
# by root and a non-root container user can't write to it.
ENV GRADLE_USER_HOME=/gradle-cache
RUN mkdir -p /gradle-cache && chmod 777 /gradle-cache

# Android SDK needs a writable home for analytics/settings
ENV ANDROID_USER_HOME=/tmp/.android
RUN mkdir -p /tmp/.android && chmod 777 /tmp/.android

# Make SDK writable so Gradle can auto-install missing components
RUN chmod -R a+w ${ANDROID_HOME}

# ── Stage 2: androidtest ────────────────────────────────────────────────────

FROM build AS androidtest

# Native libraries the Android emulator needs even with `-no-window
# -gpu swiftshader_indirect -noaudio`. The headless emulator still links
# against pulse, mesa, and assorted X libs at startup.
RUN apt-get update && apt-get install -y --no-install-recommends \
    libpulse0 \
    libnss3 \
    libxcomposite1 \
    libxcursor1 \
    libxi6 \
    libxtst6 \
    libxrandr2 \
    libasound2 \
    libatk1.0-0 \
    libcups2 \
    libgl1 \
    libglu1-mesa \
    libxss1 \
    libgbm1 \
    libdrm2 \
    && rm -rf /var/lib/apt/lists/*

# Emulator package + the API 28 system image. API 28 matches the
# `instrumented-tests (28)` and `pairing (28)` matrix entries in CI, so
# whatever passes here also exercises the legacy biometric / API 28 code
# paths. This is the largest single thing in the image — ~700MB just for
# the system image — but it's installed once and cached in the image layer.
RUN sdkmanager \
    "emulator" \
    "system-images;android-28;default;x86_64" && \
    chmod -R a+w ${ANDROID_HOME}

# Maestro CLI. Pulled directly from the github release so the installed
# version is reproducible from this Dockerfile alone (no curl|bash, no
# implicit "latest" drift between rebuilds — though the URL still resolves
# to "latest" until we pin a specific tag in a follow-up).
RUN curl -fsSL https://github.com/mobile-dev-inc/maestro/releases/latest/download/maestro.zip -o /tmp/maestro.zip && \
    unzip -q /tmp/maestro.zip -d /opt && \
    rm /tmp/maestro.zip && \
    chmod -R a+rx /opt/maestro

# Add the emulator dir AND maestro/bin to PATH. The emulator binary lives
# in $ANDROID_HOME/emulator/, which the slim build stage doesn't bother
# adding because the build path doesn't need it.
ENV PATH="${ANDROID_HOME}/emulator:/opt/maestro/bin:${PATH}"
ENV MAESTRO_CLI_NO_ANALYTICS=1

# Persistent location for AVDs. The compose service mounts a named volume
# here so AVD config + userdata survives across `docker compose run --rm`
# invocations and we don't reformat the AVD on every iteration.
ENV ANDROID_AVD_HOME=/data/avd
RUN mkdir -p /data/avd && chmod 777 /data /data/avd

# Compose runs the container as the host UID with no /etc/passwd entry,
# which leaves HOME unset. adb / maestro / various Android tools all
# resolve `~/.foo` paths and end up trying to write to `/.foo` (root of
# filesystem), which fails. Point HOME at a writable scratch dir owned
# by anyone so tools can create their own state under it.
ENV HOME=/tmp/home
RUN mkdir -p /tmp/home && chmod 777 /tmp/home

# CLAUDE.md

Guidance for Claude Code when working on this project. Distilled from the original `project-plan.md`, trimmed to the actionable parts. The conceptual explainers live in [`docs/pairing.md`](docs/pairing.md) and [`README.md`](README.md) — keep this file short and focused on *what to do and what to avoid*.

## What this project is

WhoAreWe is an Android app for pairwise identity verification over untrusted channels. Two people pair once (in person or via a trusted side channel), and from then on each phone displays a rotating six-digit TOTP code for the other — used to verify a caller / texter / emailer is who they claim to be, against deepfake-voice and impersonation attacks.

The entire value proposition is the cryptographic guarantee: *"An adversary who does not hold either device's private key cannot, at any later time and over any channel, convince one paired party that they are the other."* Everything else in the app is UI over that invariant.

## Design principles — non-negotiable

These are product invariants. A change proposal that violates one of these should be pushed back on, not implemented.

- **Offline-first, TOTP-first.** Verification must work with zero network. No server, no account, no cloud, no push. Not "mostly offline with a fallback."
- **Device-bound keys.** Private keys never leave the Android Keystore. No export, no backup, no cloud sync.
- **Pairwise trust only.** No group secrets, no web of trust, no transitive trust, no cross-signing. Each contact is an independent 1:1 pairing.
- **Conservative recovery.** Lost device → re-pair in person. No SMS, email, or cloud recovery. Degraded state is "no verification available," which is the status quo today — an acceptable failure mode.
- **Channel-agnostic verification.** The app doesn't care how a suspicious contact happened — it's just a code you can ask the other side to read.
- **No messaging.** This is not a chat app and never will be. It shows codes, nothing else.

## Out of scope — do not build without asking

- Cloud backup, cloud sync, or any server component
- Group / family shared secrets
- Web of trust, transitive trust, cross-signing
- Challenge/response verification (TOTP is the primary mechanism)
- Any chat / messaging functionality
- Desktop, web, browser extension
- iOS (Android-first until the Android version is proven)
- Integration with the phone / PSTN system

## Current state (as of last commit on `main`)

Core pair-and-verify loop works end-to-end on API 30+:

- **Crypto**: Ed25519 keygen via BouncyCastle, Ed25519→X25519 conversion + ECDH shared-secret derivation, RFC 6238 TOTP, ZXing QR encode/decode. All in `app/src/main/java/com/whoarewe/app/crypto/`.
- **Keystore**: `KeyManager` wraps Android Keystore with an AES-GCM key that encrypts the Ed25519 private key at rest. Biometric/device-credential unlock gated by `BiometricPrompt`.
- **UI**: Jetpack Compose. Four screens — Setup, ContactList, PairWizard (Choose / ShowFirst / ScanAfterShow / ShowAfterScan / Done), QrDisplay. Single `WhoAreWeViewModel`.
- **Data**: Room DB with two tables — `Identity` (one row) and `TrustedContact` (many rows).
- **Tests**: unit (`app/src/test/`), instrumented (`PairingIntegrationTest` in `app/src/androidTest/`), Maestro flows (`.maestro/`), adb-driven two-emulator e2e (`scripts/e2e-pairing.sh`, workflow `.github/workflows/e2e.yml`). Full pyramid and known gaps in [`docs/testing.md`](docs/testing.md).

### Pre-R / API 28 biometric path

`KeyManager` configures the keystore key differently by API level. On API ≥ R it uses `setUserAuthenticationParameters`, so `cipher.init()` runs upfront and `BiometricPrompt` unlocks the cipher via `CryptoObject` on success. On API < R it uses the legacy `setUserAuthenticationValidityDurationSeconds(10)`, which means `cipher.init()` itself enforces the auth window — and would throw `UserNotAuthenticatedException` if called *before* the user has authenticated.

To accommodate both:

- `KeyManager.usesLegacyAuth()` returns `true` on API < R.
- The vm's `requestGenerateIdentity` and the `AddContact` path check that flag and either pre-init the cipher (modern) or leave `BiometricRequest.cipher = null` (legacy).
- `BiometricGate` looks at `request.cipher`: non-null → `prompt.authenticate(info, CryptoObject(cipher))`; null → `prompt.authenticate(info)` only, then calls `onSuccess(null)` to signal "deferred."
- `vm.onBiometricSuccess(cipher: Cipher?)` acquires the cipher post-auth when it's null, inside the freshly-refreshed validity window.

If you change anything in this path, run the e2e job locally against an API 28 emulator, not just API 33 — the modern path is the easy case to keep working.

## Known gaps from the original MVP scope (not yet implemented or unverified)

From the old `project-plan.md` MVP list — some items are shipped, some aren't. Worth confirming and filing issues during status review:

- **Key change warning.** If a scanned QR matches a name but a different public key, warn the user and require explicit confirmation. Current `onQrScanned` dedupes by public key, not by name — a name collision with a different key would silently add a new row.
- **Clock skew tolerance.** `TotpGenerator` accepts a `periodSeconds` parameter (defaults to `TotpGenerator.PERIOD_SECONDS = 300L`, the single app-wide tunable). The displayed code is whatever the local wall clock says right now — there is no asymmetric verifier that could apply RFC 6238's ±1 window tolerance, so the only mitigation against display-side window-boundary races is the period itself. The period was bumped from 60s to 300s to address cwage/whoarewe#8; see `docs/pairing.md` for the trade-off discussion. Tune by changing `PERIOD_SECONDS`; nothing else hard-codes a step size.
- **`KeyInfo.isInsideSecureHardware()` surfacing.** The app should detect when the Keystore has silently fallen back to software-backed storage and either warn the user or log it. Not implemented.
- **Shared secret at rest.** The ECDH-derived TOTP secret is currently stored as hex in Room, unencrypted. Should probably be upgraded to live in a Keystore-encrypted blob like the identity private key. Risk: on a compromised / rooted device the shared secret is extractable.

## Crypto review — before claiming anything is "secure"

These are the paths where it's easy to produce plausible-looking code that passes functional tests while being subtly wrong. Do not let them drift without review:

1. **Ed25519 → X25519 conversion** (`EcdhExchange.kt`). Uses BouncyCastle primitives end-to-end: `Ed25519.validatePublicKeyPartial` enforces canonical encoding (y < p per RFC 8032 §5.1.3) and on-curve membership, and `X25519Field` computes the birational map `u = (1 + y) / (1 − y) mod p`. The identity point (y == 1) is rejected explicitly because the denominator is singular there. Do not reintroduce hand-rolled `BigInteger` math in this file — cwage/whoarewe#21 was specifically about removing it. Callers must run `EcdhExchange.isValidEd25519PublicKey` *before* requesting a biometric unlock so a hostile QR cannot burn an auth prompt just to fail mid-pair.
2. **TOTP correctness.** RFC 6238 Appendix B test vectors must pass. `TotpGeneratorTest` covers this — if it ever starts failing, that is not a "fix the test" situation.
3. **QR payload parsing.** Must reject malformed input, not be lenient. `QrCodeUtils.decode()` rejects on length and prefix mismatches; fuzz coverage is modest.
4. **Hex encoding.** All byte ↔ hex conversion must go through `HexCodec.bytesToHex` / `HexCodec.hexToBytes` (cwage/whoarewe#15). Do **not** reimplement inline. The obvious-looking `joinToString { "%02x".format(it) }` pattern is silently wrong for any byte ≥ 0x80 because Kotlin `Byte` is signed and `String.format` sign-extends to `Int`. The bug went undetected for a long time because both sides of every comparison applied the same buggy encoder, so the symmetric corruption cancelled out functionally — but the stored hex strings did not actually round-trip back to the original bytes. `HexCodecTest` covers the regression; if you find yourself adding a new `%02x` site, route it through `HexCodec` instead.
5. **Keystore hardware-backing detection** — not yet implemented, listed above.
6. **Shared secret at rest** — not yet hardened, listed above.

Rule: do not claim "the crypto is correct" without running test vectors, and do not hand-roll new crypto without a very explicit reason and human review.

## Conventions

- **Kotlin style**: match the existing code. No wildcard imports, four-space indentation, trailing commas on multi-line argument lists.
- **Comments**: only where the *reason* isn't obvious from the code. Don't narrate what the next line does.
- **Commits**: no AI attribution. No `Co-Authored-By: Claude`, no "Generated with Claude Code" footers. User's global `~/.claude/CLAUDE.md` governs.
- **PR bodies**: always use `gh pr create --body-file /tmp/pr.md` — see the user's global rule about shell mangling of backticks and colon-tagged strings.
- **Commit scope**: prefer multiple small coherent commits over one mega-commit when the changes are logically separable.
- **Before running destructive git operations** (reset --hard, force push, branch delete), ask.

## Running tests — use local CI, NOT the attached phone

**This project has dockerized local CI.** The exact same images and commands CI runs on GitHub Actions run locally via `docker compose`. Before pushing a branch, the expectation is that you run the relevant local CI jobs and see them pass — not push to origin and watch remote CI.

Do **not**:

- Run `./gradlew :app:connectedDebugAndroidTest` against a device attached via `adb`. Any device that shows up in `adb devices` is the user's personal phone, likely locked, and is not a test target.
- Run `maestro test` against an attached device for the same reason.
- Suggest "push the branch and let CI run" as a substitute for running local CI. Local CI is the same thing, it just doesn't round-trip through GitHub.
- Install the debug APK onto a device that appears in `adb devices` without being explicitly asked to.

Do:

- Run unit tests / builds in the `build` compose stage.
- Run instrumented + maestro + e2e tests in the `androidtest` compose stage (boots an emulator inside the container, `/dev/kvm` passthrough).
- Treat the `build` and `androidtest` services as the source of truth for "does it pass CI."

### Local CI commands

```
# Unit tests + debug build (fast, no emulator)
docker compose run --rm build ./gradlew :app:testDebugUnitTest :app:assembleDebug

# Maestro flows (boots a containerized API 28 emulator, runs the same
# invocation CI uses in .github/workflows/integration-tests.yml)
export HOST_UID=$(id -u) HOST_GID=$(id -g)
export KVM_GID=$(getent group kvm | cut -d: -f3)
docker compose run --rm androidtest scripts/local-maestro.sh \
    .maestro/setup-identity.yaml \
    --bootstrap-identity TestUser \
    .maestro/pair-wizard-navigation.yaml

# E2E pairing on two host emulators (two AVDs required)
./scripts/run-local-e2e.sh --kill-on-exit
```

`KVM_GID` varies per distro, which is why compose/the wrapper scripts derive it at runtime — `${UID:-1000}` doesn't work because `UID` is a bash built-in that isn't exported to subprocess environments (the note at the top of `docker-compose.yml` explains the gotcha in detail). If `/dev/kvm` isn't mode `0666` on the host, set `KVM_GID` explicitly or the emulator inside the androidtest container can't boot.

### Manual / real hardware scripts (NOT tests)

These are for hands-on exploration, not CI substitutes:

```
./scripts/manual-emu.sh                                     # boot one clean PIN'd emulator
./scripts/manual-pair.sh                                    # interactive phone+emulator pairing wizard
```

Requirements: JDK 21, Android SDK (`minSdk` 28, `targetSdk` 35). E2E needs two Android emulators. Both the legacy (API ≤ 29) and modern (API ≥ 30) Keystore-auth paths are exercised by the `pairing (28)` and `pairing (33)` jobs in `.github/workflows/e2e.yml`, so if you touch `KeyManager.ensureKeyStoreKey` or the `BiometricGate` composable, run both locally before pushing.

## Tests vs. reality — don't undo the intent seam

The e2e harness is the only test that exercises the real biometric Keystore path. It deliberately skips the camera and photo picker via a debug-only intent seam (`e2e_dump_qr` / `e2e_inject_qr` in `MainActivity.handleE2eIntent`). This is a conscious trade for reliability against Pixel emulator photo picker flakiness — see [`docs/testing.md`](docs/testing.md) "Known gaps" and "Why the intent seam".

Don't "fix" this by ripping out the seam without understanding why it exists. If the physical QR round-trip ever needs to be tested, add a second slower opt-in variant — don't replace this one.

## Pointers

| File | What |
| --- | --- |
| [`README.md`](README.md) | Project overview, build/test quick-start |
| [`docs/pairing.md`](docs/pairing.md) | Crypto explainer, threat model |
| [`docs/testing.md`](docs/testing.md) | Automated test layers and the e2e intent-seam rationale |
| [`docs/manual-testing.md`](docs/manual-testing.md) | Real-device manual workflow (phone + emulator) |

# CLAUDE.md

Guidance for Claude Code when working on this project. Distilled from the original `project-plan.md`, trimmed to the actionable parts. The conceptual explainers live in [`docs/pairing.md`](docs/pairing.md) and [`README.md`](README.md) — keep this file short and focused on *what to do and what to avoid*.

## What this project is

WhoAmI is an Android app for pairwise identity verification over untrusted channels. Two people pair once (in person or via a trusted side channel), and from then on each phone displays a rotating six-digit TOTP code for the other — used to verify a caller / texter / emailer is who they claim to be, against deepfake-voice and impersonation attacks.

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

- **Crypto**: Ed25519 keygen via BouncyCastle, Ed25519→X25519 conversion + ECDH shared-secret derivation, RFC 6238 TOTP, ZXing QR encode/decode. All in `app/src/main/java/com/whoami/app/crypto/`.
- **Keystore**: `KeyManager` wraps Android Keystore with an AES-GCM key that encrypts the Ed25519 private key at rest. Biometric/device-credential unlock gated by `BiometricPrompt`.
- **UI**: Jetpack Compose. Four screens — Setup, ContactList, PairWizard (Choose / ShowFirst / ScanAfterShow / ShowAfterScan / Done), QrDisplay. Single `WhoAmIViewModel`.
- **Data**: Room DB with two tables — `Identity` (one row) and `TrustedContact` (many rows).
- **Tests**: unit (`app/src/test/`), instrumented (`PairingIntegrationTest` in `app/src/androidTest/`), Maestro flows (`.maestro/`), adb-driven two-emulator e2e (`scripts/e2e-pairing.sh`, workflow `.github/workflows/e2e.yml`). Full pyramid and known gaps in [`docs/testing.md`](docs/testing.md).

### Known issues / open tracking

- **cwage/whoarewe#6** — biometric identity creation fails on API ≤ 29. `KeyManager.getEncryptionCipher()` calls `cipher.init()` before the biometric prompt runs, and the legacy `setUserAuthenticationValidityDurationSeconds(10)` path throws `UserNotAuthenticatedException`. e2e CI is pinned to API 33 as a workaround; real fix needs a pre-R code path that prompts first, inits second. `minSdk` is currently 28 so this ships broken on Pie.
- **cwage/whoarewe#3, #4** — the tracking issues for the e2e test work that's now landing in PR #5.

## Known gaps from the original MVP scope (not yet implemented or unverified)

From the old `project-plan.md` MVP list — some items are shipped, some aren't. Worth confirming and filing issues during status review:

- **Key change warning.** If a scanned QR matches a name but a different public key, warn the user and require explicit confirmation. Current `onQrScanned` dedupes by public key, not by name — a name collision with a different key would silently add a new row.
- **Clock skew tolerance.** `TotpGenerator.generateCode(secret, time)` uses a fixed 60-second window. The spec calls for accepting adjacent windows on verification. Currently each side just displays its own window's code — no tolerance logic exists because there's no "verify" function on the receiving side (humans compare).
- **`KeyInfo.isInsideSecureHardware()` surfacing.** The app should detect when the Keystore has silently fallen back to software-backed storage and either warn the user or log it. Not implemented.
- **Shared secret at rest.** The ECDH-derived TOTP secret is currently stored as hex in Room, unencrypted. Should probably be upgraded to live in a Keystore-encrypted blob like the identity private key. Risk: on a compromised / rooted device the shared secret is extractable.

## Crypto review — before claiming anything is "secure"

These are the paths where it's easy to produce plausible-looking code that passes functional tests while being subtly wrong. Do not let them drift without review:

1. **Ed25519 → X25519 conversion** (`EcdhExchange.kt`). Uses BouncyCastle primitives — do not replace with hand-rolled math.
2. **TOTP correctness.** RFC 6238 Appendix B test vectors must pass. `TotpGeneratorTest` covers this — if it ever starts failing, that is not a "fix the test" situation.
3. **QR payload parsing.** Must reject malformed input, not be lenient. `QrCodeUtils.decode()` rejects on length and prefix mismatches; fuzz coverage is modest.
4. **Keystore hardware-backing detection** — not yet implemented, listed above.
5. **Shared secret at rest** — not yet hardened, listed above.

Rule: do not claim "the crypto is correct" without running test vectors, and do not hand-roll new crypto without a very explicit reason and human review.

## Conventions

- **Kotlin style**: match the existing code. No wildcard imports, four-space indentation, trailing commas on multi-line argument lists.
- **Comments**: only where the *reason* isn't obvious from the code. Don't narrate what the next line does.
- **Commits**: no AI attribution. No `Co-Authored-By: Claude`, no "Generated with Claude Code" footers. User's global `~/.claude/CLAUDE.md` governs.
- **PR bodies**: always use `gh pr create --body-file /tmp/pr.md` — see the user's global rule about shell mangling of backticks and colon-tagged strings.
- **Commit scope**: prefer multiple small coherent commits over one mega-commit when the changes are logically separable.
- **Before running destructive git operations** (reset --hard, force push, branch delete), ask.

## Common commands

```
# Build
./gradlew :app:assembleDebug

# Test layers
./gradlew :app:testDebugUnitTest                            # unit
./gradlew :app:connectedDebugAndroidTest                    # instrumented (needs device)
maestro test .maestro/                                      # smoke flows
./gradlew e2ePairing -PdeviceA=<a> -PdeviceB=<b>            # e2e explicit serials
./scripts/run-local-e2e.sh --kill-on-exit                   # e2e with auto-booted emulators
```

Requirements: JDK 21, Android SDK (`minSdk` 28, `targetSdk` 35). E2E needs two Android emulators (API 30+ until cwage/whoarewe#6 is fixed).

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

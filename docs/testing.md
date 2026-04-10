# Testing

Four layers, each covering different concerns.

## Unit tests — `./gradlew :app:testDebugUnitTest`

Pure-JVM tests in `app/src/test/`. Cover the deterministic math that does not need a device:

- **`EcdhExchangeTest`** — X25519 shared-secret derivation, including the commutativity property: `mix(a_priv, b_pub) == mix(b_priv, a_pub)`.
- **`TotpGeneratorTest`** — RFC 6238 TOTP generation at known vectors and 60-second window math.
- **`QrCodeUtilsTest`** — encode/decode round-trip, malformed payload rejection.

Fast. Runs on every commit. No device, emulator, or Android runtime required.

## Instrumented tests — `./gradlew :app:connectedDebugAndroidTest`

JUnit4 tests in `app/src/androidTest/` that run inside a real Android VM. Cover anything the JVM cannot:

- **`PairingIntegrationTest`** — full crypto pipeline through an in-memory Room database. Generates Ed25519 keypairs, QR-encodes Alice's public key, QR-decodes it from "Bob's side", derives shared secrets both ways, stores the contact row, and asserts matching TOTP codes across several time windows. Also covers duplicate-detection by public key and multi-contact independence.

Runs on API 28 (Nexus 6) in CI. Does *not* exercise the Android Keystore or `BiometricPrompt` — it uses BouncyCastle directly so the tests stay fast and deterministic.

## Maestro flows — `maestro test .maestro/`

Surface-level YAML flows that click through the app UI:

- `setup-identity.yaml` — create an identity
- `pair-wizard-navigation.yaml` — walk the Pair wizard screens

Fast smoke tests to catch broken navigation. They do not verify crypto correctness and currently swallow failures (`maestro test … || true`) so they do not block CI on flakiness.

## End-to-end pairing — `./scripts/run-local-e2e.sh`

Two-emulator driver that boots both devices, creates identities on each through the real UI and real biometric/PIN entry, then performs a bidirectional pairing and asserts both devices compute the same six-digit TOTP code.

Locally:

```
./scripts/run-local-e2e.sh --kill-on-exit
```

In CI: `.github/workflows/e2e.yml` boots one emulator via `reactivecircus/android-emulator-runner`, launches a second on port 5556 from the runner's `script:` block, and runs `scripts/ci-e2e-pairing.sh`.

### What it actually exercises

This is the only test layer that runs the real production path through biometric-unlocked Keystore keys. For both devices:

- `SetupScreen` typing and "Create Identity" button tap
- Real `WhoAmIViewModel.requestGenerateIdentity` → real `KeyManager.getEncryptionCipher`
- Real `BiometricPrompt` with device-credential fallback → real PIN entry (`1234`) driven via adb
- Real Android Keystore AES key generation
- Real Ed25519 keypair generation and encrypted-at-rest storage
- Real `onQrScanned(rawData)` — the exact method the camera/picker calls in production
- Real `KeyManager.getDecryptionCipher` → second biometric unlock → real private-key decrypt
- Real `EcdhExchange.deriveSharedSecret(our_priv, their_pub)` X25519 exchange
- Real Room insert of `TrustedContact(publicKey, totpSecret)`
- Real `TotpGenerator` against the stored secret
- Real `ContactListScreen` composable, with the assertion reading the rendered six-digit code off a `uiautomator dump`

The QR strings fed to each device come from the same `QrCodeUtils.encode(displayName, publicKey)` call the pair wizard makes inside `ShowQrStep` — byte-for-byte identical. From `onQrScanned` forward there is zero behavioral difference between "the test harness handed me this string via intent" and "I just scanned it with my camera."

### Known gaps

Three parts of production are deliberately skipped:

1. **Pair wizard UI navigation on the receiving side.** The test injects QR data via a debug-only intent (`e2e_inject_qr`) rather than going through `ContactListScreen` → Pair icon → `PairWizardScreen.ChooseStep` → `ScanStep`. The receiving device's `pairStep` transitions `null → ShowAfterScan` without the intermediate `Choose` / `ShowFirst` / `ScanAfterShow` states ever being rendered. Nothing about the crypto changes — the UI state machine is simply not exercised on that side.
2. **Camera scanning and photo picker.** `ScanContract` and `QrDecoder.decodeFromUri` are not invoked at all. If a future change made the QR bitmap too dense, too low-contrast, or otherwise unreadable when photographed off a real screen, this test would not catch it. `QrCodeUtilsTest` still verifies the encode/decode round-trip in memory.
3. **Real bitmap-to-camera round-trip.** No photons are involved. The test does not render the QR onto a framebuffer, screencap it, push the PNG to the other device, and feed it through the photo picker. That is precisely what the debug intent seam was introduced to dodge, because the photo picker on Pixel emulators is flaky enough to eat the test's reliability budget.

### Why the intent seam, then

The original design considered doing the full physical round-trip and abandoned it because the photo picker on API 33+ Pixel emulators is flaky enough that test reliability would suffer. The intent seam is a conscious trade: fast, reliable, deterministic verification of the crypto-correctness property (the thing that actually matters for security), at the cost of leaving the "glass and light" part of the loop covered only by Maestro smoke and manual testing.

If either of the skipped pieces ever causes real breakage, the answer is a second, slower, opt-in e2e variant that does the full camera round-trip one direction — not as a replacement for this test, but as a supplement. That would give two layers: "the math is wired up right" (this test, a few minutes, reliable) and "two real phones can physically pair" (slower, allowed to be slightly flaky, run on a schedule).

### Debug intent seam

`MainActivity.handleE2eIntent` accepts two intent extras, both gated on `BuildConfig.DEBUG`:

- `--ez e2e_dump_qr true` — log this device's QR payload to logcat under the `WhoAmI-E2E` tag. The payload is computed via `QrCodeUtils.encode(displayName, publicKey)`, the same function the pair wizard's `ShowQrStep` composable uses.
- `--es e2e_inject_qr <payload>` — feed a QR string into `WhoAmIViewModel.onQrScanned`, bypassing the scanner and picker UIs.

Both extras are removed after handling so a stale intent cannot replay. Release builds short-circuit on the `BuildConfig.DEBUG` guard at the top of `handleE2eIntent` and carry no additional surface.

Intents must be delivered with flags `0x30000000` (`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP`). `NEW_TASK` alone does not route through `onNewIntent` on default-launchMode activities.

## CI matrix

| Workflow | Emulator API | Covers |
| --- | --- | --- |
| `unit-tests.yml` | none | Unit tests |
| `integration-tests.yml` | API 28 Nexus 6 | Instrumented tests + Maestro flows |
| `e2e.yml` | API 33 Pixel 6 ×2 | End-to-end pairing harness |

The e2e job is pinned to API 33 because `KeyManager` uses `setUserAuthenticationParameters` on API 30+ and a time-bound key on API ≤ 29. The legacy path currently breaks the biometric flow and is tracked in cwage/whoarewe#6.

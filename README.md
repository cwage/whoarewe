# WhoAmI

Android app for pairwise identity verification over untrusted channels. After a one-time in-person pairing, each party can prove to the other that a later phone call, text, or email actually came from them — by reading a shared six-digit code that only the two paired devices can generate.

## How it works (tl;dr)

1. **Pair once, in person.** Two people who already trust each other (because they're in the same room) scan each other's QR codes. Under the hood this is an X25519 ECDH exchange that leaves both phones holding the same 20-byte shared secret. No network, no server, no account.
2. **Verify later, remotely.** The shared secret is fed into a TOTP generator. Each contact row displays a rotating six-digit code. If "Bob" calls Alice a week later, she asks him to read his code. If it matches the one her phone currently shows for Bob, the caller has Bob's actual device.

See [`docs/pairing.md`](docs/pairing.md) for the full explainer, including why pairing needs two QR codes instead of one, what the QR codes do and don't contain, and what the threat model actually buys you.

## Build

```
./gradlew :app:assembleDebug
```

Requires JDK 21 and the Android SDK. `minSdk` 28, `targetSdk` 35.

## Test

| Layer | Command | What it covers |
| --- | --- | --- |
| Unit | `./gradlew :app:testDebugUnitTest` | Pure-JVM crypto math — ECDH, TOTP, QR codec |
| Instrumented | `./gradlew :app:connectedDebugAndroidTest` | Room DB + crypto integration on a real Android VM |
| Maestro | `maestro test .maestro/` | Smoke flows through the setup and pair wizard screens |
| End-to-end | `./scripts/run-local-e2e.sh --kill-on-exit` | Two emulators, real biometric/PIN, full ECDH + TOTP handshake |

The e2e harness drives two real emulators through identity creation, biometric unlock, and bidirectional pairing, then asserts both devices compute the same six-digit code. Its design, the debug intent seam it uses, and the deliberately known gaps are all documented in [`docs/testing.md`](docs/testing.md).

## CI

Three workflows run on every PR to `main`:

- `unit-tests.yml` — JVM unit tests
- `integration-tests.yml` — instrumented tests + Maestro flows on an API 28 emulator
- `e2e.yml` — adb-driven pairing test on two API 33 emulators

## Project layout

```
app/                        Android application module
  src/main/java/com/whoami/app/
    crypto/                 Keystore, X25519, TOTP, QR codec
    data/                   Room entities and DAO
    ui/screens/             Compose screens
scripts/                    Test harness and dev utilities
.maestro/                   Maestro E2E flows
.github/workflows/          CI definitions
docs/                       Architecture and testing notes
```

## License

MIT. See [LICENSE](LICENSE).

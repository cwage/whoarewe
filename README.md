# WhoAreWe

> **Status: Proof of concept.** This is a working prototype that demonstrates the idea end-to-end. It is not audited, not hardened, and not ready for real-world security decisions. Use it to kick the tires and think about the problem — not to protect anything that matters yet.

## The problem

Voice cloning and deepfakes are cheap and getting cheaper. Caller ID is trivially spoofed. When someone calls you claiming to be your spouse, your parent, or your business partner, there is no reliable way to confirm it over the phone — and the tools that could fake their voice convincingly already exist today.

WhoAreWe is an experiment in solving this with commodity cryptography and zero infrastructure. Two people pair their phones once — in person, or by exchanging QR screenshots over any channel they already trust — and from then on each phone displays a rotating six-digit code for the other. To verify a suspicious call, you just ask: *"What does your code for me say right now?"* Only the real person's physical device can answer correctly.

No server, no account, no network required. The verification works even if both phones are in airplane mode.

## How it works (tl;dr)

1. **Pair once, over a trusted channel.** Two people who already trust each other exchange QR codes — scanning in person, or screenshotting and sending over any channel they trust. Under the hood this is an X25519 ECDH exchange that leaves both phones holding the same 20-byte shared secret. No network, no server, no account.
2. **Verify later, remotely.** The shared secret is fed into a TOTP generator. Each contact row displays a rotating six-digit code. If "Bob" calls Alice a week later, she asks him to read his code. If it matches the one her phone currently shows for Bob, the caller has Bob's actual device.

**What this doesn't do:** WhoAreWe doesn't help you figure out who someone is in the first place. It assumes you already know — because you're standing next to them, or because you trust the channel you're exchanging QR codes over. What it does is carry that trust forward in time: the next time that person contacts you from an unknown number or a new email address, you can verify it's them. The initial authentication is your problem; the ongoing verification is the app's.

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
| Maestro | `maestro test .maestro/` | Smoke flows through setup, add-contact, and QR display screens |
| End-to-end | `./scripts/run-local-e2e.sh --kill-on-exit` | Two emulators, real biometric/PIN, full ECDH + TOTP handshake |

The e2e harness drives two real emulators through identity creation, biometric unlock, and bidirectional pairing, then asserts both devices compute the same six-digit code. Its design, the debug intent seam it uses, and the deliberately known gaps are all documented in [`docs/testing.md`](docs/testing.md).

For trying the app out by hand — installing on your own phone, pairing with an emulator, simulating a fingerprint from adb — see [`docs/manual-testing.md`](docs/manual-testing.md).

## CI

Three workflows run on every PR to `main`:

- `unit-tests.yml` — JVM unit tests
- `integration-tests.yml` — instrumented tests + Maestro flows on an API 28 emulator
- `e2e.yml` — adb-driven pairing test on two API 33 emulators

## Project layout

```
app/                        Android application module
  src/main/java/com/whoarewe/app/
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

# Pairing and Verification

> This document describes the cryptographic design of a **proof of concept**. The protocol has not been formally audited. See the [README](../README.md) for project status.

WhoAreWe's job is to let two humans who have established trust once — whether face-to-face or over a channel they already trust — prove to each other, later and remotely, that they are who they say they are. No server, no account, no phone number, no social graph. The immediate motivation is the rise of convincing voice clones and deepfakes: when anyone can fake anyone's voice on a phone call, you need an out-of-band way to check that the person you're talking to actually holds the device you trust.

## The core idea

Once Alice and Bob have paired, each of their phones displays a rotating six-digit code in the other's contact row. Both phones compute the same code at the same time. The codes change every five minutes.

When "Bob" later calls Alice from an unfamiliar number, or texts her from a new handle, or appears in her inbox as `robert.smith.backup@outlook.com`, she can ask:

> What does your WhoAreWe code for me say right now?

If the caller reads back a sequence that matches what Alice's phone currently shows for Bob, Alice knows she is talking to someone in physical possession of Bob's actual device — because only that device holds the secret required to compute that code, and the code rotates often enough that a stale shoulder-surf from days ago cannot fake it.

## How the pairing works

The pair step establishes a **shared secret**: 20 bytes that exist on both phones and nowhere else. The secret is derived from an X25519 Elliptic Curve Diffie-Hellman exchange.

1. Alice and Bob each create an identity on their own phone, which generates an Ed25519 keypair. The private key is encrypted at rest by an Android Keystore key that can only be unlocked with the user's biometric or device credential. The public key is stored in plaintext — that is what public keys are for.
2. Each phone renders its public key as a QR code: `whoarewe:v1:<base64url pubkey>:<display name>`.
3. Alice gets Bob's QR — either by scanning it with the camera (if they're in the same room) or by importing a screenshot Bob sent over a channel she already trusts. Her phone now knows Bob's public key.
4. Alice biometrically unlocks her private key and her phone derives a shared secret: Ed25519 keys are converted to X25519, the ECDH agreement is SHA-256 hashed, and the first 20 bytes become the TOTP key.
5. Alice's phone stores a `TrustedContact` row containing Bob's name, Bob's public key, and that shared secret.
6. Bob does the same in reverse: gets Alice's QR (scan or import), derives `shared_secret = X25519(bob_private, alice_public)`.

Because of how ECDH works, the two results are identical — even though neither private key ever left its device. An observer who intercepts both QR codes learns both public keys but cannot reconstruct the shared secret without at least one of the private keys.

**A note on remote pairing:** Sending QR screenshots over any trusted channel is safe from eavesdroppers — the public keys alone are useless. But remote pairing trusts the channel for *authenticity*, not just encryption. If an attacker could replace the QR in transit, they could substitute their own public key and MITM the pairing. The protection is that you trust the channel to deliver messages from who it says they're from — the same trust you already place in it for everything else you use it for.

## Why two QR codes, not one

A common point of confusion: after Alice scans Bob's QR, why does her screen then display her *own* QR? She already has Bob's public key — isn't the pairing done?

From Alice's perspective, yes. She has already computed the shared secret and stored it.

But pairing is symmetric: both phones need the shared secret, and Bob's phone still has no idea Alice's public key exists. Until Bob's phone performs its half of the exchange, only Alice can generate matching codes — Bob has nothing to generate from. The "Show this to Bob" screen is literally Alice's phone telling Bob "your turn." Once Bob scans, both sides have independently derived the same 20 bytes and the contact is live on both devices.

## What the QR codes do and don't contain

| Field | Secret? | Purpose |
| --- | --- | --- |
| `whoarewe:v1:` prefix | no | Magic bytes and version |
| Public key (32 bytes, base64url) | **no** | Public keys can be shouted from rooftops |
| Display name | no | Human-readable label for the contact row |

There is no session key, no nonce, no handshake material. The QR code is a business card. Its "secrecy" is not what keeps the system safe — the secrecy of the private keys, locked inside each phone's Keystore, is.

In practice this means you can share your QR code freely in any channel where you're confident recipients know it came from you. If you're in a group chat with several people you trust, posting your QR for all of them to see is fine — an observer who captures both sides' public keys still cannot derive the shared secret without a private key. The only thing that matters is that the person who scans your QR trusts it actually came from you, not that the QR itself stayed hidden.

This is also why the end-to-end test (see [`testing.md`](testing.md)) can read each device's QR payload via a debug intent instead of going through the camera: the payload is a pure function of data that already exists on disk the moment an identity is created, and no amount of careful ceremony around *how* it is displayed changes what the next phone does with it.

## From shared secret to six-digit code

The stored `shared_secret` is used as an HMAC-SHA1 key for a standard RFC 6238 TOTP generator. At any given moment both phones HMAC the same timestamp with the same key and produce the same six digits. A different contact produces a different shared secret and therefore a different code; compromising one pair does not compromise any other.

### Why the rotation period is five minutes (and not sixty seconds)

The TOTP step size is the single most important UX knob in the whole system, and the right value for it is not obvious.

The shorter the step, the sooner a captured code becomes useless — but also the more often the wall-clock rolls over a window boundary while two phones are being compared by a human. Both phones display *their own* current code from *their own* local clock; there is no verifier, no submission, no asymmetry. If Bob's phone has just ticked over to the next window and Alice's phone hasn't yet, the two displayed codes legitimately disagree for a fraction of a second despite both being correct outputs of the same shared secret. With a 60-second step that boundary lands about every minute and the disagreement window is about 1-2% of every minute. The risk isn't theoretical — the e2e test in `scripts/e2e-pairing.sh` hit it on CI as cwage/whoarewe#8, where two devices agreed on the secret but displayed adjacent-window codes during a comparison taken two seconds after a minute mark.

In a system that is supposed to detect impersonators by *non-matching* codes, briefly displaying non-matching codes due to clock skew is a false positive on the trust check — exactly the failure mode the whole product is meant to make impossible. The cryptography is fine; the comparison is what's racing.

A five-minute (300-second) step shifts the disagreement probability from roughly one in fifty to roughly one in three hundred for any plausible inter-device clock skew, which on phones with auto-set time is well under a second. In exchange, a captured code is reusable for up to five minutes instead of up to one. Since there is no submit/verify channel an attacker could replay a captured code *into* — the code is purely a visual comparison artifact — the longer reuse window does not meaningfully change the threat model. The attacker who shoulder-surfed your code today still cannot impersonate Bob tomorrow.

The constant lives at `TotpGenerator.PERIOD_SECONDS` in `app/src/main/java/com/whoarewe/app/crypto/TotpGenerator.kt`. Everything in the app — display tick, progress ring, e2e tests, future verifier — derives from that one value. Tune it there.

### Why the e2e test does not assert on displayed codes

`scripts/e2e-pairing.sh` does not assert that two devices display the same six-digit code. It asserts that they store the same 20-byte shared secret (read via the debug-only `e2e_dump_secrets` intent in `MainActivity.handleE2eIntent`). Equal stored secrets *is* the cryptographic invariant the product depends on; equal displayed codes is a downstream consequence that races the wall clock. The test logs the displayed codes as a smoke check but only fails on the secret comparison.

## What this is not

- **Not identity verification in the legal sense.** The pairing does not prove anything about Bob's real name or biographical claims. It proves that a later communication is from the same physical device you paired with — nothing more, nothing less.
- **Not a messenger.** There is no server, no directory, no presence, no delivery. WhoAreWe has nothing to say about *how* you contact Bob in the future, only about how you recognize him when that contact happens.
- **Not unbreakable.** If Bob loses his phone and an attacker unlocks it, the attacker can impersonate Bob to Alice until Alice notices, re-pairs, or revokes the contact. This is the same failure mode as any other secret-on-device system. Explicit revocation and re-pairing are future work.

## Threat model in one sentence

**An adversary who does not hold either device's private key cannot, at any later time and over any channel, convince one paired party that they are the other.**

That is the single property the cryptography actually buys you. Everything else in the app is UI over that invariant.

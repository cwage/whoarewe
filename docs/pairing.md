# Pairing and Verification

WhoAreWe's job is to let two humans who have met once in person prove to each other, later and remotely, that they are who they say they are — without relying on any server, account, phone number, or social graph to vouch for it.

## The core idea

Once Alice and Bob have paired, each of their phones displays a rotating six-digit code in the other's contact row. Both phones compute the same code at the same time. The codes change every 60 seconds.

When "Bob" later calls Alice from an unfamiliar number, or texts her from a new handle, or appears in her inbox as `robert.smith.backup@outlook.com`, she can ask:

> What does your WhoAreWe code for me say right now?

If the caller reads back a sequence that matches what Alice's phone currently shows for Bob, Alice knows she is talking to someone in physical possession of Bob's actual device — because only that device holds the secret required to compute that code, and the code rotates every minute so a stale shoulder-surf from a month ago cannot fake it.

## How the pairing works

The pair step establishes a **shared secret**: 20 bytes that exist on both phones and nowhere else. The secret is derived from an X25519 Elliptic Curve Diffie-Hellman exchange.

1. Alice and Bob each create an identity on their own phone, which generates an Ed25519 keypair. The private key is encrypted at rest by an Android Keystore key that can only be unlocked with the user's biometric or device credential. The public key is stored in plaintext — that is what public keys are for.
2. Each phone renders its public key as a QR code: `whoarewe:v1:<base64url pubkey>:<display name>`.
3. Alice scans Bob's QR. Her phone now knows Bob's public key.
4. Alice biometrically unlocks her private key and her phone computes `shared_secret = X25519(alice_private, bob_public)`.
5. Alice's phone stores a `TrustedContact` row containing Bob's name, Bob's public key, and that shared secret.
6. Bob does the same in reverse: scans Alice's QR, derives `shared_secret = X25519(bob_private, alice_public)`.

Because of how ECDH works, the two results are identical — even though neither private key ever left its device. An observer who captures both QR codes (or a camera shoulder-surfing the whole exchange) learns both public keys but cannot reconstruct the shared secret without at least one of the private keys.

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

This is also why the end-to-end test (see [`testing.md`](testing.md)) can read each device's QR payload via a debug intent instead of going through the camera: the payload is a pure function of data that already exists on disk the moment an identity is created, and no amount of careful ceremony around *how* it is displayed changes what the next phone does with it.

## From shared secret to six-digit code

The stored `shared_secret` is used as an HMAC-SHA1 key for a standard RFC 6238 TOTP generator with a 60-second step. At any given moment both phones HMAC the same timestamp with the same key and produce the same six digits. No clock sync protocol is needed — both phones rely on their own wall clocks being close to `now`, which they reliably are on any modern device.

A different contact produces a different shared secret and therefore a different code. Compromising one pair does not compromise any other.

## What this is not

- **Not identity verification in the legal sense.** The pairing does not prove anything about Bob's real name or biographical claims. It proves that a later communication is from the same physical device you paired with — nothing more, nothing less.
- **Not a messenger.** There is no server, no directory, no presence, no delivery. WhoAreWe has nothing to say about *how* you contact Bob in the future, only about how you recognize him when that contact happens.
- **Not unbreakable.** If Bob loses his phone and an attacker unlocks it, the attacker can impersonate Bob to Alice until Alice notices, re-pairs, or revokes the contact. This is the same failure mode as any other secret-on-device system. Explicit revocation and re-pairing are future work.

## Threat model in one sentence

**An adversary who does not hold either device's private key cannot, at any later time and over any channel, convince one paired party that they are the other.**

That is the single property the cryptography actually buys you. Everything else in the app is UI over that invariant.

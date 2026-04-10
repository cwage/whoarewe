# WhoAmI — Pairwise Identity Verification App

## Problem

AI-powered fraud (deepfake voice/video, sophisticated phishing) makes it trivially easy to impersonate someone over phone calls and messages. There is no simple, consumer-grade tool for ordinary people to cryptographically verify that the person they're talking to is who they claim to be. Existing advice boils down to "use a family code word," which is evidence the market hasn't solved this.

## Core Insight

- STIR/SHAKEN verifies phone numbers, not people
- Signal verifies messaging identities, but only within Signal
- Nobody has built a standalone, cross-channel "prove you're really you" tool
- The failure mode of this system not working is "the status quo" — no verification at all — so the stakes for recovery/availability are low, which simplifies the design dramatically

## Design Principles

- **Authenticator app UX** — the main screen is a list of people (not services) with rolling 6-digit codes, exactly like Aegis/Google Authenticator. The mental model is already trained into millions of people.
- **TOTP-first, offline-first** — verification must work with zero data connectivity. High-pressure, low-connectivity moments (cruise ship, hospital, overseas) are exactly when scammers strike and exactly when this tool must work.
- **Pairwise trust only** — no group secrets, no keyservers, no transitive trust (initially)
- **Device-bound keys** — keys never leave secure hardware
- **Bootstrap over existing trusted channels** — Signal, iMessage, or in-person QR scan
- **Conservative recovery** — lost device = re-verify in person or over trusted channel. No cloud recovery, no SMS recovery, no email recovery. Degraded state is just "no verification available," which is what everyone has today
- **Channel-agnostic verification** — works regardless of how the suspicious contact happened (phone call, text, email, carrier pigeon)

## Architecture

### Data Model

```
Identity (local device):
  - display_name: string
  - keypair: Ed25519 (device-backed, Secure Enclave / Android Keystore)
  - created_at: timestamp

TrustedContact:
  - display_name: string
  - public_key: Ed25519 public key
  - totp_secret: 160-bit shared secret (derived via ECDH during key exchange)
  - verified_at: timestamp
  - notes: string (optional, e.g. "my brother")
```

### Crypto Primitives

- **Key generation**: Ed25519 keypair, generated and stored in platform secure enclave
- **QR payload**: Encode public key + display name + key fingerprint (truncated hash for visual confirmation)
- **Shared secret derivation**: During key exchange, both parties perform X25519 ECDH using their keypairs to derive a shared TOTP secret. Neither party transmits the secret directly — both compute it independently from the exchanged public keys. (Ed25519 keys can be converted to X25519 for this purpose.)
- **TOTP code generation**: Standard RFC 6238 — HMAC-SHA1(shared_secret, floor(current_time / 30)) truncated to 6 digits. Both apps independently compute the same code from the shared secret and their local clock. No connectivity required.

### Key Exchange Flow

```
Alice                                  Bob
  |                                     |
  |-- [generates QR: pubkey+name] -->   |
  |   (sent via Signal or in person)    |
  |                                     |
  |                          [scans QR] |
  |                  [stores Alice key] |
  |              [derives shared secret |
  |               via ECDH(bob, alice)] |
  |                                     |
  |   <-- [generates QR: pubkey+name] --|
  |       (sent via Signal or in person)|
  |                                     |
  [scans QR]                            |
  [stores Bob key]                      |
  [derives shared secret                |
   via ECDH(alice, bob)]                |
  |                                     |
  |  (both now have each other's        |
  |   public key AND the same           |
  |   shared TOTP secret, derived       |
  |   independently via ECDH)           |
```

Note: The shared secret is never transmitted. Both parties compute it independently from the exchanged public keys using Diffie-Hellman. This is the same trick that makes Signal's key exchange work.

### Trust Model for Enrollment

The app treats any scanned QR code as trusted. The human decides whether to trust the channel the QR arrived on — in person, Signal, SMS, email, whatever. The app doesn't ask and doesn't distinguish.

This mirrors how authenticator apps already work: you scan a QR, you get a code. The app doesn't audit how you got the QR.

The natural safety net: if a MITM substituted a QR during exchange, the codes won't match the first time the two real people try to verify verbally. The system is self-correcting as long as people use it. No enrollment tiers or channel-trust UI needed.

### Verification Flow

```
Alice (suspicious)                     Bob (caller)
  |                                     |
  |  "Read me your code"                |
  |                                     |
  [opens app]                           [opens app]
  [sees list of contacts               [sees list of contacts
   with rolling codes:]                  with rolling codes:]

   jwage (brother)  482913               Alice (sister)  482913
   Dad              817204               Dad             291058
   Grandma          639571               Mom             743820
  |                                     |
  |            "four eight two nine     |
  |             one three"              |
  |                                     |
  [matches -> that's really Bob]        |
```

Both apps independently compute the same code from the shared secret + current time. No connectivity, no server, no push notifications. Just clocks and math.

## Open Design Questions

### Challenge/Response as Future Enhancement

TOTP is the primary and MVP verification mechanism. A push-based challenge/response flow (using the asymmetric keys) could be added later for app-to-app verification when both parties have connectivity, but it is not needed for the core use case and adds complexity.

### Cross-Signing / Web of Trust (Future)

- Initially: trust is strictly pairwise. I only trust keys I personally received.
- Future possibility: "My brother verified my mom's key, I trust my brother, so I'll accept my mom's key transitively." Opens the door to web-of-trust but adds significant complexity. Keep the architecture open to this but don't build it first.

### Key Rotation / Device Change

- New device = new keypair. Old trusted contacts see "Bob's key has changed" (similar to Signal's key change warning).
- Re-verification required: scan new QR over trusted channel or in person.
- No silent key migration. This is a feature, not a bug.

### Multi-Device

- Defer. Single device per identity initially. Multi-device adds key synchronization problems that are orthogonal to the core value proposition.

### Platform

- **PoC: Native Android (Kotlin).** The app logic is small enough that native is simpler than any cross-platform framework. Direct Android Keystore integration, no framework overhead, F-Droid friendly out of the box.
- **iOS: Rewrite in Swift if viable.** The core logic (keygen, ECDH, TOTP, QR) is a few hundred lines. Rewriting from first principles for iOS is cheaper and cleaner than maintaining a cross-platform abstraction for this little code. Native also gives better Secure Enclave integration.
- No web version — browser crypto storage is weaker and the threat model assumes you have your phone.

## MVP Scope

1. **Generate identity** — create keypair on first launch, set display name
2. **Main screen** — authenticator-style list of trusted contacts, each showing a rolling 6-digit TOTP code. This is the screen you see when you open the app. That's it. Names and codes.
3. **Add contact (export)** — generate QR code containing your public key + display name, share via Signal/iMessage or display for in-person scan
4. **Add contact (import)** — scan QR code (camera or from image in chat), derive shared secret via ECDH, store contact. Code appears in the list immediately.
5. **Key change warning** — if you scan a QR for a name that already exists with a different key, warn and require explicit confirmation to replace
6. **Clock skew tolerance** — accept codes from adjacent 30-second windows to handle slight clock drift between devices

## Non-Goals (For Now)

- Integration with phone/PSTN system
- Group/family shared secrets
- Cloud backup or sync
- Transitive trust / web of trust
- Challenge/response verification (future enhancement)
- Desktop or web client
- Any form of messaging — this is not a chat app

## Tech Stack (Android PoC)

- **Language**: Kotlin
- **Crypto**: Android Keystore for key generation/storage, Bouncy Castle or libsodium-jni for Ed25519-to-X25519 conversion and ECDH
- **Secure storage**: Android Keystore (hardware-backed on devices that support it)
- **QR**: ZXing or ML Kit barcode scanning (ML Kit may have Google Play Services dependency — verify F-Droid compatibility, otherwise ZXing)
- **TOTP**: RFC 6238 — small enough to implement directly, no library needed
- **Local storage**: Room/SQLite for contact list, Keystore for secrets
- **UI**: Jetpack Compose — simple list view, nothing fancy

## Threat Model

**In scope:**
- Voice/video deepfake impersonation over phone calls
- Caller ID spoofing
- Social engineering via text/email claiming to be a known person

**Out of scope (initially):**
- Device compromise (if attacker has your unlocked phone, all bets are off)
- State-level adversaries
- Protecting against a compromised Signal account used during bootstrap (you're trusting Signal's security for the initial exchange)

**Accepted risks:**
- Bootstrap trust depends on the security of the channel used for QR exchange
- Lost device = loss of verification capability until re-enrolled (acceptable: degrades to status quo)
- "My phone died" social engineering still works if the target doesn't hold firm on the "no code, no trust" policy — this is a human discipline problem, not a technical one
- TOTP relay attack: if a scammer is simultaneously on a call with both parties, they could relay the code in real-time within the 30-second window. Sophisticated and unlikely for the target demographic, but theoretically possible. Mitigations (directional codes, challenge-specific codes) exist but add complexity.

## Security Review Requirements

This is a crypto identity app. The entire value proposition is the cryptographic guarantee. "It shows codes and they match" is not sufficient — the implementation must actually provide the security properties it claims. LLM-generated crypto code in particular warrants skepticism: it tends to produce plausible-looking implementations that pass functional tests while getting subtle but critical details wrong.

### Critical paths that need human review before trusting

**Ed25519-to-X25519 key conversion**
- ECDH requires X25519 (Curve25519) keys, but we generate Ed25519 keys for the Keystore identity. The conversion between these is a specific mathematical operation (clamping, coordinate mapping) that's easy to get subtly wrong.
- Risk if wrong: ECDH still "works" (produces a shared secret, codes match) but the resulting secret may have reduced entropy or be predictable.
- What to verify: that the conversion uses a well-tested library path (e.g. libsodium's `crypto_sign_ed25519_pk_to_curve25519`), not a hand-rolled implementation.

**Android Keystore hardware-backing**
- On some devices, Keystore silently falls back to software-backed storage. Keys "work" but aren't actually protected by hardware.
- The app should query `KeyInfo.isInsideSecureHardware()` and surface this to the user, or at minimum log it.
- Risk if wrong: keys are extractable from a rooted/compromised device, defeating the "device-bound" guarantee.

**TOTP implementation correctness**
- RFC 6238 has specific requirements: big-endian 8-byte time counter, HMAC-SHA1 (or SHA256), dynamic truncation with specific offset extraction, zero-padding of codes shorter than 6 digits.
- "Simple" to implement, easy to get a byte-ordering or truncation detail wrong in a way that still produces 6-digit codes but breaks interoperability or reduces the code space.
- What to verify: test vectors from RFC 6238 Appendix B must pass. This is non-negotiable and easy to test.

**QR payload parsing**
- Malformed or malicious QR codes must not crash the app, cause unexpected state, or inject data beyond the expected fields.
- The parser should reject anything that doesn't match the expected format, not attempt to be lenient.
- What to verify: fuzz the QR parser with garbage input, oversized payloads, and valid-looking payloads with wrong field types.

**Shared secret storage**
- The TOTP shared secret derived via ECDH must be stored with the same protection as private keys (Keystore or at minimum encrypted storage), not in plaintext in the Room database.
- What to verify: inspect the actual storage path on a rooted device or emulator.

### Review approach

A formal audit is overkill for a family PoC, but before sharing with anyone:

1. **Test vectors first.** TOTP RFC vectors and known-answer tests for the ECDH derivation. If these pass, the math is right. If they don't, nothing else matters.
2. **Read the crypto code manually.** It's a few hundred lines. A knowledgeable person (or a second LLM pass specifically focused on "find the security bugs in this code") can review it in an afternoon.
3. **Verify Keystore behavior on actual target devices.** Emulators don't have secure hardware. Test on the real phones that will run this.
4. **Check what's actually in the QR.** Generate one, decode it manually, confirm it contains only what you expect.

The crypto surface is small enough that "one careful person spends a Saturday reviewing it" is a credible review process. The risk isn't that the review is expensive — it's that you skip it because the app works and the codes match.

## Viability & Distribution

### Why this doesn't exist yet

- **TOTP apps are "service-to-user," not "user-to-user."** Every authenticator app assumes one party is a server. Peer-to-peer key exchange is a different interaction pattern even though the crypto is identical. It's not a feature bolt-on to existing apps — it's a different product concept sharing the same primitive.
- **The problem isn't mass-legible yet.** Deepfake voice scams are rising fast but haven't hit the tipping point where normal people feel they need tooling. FBI/FTC are increasingly warning about AI voice cloning, so this is changing.
- **It falls between product categories.** Not a password manager, not a 2FA app, not a messenger. Hard to pitch in a roadmap meeting. Easy to explain to a person who's been scared by a scam call.
- **Over-engineering reflex.** Most people who think about this jump to web-of-trust, remember PGP's failure, and give up. The insight that pairwise TOTP with QR exchange is sufficient gets lost.
- **Platform incentives are misaligned.** Google and Apple want to be the identity provider. A peer-to-peer trust model is philosophically opposite to what they sell. They'll build "verified calls for businesses," not "verify your grandson."

### Why OSS is the only credible path

The entire premise is "no server, no accounts, no cloud, keys never leave your device." A commercial app behind a company immediately invites reasonable skepticism: are they phoning home? Will they add analytics? Will they get acquired? OSS makes the trust model verifiable. The code does what it says, anyone can audit it.

A proprietary paid app also faces the adoption problem squared — people won't pay for something they don't think they need yet, for a threat they haven't personally experienced.

### Realistic adoption model

- **Not a product, a family tool.** One technical person per family installs it on everyone's phones. The "Linux dad" bootstraps the family cluster.
- **Android-first, F-Droid-friendly.** No Google Play Services dependency. Sideloadable. iOS is important for real-world family coverage but adds Apple developer program overhead — defer or tackle as a second platform.
- **The app is partly a prop.** Even with low adoption, establishing the family norm — "if someone calls claiming to be me and can't read a code, it's not me" — changes behavior. The app is the artifact that makes the social rule concrete.
- **PoC scope: 3-4 people.** If it works for one family, it works. Anything beyond that is organic.

### Platform considerations

- Most families span iOS and Android. Cross-platform support matters for real-world use even if the PoC starts on one platform.
- Cross-platform framework (Flutter, React Native, Kotlin Multiplatform) vs. native per platform is TBD. Tradeoffs: framework = faster to ship both platforms, native = better secure enclave integration.
- F-Droid requires fully open source with no proprietary dependencies. This aligns well with the trust model.
- Apple App Store requires a $99/year developer account and review process. Not a blocker but adds friction for a hobby/OSS project.

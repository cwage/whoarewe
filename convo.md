

hear me out: fraud (cold calls, phishing, blah blah) has been a problem for decades, now AI (deepfake video/voice/etc) is making it worse (i.e. smarter). it raises the question e.g. if a scammer calls my grandma and pretends to be me, why isn't there better tooling even a grandma can use to verify it's actually me? I realize PKI is not exactly grandma-understanding territory, but grandmas these days can at least use a phone app. "key signing parties" and web of trust never really took off e.g. with PGP in the olden days cus it was irretrievably neckbeard territory. more people use signal these days and few of them have any idea how the crypto actually works, but for the most part, it does work and provide a measure of security. why couldn't there be a phone app that trivializes: generate a signing key, generates QR codes for exchanging/cross-signing between friends and family, etc. maximal ease of use so if you get a phishy (pun intended) phone call from someone claiming to be someone you know, you could ask them to prove it

I’m checking whether anything like this already exists in a usable form, and where the real failure points are: crypto, UX, or phone-platform constraints.

There is existing machinery for pieces of this, but not a clean family-grade “prove it’s really you” flow. I’m checking the gap between what telecom networks verify and what a family actually needs in the moment.

Thought for 16s
Yes. Technically, this is very doable.

What you’re describing is basically: “make end-user identity verification work like Signal safety numbers, but for ordinary family communications and emergency calls.” That is not a crazy idea. In fact, pieces of it already exist: Signal has per-contact verification via safety numbers and QR codes, and Apple has Contact Key Verification for iMessage contacts. The problem is not that the crypto is impossible; it is that the last-mile UX, recovery, and adoption problem is ugly. 

The telecom side also does not solve your actual problem. STIR/SHAKEN helps carriers verify that the displayed phone number wasn’t spoofed in transit, but the FCC explicitly notes that this still does not tell the consumer who is actually calling in a meaningful sense. Even a well-authenticated phone number is not the same thing as “this is really my grandson” or “this voice is really Chris.” 

So why isn’t there a good grandma app already? Mostly because of five boring reasons:

Bootstrapping trust is hard.
The very first time grandma installs the app, how does she know she scanned the right QR code and not a scammer’s? Signal solves this by assuming you already have a trusted chat relationship and can verify out-of-band; that is fine for security-minded users, but family-wide rollout is messy. 

Account recovery is poison.
Real people lose phones, forget passwords, change numbers, and replace devices. The more secure the identity proof is, the more painful recovery becomes. Consumer products routinely weaken this layer because support burden and lockouts are worse for business than theoretical impersonation attacks.

Phone calls are the wrong primitive.
A normal PSTN call has almost no room for a clean consumer cryptographic identity ceremony. Messaging apps control both ends, so they can show “verified contact” states. Random inbound phone calls from the public network do not have that same end-to-end trust model. STIR/SHAKEN is network-level number authentication, not person-level identity proof. 

The attack moves to social engineering.
Even now, official guidance often falls back to “use a family code word” because that is what people can actually remember and use under pressure. Signal’s own safety advice literally recommends verifying with a pre-chosen code word. That is a sign that the human factors problem is bigger than the cryptography problem. 

There is weak commercial incentive.
“Family anti-impersonation app” is important, but it is not obviously a massive standalone consumer business. Phone platforms instead ship partial features like scam detection, spam filtering, and business verified calls because those are easier to productize at scale. Google, for example, has Verified Calls for businesses and scam-detection features on Android/Pixel, but that is not the same as a personal web-of-trust system. 

That said, your proposed design is basically sane:

install app

generate device-backed keypair

show a giant “Verified Family” QR code

in person, scan each other once

app stores a signed contact identity

later, during a suspicious call, grandma presses “Challenge caller”

caller gets a push in their app to sign a nonce

grandma sees green: cryptographically verified as Chris’s registered phone or red: not verified

That would work best if it did not try to verify over raw phone audio itself. Better model: the phone call is just the alarm bell; the app performs the identity proof over data/push. In other words: “Someone says they are Chris on a call? Fine. Make them answer the app challenge.”

The real constraints are practical:

You need both parties to have the app installed beforehand.

You need a dead-simple re-verification flow when someone gets a new phone.

You need a very blunt UI: Verified / Not verified / Previously verified but changed.

You need to avoid exposing users to QR-code scams and fake recovery flows, which are already a thing in secure messengers. 

So the answer is: yes, this should exist; no, the blocker is not the math. The blocker is that secure identity systems become unusable the moment you try to make them work for normal families, device churn, elderly users, and legacy phone networks all at once.

The best near-term version is probably not “PGP for grandmas.” It is:

a very opinionated family app with

in-person QR onboarding,

device-bound keys,

one-tap challenge/response,

large explicit trust states,

and a fallback family code phrase for when the app path fails.

That would cover a lot of the “grandma got a fake emergency call” scenario better than today’s phone network does. The fact that mainstream advice still leans on code words is basically evidence that the market has not solved this cleanly yet. 

A rough product sketch for how I’d design it is possible.


right, i wasn't thinking of something that magically dovetails with PSTN (which signal is already ... kinda trying to do), more like a general-purpose "out of band" (admittedly it's still a phone app) confirmation. TOTP! grandma: "if this is really you, read me the 6 digit code on our family security app"

Thought for a couple of seconds
Yes, that is much closer to something people could actually use.

TOTP is appealing here because it gives you a simple human ceremony:

grandma gets suspicious call

she opens “Family Verify”

app shows current 6-digit code for you

she says “read me the Chris code”

real you reads the same code from your app

scammer cannot, unless they already compromised your device/account

That is basically a shared-secret challenge-response system, and it avoids almost all the “explain public keys to grandma” nonsense.

Why this is good:

easy mental model: same as bank / authenticator codes

fast: no QR scan needed in the moment

works over any channel: phone call, text, email, whatever

out-of-band enough for the actual threat model: voice clone on one channel, proof on another mechanism

no need for telecom cooperation

Why it still gets tricky:

Who generates the code?
Normal TOTP is based on a shared secret between two parties. For a family app, each pair or group would need a secret established beforehand.

Enrollment is still the hard part.
You still need the one-time bootstrap:

install app

add family member

scan QR or accept invite

store shared secret

Recovery is dangerous.
If you lose your phone, how do you restore without making it trivial for an attacker to take over your identity?

A scammer can stall for time.
They might say “my phone died,” “I’m calling from the hospital,” etc. So this can’t be the only defense; it has to be part of a social rule:
no code, no trust, period.

Pairwise secrets don’t scale cleanly.
If grandma has 12 family contacts, the app has to manage all that invisibly.

The stronger version is probably:

app uses proper public-key crypto under the hood

but presents it to the user like TOTP

when grandma taps your name, app shows:

either a rolling 6-digit challenge for you to answer, or

a “send challenge” button that pings your app and returns a signed approval

So under the hood it can be more robust than plain shared-secret TOTP, but the UI remains “read me the 6 digits.”

That is probably the right design principle:
PKI inside, TOTP-shaped UX outside.

Even simpler, family policy could be:

every close relative gets this app

if there is urgency, money, jail, hospital, or “don’t tell anyone,” you always verify through the app

no valid code = hang up and call back on the saved number

That solves the real problem, which is not “perfect identity on the PSTN,” but:
“give normal people a dirt-simple ritual that breaks AI voice scams.”

Your version is much better than trying to teach people about signatures or key fingerprints. It reduces to:
“If it’s really me, prove you have our shared family trust app.”

The only real reason this is not already everywhere is that onboarding/recovery/support are annoying, not that the core idea is bad.


i mean recovery is a problem sure, but the fallback of failed recovery is "nothing", which is what currently people are using to verify identity

Thought for a second
Exactly. That is the right framing.

For this kind of system, failed recovery is not catastrophic in the way it would be for a bank or password manager. The failure mode is just:

“we lose the stronger proof mechanism and fall back to the crappy status quo.”

That is much more acceptable.

This is why the consumer-security industry often overcomplicates this stuff: they treat every identity system like it must support seamless recovery, cross-device sync, zero friction, mass adoption, and no support burden. But for your use case, the bar is lower and more realistic:

best case: family has a strong out-of-band verification tool

worst case: they don’t, and must revert to callback / code word / skepticism

That is already a net win.

In fact, a little recovery friction is probably good here. If the point is “prove you are really Chris,” then a silent, easy, cloud-based account recovery path that lets an attacker rebind identity would be worse than just saying:

“Chris got a new phone; re-scan in person or via another already-trusted family member.”

That sounds inconvenient, but this is family identity, not Instagram.

So yes, a product in this space should probably be designed with deliberately conservative assumptions:

device loss does not equal automatic identity continuity

new phone = trust reset until re-verified

recovery may require another trusted family member or an in-person QR rescan

if recovery fails, the app simply stops being authoritative until trust is re-established

That is not a bug. That is an honest security model.

Honestly the more I think about it, the more the main missing ingredient is not crypto but product discipline. Most apps would ruin this by trying to be too clever:

“recover by email”

“recover by SMS”

“recover with security questions”

“recover with cloud backup”

…and now the attacker just attacks the weakest recovery rail.

Whereas your model can tolerate bluntness:

“No verified device, no proof. Use legacy methods until re-enrolled.”

That is perfectly sane for anti-impersonation.

The strongest version is probably something like:

trust is anchored to a device key

family members verify each other once

emergency verification is challenge/response

recovery is intentionally manual and social, not frictionless

losing access degrades gracefully to ordinary caution rather than causing account catastrophe

That is a much better fit for the actual problem than the usual Silicon Valley obsession with never letting the user feel any friction.





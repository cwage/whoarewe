# Manual testing

Sometimes you want to try the app by hand — install it on your own phone, pair with an emulator, watch a real biometric prompt fire. This doc covers the basics.

For the fully automated two-emulator test, see [`testing.md`](testing.md).

## The easy path: `scripts/manual-pair.sh`

Most of this doc describes a multi-step adb dance: build APK, install on both sides, create identities, ferry QR screenshots between devices, compare codes. All of that is now wrapped in one wizard script:

```
./scripts/manual-pair.sh
```

Plug your phone in (with USB debugging authorized) and run that. With no arguments it will:

1. Detect the single USB-connected phone (errors out if there are zero or multiple)
2. Build the debug APK if missing
3. Uninstall + reinstall `com.whoarewe.app` on the phone for clean data
4. Boot a fresh emulator via [`scripts/manual-emu.sh`](../scripts/manual-emu.sh) (wipes the AVD, sets PIN `1234`, installs APK, unlocks) — or reuse an already-running one on port 5554
5. Launch the app on both sides
6. Walk you through identity creation on each (you enter your real biometric on the phone and `1234` on the emulator)
7. Walk you through the pair wizard step-by-step, automatically screencapping one device after each step and pushing the PNG into the other device's `Pictures/Screenshots/` directory with a `MEDIA_SCANNER_SCAN_FILE` broadcast so it appears as the newest photo in the photo picker
8. Scrape the rendered six-digit TOTP code off both devices' contact lists via `uiautomator dump`, retry a few times to absorb any TOTP window-boundary race, and assert they match

Flags cover the non-default shapes:

```
--phone SERIAL       force a specific phone when multiple are attached
--emu SERIAL         reuse a specific emulator serial
--avd NAME           which AVD manual-emu.sh should boot (default: first)
--pin NNNN           custom emulator PIN (default 1234)
--port NNNN          emulator port (default 5554)
--skip-install       don't touch existing installs on either side
--swap-roles         emulator is "show first", phone is "scan first"
```

The rest of this doc is the "what's actually happening under the hood" reference for when the script breaks, or when you want to drive one of the steps by hand (e.g. enrolling a simulated fingerprint on the emulator instead of using the PIN bouncer).

## Prerequisites

- Debug APK: `./gradlew :app:assembleDebug`
- `adb` on your `$PATH`
- Every device involved must have a **screen lock** (PIN, pattern, or biometric) configured *before* launching the app. WhoAreWe generates a Keystore key with `setUserAuthenticationRequired(true)`, which cannot be created on a device with no secure lock.

## Installing on a phone

Enable **Developer options → USB debugging** on the phone (Settings → About phone → tap Build number 7 times to unlock Developer options). Plug it in over USB and accept the RSA debugging prompt that appears on the phone.

```
adb devices
```

Should show the phone with status `device`. If it shows `unauthorized`, revoke and re-accept the debugging prompt (Developer options → Revoke USB debugging authorizations, then replug).

Install:

```
adb -s <phone-serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

If you previously installed a release build (or a debug build signed by a different keystore), install will fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`. Uninstall first, then install — note that uninstalling wipes all app data on that device:

```
adb -s <phone-serial> uninstall com.whoarewe.app
adb -s <phone-serial> install app/build/outputs/apk/debug/app-debug.apk
```

## Pairing a phone with an emulator

The most useful manual setup: one real device (real fingerprint sensor, real hardware Keystore) paired with an emulator you can poke with adb.

> **Shortcut:** steps 1, 3, 4, 5 and 6 below are automated by [`scripts/manual-pair.sh`](../scripts/manual-pair.sh). Step 1 alone is automated by [`scripts/manual-emu.sh`](../scripts/manual-emu.sh) if you only need a fresh, pinned, APK-installed emulator for some other purpose. The hand-driven versions below are the reference for what those scripts do under the hood.

### 1. Boot an emulator with a PIN

```
~/Android/Sdk/emulator/emulator -avd <your-avd> -wipe-data -no-snapshot-load -no-snapshot-save -port 5554 &
adb -s emulator-5554 wait-for-device
adb -s emulator-5554 shell locksettings set-pin 1234
```

The PIN gives the app a device credential to fall back on when biometric isn't available. `1234` is arbitrary — pick whatever you want, the app never sees it.

The three `-wipe-data -no-snapshot-load -no-snapshot-save` flags together are what guarantee a clean boot. Any of them alone is insufficient — `-no-snapshot-save` only prevents *saving* state back on shutdown, so a previously-saved snapshot will still reload on the next boot and bring back a stale identity from some earlier session. Ask me how I know.

### 2. Simulated fingerprint on the emulator (optional)

If you'd rather exercise the biometric path than the PIN path on the emulator side, enroll a fingerprint once:

1. Open **Settings → Security → Pixel Imprint** (or **Fingerprint**) on the emulator.
2. Start a new enrollment. When the system asks you to touch the sensor, run this from your host shell:
   ```
   adb -s emulator-5554 emu finger touch 1
   ```
   The `1` is a fingerprint ID you're inventing. Use the same number every time. Repeat the command each time the enrollment wizard asks for another touch.
3. From then on, any time the app's biometric prompt appears, run the same `emu finger touch 1` command to authenticate.

`emu finger touch N` only does anything while the system is actively listening for a fingerprint — running it at any other time is a no-op.

### 3. Install on the phone

Install the debug APK as in the section above. Confirm the phone has a screen lock set — real fingerprint, face unlock, PIN, or pattern are all fine, the app doesn't care which.

### 4. Create identities on both devices

Launch WhoAreWe on each. On each device:

1. Type a display name
2. Tap **Create Identity**
3. Authenticate when the biometric prompt appears — fingerprint on the phone, PIN or `emu finger touch` on the emulator

Both devices should land on the Contacts screen with an empty contact list and an identity fingerprint line at the bottom.

### 5. Exchange QR codes

Scanning a QR off the other device's screen with the camera works in theory but is finicky when screens are different sizes and angles. It's usually easier to ferry a screenshot between the two.

On whichever device goes first, tap the **Pair** icon (top right) → **Show my code first**. The device now shows its QR.

On the other device, tap **Pair** → **Scan their code first** → **Import QR from image**. You need the first device's QR in this device's photo library.

**Phone-to-emulator transfer** (or any direction — the commands are symmetric, just swap the `-s` targets):

```
adb -s <from-serial> exec-out screencap -p > /tmp/qr.png
adb -s <to-serial>   push /tmp/qr.png /sdcard/Pictures/Screenshots/qr.png
adb -s <to-serial>   shell am broadcast \
    -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d file:///sdcard/Pictures/Screenshots/qr.png
```

`exec-out screencap -p` streams the PNG bytes straight to host stdout — no intermediate `/sdcard` round-trip on the source side. The `MEDIA_SCANNER_SCAN_FILE` broadcast pokes the media store so the new PNG shows up in the photo picker grid immediately; without it the file is on disk but the picker doesn't know about it.

Push target matters: `/sdcard/Pictures/Screenshots/` is what the photo picker surfaces by default. `/sdcard/Download/` may or may not show up in the picker's "recent" grid depending on the system image.

If the target is your real phone and you'd rather keep the screenshot out of `adb push` entirely, the `/tmp/qr.png` from the `exec-out screencap` above is just a PNG on your host — move it to the phone however you normally move files (AirDrop, Syncthing, Google Photos, email to yourself, etc) and pick it with the photo picker from there.

### 6. Complete the exchange

Once the QR screenshot is on the second device, pick it from its photo picker. Authenticate (fingerprint / PIN). The second device inserts a contact for the first device and advances to **Almost done**, which displays the second device's own QR.

Now repeat in reverse: screenshot the "Almost done" QR, ferry it to the first device, and import it there. Authenticate. Both devices should end up on a **Paired!** screen, tap Back to contacts.

### 7. Verify

Both devices should now show each other in the contact list, each with a rotating six-digit code. **The codes must match** between the two devices. If they don't, something is broken and we'd like to hear about it.

## What to verify: screen capture protection

Since cwage/whoarewe#22, the app window is `FLAG_SECURE` by default — the flag is set in `MainActivity.onCreate` before the first frame is composed — and is cleared only while the pair wizard is on **Show your code** / **Almost done** (the two steps that display the user's own QR, which must stay captureable for manual pairing). Everything else (Loading, Setup, contact list, Choose / Scan their code / Paired!) stays secure.

`FLAG_SECURE` blocks the stock screenshot button, `MediaProjection` screen recorders, the task-switcher / recents thumbnail, and cast/mirror displays from capturing whatever the window is currently showing.

Quick sanity checks on a real device:

- On the **contact list**: press the screenshot button. On modern Pixels (API 33+) you still get a PNG file, but the app surface composites as solid black — only the system status bar at the top is visible, no TOTP codes. Older Android versions show a "Can't take screenshot due to app policy" toast instead. Either way, the codes are not in the capture.
- Background the app from the contact list with the recents gesture — the WhoAreWe tile in the task switcher should render as blank / no readable code list.
- In the pair wizard on **Show your code** / **Almost done** (ShowFirst / ShowAfterScan): the screenshot button must still work and capture a real QR image. Manual pairing relies on `adb exec-out screencap -p` working here (see `scripts/manual-pair.sh`'s `transfer_qr`). If these screens also screenshot as black, the predicate in `MainActivity` is inverted.
- On **Setup** and **Choose / Scan their code / Paired!**: screenshot behavior is also secure (no secrets on those screens, but defaulting to secure and only exempting the QR screens is simpler and avoids any window where the flag is off by mistake).

What this does **not** block: a camera pointed at the screen, accessibility-service text exfiltration (tracked separately in cwage/whoarewe#28), or a rooted adversary reading the framebuffer. `FLAG_SECURE` is a screen-capture defence, not a full confidentiality guarantee.

## Gotchas

- **API 28 / 29 use the legacy Keystore auth path.** On those API levels `KeyManager` configures the key with `setUserAuthenticationValidityDurationSeconds(10)` instead of `setUserAuthenticationParameters`, which forces `cipher.init()` to run *after* `BiometricPrompt` has refreshed the auth window. The app handles this automatically — you don't have to do anything — but if something breaks in the keygen or AddContact path on an old emulator and works fine on API 30+, that's where to start looking. The legacy path is covered by the `pairing (28)` job in `.github/workflows/e2e.yml` and was historically broken (cwage/whoarewe#6, fixed in PR #10).
- **Photo picker grid caching.** If a freshly pushed PNG doesn't show up in the picker, the `MEDIA_SCANNER_SCAN_FILE` broadcast above usually fixes it. On some system images you may need to kill and relaunch the app so the picker re-reads the media store.
- **Simulated fingerprint needs a real enrollment first.** `emu finger touch N` authenticates against fingerprint ID `N` — but `N` has to have been enrolled through the settings wizard. Fresh emulators have no enrolled prints.
- **Screenshot contrast.** On phones with very dark UI themes, screenshotting the QR code can sometimes produce images ZXing struggles to decode. If a scan fails, try taking the screenshot while the pair wizard is on a bright/white background.

# Manual testing

Sometimes you want to try the app by hand — install it on your own phone, pair with an emulator, watch a real biometric prompt fire. This doc covers the basics.

For the fully automated two-emulator test, see [`testing.md`](testing.md).

## Prerequisites

- Debug APK: `./gradlew :app:assembleDebug`
- `adb` on your `$PATH`
- Every device involved must have a **screen lock** (PIN, pattern, or biometric) configured *before* launching the app. WhoAmI generates a Keystore key with `setUserAuthenticationRequired(true)`, which cannot be created on a device with no secure lock.

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
adb -s <phone-serial> uninstall com.whoami.app
adb -s <phone-serial> install app/build/outputs/apk/debug/app-debug.apk
```

## Pairing a phone with an emulator

The most useful manual setup: one real device (real fingerprint sensor, real hardware Keystore) paired with an emulator you can poke with adb.

### 1. Boot an emulator with a PIN

```
~/Android/Sdk/emulator/emulator -avd <your-avd> -port 5554 &
adb -s emulator-5554 wait-for-device
adb -s emulator-5554 shell locksettings set-pin 1234
```

The PIN gives the app a device credential to fall back on when biometric isn't available. `1234` is arbitrary — pick whatever you want, the app never sees it.

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

Launch WhoAmI on each. On each device:

1. Type a display name
2. Tap **Create Identity**
3. Authenticate when the biometric prompt appears — fingerprint on the phone, PIN or `emu finger touch` on the emulator

Both devices should land on the Contacts screen with an empty contact list and an identity fingerprint line at the bottom.

### 5. Exchange QR codes

Scanning a QR off the other device's screen with the camera works in theory but is finicky when screens are different sizes and angles. It's usually easier to ferry a screenshot between the two.

On whichever device goes first, tap the **Pair** icon (top right) → **Show my code first**. The device now shows its QR.

On the other device, tap **Pair** → **Scan their code first** → **Import QR from image**. You need the first device's QR in this device's photo library.

**Phone-to-emulator transfer:**

Take a screenshot on the phone (power + volume-down). Then on your host:

```
adb -s <phone-serial> pull /sdcard/Pictures/Screenshots/<latest>.png /tmp/qr.png
adb -s emulator-5554 push /tmp/qr.png /sdcard/Download/qr.png
adb -s emulator-5554 shell am broadcast \
    -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d file:///sdcard/Download/qr.png
```

The `MEDIA_SCANNER_SCAN_FILE` broadcast pokes the media store so the new PNG shows up in the photo picker grid immediately.

**Emulator-to-phone transfer:**

```
adb -s emulator-5554 shell screencap -p /sdcard/qr.png
adb -s emulator-5554 pull /sdcard/qr.png /tmp/qr.png
```

Then move `/tmp/qr.png` to the phone however you normally move files (AirDrop, Syncthing, Google Photos upload, email to yourself, etc).

### 6. Complete the exchange

Once the QR screenshot is on the second device, pick it from its photo picker. Authenticate (fingerprint / PIN). The second device inserts a contact for the first device and advances to **Almost done**, which displays the second device's own QR.

Now repeat in reverse: screenshot the "Almost done" QR, ferry it to the first device, and import it there. Authenticate. Both devices should end up on a **Paired!** screen, tap Back to contacts.

### 7. Verify

Both devices should now show each other in the contact list, each with a rotating six-digit code. **The codes must match** between the two devices. If they don't, something is broken and we'd like to hear about it.

## Gotchas

- **API ≤ 29 biometric is currently broken.** See cwage/whoarewe#6. Use API 30+ for manual testing until that's fixed.
- **Photo picker grid caching.** If a freshly pushed PNG doesn't show up in the picker, the `MEDIA_SCANNER_SCAN_FILE` broadcast above usually fixes it. On some system images you may need to kill and relaunch the app so the picker re-reads the media store.
- **Simulated fingerprint needs a real enrollment first.** `emu finger touch N` authenticates against fingerprint ID `N` — but `N` has to have been enrolled through the settings wizard. Fresh emulators have no enrolled prints.
- **Screenshot contrast.** On phones with very dark UI themes, screenshotting the QR code can sometimes produce images ZXing struggles to decode. If a scan fails, try taking the screenshot while the pair wizard is on a bright/white background.

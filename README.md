# BlueCall

Low-latency voice calls between two Android phones over Bluetooth. No internet, SIM or pairing needed. Open BlueCall on both phones, and each one shows the other in its *Nearby phones* list. Tap to call.

Built for Samsung Galaxy phones on Android 12 or newer, and works on any Android 12+ phone with Bluetooth LE.

## Features

- **Discovery**: phones with BlueCall open find each other over Bluetooth LE. Only BlueCall phones appear, and there are no pairing or "make discoverable" prompts.
- **Real-time audio**:
  - Opus codec (24 kbps, 48 kHz, DTX).
  - Adaptive jitter buffer.
  - The Bluetooth link is switched to its fast connection interval and the 2M PHY during calls only.
  - Designed for about 70–120 ms mouth-to-ear delay (a target, not yet measured on hardware). The call screen shows a live estimate.
- **Noise cancellation**: five separate switches, all **on by default**, and you can change them mid-call:
  - Echo cancellation (phone's built-in)
  - Noise suppression (phone's built-in)
  - Automatic gain control (phone's built-in)
  - AI noise reduction (RNNoise)
  - Wind & rumble low-cut filter (100 Hz)
- **Call notifications**:
  - A quiet "BlueCall is on" notice while idle.
  - A call-style notification during calls: status-bar call chip, timer, Hang up, Mute and Speaker buttons.
  - Full controls on the **lock screen**.
  - On One UI 8 / Android 16+, the call is requested as a Live Update for the Now Bar.
- **Incoming calls**: ring through your normal ringtone (respects silent mode and Do Not Disturb). The full-screen call screen opens over the lock screen.
- **Audio output**: earpiece (screen turns off at your ear), speaker, Bluetooth headset or wired headset.
- **Interruptions**: a regular phone call automatically puts the BlueCall call on hold.

## Battery behaviour

| BlueCall is… | What runs |
|---|---|
| **Exited** (Exit button, swiped from Recents, or auto-offline) | **Nothing.** The process is ended. There's no boot receiver, no scheduled jobs or alarms, no push messages and no sticky service, so nothing can start BlueCall except you opening it. |
| **Open, on screen** | A foreground service, Bluetooth LE advertising (balanced), and scanning with a filter the hardware applies. |
| **Open, locked or in background** | Low-power advertising only (about one packet burst per second, done by the Bluetooth chip). No scanning, no wakelocks, no timers. Incoming calls still get through. |
| **In a call** | Audio, the Bluetooth link at high priority, a partial wakelock, and the proximity screen-off lock on the earpiece. All are released when the call ends. |

- **Auto-offline** (Settings, default 30 min): if no call happens for that long while BlueCall is in the background, it shuts down completely. It never wakes the phone for this. It uses a non-wakeup alarm plus a check when the screen turns on, so it acts the next time the phone wakes anyway.
- **Samsung**: One UI can pause background apps. BlueCall offers a one-tap "Unrestricted" battery setting and a shortcut to *Battery › Background usage limits › Never sleeping apps*. These only matter while BlueCall is open; after Exit nothing of it runs.

## Building

Requirements: JDK 17+, Android SDK Platform **37.1**, NDK **29.0.14206865**, CMake **3.31.6**. Android Studio installs these from the SDK Manager.

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JVM unit tests (protocol, jitter buffer, state machine, settings)
./gradlew lintDebug
./gradlew connectedDebugAndroidTest   # on a connected phone: native Opus/RNNoise smoke test
./gradlew assembleRelease        # R8-minified, signed: app/build/outputs/apk/release/app-release.apk
```

Install the APK on **two** phones, open BlueCall on both, and grant **Nearby devices**, **Microphone** and **Notifications**.

The native audio libraries are vendored in `app/src/main/cpp/third_party/`. See [VENDORED.md](app/src/main/cpp/third_party/VENDORED.md) for versions, checksums and what was trimmed.

## Releases

Ready-to-install APKs are on the [Releases page](https://github.com/codemew/BlueCall/releases).

- **Signing**: release builds are signed with a **public test key** (`app/signing/bluecall-test.jks`), so each new build installs over the previous one. It's fine for personal testing, not for wide distribution. To sign with your own private key instead, see [app/signing/README.md](app/signing/README.md).
- **Cutting a release**:
  1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
  2. Add `.github/release-notes/vX.Y.Z.md`.
  3. Push. You can also push a `vX.Y.Z` tag, or start the workflow from the Actions tab.

  If release `vX.Y.Z` doesn't exist yet, the [release workflow](.github/workflows/release.yml) runs the tests, builds and signs the APK, creates the tag and publishes the release with a `SHA256SUMS.txt`. Releases are marked *pre-release* while `PRERELEASE` in that workflow is `"true"`.

## Testing on two phones

The Bluetooth and audio paths need real hardware. Checklist:

1. **Discovery**: open BlueCall on both phones. Each lists the other within about 2 s.
2. **Call**: tap the other phone. It rings. Answer from the call screen or the notification.
   - Speak both ways and check the delay readout and link quality.
   - Toggle each noise switch mid-call.
   - Switch speaker/earpiece. The screen should turn off at your ear on the earpiece.
3. **Locked**: lock both phones and wait 10+ minutes. Call one from the other: it rings and the call screen shows over the lock screen. Answer without unlocking.
4. **Exit leaves nothing behind**: tap **Exit**, then run:

   ```bash
   adb shell pidof com.codemew.bluecall                         # prints nothing
   adb shell dumpsys activity services com.codemew.bluecall     # no ServiceRecord
   adb shell dumpsys power | grep -i bluecall                   # no wake locks
   adb shell dumpsys alarm | grep -i bluecall                   # no alarms
   adb shell dumpsys bluetooth_manager | grep -i -A3 bluecall   # no advertisers/scanners
   ```

   Overnight check: `adb shell dumpsys batterystats --reset`, exit BlueCall, and the next morning `adb shell dumpsys batterystats com.codemew.bluecall` shows no wakeups or CPU time.
5. **Auto-offline**: set it to 15 min, go to the home screen, lock the phone. Once the phone next wakes after 15 min, BlueCall has shut down and posted a "went offline" note.

## How it works

```
 Phone A                                               Phone B
 ───────                                               ───────
 BLE scan (UI visible) ◄──── advertisement: version, L2CAP PSM, session id ──── BLE advertise
                            scan response: display name
 L2CAP connect (PSM) ─────────────────────────────────────────────────────► L2CAP server
 HELLO + CALL_REQUEST ───────────────────────────────────────────────────► rings
                      ◄───────────────────────────────────── HELLO, CALL_ACCEPT
 mic → low-cut → RNNoise → Opus ── AUDIO(seq, opus) frames ──► jitter buffer → Opus/PLC → speaker
                      ◄──────────────── same in reverse ─────────────────
 PING every 2 s ◄─► PONG (latency readout, drop detection)       HANGUP ends it
```

- `call/`: pure-Kotlin call state machine. Every transition, including shutdown from every state, is unit-tested.
- `protocol/`: framing (`[type][len][payload]`), messages, and `CallSession` (reader/writer threads, bounded audio queue, ping).
- `bluetooth/`: advertiser, scanner, L2CAP server/connector, and `LinkTuner` (fast link during calls).
- `audio/`: `AudioEngine` (capture and playout threads), `JitterBuffer`, `AudioRouter` (mode, focus, output device, proximity) and `VoiceEffects`.
- `service/`: `BlueCallService` (owns everything; one `shutdown()` path) and the notifications.
- `power/`: wakelocks, the non-wakeup idle check, and battery-settings shortcuts.
- `cpp/`: JNI bridge to libopus 1.5.2 and RNNoise.

## Limits

- Range is Bluetooth LE range: roughly 10–30 m indoors, more in open space.
- Both phones must have BlueCall open (it can be in the background or locked).
- One-to-one calls only. Android 12+ only (no iOS).

## Licences

- App code: MIT (see `LICENSE`).
- libopus and RNNoise: BSD-3-Clause.
- Status-bar and launcher glyph: Material icon "phone_bluetooth_speaker", Apache 2.0.

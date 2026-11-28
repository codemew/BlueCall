# BlueCall

Low-latency voice calls between two Android phones over Bluetooth or direct Wi-Fi. No internet, SIM, router or pairing needed. Open BlueCall on both phones, and each one shows the other in its *Nearby phones* list. Tap the Bluetooth or Wi-Fi button to call.

Built for Samsung Galaxy phones on Android 12 or newer, and works on any Android 12+ phone with Bluetooth LE. Wi-Fi calls need Wi-Fi Aware on both phones.

## Features

- **Discovery**: phones with BlueCall open find each other over Bluetooth LE and, where both have it, Wi-Fi Aware. Only BlueCall phones appear, once each, and there are no pairing or "make discoverable" prompts.
- **Bluetooth or Wi-Fi, your choice**: each nearby phone shows a Bluetooth button, a Wi-Fi button, or both. Tap the one to use for that call. The call screen and notification show which one is in use.
  - **Wi-Fi** is direct phone-to-phone Wi-Fi (Wi-Fi Aware). It needs no router or network, has more range and bandwidth (Opus at 32 kbps), and leaves Bluetooth free for earphones.
  - **Bluetooth** works on every phone, and is the one that still gets through to a phone in deep sleep.
  - With Bluetooth off, BlueCall stays online over Wi-Fi alone.
- **Earphones, car kits and watches**: calls are registered with Android's calling system (Jetpack Telecom), so:
  - Bluetooth earphones that connect **during a call** take it straight away, even from speaker or earpiece. When they disconnect, the call goes back to where it was: speaker stays speaker.
  - Earphone, car and watch buttons answer and hang up.
  - With several devices connected, the call screen lists each one by name.
  - A SIM call puts the BlueCall call on hold, with a **Resume** button afterwards.
  - On phones without the calling system, or with "Use system calling" off in Settings, BlueCall does the same routing itself.
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
- **Settings › Calls**: "Wi-Fi calling" and "Use system calling" switches, both on by default.
- **Appearance**:
  - Frosted-glass cards and buttons with real background blur, over flowing liquid colours. The call screen flows at full speed and full resolution; Home and Settings drift slowly.
  - Smooth spring animations for presses, screen changes, list updates and call controls. Halos pulse around the avatar while ringing.
  - 14 colour themes: your phone's own colours, 12 presets (Ocean, Aurora, Sunset, Lavender, Rose, Forest, Mint, Citrus, Ember, Coral, Midnight, Graphite) and a custom colour from any hue.
  - Light, dark or system mode, and Clear, Frosted or Solid glass (Solid turns blur off for maximum readability).
  - "Animated backgrounds" can be switched off in Settings. Motion also pauses automatically in Battery Saver and when system animations are off.

## Battery behaviour

| BlueCall is… | What runs |
|---|---|
| **Exited** (Exit button, swiped from Recents, or auto-offline) | **Nothing.** The process is ended. There's no boot receiver, no scheduled jobs or alarms, no push messages and no sticky service, so nothing can start BlueCall except you opening it. Android's calling system only keeps a record that BlueCall can place calls; it never runs BlueCall's code between calls. |
| **Open, on screen** | A foreground service, Bluetooth LE advertising (balanced), and scanning with a filter the hardware applies. With Wi-Fi calling on: Wi-Fi Aware publishing and passive discovery. The liquid background animates at up to 60 Hz while a BlueCall screen is visible (paused in Battery Saver or if turned off). |
| **Open, locked or in background** | Low-power advertising only (about one packet burst per second, done by the Bluetooth chip). With Wi-Fi calling on, Wi-Fi Aware publishing too: the Wi-Fi chip answers on its own in Wi-Fi Aware's short discovery windows, which Android spaces out when the screen is off. No scanning, no wakelocks, no timers, no app wakeups. Incoming calls still get through. |
| **In a call** | Audio, a partial wakelock, and the proximity screen-off lock on the earpiece. Bluetooth calls add the Bluetooth link at high priority; Wi-Fi calls add a Wi-Fi Aware data link for that call only. All are released when the call ends. |

- **Auto-offline** (Settings, default 30 min): if no call happens for that long while BlueCall is in the background, it shuts down completely. It never wakes the phone for this. It uses a non-wakeup alarm plus a check when the screen turns on, so it acts the next time the phone wakes anyway.
- **Wi-Fi calling** costs a little more battery while BlueCall is open than Bluetooth alone. Turn it off in *Settings › Calls* if you only use Bluetooth. After Exit it makes no difference: nothing runs.
- **Samsung**: One UI can pause background apps. BlueCall offers a one-tap "Unrestricted" battery setting and a shortcut to *Battery › Background usage limits › Never sleeping apps*. These only matter while BlueCall is open; after Exit nothing of it runs.

## Building

Requirements: JDK 17+, Android SDK Platform **37.1**, NDK **29.0.14206865**, CMake **3.31.6**. Android Studio installs these from the SDK Manager.

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JVM unit tests (protocol, jitter buffer, state machine, routing, Wi-Fi payloads, settings)
./gradlew lintDebug
./gradlew connectedDebugAndroidTest   # on a connected phone: native Opus/RNNoise smoke test
./gradlew assembleRelease        # R8-minified, signed: app/build/outputs/apk/release/app-release.apk
```

Install the APK on **two** phones, open BlueCall on both, and grant **Nearby devices**, **Microphone** and **Notifications**. On Android 13+, "Nearby devices" also covers Wi-Fi calls. On Android 12, Wi-Fi calls also need location access and Location turned on (Android's rule for Wi-Fi Aware there); BlueCall asks only if you want Wi-Fi calls, and never uses your location.

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
3. **Wi-Fi call** (both phones with Wi-Fi Aware and Wi-Fi on, no network needed): each phone shows a Wi-Fi button next to the other. Call with it. The call screen reads "BlueCall · Wi-Fi". Turn Bluetooth off on both phones: BlueCall stays online and Wi-Fi calls still work.
4. **Earphones**:
   - Mid-call on **speaker**, turn Bluetooth earphones on: audio moves to them within a couple of seconds. Turn them off: audio returns to the speaker.
   - Repeat from the **earpiece**: it returns to the earpiece.
   - Answer and hang up with the earphones' button.
   - With two Bluetooth devices connected, both are listed under the speaker button.
5. **SIM call**: call the phone's number during a BlueCall call. BlueCall goes on hold; after the SIM call, tap **Resume**.
6. **Locked**: lock both phones and wait 10+ minutes. Call one from the other: it rings and the call screen shows over the lock screen. Answer without unlocking.
7. **Exit leaves nothing behind**: tap **Exit**, then run:

   ```bash
   adb shell pidof com.codemew.bluecall                         # prints nothing
   adb shell dumpsys activity services com.codemew.bluecall     # no ServiceRecord
   adb shell dumpsys power | grep -i bluecall                   # no wake locks
   adb shell dumpsys alarm | grep -i bluecall                   # no alarms
   adb shell dumpsys bluetooth_manager | grep -i -A3 bluecall   # no advertisers/scanners
   adb shell dumpsys wifiaware | grep -i bluecall               # no Wi-Fi Aware client
   adb shell dumpsys telecom | grep -i -A2 com.codemew.bluecall # only the calling account, no calls
   ```

   Overnight check: `adb shell dumpsys batterystats --reset`, exit BlueCall, and the next morning `adb shell dumpsys batterystats com.codemew.bluecall` shows no wakeups or CPU time.
8. **Auto-offline**: set it to 15 min, go to the home screen, lock the phone. Once the phone next wakes after 15 min, BlueCall has shut down and posted a "went offline" note.

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

Over Wi-Fi (Wi-Fi Aware), discovery and the data link replace the Bluetooth parts; the call itself is the same:

```
 Caller (subscriber)                                   Callee (publisher)
 passive discovery (UI visible) ◄── service info: version, session id, name ── publish
 CONNECT (nonce, per-call passphrase) ─────────────────────────────► listens on a TCP port,
                      ◄─────────────────────────────── READY        requests the data link (with port)
 requests the data link ◄═══════════ Wi-Fi Aware data link ═══════════► accepts one connection
 TCP to the peer's IPv6 address and port, then HELLO + CALL_REQUEST … as above
```

- `call/`: pure-Kotlin call state machine, and `PeerDirectory`, which merges Bluetooth and Wi-Fi sightings by session id. Every transition, including shutdown from every state, is unit-tested.
- `protocol/`: framing (`[type][len][payload]`), messages, `CallLink` (one connection, whichever radio), and `CallSession` (reader/writer threads, bounded audio queue, ping).
- `bluetooth/`: advertiser, scanner, L2CAP server/connector, and `LinkTuner` (fast link during calls).
- `wifi/`: Wi-Fi Aware publishing and discovery, the CONNECT/READY/BUSY messages, and the data link for each side of a call.
- `telecom/`: `SystemCall`, the bridge to Android's calling system (Jetpack Telecom).
- `audio/`: `AudioEngine` (capture and playout threads), `JitterBuffer`, `RoutePolicy` (where audio goes as devices come and go, unit-tested), `AudioRouter` (follows the calling system, or sets mode, focus and the output device itself; proximity) and `VoiceEffects`.
- `service/`: `BlueCallService` (owns everything; one `shutdown()` path) and the notifications.
- `power/`: wakelocks, the non-wakeup idle check, and battery-settings shortcuts.
- `cpp/`: JNI bridge to libopus 1.5.2 and RNNoise.

## Limits

- Range is Bluetooth LE range: roughly 10–30 m indoors, more in open space. Direct Wi-Fi usually reaches a little further.
- Both phones must have BlueCall open (it can be in the background or locked).
- **Wi-Fi calls**:
  - Both phones need Wi-Fi Aware. Many Galaxy S, Note and Fold models have it; most A-series phones don't. Phones without it show only the Bluetooth button.
  - Android pauses Wi-Fi Aware while a phone is in deep sleep, or using a hotspot. Wi-Fi calls reach it again once it wakes; Bluetooth calls always get through.
  - Wi-Fi calls are not end-to-end encrypted. Android sets up the Wi-Fi Aware link with a per-call passphrase, but it is exchanged in the clear, so it doesn't protect against someone nearby listening in. Bluetooth calls aren't encrypted either.
- A Bluetooth call and Bluetooth earphones share one radio. If the audio breaks up with earphones on, a Wi-Fi call is more robust.
- One-to-one calls only. Android 12+ only (no iOS).

## Licences

- App code: MIT (see `LICENSE`).
- libopus and RNNoise: BSD-3-Clause.
- Status-bar and launcher glyph: Material icon "phone_bluetooth_speaker", Apache 2.0.

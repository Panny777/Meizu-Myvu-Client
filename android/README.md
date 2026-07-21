# MYVU Android Client

> Unofficial, community-built client for Meizu MYVU AR glasses; not affiliated
> with or endorsed by Meizu.

A Java Android client for the Meizu MYVU (Star Air, model XGA010C) AR glasses,
reverse-engineered from the official app and a working Python reference client
(`../myvu_client`). It pairs, drives every documented feature, mirrors real
phone notifications, runs turn-by-turn navigation, and hosts a voice assistant
that uses the glasses' own microphone.

Package `com.myvu.client`. Java, no Kotlin. `minSdk 26`, tested on API 31.

## What works

- **Connection** — BLE bring-up + ECDH bond, then the classic-BT app relay.
- **Notifications** — manual, plus live mirroring of real phone notifications.
- **Teleprompter, system settings, queries, clock sync.**
- **Navigation** — OSRM routing + FusedLocation, rendered on the lens HUD.
- **AI assistant** — glasses mic → selectable Groq or local STT → Claude,
  ChatGPT, Gemini, or a local OpenAI-compatible LLM → selectable device or HTTP TTS.

## Architecture

Two Bluetooth transports run at once, and the order is **not** optional:

1. **BLE first.** A cold `createBond()` over BR/EDR just times out (~13s, no ACL)
   — the glasses' classic radio does not page-scan until BLE has woken them. BLE
   carries the ECDH bond and is the **only** place the app relay's address is
   announced (`CMD_SPP_SERVER_UUID_SYNC`), because the glasses regenerate that
   RFCOMM UUID every session.
2. **RFCOMM second**, to that per-session UUID (which SDP happens to resolve to
   channel 13). This is the link that actually carries app traffic.

Everything above the transports is transport-agnostic: the relay layer, the
RunAsOne session handshake, and every feature. All protocol state lives on one
thread (`myvu-conn`), so `protocol/` and `app/` need no locking.

```
transport/ble   GATT, packet codec, message channel, ECDH pairing, heartbeat
transport/bt    RFCOMM framing + the per-session-UUID socket
protocol        TLV, protobuf, relay, session, init burst
app             StMessage envelope, InboundRouter, feature builders
service         foreground service, ConnectionManager, RelaySupervisor
ai              glasses-mic capture, Opus decode, STT/LLM/TTS clients
nav             OSRM, RouteTracker, FusedLocation, HUD frames
ui              connect screen + live log
```

## Building

The Gradle wrapper is checked in, so a clone needs only a JDK 17 and an Android
SDK (`compileSdk 34`) — it fetches the pinned Gradle 8.14.3 itself:

```sh
cd android
./gradlew :app:assembleDebug      # gradlew.bat on Windows
./gradlew test                    # JVM unit tests (codec, crypto, protocol)
```

The APK lands in `app/build/outputs/apk/debug/`. Point the SDK location at your
install via `local.properties` (`sdk.dir=...`) or the `ANDROID_HOME` env var —
that file is deliberately untracked.

Release builds (`:app:assembleRelease`) are signed only when
`android/keystore.properties` points at a local keystore (see the comment in
`app/build.gradle`); both files are untracked secrets, so a fresh clone still
builds — it just produces an unsigned release APK. Signed APKs are published on
the GitHub releases page. **Keep the keystore and keystore.properties backed
up outside the repo**: Android only installs an update over an existing app
when it is signed with the same key, so losing them strands every installed
copy on its current version.

## Running it

1. **Turn off the glasses' other central.** They accept one BLE central at a
   time. Force-stop the official app (`com.upuphone.star.launcher.intl`) and
   disconnect any other paired phone, or BLE pairing will be rejected ~1s in.
2. Enter the glasses' MAC and configure the assistant services. Cloud providers
   require their API keys; local OpenAI-compatible services accept a configurable
   endpoint, model, and optional Bearer token. Settings are stored in
   `SharedPreferences` only — never in source.
3. Grant notification access (for mirroring) via the in-app button.
4. Connect. The link lives in a foreground service and survives backgrounding.

Long-press **Clear log** to share the diagnostic log.

### Configuring assistant services

The three stages are selected independently in Settings, so cloud and local
services can be mixed:

| Stage | Hosted/device choices | Local/API choice | Expected API |
|---|---|---|---|
| AI answer | Claude, ChatGPT, Gemini | Local | OpenAI-compatible `POST /v1/chat/completions` |
| Speech to text | Groq | Local | OpenAI-compatible multipart `POST /v1/audio/transcriptions` |
| Text to speech | Android device engine | HTTP API | OpenAI-compatible JSON `POST /v1/audio/speech`, returning WAV audio |

Each choice remembers its own settings. Local services accept a full endpoint
URL and an optional Bearer token. A local AI model ID is required; use an ID
returned by that server's `GET /v1/models`. The local STT model defaults to
`whisper`. HTTP TTS model and voice fields are optional and are omitted from the
request when blank.

For the companion servers in this repository's parent `servers` directory, the
pre-filled endpoint values are:

```text
AI:  http://10.0.0.2:1234/v1/chat/completions
STT: http://10.0.0.2:1235/v1/audio/transcriptions
TTS: http://10.0.0.2:1236/v1/audio/speech
```

Change the host or port in Settings when the servers run elsewhere. HTTPS is
accepted for any host; cleartext HTTP is restricted to literal private-LAN or
loopback addresses so prompts, microphone audio, and credentials are not sent
unencrypted to a public host. Provider, endpoint, key, model, voice, and system
prompt settings are re-read for each turn, so changes do not require a reconnect.

## Gotchas learned the hard way

- **Installing on MIUI:** `adb install` is blocked for new packages
  (`INSTALL_FAILED_USER_RESTRICTED`). Use
  `adb push app-debug.apk /data/local/tmp/ && adb shell pm install -r -t /data/local/tmp/app-debug.apk`.
- **Wedged RFCOMM stack:** after many socket cycles the phone's Bluetooth stack
  can get stuck (`RFCOMM_CreateConnection: already at opened state`, `MCB_state=4`),
  and every relay connect then fails with `read failed ... read ret: -1`. This is
  the phone, not the glasses — the client detects it and says so. **Toggle the
  phone's Bluetooth off and on** to clear it.
- **Notification ids:** the glasses REBOOT on a malformed notification id. It
  must be `phone-<packageName>-<numericId>` — never Android's `StatusBarNotification.getKey()`.
- **Glasses mic audio (`code:109`):** field 5 is not a raw Opus packet. It is
  `[2-byte big-endian length][Opus frame]` (config 11, SILK wideband 16 kHz).
  Feeding the length prefix to the decoder produces speech-shaped garbage.
- **Nav routing:** `open_app` goes to the launcher (it opens apps); `navi_info`
  frames go to the nav app. Both are sourced from `com.upuphone.ar.navi.lite`.
  The HUD will not open unless the phone also answers the glasses' launch-app
  request (`type:11` → `type:12`).

## Not implemented, on purpose

- **Call / media status** (`callStatus`, `audio_multi`): the decompiled payloads
  carry no usable data (`audioBytes` is `transient`; `PhoneCallStatus` is only
  ever consumed, never sent), so there is nothing to faithfully populate.
- **The `hfp.py` AT-responder** from the Python client: Android holds HFP/A2DP
  natively as a phone.

Per the reference client's warnings, these are also deliberately absent:
`do_recovery`, `system_account`, `system_glass_active`, `user_feedback`.

## Known limitations

- The navigation maneuver-icon mapping (`nav/IcMap`) is **provisional** — the
  glasses' arrow enum was never documented. Use the in-app **IC calibrate**
  button to photograph each value and correct it.
- Release builds keep `minifyEnabled false`: the `createBond` reflection in
  `service/Bonding` would need explicit R8 keep rules otherwise.

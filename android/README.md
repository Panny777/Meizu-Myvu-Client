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
- **Live captions** — the glasses' subtitle scene, driven by Deepgram streaming
  speech-to-text instead of the vendor cloud.

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
transcribe      live-caption protocol, session state, Deepgram streaming STT
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

### Live captions

Subtitles whatever the glasses' microphone hears onto the lens, live. This is a
**separate protocol** from the AI assistant above: the assistant is the shared
`{"code":N}` channel, captioning is a point-to-point link between a phone-side
package and its glasses-side twin, e.g. `com.upuphone.ar.translation.phone` ↔
`com.upuphone.ar.translation.glasses`. Every message is
`{"msgCmd":<int>,"data":"<escaped json>"}` — `data` is a **string** holding
escaped JSON, not a nested object; a nested object parses fine and then binds to
nothing.

The glasses only capture and render. The phone does all the recognition, which
is exactly why this is implementable: we put Deepgram where the vendor cloud
was, and the glasses cannot tell.

```
phone → launcher   open_app scene        starts the glasses-side PROCESS
phone → glasses    52 transState 1       NotStart, then the subtitle config
glasses → phone    type:11               "launch your companion service"
phone → glasses    type:12               ack (InboundRouter)
phone → launcher   open_app scene AGAIN  ← this is what opens the scene's UI
phone → glasses    52 transState 3       Preparing; the lens shows "connecting"
                   51 recordState 1      recorder INIT
                   60 language set
                   52 ext 16             show-waiting spinner
       ── the Deepgram socket opens here, inside that spinner ──
phone → glasses    52 transState 4       Running
                   51 recordState 2      START — this is what opens the mic tap
glasses → phone    {"msgCmd":4} + 240B   one raw 60 ms Opus packet per message
phone → glasses    52 ext 20             voice detected — unblocks the lens
phone → glasses    53 caption frames     partials grow, then one final
phone → glasses    51 recordState 3/4, 52 transState 2, 66   on stop
```

Three of those are load-bearing in ways that are invisible until you hit them:

- **`open_app` is sent twice.** The first starts the process; the scene's UI only
  opens after the `type:11`/`type:12` launch handshake, so the vendor re-issues
  it 14 ms after acking. Send it once and you get a scene that runs the recorder
  and streams audio but never draws — captions arrive and land on a loading
  screen forever.
- **`recordState 2` opens the mic tap.** The first Opus packet arrived 180 ms
  after it, so the recogniser must already be connected when it goes out.
- **The lens gates its subtitle view on a VAD state the phone must drive**:
  `extCode 20` when speech starts, `14`/`19`/`15` at 2 s/5 s/5 min of silence.
  The vendor derives these from its noise-reduction engine; we take them from
  Deepgram's `SpeechStarted` plus timers. Without them the lens waits for speech
  that, as far as it knows, never comes.

Captioning **refuses to run on the BLE fallback** and waits for the relay
instead. A session that starts on BLE and finishes on the relay is split across
two transports with two independent msgId runs, and the glasses act on only half
of it.

### Starting it from the glasses

Opening the scene on the lens works too — the phone joins the session rather
than the other way round. The trigger is the same `type:11` launch request:
the glasses ask the phone to start the app's companion service, and that request
naming a captioning package is the only usable "started on the glasses" signal
we get. (The vendor's own trigger, `msgCmd 1`, is only sent to a phone package
the interconnect service has registered, which we cannot be.)

In that direction the phone skips both `open_app`s — the scene is already
opening, and asking the launcher to open it again restarts it underneath.

Configure it in **Settings → Live captions**: a Deepgram API key, model
(`nova-3`), and language (`en`, or `multi` on nova-3 for mixed speech). The
"language told to the glasses" is separate and only affects the lens UI — the
firmware accepts `en`, `cn`, `cnen`, `ja` and nothing else.

**Glasses-side app** picks which scene to open. *Translator* is the default
because it ships on international firmware and is what the reference capture
used; *Transcribe* is the note-taking app and may not be installed on every
build, in which case the launcher silently ignores the request.

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
- **Caption audio (`msgCmd:3|4`) is framed differently** from `code:109` on the
  very same field 5: it *is* a raw Opus packet, 240 bytes, one 60 ms frame, no
  length prefix. Two audio paths, two framings — do not share the parser.
- **Opus decodes at 48 kHz internally,** so MediaCodec may hand back 48 kHz PCM
  whatever OpusHead declares. Streaming that to a socket opened as
  `sample_rate=16000` does not error; it transcribes chipmunk speech into
  confident nonsense. `transcribe/PcmConverter` normalises using the decoder's
  reported rate rather than the assumed one.
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

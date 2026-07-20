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
- **AI assistant** — glasses mic → Opus decode → Groq Whisper → Claude → TTS.

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
ai              glasses-mic capture, Opus decode, Groq STT, Claude, TTS
nav             OSRM, RouteTracker, FusedLocation, HUD frames
ui              connect screen + live log
```

## Running it

1. **Turn off the glasses' other central.** They accept one BLE central at a
   time. Force-stop the official app (`com.upuphone.star.launcher.intl`) and
   disconnect any other paired phone, or BLE pairing will be rejected ~1s in.
2. Enter the glasses' MAC, and (for the AI assistant) a **Claude** and a **Groq**
   API key. Keys are stored in `SharedPreferences` only — never in source.
3. Grant notification access (for mirroring) via the in-app button.
4. Connect. The link lives in a foreground service and survives backgrounding.

Long-press **Clear log** to share the diagnostic log.

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

# Meizu MYVU Client

> Unofficial, community-built client for Meizu MYVU AR glasses; not affiliated
> with or endorsed by Meizu.

A reverse-engineered client for the **Meizu MYVU (Star Air, model `XGA010C`)**
AR glasses. It speaks the glasses' own Bluetooth protocol directly — no official
app required — to pair, drive the on-lens UI, push notifications, run a
teleprompter and turn-by-turn navigation, act as a remote trackpad, and host a
voice assistant that uses the glasses' built-in microphone.

There are two implementations of the same protocol:

| Folder | What it is | Runs on |
|---|---|---|
| [`android/`](android/) | Full-featured Java Android app (`com.myvu.client`). **The stable client — start here.** | Android (minSdk 26, tested on API 31) |
| [`python/`](python/) | The original reverse-engineering reference the Android port was built from. **Rough and not stable** — kept as-is for protocol study, not as a polished tool. | Windows (uses WinRT for BLE + classic-BT) |

Each folder has its own README with build/run details:
[android/README.md](android/README.md) · [python/README.md](python/README.md).

## What works

- **Connection** — BLE bring-up + ECDH bond, then the classic-Bluetooth app relay. Optional **auto-search** discovers the glasses over a BLE scan, so you don't need the MAC.
- **"Phone connected" state** — connects the standard HFP/A2DP profiles so the glasses light their own connected indicator, not just the app relay.
- **Notifications** — send your own, or mirror real phone notifications to the lens.
- **Teleprompter**, **system settings** (volume, brightness, Wi-Fi, wear detection, zen mode, screen-off, standby position…), **clock sync**, and status **queries**.
- **Navigation** — full turn-by-turn HUD (OSRM routing, Nominatim geocoding), driven by the phone's location.
- **Trackpad** — the phone as a remote touchpad for the glasses' launcher (tap / double-tap / long-press / swipe).
- **AI assistant** — press the glasses' button or type a question; speech-to-text, an LLM answer, and text-to-speech back to the glasses. On Android, STT, the answering provider, and TTS are independently selectable between hosted options and configurable local OpenAI-compatible endpoints; the Python client answers via Claude.

## How it works (short version)

The glasses require **two Bluetooth links at once**:

1. **BLE first.** The classic radio won't answer a page until BLE has woken the
   glasses. BLE carries version negotiation, an **ECDH bond** (AES-encrypted
   `DeviceInfo` exchange), the `AUTH_SUCCESS` handshake, an "init burst" of
   opening messages, and a 3 s heartbeat. It is also the **only** place the
   app-relay's address is announced — the glasses regenerate a random RFCOMM
   service UUID every session and sync it over BLE (`SPP_SERVER_UUID_SYNC`).
2. **Classic-BT (RFCOMM) second**, to that per-session UUID. This is the link
   that actually carries app traffic. Each feature is a JSON
   `{"action": …}` message over a "RunAsOne" relay, routed to a target package
   on the glasses (e.g. `com.upuphone.star.launcher`).

The glasses' microphone streams back as Opus frames; navigation, the trackpad
("phonepad"), and the assistant are all just JSON actions over the same relay.

## Hardware / prerequisites

- A Meizu MYVU / Star Air pair, already **BR/EDR-bonded** to the phone/PC.
- During testing, keep any *other* paired phone (and other machines running a
  client) disconnected — the glasses accept **one** central at a time.

## Reverse-engineering note

The protocol was recovered from Bluetooth captures and by studying the official
app. That decompiled app is **not included here** — it's Meizu's proprietary
code. What was learned from it lives in these clients and their docs, not in the
form of redistributed sources.

## Status

A hobby/interoperability project, developed and hardware-verified against one
pair of glasses. It is not a product and comes with no warranty. See the
disclaimer at the top.

## License

[MIT](LICENSE) © 2026 Panny777. The licence covers the original code in this
repository only; it grants no rights to Meizu's trademarks, patents, or the
decompiled app, which is not distributed here.

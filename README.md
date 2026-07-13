# 🕶️ myvu-client — an unofficial Python client for Meizu MYVU AR glasses

A from-scratch Python client that talks directly to Meizu MYVU (Star Air,
model `XGA010C`) AR glasses over Bluetooth — **no phone, no official app
required**. It performs the real app-layer pairing handshake, joins the
glasses' session, and can push notifications, drive the teleprompter, and
read live telemetry, all reverse-engineered from a decompiled APK and a
Bluetooth packet capture of the official app.

> **Status:** both transports are fully working and confirmed live — a
> notification sent from this script displays on the lens with zero phone
> involved, and as of the classic-Bluetooth breakthrough below, **the
> teleprompter works too**, driven entirely from this client. See
> [Final status](#-final-status) for the honest details.

> **Not affiliated with Meizu, Upuphone, or Flyme.** This is an independent
> interoperability project built by observing the official app's own network
> traffic — no proprietary source code is included here, and nothing here
> bypasses any account, license, or DRM system (there isn't one — see
> [Layer 4](#layer-4--crypto-cryptopy)).

## Table of contents

- [Why this exists](#why-this-exists)
- [Features](#features)
- [Quick start](#quick-start)
- [How it works](#how-it-works)
- [Protocol deep dive](#protocol-deep-dive) *(the full reverse-engineering writeup)*
- [Troubleshooting](#troubleshooting)
- [Final status](#-final-status)
- [Classic-Bluetooth (RFCOMM) — how the teleprompter got working](#classic-bluetooth-rfcomm--how-the-teleprompter-got-working)
- [Project structure](#project-structure)
- [Contributing](#contributing)

## Why this exists

Meizu's official MYVU app is the only supported way to use these glasses, and
tools like nRF Connect can pair at the Bluetooth level but get nowhere — the
glasses just sit there ignoring you. That's not a manufacturer lockout or a
signed-certificate check; it's because the app performs its own multi-step
handshake on top of plain BLE that no generic BLE tool knows how to speak.
This project reverse-engineers that handshake end to end and reimplements it
from scratch in Python, so the glasses can be driven by anything you want —
scripts, home automation, other platforms — instead of only the official app.

## Features

- 🔐 **Full pairing handshake** — EC P-256 ECDH key exchange, byte-for-byte
  compatible with the real app (verified against a packet capture)
- 📡 **Live telemetry** — battery, wear state, key presses, screen state,
  streamed continuously once connected
- 🔔 **Push notifications to the lens** from any Python script
- 📜 **Teleprompter control** — open it and load arbitrary text. Requires the
  classic-BT link (`python run_glasses.py`, not plain `run.py` — see
  [Classic-Bluetooth (RFCOMM)](#classic-bluetooth-rfcomm--how-the-teleprompter-got-working))
- 🔊 **Volume, brightness, WiFi, and standby-widget control**
- 🔍 **Query any device status** (battery, language, zen mode, WiFi list, ...)
- 🛠️ **Send any app command** via `send_action()` — the protocol vocabulary is
  fully documented below, so new features are a small addition, not a new
  reverse-engineering project
- 🤖 **`ask <question>`** — a text-only stand-in for the AI assistant: generates
  an answer with the Claude API (Haiku 4.5) and pushes it to the lens as a
  caption through the same JSON channel the real assistant uses (no
  microphone/speaker audio required — see [Layer 7](#layer-7--driving-features-applayerpy))
- 🧪 **Offline self-test suite** — every protocol layer is validated against
  real captured bytes, no hardware required to verify correctness
- 💬 **Interactive REPL** for driving the glasses live from the terminal
- 📝 **Two-tier logging** — the console shows connection milestones only; every
  packet, ACK, and telemetry message is written to `myvu.log` for later review

## Quick start

```bash
pip install -r requirements.txt

# sanity check: validates every protocol layer against real captured bytes
python selftest.py                  # -> ALL 22 CHECKS PASSED

# find your glasses
python run.py                       # scans and lists nearby StarryNet devices

# connect, pair, and drop into the control REPL
python run.py <BLE-ADDRESS>
```

Once connected you get an interactive prompt:

```
myvu> notify Hello from Python!
myvu> notify Meeting | Standup starts in 5 minutes
myvu> tici Welcome to my talk.\nFirst point here.
myvu> hl 1
myvu> vol 8
myvu> bright 5
myvu> wifi off
myvu> query get_device_info
myvu> raw {"action":"system","data":{"action":"get_device_info"}}
myvu> ask What's a good icebreaker for a team meeting?
```

| command | effect |
|---|---|
| `notify <text>` | push a notification card to the lens |
| `notify <title> \| <body>` | notification with a separate title |
| `tici <text>` | open the teleprompter and load this text (`\n` for line breaks) — needs `run_glasses.py`, see below |
| `hl <index>` | scroll/highlight the teleprompter to paragraph `<index>` — same requirement |
| `vol <0-15>` | set the glasses' volume |
| `bright <value>` | set the glasses' screen brightness |
| `wifi on\|off` | turn the glasses' own WiFi radio on/off |
| `fov <0-3>` | set the field-of-view position of the standby widgets (confirmed range) |
| `query <action>` | send a no-arg status query; reply lands in `myvu.log`, not inline |
| `raw <json>` | send any raw app-action JSON |
| `ask <question>` | generate a Claude answer and push it to the lens as text (see [Layer 7](#layer-7--driving-features-applayerpy)) |
| `help` / `q` | show detailed help for every command / disconnect and exit |

`ask` needs the `anthropic` package (already in `requirements.txt`) and an API
key — set `ANTHROPIC_API_KEY`, or run `ant auth login`.

Run `help` in the REPL for a longer description of each command, including the
full list of known `query` action names.

If the glasses were previously paired with a real phone, pass that phone's
Bluetooth MAC so the glasses recognize the identity:

```bash
python run.py <BLE-ADDRESS> --mac 7C:A3:75:D0:94:F1
```

By default the console only prints connection milestones and your own command
confirmations — every packet, ACK, and telemetry message (key presses, battery
stats, event tracking, ...) is written to `myvu.log` instead, so the terminal
stays readable during a live session:

```bash
python run.py <BLE-ADDRESS>              # console: milestones only, full detail -> myvu.log
python run.py <BLE-ADDRESS> --debug      # also mirror full detail to the console
python run.py <BLE-ADDRESS> --log-file custom.log
tail -f myvu.log                         # watch full detail live in another terminal
```

### Teleprompter (`tici`/`hl`): use `run_glasses.py`, not `run.py`

Plain `run.py` only opens the BLE link. The teleprompter (and probably
anything else gated the same way) additionally needs a classic-Bluetooth
link — not just any classic-BT connection, but the *specific*, per-session
relay channel the glasses negotiate over BLE, plus a Hands-Free Profile (HFP)
handshake. `run_glasses.py` does all of it automatically:

```bash
# one-time prerequisite: classic-BT pairing (see the section below first --
# it has a real crash history, read it before running this)
python pair_glasses.py <BT-ADDRESS>

# then every session:
python run_glasses.py <BT-ADDRESS>
myvu> tici Welcome to my talk.\nFirst point here.
```

Windows only (it uses `winsdk`'s WinRT Bluetooth APIs for SDP-by-UUID RFCOMM
resolution — see the section below for exactly why that's necessary).

## How it works

Getting from "BLE connected" to "glasses fully respond to commands" turned
out to be a five-layer stack, each one reverse-engineered from a decompiled
APK cross-checked against a live packet capture:

1. **Pairing** — an ECDH key exchange over a dedicated BLE characteristic
2. **A reliable packet-fragmentation layer** on top of raw BLE writes
3. **Version/session negotiation**, in two phases
4. **A sequenced application-message relay** (drop this and the glasses
   silently discard everything you send)
5. **The application command vocabulary itself** — JSON actions like
   `notification`, `tici` (teleprompter), `system`, etc.

Every one of those was a genuine "why doesn't this work" investigation with a
concrete, verifiable answer — the full writeup is below for anyone curious
(or trying to do the same thing for a different device).

## Protocol deep dive

<details>
<summary><b>Click to expand the full reverse-engineering writeup (all 7 layers)</b></summary>

### Layer 1 — BLE (`uuids.py`)
Service `0x0BD1`. Two channels, each a characteristic used for both write
(write-without-response) and notify:

| role | Air UUID | capture handle |
|------|----------|----------------|
| INTERNAL (link/pairing) | `0x2020` | `0x0023` |
| EXTERNAL (app JSON)     | `0x2021` | `0x0026` |

### Layer 2 — packet transport (`packets.py`, `channel.py`)
Little-endian, 2-byte `sn` prefix. `sn==0` → control packet
(CTR/FastCTR/MixCTR/Single/ACK), `sn!=0` → data fragment. Fragment size
`DMTU = ATT_MTU - 5`. Verified forms:
* pairing channel: `SinglePacket`(type 2, pkg 16) ↔ `SingleACK`(type 3)
* app channel: `SINGLE_NO_ACK`(type 9) small, `MIX_CTR`(type 8)+data large, pkg 0

### Layer 3 — LinkProtocol (`linkproto.py`)
protobuf `LinkProtocol{ device_id, cmd, data }`.
`device_id = dealDeviceId(identifier)` = reverse + bitwise-NOT of the 6-byte MAC
(verified against a real capture: `7ca375d094f1 → 0e6b2f8a5c83`). Commands used:
`WRITE_SWITCH_KEY(11)`, `WRITE_SWITCH_INFO(13)`.

### Layer 4 — crypto (`crypto.py`)
EC P-256 (secp256r1) ECDH; raw shared secret (32 B) used directly as the AES key
(no KDF). Public keys exchanged as X.509 SPKI DER (91 B). IV = 16 ASCII chars of
a UUID4. Symmetric mode is **negotiated** (`"e"` in the version JSON):
`1=AES/CBC/PKCS5`, `2=AES/CTR`, else `AES/GCM`. A captured session negotiated
`e=1` → **AES/CBC**.

There is no manufacturer certificate or signature check anywhere in this
handshake — any client that speaks the protocol correctly is accepted. That's
the actual answer to "why does nRF Connect fail": it never performs this
handshake, not because of a device-binding lockout.

### Handshake sequence (`client.py::pair`)
1. **version negotiation** — FAST_CTR pkg 17, JSON `{"i","v","e","m","b","c"}`;
   reply sets the AES mode.
2. **WRITE_SWITCH_KEY** — `WriteSwitchKey{ key=our SPKI pubkey, info=our MAC }`.
3. **← WRITE_SWITCH_KEY** — `key = glasses pubkey ‖ 16-B IV`,
   `info = AES(glasses DeviceInfo)`. We derive the shared secret and decrypt it
   (proof the bond is valid).
4. **WRITE_SWITCH_INFO** — our `AES(WriteSwitchInfo{ AES(our DeviceInfo) })`
   (double-encrypted, per `generateDeviceInfoSwitchData`). Bond established.

### Layer 5 — RunAsOne relay / SuperMessage (`tlv.py`, `relay.py`)

After the ECDH bond and the ability handshake, the glasses still show "Open MYVU
AR App" until they receive properly **sequenced** app messages on the relay
layer.

* **Not account/server-gated.** No server/cloud/token calls exist in the connect
  path; the account/active-state messages are analytics only. The whole
  handshake is local.
* The app messages ride a reliable, sequenced channel. Wire format of one frame:

  ```
  0x01  TlvBox{ 112 category, 113 payload=TlvBox{
            100 msgType(3=data,4=ack), 101 msgId(int32 SEQUENCE),
            103 needCallback, 109 appUniteCode, 105 msgBody(app StMessage) } }
  ```
* **Why a naive replay of captured bytes fails:** the glasses track the last
  received sequence number (0 on a fresh connect) and treat a message whose
  `msgId` is far above it as a huge out-of-order gap — they buffer it instead of
  delivering it. Replaying a capture's stale `msgId`s means the glasses ACK at
  the transport layer but never deliver to the app. The fix is a **fresh
  sequence starting at 1**.

`relay.RelaySequencer` owns the outgoing counter, builds data/ACK frames, and the
client auto-ACKs the glasses' inbound data messages.

### Layer 6 — two-phase ability auth (StreamReq)

The `0x02`-class messages are `StreamReq` protobufs, where field 1 is
`StreamType`. The ability handshake is **two-phase** and BOTH are required for
the glasses to engage:
1. `AUTH` (type 0) — initial ability announcement (`build_ability_message`)
2. `AUTH_SUCCESS` (type 12) — confirm sent after the glasses' reply
   (`build_auth_success_message`). **Without this the glasses ACK data but never
   engage the app layer.**

### Layer 7 — driving features (`applayer.py`)

Incoming app messages are parsed automatically (JSON body in field 4 of the
StMessage envelope). To **send** commands, the StMessage envelope is
`{2:srcPkg, 3:dstPkg, 4:json, 6:msgId}` — built by `send_action()`.

Programmatic API: `client.push_notification(title, content)`,
`client.open_teleprompter(text)`, `client.teleprompter_highlight(index)`,
`client.set_volume(value)`, `client.set_brightness(value)`,
`client.toggle_wifi(enable)`, `client.set_standby_position(0-3)`,
`client.set_fov_pos_type(value)`, `client.query(action_name)`,
`client.send_action(json_str, target_pkg=...)`, `client.ask_ai(question)`.

Almost every "system"-category command shares one envelope:
`{"action":"system","data":{"action":"<verb>", ...}}` — `"system"` is just the
routing tag, the real command is `data.action`. Reverse-engineered verbs, from
`SuperMessageManger` in the decompiled app:

* **Queries** (no payload, glasses reply async): `get_device_info`,
  `get_language`, `get_zen_mode`, `get_air_mode`, `get_brightness`,
  `get_volume_stream_type`, `get_screen_off_time`, `get_wear_detection_mode`,
  `get_music_tp_control_mode`, `get_fov_pos_type`, `get_standby_position`,
  `get_standby_widget_lists`, `get_network_valid`, `request_wifi_list`,
  `request_phone_battery`, `get_glass_log`
* **Setters**: `set_volume`, `set_brightness`, `set_brightness_finish`,
  `toggle_wifi`, `set_standby_position` (**confirmed range 0-3**, sets the
  field-of-view position of the standby widgets while idle),
  `set_fov_pos_type` (meaning not yet confirmed)
* **Not user-facing — internal plumbing, don't bother wiring these up**:
  `system_account`/`account_state` (tells the glasses which Flyme account is
  logged in), `system_glass_active`/`req_active_state`/`req_active_info` (an
  internal NPS-survey eligibility check, gated on account login — nothing to
  do with connection state), `user_feedback` (glasses **asking the phone** to
  upload diagnostic logs, not something to trigger from here)
* **`do_recovery`** exists in the code — name suggests a factory-reset/recovery
  trigger. Not implemented here on purpose; don't send this one.

#### The real AI assistant's audio path (and why `ask` doesn't need it)

Capture analysis of a live AI-assistant interaction shows three separate
transports involved, only one of which is the JSON channel this client
already speaks:

* **Microphone → phone:** continuous 346-byte binary chunks (likely
  Opus-encoded) sent glasses→phone over the **same classic-BT RFCOMM channel**
  as the JSON relay (channel 13), every ~40-80ms — not a standard Bluetooth
  SCO/HFP voice call (HFP negotiates but no SCO audio was observed).
* **TTS speech → glasses:** plays over standard **A2DP** (phone→glasses),
  confirmed by packet-timing correlation with the AI-interaction window.
* **ASR/TTS caption text:** rides the *same* JSON `code`-tagged protocol as
  everything else in this document (`src`/`dst` = `com.upuphone.ai.assistant`):
  `code:101` = ASR text (`type:0` partial / `type:1` final), `code:5` =
  TTS content (`payload.ttsData.text`), `code:6` = TTS play state
  (`1`=playing, `2`=finished), `code:104` = state change, `code:102` =
  skill/intent result.

Real mic capture and real TTS playback both need actual SCO/A2DP *audio
streaming* over classic-BT, which is not implemented (the classic-BT
transport itself now works — see
[Classic-Bluetooth (RFCOMM)](#classic-bluetooth-rfcomm--how-the-teleprompter-got-working)
— but `hfp.py` only does the AG signaling handshake, no real audio path) —
but the caption **text** doesn't need any of that. `ask_ai()` in
`applayer.py` generates an answer with the Claude API and pushes it through
the plain-BLE JSON relay we already fully control, mimicking the real
assistant's `code:101` → `code:6`(playing) → `code:5` → `code:6`(finished)
message sequence. This is an experiment: whether the lens visibly reacts to
these JSON messages alone (vs. requiring real audio activity to unlock that
UI) hasn't been confirmed against real hardware.

</details>

## Troubleshooting

**`BleakError: Not connected` / "disconnected by peer" right after connecting.**
The glasses drop unknown centrals while they are still bonded/connected to their
phone. Fix: turn **Bluetooth OFF on the phone** that owns the glasses (or
"disconnect"/"forget" them in the MYVU app), then rerun. Only one central at a
time.

`pair()` (link-layer SMP) is **off by default** — on Windows it tended to bounce
the connection, and the glasses authenticate at the app layer instead. If a unit
genuinely needs SMP, construct `MyvuClient(..., do_pair=True)`.

If the link is stable but the glasses were previously bonded to the real app,
they may expect a known `device_id`. Pass `--mac <PHONE_BT_MAC>` to present the
same identifier the official app used.

**"Proven offline" vs. "needs a live device":** `selftest.py` proves packet
framing, protobuf encoding, the `dealDeviceId` transform, SPKI key format, and
ECDH+AES in all three modes match a real capture byte-for-byte. Things that can
only be confirmed on real hardware: whether link-layer pairing is needed on
your OS/adapter, the negotiated ATT MTU (fine if ≥ ~185), and the glasses'
exact timing tolerances.

## 🎯 Final status

**Both transports: fully working, confirmed live.** Pairing (ECDH), the
ability/relay handshake, and app commands all work over BLE — a notification
pushed from this Python client **displayed on the lens** with no phone or
official app involved. The glasses answer every query (`device_info`,
`language`, battery, AI version) and stream their live event telemetry
indefinitely.

**The teleprompter works too, as of the classic-Bluetooth breakthrough
below.** It took a genuinely deep investigation — the short version: `tici`
is gated behind more than "some classic-BT link exists". It needs the
*specific*, per-session app-relay channel the glasses negotiate dynamically
over BLE (not a fixed channel), **plus** a Hands-Free Profile connection,
matching exactly what the official app does. `run_glasses.py` does both
automatically. See
[Classic-Bluetooth (RFCOMM)](#classic-bluetooth-rfcomm--how-the-teleprompter-got-working)
below for the full story, and note the real crash history around classic-BT
*pairing* specifically (not the everyday connect flow) before running
`pair_glasses.py`.

## Classic-Bluetooth (RFCOMM) — how the teleprompter got working

⚠️ **Read the pairing-crash-history part before touching classic-BT pairing
with these glasses.** The rest of this section is a (long) confirmed-working
writeup, kept because the dead ends matter as much as the answer if you're
reverse-engineering something similar.

### The short version

1. **Channel 13 is not the app-relay channel.** It's a separate, fixed
   handshake/liveness channel. The real relay channel is a **classic-BT
   RFCOMM service at a random UUID the glasses generate fresh every
   session**, synced to the phone over BLE via a `LinkProtocol` message
   (`CMD_SPP_SERVER_UUID_SYNC = 70`, see `linkproto.py`) *before* any
   classic-BT connect is attempted. The captured "channel 13" from earlier
   investigation was just whatever channel got assigned to that one
   session's random UUID — coincidence, not a protocol constant.
2. **Python's raw `socket.AF_BLUETOOTH` on Windows can't resolve a UUID to a
   channel** (no SDP-by-UUID, unlike Android's
   `createRfcommSocketToServiceRecord`). The fix is WinRT's
   `Windows.Devices.Bluetooth.Rfcomm` API, which does real SDP resolution —
   see `myvu/rfcomm_winrt.py`.
3. **Even with the right channel, `tici` still gated behind "connect to
   mobile first".** The decompiled official app's `BrEdrMasterManager.
   connectBrEdr()` always connects **Hands-Free Profile (HFP)** alongside
   the relay channel, and the original packet capture confirms the real
   phone establishes HFP (and A2DP) *before* the relay channel opens. HFP is
   a standard, spec'd profile (fixed UUID `0000111e-...`, well-known AT
   commands) — no reverse-engineering needed, just replaying the real
   phone's own captured AG-role responses. See `myvu/hfp.py`. Once HFP's
   handshake completes alongside the relay channel, `tici` opens for real —
   confirmed live, with the glasses replying `glass_tici_started` and
   `open_result_v2`, message types never seen before this fix.

Run `python run_glasses.py <BT-ADDRESS>` to get all three pieces
automatically (BLE session, negotiated relay channel via WinRT SDP, HFP
handshake) before dropping into the same REPL as `run.py`.

### The pairing crash history (read this first)

Packet capture analysis showed the official app also opens a **classic-BT SPP
connection** (plus A2DP audio, plus the Hands-Free Profile RFCOMM channel
mentioned above) alongside BLE, carrying the app-relay protocol wrapped in a
small frame: `eaca9353(magic) + len:4BE + 0002(const) + <same relay/StreamReq
payload as BLE>`. This is implemented in `rfcomm.py`, `rfcomm_winrt.py`,
`rfcomm_client.py`, `hfp.py`, and `run_glasses.py` — but classic-BT *pairing*
specifically (not the everyday connect flow, which is safe and routine once
paired) has a real crash history and **is not something to casually retry**:

**Ruled out as alternatives:** the account/telemetry actions documented in
[Layer 7](#layer-7--driving-features-applayerpy) (`system_account`,
`system_glass_active`) were directly tested as possible triggers for clearing
this message — `send_init_burst()` already replays the *exact* captured
`system_account` message (real accountId, `value:true`) every run, and the
prompt persists regardless. Capture analysis backs this up: the classic-BT ACL
connection completes at **t=43.28s**, a full 11 seconds before the RFCOMM data
channel or the BLE `system_account` message ever appear (~t=54s) — the classic
link is established early and independently, not as a reaction to any BLE
message. The classic-BT/RFCOMM connection was indeed the gate — confirmed
below, along with the second piece (HFP) that wasn't obvious until the
decompiled app and a fresh capture were cross-checked.

**Confirmed on real hardware:** invoking `tici` (the teleprompter) while only
BLE-connected pops up "Please connect to mobile first" on the lens. This is
direct evidence the gate is real and specifically blocks the teleprompter —
not just a cosmetic home-screen state.

It requires classic (BR/EDR) Bluetooth bonding first, and attempting that
bonding **crashed the glasses** (repeated spontaneous reboots) when done via
Windows' native pairing UI.

**Update — fresh HCI snoop of a real phone pairing (2026-07-13):** a full
`btsnoop` capture was taken on the phone covering (1) a first-ever pairing from
a forgotten/unbonded state, and (2) a force-stop-the-app-then-reopen reconnect
~35 minutes later. This replaces guesswork with confirmed facts and corrects
an earlier assumption in this doc:

- **The real phone also uses Numeric Comparison, not Just Works.** Both sides
  declare IO capability `DisplayYesNo` with MITM required (phone:
  `MITM_Required-DedicatedBonding`, glasses: `MITM_Required-GeneralBonding`),
  and the phone receives a genuine `User Confirmation Request` with a 6-digit
  numeric value. **The earlier theory that Windows crashed the glasses by
  picking Numeric Comparison instead of Just Works was wrong** — the real
  phone negotiates the same association model Windows did. Forcing a
  `NoInputNoOutput` BlueZ agent (which forces Just Works) would make a client
  diverge from real hardware, not match it — **that recommendation is
  retracted.**
- **There is no persistent classic-BT bonding.** Both captured sessions —
  including the reconnect, 35 minutes after the first pairing — ran the
  *entire* SSP negotiation from scratch (`Link Key Request` came back empty,
  followed by a full IO-capability exchange and a **new** numeric-comparison
  code each time: `940028` then `404870`). The phone requests
  `DedicatedBonding` (non-persistent), so nothing is cached — every classic-BT
  connection re-pairs. Since no dialog was shown to a human 35 minutes into
  passive use, the real app must be auto-confirming the numeric comparison
  programmatically (e.g. Android's `BluetoothDevice.setPairingConfirmation`)
  rather than surfacing system pairing UI.
- **The `eaca9353`-framed relay channel is confirmed live in this capture**
  (135 occurrences), immediately reachable after SSP + encryption complete, an
  SDP browse, and a separate unrelated HFP RFCOMM channel (`AT+BRSF=`,
  `AT+CIND=?`, `AT+XAPL=...`) — `rfcomm.py`'s framing assumptions check out
  against this fresh capture, not just the original session capture.
- **Channel numbers, precisely confirmed via the raw SABM/UA control frames**
  (not just payload pattern-matching): the phone opens three RFCOMM channels
  on one multiplexer — channel 0 (mux control), **channel 3 (Hands-Free
  Profile AT commands — unrelated, ignore)**, and **channel 13**, whose
  SABM/UA handshake completes 15ms before the first `eaca9353` frame appears.
  Don't assume the first non-control channel you find is the app relay — it's
  channel 3 first, then 13.
- **Timing confirms the classic-BT connection is independent of BLE**, as
  originally suspected: the phone's classic-BT `Remote Name Request` (the
  first step) fires *before* the BLE ability/session handshake even finishes
  — it's a parallel OS/app-level process, not a reaction to anything sent
  over the BLE JSON channel, so there's no BLE message that can trigger it.

The likely real cause of the Windows crash is therefore something other than
the SSP association model — e.g. Windows' own SSP/L2CAP timing, its handling
of `DedicatedBonding`, or its auto-retry behavior against a paired-but-broken
device turning one bad attempt into a reboot loop. That still hasn't been
isolated.

**Update — `pair_glasses.py` (WinRT-based) now works, used successfully many
times without a crash.** The fix was replicating the *real* flow instead of
using Windows' Settings UI: present IO capability `DisplayYesNo` with
MITM-required dedicated bonding via WinRT's `DeviceInformationCustomPairing`,
and programmatically auto-accept the `User Confirmation Request` (matching
the numeric value, no human prompt) — see `myvu/rfcomm_pair.py`. **Still use
`pair_glasses.py`, never Windows' native Settings UI pairing dialog**, which
retains its crash history. Pairing can still be slow/flaky (the SSP
negotiation sometimes needs a couple of retries, and a concurrent BLE session
open at the same time measurably helped reliability in testing — the real
phone's own capture shows its classic-BT connection attempt starting almost
simultaneously with BLE session establishment), but it is no longer expected
to crash the glasses when driven through `pair_glasses.py`.

If the glasses ever get stuck in a reboot loop after a pairing attempt: turn off
Bluetooth on the host machine (or forget/remove the device) immediately to stop
it from auto-retrying, and give the glasses a few minutes untouched to settle.

### The relay channel and HFP — full technical writeup

Once paired, the actual "why doesn't `tici` work" investigation had two
separate wrong turns before the real answer:

**Wrong turn 1: assuming channel 13 was the app-relay channel.** It answers
the ability/AUTH handshake (a fixed, simple exchange), which is why early
testing looked like partial success — the handshake completed and got a real
reply. But every actual app command sent afterward (notifications, `tici`)
got zero response, not even from the *init burst* itself. The real relay
channel turned out to be a **classic-BT RFCOMM service published at a random
UUID the glasses generate fresh each session** and sync to the phone over
BLE via a `LinkProtocol` message — `CMD_SPP_SERVER_UUID_SYNC = 70` (values
71-73 are related SPP negotiation commands, all in `linkproto.py`'s COMMAND
enum, cross-checked against the decompiled app's
`Starry.StarryLinkEncrypt.COMMAND` enum and
`SPPNegotiateProtocolManager.handleServerUUIDSync`). The payload is a 4-byte
**little-endian** int (confirmed empirically: a captured payload of `21 91
00 00` only falls inside `SecureRandom.nextInt(65535)`'s range when read
little-endian — `0x9121` = 37153; big-endian gives `0x21910000`, far out of
range) that gets formatted into a full Bluetooth Base UUID:
`0000{short:04x}-0000-1000-8000-00805f9b34fb` (see
`linkproto.spp_short_uuid_to_str`).

**Getting a live UUID isn't enough on Windows**, because
`socket.AF_BLUETOOTH`/`BTPROTO_RFCOMM` only connects by channel *number* —
there's no SDP-by-UUID resolution like Android's
`createRfcommSocketToServiceRecord(uuid)`. The fix is
`Windows.Devices.Bluetooth.Rfcomm`: `BluetoothDevice.
get_rfcomm_services_for_id_async(RfcommServiceId.from_uuid(...))` does the
real SDP lookup and resolves straight to a connectable host/service name
pair for a `StreamSocket`. See `myvu/rfcomm_winrt.py` — every WinRT API name
in it was verified against the installed `winsdk` package by introspection,
not guessed.

**Wrong turn 2: assuming the relay channel alone was sufficient.** With the
real UUID and WinRT SDP resolution, every relay message finally started
getting ACKed — the *entire* init burst, for the first time in this whole
investigation. But `tici` still popped "Please connect to mobile first".
Cross-checking the decompiled app's `BrEdrMasterManager.connectBrEdr()`
showed it *always* connects Hands-Free Profile (HFP) and A2DP alongside the
relay channel — never just the relay channel alone — and the original packet
capture confirms the ordering: HFP and A2DP both connect *before* the relay
channel opens in the real session. Checking Windows' own paired-device list
confirmed neither profile had been auto-connected (registry: `COD` cached as
`0` for this device — Windows only auto-installs HFP/A2DP drivers when it
learns a device's Class-of-Device bits during an *inquiry scan*, which never
happened here since every connection went directly by MAC address, bypassing
discovery).

Rather than fight Windows' driver auto-detection, `myvu/hfp.py` implements a
minimal HFP **Audio Gateway (AG)** responder — the glasses are the
Hands-Free unit (HF) and send AT commands (`AT+BRSF=767`, `AT+CIND=?`,
`AT+CMER=...`, `AT+XAPL=...`); the phone (AG) answers them. HFP has a fixed,
well-known UUID (`0000111e-0000-1000-8000-00805f9b34fb`, not session-random
like the relay channel) and is a standardized profile, so no
reverse-engineering was needed for the *format* — only the exact reply
bytes, which were pulled directly from the original capture (the real
phone's own AG-role responses, e.g. `AT+BRSF=767` → `+BRSF: 3943` then
`OK`), confirmed byte-identical across two independent capture sessions.
`myvu/hfp.py` connects via the same WinRT SDP-by-UUID mechanism as the relay
channel, then just replays the captured reply for each recognized AT line —
no real call-control or audio streaming, just enough signaling for the
glasses to consider a phone "properly" connected.

With the relay channel and HFP both up, `tici` opens for real: the glasses
reply `glass_tici_started`, `send_content_reply`, and `open_result_v2` with
computed `paragraphIndexes` — none of which had ever appeared in this
investigation until this fix. `run_glasses.py` wires connect → wait for the
UUID sync → relay channel via WinRT → HFP handshake → REPL into one command.

## Project structure

```
myvu_client/
├── run.py                REPL entry point (BLE only -- notify/query/etc, not tici)
├── run_glasses.py         REPL entry point (BLE + classic-BT relay + HFP -- tici works)
├── run_rfcomm.py          low-level classic-BT entry point (fixed channel 13 only --
│                          ability handshake works, app commands don't; superseded
│                          by run_glasses.py for anything beyond debugging channel 13)
├── pair_glasses.py         classic-BT pairing (WinRT-based; read the crash-history
│                          section before using -- Settings UI pairing is unsafe)
├── probe_rfcomm.py         low-level RFCOMM connectivity probe
├── selftest.py            offline validation suite (no hardware needed)
├── captured_init.txt      reference init-message sequence from a real capture
└── myvu/
    ├── uuids.py           BLE service/characteristic UUIDs
    ├── packets.py         packet transport layer (fragmentation, ACKs)
    ├── channel.py         reliable message channel built on packets.py
    ├── crypto.py          ECDH + AES (all 3 modes)
    ├── linkproto.py       LinkProtocol protobuf + pairing/SPP-negotiation message builders
    ├── session.py         version negotiation + two-phase ability auth
    ├── tlv.py             TlvBox codec (used by the relay layer)
    ├── relay.py           sequenced application-message relay
    ├── applayer.py        shared feature API (notify, teleprompter, send_action,
    │                      send_init_burst -- shared by BLE and classic-BT)
    ├── client.py          BLE client -- ties every layer together
    ├── rfcomm.py           classic-BT transport + framing, fixed-channel socket connect
    ├── rfcomm_winrt.py     classic-BT transport connecting by SDP-resolved UUID (the
    │                      one that actually reaches the real app-relay channel)
    ├── rfcomm_pair.py      WinRT-based classic-BT pairing (see pair_glasses.py)
    ├── hfp.py              minimal Hands-Free Profile Audio-Gateway responder
    └── rfcomm_client.py    classic-BT client -- same AppLayerMixin as client.py, just
                           swap which transport (rfcomm.py or rfcomm_winrt.py) it holds
```

## Contributing

Issues and PRs are welcome — especially if you have a different MYVU model, a
different Meizu/Upuphone StarryNet device, or want to add more app actions.
If you can confirm (or disprove) whether `ask <question>` visibly displays on
real hardware, that's a particularly useful report — see
[Layer 7](#layer-7--driving-features-applayerpy) for what's still unverified.
If you reverse-engineer something new, a packet capture + the specific finding
is the most useful kind of contribution.

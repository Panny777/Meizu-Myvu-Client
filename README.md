# 🕶️ myvu-client — an unofficial Python client for Meizu MYVU AR glasses

A from-scratch Python client that talks directly to Meizu MYVU (Star Air,
model `XGA010C`) AR glasses over Bluetooth — **no phone, no official app
required**. It performs the real app-layer pairing handshake, joins the
glasses' session, and can push notifications, drive the teleprompter, run a
full voice AI assistant (wake word → speak → spoken answer), sync the clock,
change system settings, and read live telemetry — all reverse-engineered from a
decompiled APK and a Bluetooth packet capture of the official app.

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
- [The voice AI assistant](#-the-voice-ai-assistant)
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
- 🎙️ **Full voice AI assistant** — press the AI button (or say the wake word
  **"小溪小溪" / "Hey Aicy"**) and speak: your words are transcribed with Groq
  Whisper, answered with the Claude API, shown as a **streaming caption** on the
  AI page, and **spoken back over the glasses' A2DP speaker** in a natural Groq
  voice — a continuous, multi-turn conversation. See
  [The voice AI assistant](#-the-voice-ai-assistant). (`ask <question>` remains
  as a text-only variant.)
- ⏰ **Automatic clock sync** — the glasses' clock is set from this PC on connect
  (and whenever the glasses request it), mirroring the official app's
  `SyncOffSetTime`
- 🔊 **Volume, brightness, WiFi, and standby-widget control**
- ⚙️ **System settings** — language, device name, screen-off timeout, zen/DND,
  air (minimal) mode, wear detection, music touch-panel mode — all matching the
  official app's `ControlUtils` payloads
- 🔍 **Query any device status** (battery, language, zen mode, WiFi list, ...)
- 🛠️ **Send any app command** via `send_action()` — the protocol vocabulary is
  fully documented below, so new features are a small addition, not a new
  reverse-engineering project
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
| `ask <question>` | generate a Claude answer and push it to the lens as text (the text-only variant of the [voice assistant](#-the-voice-ai-assistant)) |
| `synctime` | re-push this PC's clock to the glasses (also runs automatically on connect) |
| `lang <language> <country>` | set the glasses' language, e.g. `lang en US` |
| `name <text>` | rename the glasses |
| `screenoff <seconds>` | display auto-off timeout, e.g. `screenoff 30` |
| `zen [on\|off]` | do-not-disturb (default on) |
| `air [on\|off]` | minimal mode — **closes all apps** and may restrict functions (default on) |
| `wear [on\|off]` | auto on/off when worn (default on) |
| `musictp [on\|off]` | music touch-panel control mode (default on) |
| `help` / `q` | show detailed help for every command / disconnect and exit |

The **AI button** and **wake word** are hardware triggers, not typed commands —
they start the [voice assistant](#-the-voice-ai-assistant).

`ask` needs the `anthropic` package (already in `requirements.txt`) and an API
key — set `ANTHROPIC_API_KEY`, or run `ant auth login`. The **voice assistant**
additionally needs `GROQ_API_KEY` (for speech-to-text and text-to-speech) — put
both keys in a `.env` file in `myvu_client/`.

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
connection so the glasses believe a real phone is attached.

**One-time setup — pair the glasses as an AUDIO device (this is the stable
way, and it matters):**

1. **Settings → Bluetooth & devices → Add device → Bluetooth**, and pair
   **MYVU DC47**. It will appear in *both* "Audio devices" and "Other devices".
2. **Remove the glasses from the "Other devices" section** (leave the
   "Audio devices" entry). This step is essential: the generic "Other devices"
   entry is a bare data pairing that makes the glasses **spontaneously reboot**;
   the "Audio devices" entry is Windows natively holding HFP/A2DP, which is the
   connection shape the glasses' firmware expects — with it, they stay stable.

> Pair via the Windows **Add device** wizard, not any script. Programmatic
> pairing was investigated thoroughly and does **not** work for this device —
> app-level WinRT can only reach the glasses' BLE endpoint, and pairing that
> yields a BLE-only bond (the crashy "Other devices" kind), never the audio
> pairing. See the [classic-BT section](#classic-bluetooth-rfcomm--how-the-teleprompter-got-working)
> for the full evidence. `pair_glasses.py` / `pair_glasses_audio.py` remain in
> the repo as the investigation + diagnostics, but are **not** the setup path.

**Then, every session:**

```bash
python run_glasses.py <BT-ADDRESS> --no-hfp
myvu> tici Welcome to my talk.\nFirst point here.
```

`--no-hfp` because Windows already holds the Hands-Free connection natively
(from the audio pairing) — our in-app HFP responder would only conflict with
it. (If you ever run against glasses *not* paired as an audio device, drop
`--no-hfp` and the in-app HFP handshake in `hfp.py` runs instead.)

Windows only (it uses `winsdk`'s WinRT Bluetooth APIs for SDP-by-UUID RFCOMM
resolution — see the section below for exactly why that's necessary).

## 🎙️ The voice AI assistant

Unlike the earlier text-only `ask`, this drives the glasses' AI page the way the
real phone does — **speak a question, see your words appear, hear the answer
spoken back** — and loops into a continuous conversation. It runs on top of the
same `run_glasses.py` classic-BT session:

```bash
python run_glasses.py <BT-ADDRESS> --no-hfp
```

**How to trigger it (hardware, not a typed command):**
- **Press the AI button** on the glasses, or
- **Say the wake word** — **"小溪小溪"** (Xiǎoxī Xiǎoxī) or its English variant
  **"Hey Aicy"**. These are the only phrases the glasses' on-device keyword
  spotter is trained for; the wake word isn't a free-text setting (see below).

**What happens on each turn:**
1. The glasses send an AI-start message over the relay — `code:3` for the button
   (`CODE_START_VR_REQ`) or `code:7` for the wake word (`CODE_VOICE_WAKEUP_VR_REQ`).
2. We record from the glasses' **Windows HFP microphone** until you stop speaking
   (silence detection), then transcribe with **Groq Whisper**
   (`whisper-large-v3-turbo`).
3. Your words are pushed back as a **streaming caption** — a series of growing
   `code:101` partial ASR results, matching how the real glasses render a
   building caption — then the final.
4. An answer is generated with the **Claude API**, and **spoken over the glasses'
   A2DP speaker** using a natural **Groq (Orpheus) voice** (default `hannah`),
   with Windows SAPI as an offline fallback.
5. The assistant returns to listening for a follow-up. The conversation ends when
   you stay silent, **say a stop phrase** ("stop", "goodbye", "that's all", …),
   or press the AI button again.

**Requirements:**
- The glasses must be paired to Windows as an **audio device** (see the
  teleprompter setup above) — that's what exposes the HFP mic and A2DP speaker as
  normal Windows audio devices. Keep them set as the audio device; do **not** set
  the MYVU as the Windows default mic, or its audio is routed to Windows instead
  of to our recorder.
- `GROQ_API_KEY` (STT + TTS) and `ANTHROPIC_API_KEY` (answers) in `myvu_client/.env`.

**Configurable via `.env`** (defaults shown):

```dotenv
GROQ_STT_MODEL=whisper-large-v3-turbo
GROQ_TTS_MODEL=canopylabs/orpheus-v1-english
GROQ_TTS_VOICE=hannah   # options: autumn diana hannah austin daniel troy
```

**On the wake word / why it isn't customizable:** detection runs as a small
trained model on the glasses' low-power DSP, re-verified on the real phone by a
chipset SoundTrigger engine (Qualcomm/Unisoc) that can't run on Windows — so we
just trust the glasses' first-stage detection and start on `code:7`. Changing to
an arbitrary phrase would require retraining/deploying a new DSP model, which the
firmware doesn't expose; the built-in set is "小溪小溪", "Hey Aicy", and
"Xiaoxi, Xiaoxi". (A truly custom phrase would mean running our own always-on
keyword spotter on the PC mic instead — not implemented.)

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
`client.send_action(json_str, target_pkg=...)`, `client.ask_ai(question)`,
`client.sync_time()`, and the `ControlUtils` settings
`client.set_language(language, country)`, `client.set_device_name(name)`,
`client.set_screen_off_time(seconds)`, `client.set_zen_mode(on)`,
`client.set_air_mode(on)`, `client.set_wear_detection(on)`,
`client.set_music_tp_control(on)`. Voice-assistant helpers:
`client.ai_session_ack()`, `client.ai_send_recognized()` (streaming caption),
`client.ai_send_answer(answer, speak=...)`.

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
* **`ControlUtils` settings** (wired via `client.set_*`): `set_language`,
  `set_device_name`, `set_screen_off_time`, `set_zen_mode`, `set_air_mode`
  (MYVU's minimal mode — **closes all apps**, not airplane mode),
  `set_wear_detection_mode`, `set_music_tp_control_mode`. These nest their
  params under a `value` object (`{"action":"system","data":{"action":<verb>,
  "value":{<key>:<val>}}}`) — unlike `set_volume`/`set_brightness`, which take
  a flat string value. `run.py`/`run_glasses.py` also apply sensible defaults on
  connect: **clock sync** (`SyncOffSetTime`), **wear detection on**, **zen off**.
* **Not user-facing — internal plumbing, don't bother wiring these up**:
  `system_account`/`account_state` (tells the glasses which Flyme account is
  logged in), `system_glass_active`/`req_active_state`/`req_active_info` (an
  internal NPS-survey eligibility check, gated on account login — nothing to
  do with connection state), `user_feedback` (glasses **asking the phone** to
  upload diagnostic logs, not something to trigger from here)
* **`do_recovery`** exists in the code — name suggests a factory-reset/recovery
  trigger. Not implemented here on purpose; don't send this one.

#### The AI assistant protocol (`com.upuphone.ai.assistant`)

A live AI interaction uses three transports, all of which we now drive (see
[The voice AI assistant](#-the-voice-ai-assistant) for the end-to-end flow):

* **Caption / control text** rides the JSON `code`-tagged protocol
  (`src`/`dst` = `com.upuphone.ai.assistant`):
  `code:3`/`code:7` = AI-start (button / wake word), `code:4` = session ack
  (**required** — without it the glasses show "service error"), `code:101` =
  ASR text (`type:0` partial / `type:1` final), `code:104` = VAD state,
  `code:5` = TTS content (`payload.ttsData.text`), `code:6` = TTS play state
  (`1`=playing, `2`=finished), `code:107` = idle/end.
* **Microphone → phone:** the glasses' mic. When the glasses are the Windows
  audio device, Windows decodes the HFP audio to plain PCM and we record it
  there (the path `voice.py` uses). When they're *not* the Windows mic, the
  glasses instead stream compressed **Opus** frames over the relay (`code:109`
  `CODE_RECORD_DATA_TRANS`; the wake-word buffer likewise arrives as Opus via
  `code:402`).
* **TTS speech → glasses:** plays over standard **A2DP** (phone→glasses) — the
  MYVU shows up as a normal Windows output device, which is how `voice.speak()`
  plays the synthesized answer.

`ask_ai()` in `applayer.py` is the text-only shortcut: it generates a Claude
answer and pushes just the caption sequence (`code:4` → `code:101` →
`code:5`/`code:6`) without recording or playing audio. The full voice loop in
`run.py` adds the real mic capture, Groq STT, streaming caption, and spoken
A2DP answer on top of the same messages.

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

**The crash cause, finally isolated: it's the *kind* of pairing, not a timing
bug.** The glasses reboot when paired as a **bare/generic data device** (the
"Other devices" entry — no audio profiles). They are **stable** when paired as
an **audio device** (HFP/A2DP), because that's the phone-shaped connection
their firmware expects. Both are confirmed on hardware: the generic pairing
(whether from Windows' Settings UI, or from `pair_glasses.py`'s WinRT
`DeviceInformationCustomPairing`, or from `pair_glasses_audio.py` pairing the
BLE endpoint) reboots them; the audio pairing (Windows **Settings → Add
device**) is smooth, and the teleprompter works over it. So:

> **Pair as an audio device via Settings → Add device, then remove the
> "Other devices" entry.** That is the setup, full stop.

**Programmatic audio pairing was investigated hard and does NOT work here —
this is a confirmed dead-end, not an untried idea.** The chain of evidence:
- Pairing by raw MAC (`pair_glasses.py`, WinRT `from_bluetooth_address_async`)
  → generic classic pairing → crash.
- Discovering + pairing over BLE (`pair_glasses_audio.py`) → BLE-only bond in
  "Other devices" → still not the audio pairing.
- A `--probe` diagnostic (`pair_glasses_audio.py --probe`, in
  `rfcomm_pair.probe_endpoints`) enumerated every unpaired thing Windows can
  see for the glasses across a fair scan window: **only the BLE endpoint is
  reachable.** No classic association endpoint, no device container appears —
  the glasses don't answer a classic inquiry, and Windows exposes nothing
  else to pair. The Settings **Add device** wizard succeeds because it runs
  with system-level access that bridges BLE discovery to a classic *audio*
  pairing; app-level WinRT only ever sees the BLE endpoint, whose pairing is
  BLE-only.

`pair_glasses.py` / `pair_glasses_audio.py` are kept for that investigation
and for their useful bits (`--probe`, and `--unpair` which programmatically
tears down an *active* bond via `unpair_async` — handy right after a wrong
BLE bond, though it can't purge a stale cached "Other devices" entry).

If the glasses ever get stuck in a reboot loop after a bad (generic) pairing:
turn off Bluetooth on the host machine (or remove the device from Settings)
immediately, and give the glasses a few minutes untouched to settle.

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
UUID sync → relay channel via WinRT → HFP → REPL into one command.

In the recommended setup (glasses paired as an **audio** device),
**Windows already holds HFP/A2DP natively**, so run with `--no-hfp` and the
in-app `hfp.py` responder is skipped — Windows' native Hands-Free connection
clears the "connect to mobile" gate, and `run_glasses.py` only has to open
the BLE session and the relay channel. The in-app `hfp.py` path (default,
without `--no-hfp`) is the fallback for glasses that are *not* paired as an
audio device.

## Project structure

```
myvu_client/
├── run.py                REPL entry point (BLE only -- notify/query/etc, not tici)
├── run_glasses.py         REPL entry point (BLE + classic-BT relay + HFP -- tici works).
│                          Use --no-hfp when the glasses are paired as an audio device.
├── run_rfcomm.py          low-level classic-BT entry point (fixed channel 13 only --
│                          ability handshake works, app commands don't; superseded
│                          by run_glasses.py for anything beyond debugging channel 13)
├── pair_glasses.py         classic-BT pairing INVESTIGATION (WinRT raw-MAC pairing ->
│                          generic bond -> crashes; NOT the setup path -- pair via
│                          Windows Settings > Add device as an audio device instead)
├── pair_glasses_audio.py   audio-pairing INVESTIGATION + diagnostics (--probe / --unpair).
│                          Proved programmatic audio pairing isn't reachable; kept for
│                          the finding and the diagnostics, NOT the setup path
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
    ├── applayer.py        shared feature API (notify, teleprompter, AI assistant,
    │                      time sync, system settings, send_action, send_init_burst
    │                      -- shared by BLE and classic-BT)
    ├── voice.py           voice assistant I/O: record the glasses' Windows HFP mic,
    │                      Groq Whisper STT, Groq (Orpheus)/SAPI TTS out the A2DP speaker
    ├── client.py          BLE client -- ties every layer together
    ├── rfcomm.py           classic-BT transport + framing, fixed-channel socket connect
    ├── rfcomm_winrt.py     classic-BT transport connecting by SDP-resolved UUID (the
    │                      one that actually reaches the real app-relay channel)
    ├── rfcomm_pair.py      WinRT pairing investigation + diagnostics (probe_endpoints,
    │                      unpair, discover_and_pair_as_audio -- see pair_glasses*.py)
    ├── hfp.py              minimal Hands-Free Profile Audio-Gateway responder
    │                      (fallback for glasses not paired as an audio device)
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

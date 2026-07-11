# 🕶️ myvu-client — an unofficial Python client for Meizu MYVU AR glasses

A from-scratch Python client that talks directly to Meizu MYVU (Star Air,
model `XGA010C`) AR glasses over Bluetooth — **no phone, no official app
required**. It performs the real app-layer pairing handshake, joins the
glasses' session, and can push notifications, drive the teleprompter, and
read live telemetry, all reverse-engineered from a decompiled APK and a
Bluetooth packet capture of the official app.

> **Status:** the BLE transport is fully working and confirmed live — a
> notification sent from this script displays on the lens, with zero phone
> involved. See [Final status](#-final-status) for the honest details on
> what works and what's still cosmetic-only.

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
- [Classic-Bluetooth investigation (parked — read before touching)](#classic-bluetooth-rfcomm-investigation--parked)
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
- 📜 **Teleprompter control** — open it and load arbitrary text
- 🛠️ **Send any app command** via `send_action()` — the protocol vocabulary is
  fully documented below, so new features (AI assistant, etc.) are a small
  addition, not a new reverse-engineering project
- 🧪 **Offline self-test suite** — every protocol layer is validated against
  real captured bytes, no hardware required to verify correctness
- 💬 **Interactive REPL** for driving the glasses live from the terminal

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
myvu> raw {"action":"system","data":{"action":"get_device_info"}}
```

| command | effect |
|---|---|
| `notify <text>` | push a notification card to the lens |
| `notify <title> \| <body>` | notification with a separate title |
| `tici <text>` | open the teleprompter and load this text |
| `hl <index>` | scroll/highlight the teleprompter to paragraph `<index>` |
| `raw <json>` | send any raw app-action JSON |
| `help` / `q` | show help / disconnect and exit |

If the glasses were previously paired with a real phone, pass that phone's
Bluetooth MAC so the glasses recognize the identity:

```bash
python run.py <BLE-ADDRESS> --mac 7C:A3:75:D0:94:F1
```

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
`client.send_action(json_str, target_pkg=...)`.

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

**BLE client: fully working, confirmed live.** Pairing (ECDH), the ability/relay
handshake, and app commands all work — a notification pushed from this Python
client **displayed on the lens** with no phone or official app involved. The
glasses answer every query (`device_info`, `language`, battery, AI version) and
stream their live event telemetry indefinitely.

**Known cosmetic limitation:** the glasses' own home-screen text can stay on
"Connecting…"/"Open MYVU AR App" because that specific UI state is tied to a
**second, classic-Bluetooth (SPP/RFCOMM) connection** that runs in parallel with
BLE on the real phone (see below). This does not block any feature — it's
cosmetic on the glasses' side.

## Classic-Bluetooth (RFCOMM) investigation — PARKED

⚠️ **Read this before touching classic-BT pairing with these glasses.**

Packet capture analysis showed the official app also opens a **classic-BT SPP
connection** (RFCOMM channel 13, plus A2DP audio) alongside BLE, carrying the
identical application protocol wrapped in a small frame:
`eaca9353(magic) + len:4BE + 0002(const) + <same relay/StreamReq payload as BLE>`.
This is fully implemented and offline-validated against captured bytes in
`rfcomm.py`, `rfcomm_client.py`, `run_rfcomm.py`, and `probe_rfcomm.py` — but is
**not something to casually try**:

It requires classic (BR/EDR) Bluetooth bonding first, and attempting that
bonding **crashed the glasses** (repeated spontaneous reboots) when done via
Windows' native pairing UI — likely because Windows chose the "Numeric
Comparison" SSP method, a more complex negotiation path that cheap embedded
Bluetooth stacks often handle worse than "Just Works". Windows also
aggressively auto-retries connecting to paired-but-broken devices, which turned
one failed pairing attempt into a repeating crash loop.

**If you ever want to pick this up:** the untried, likely-safer path is pairing
via BlueZ (Linux — a live-boot USB is easiest, since it uses the PC's built-in
Bluetooth adapter with no driver/kernel setup) with the agent forced to
`NoInputNoOutput` (`bluetoothctl` → `agent NoInputNoOutput` → `default-agent` →
`pair <MAC>`), which forces Just Works instead of Numeric Comparison. This is
**not guaranteed** to avoid a crash — treat it as an experiment, not a fix.
**Do not attempt classic-BT pairing with these glasses from Windows.**

If the glasses ever get stuck in a reboot loop after a pairing attempt: turn off
Bluetooth on the host machine (or forget/remove the device) immediately to stop
it from auto-retrying, and give the glasses a few minutes untouched to settle.

## Project structure

```
myvu_client/
├── run.py                REPL entry point (BLE)
├── run_rfcomm.py          entry point (classic-BT — parked, see warning above)
├── probe_rfcomm.py         low-level RFCOMM connectivity probe
├── selftest.py            offline validation suite (no hardware needed)
├── captured_init.txt      reference init-message sequence from a real capture
└── myvu/
    ├── uuids.py           BLE service/characteristic UUIDs
    ├── packets.py         packet transport layer (fragmentation, ACKs)
    ├── channel.py         reliable message channel built on packets.py
    ├── crypto.py          ECDH + AES (all 3 modes)
    ├── linkproto.py       LinkProtocol protobuf + pairing message builders
    ├── session.py         version negotiation + two-phase ability auth
    ├── tlv.py             TlvBox codec (used by the relay layer)
    ├── relay.py           sequenced application-message relay
    ├── applayer.py        shared feature API (notify, teleprompter, send_action)
    ├── client.py          BLE client — ties every layer together
    ├── rfcomm.py           classic-BT transport + framing (parked)
    └── rfcomm_client.py    classic-BT client (parked)
```

## Contributing

Issues and PRs are welcome — especially if you have a different MYVU model, a
different Meizu/Upuphone StarryNet device, or want to add more app actions
(the AI assistant flow is documented in the protocol but not yet wired up).
If you reverse-engineer something new, a packet capture + the specific finding
is the most useful kind of contribution.

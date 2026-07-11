# MYVU / StarryNet BLE client

A from-scratch Python client that connects to Meizu MYVU (Star Air, model
`XGA010C`) AR glasses, performs the app-layer **ECDH pairing handshake**, and
streams application messages — reverse-engineered from the decompiled app
(`com.upuphone.starrynet.*`) and a `btsnoop_hci` capture of the official app.

This answers the original question: **the glasses reject nRF Connect not because
of device-binding, but because nRF Connect never performs the StarryNet
handshake.** There is no manufacturer certificate or signature check in the
handshake — any client that speaks the protocol correctly is accepted.

## Why this should work

`selftest.py` validates every offline layer against **real captured bytes**,
including re-encoding the captured `WRITE_SWITCH_KEY` frame byte-for-byte:

```
python selftest.py      # -> ALL 22 CHECKS PASSED
```

## Install & run

```
pip install -r requirements.txt
python run.py                       # scan, list StarryNet devices
python run.py <BLE-ADDRESS>         # connect + pair + listen
```

## Protocol map (all reverse-engineered)

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
(verified: `7ca375d094f1 → 0e6b2f8a5c83`). Commands used:
`WRITE_SWITCH_KEY(11)`, `WRITE_SWITCH_INFO(13)`.

### Layer 4 — crypto (`crypto.py`)
EC P-256 (secp256r1) ECDH; raw shared secret (32 B) used directly as the AES key
(no KDF). Public keys exchanged as X.509 SPKI DER (91 B). IV = 16 ASCII chars of
a UUID4. Symmetric mode is **negotiated** (`"e"` in the version JSON):
`1=AES/CBC/PKCS5`, `2=AES/CTR`, else `AES/GCM`. The captured session negotiated
`e=1` → **AES/CBC**.

### Handshake sequence (`client.py::pair`)
1. **version negotiation** — FAST_CTR pkg 17, JSON `{"i","v","e","m","b","c"}`;
   reply sets the AES mode.
2. **WRITE_SWITCH_KEY** — `WriteSwitchKey{ key=our SPKI pubkey, info=our MAC }`.
3. **← WRITE_SWITCH_KEY** — `key = glasses pubkey ‖ 16-B IV`,
   `info = AES(glasses DeviceInfo)`. We derive the shared secret and decrypt it
   (proof the bond is valid).
4. **WRITE_SWITCH_INFO** — our `AES(WriteSwitchInfo{ AES(our DeviceInfo) })`
   (double-encrypted, per `generateDeviceInfoSwitchData`). Bond established.

## What's proven vs. what needs the hardware

**Proven offline (selftest):** packet framing, protobuf, `dealDeviceId`, SPKI key
format, ECDH+AES in all three modes, and exact reproduction of the captured
`WRITE_SWITCH_KEY` frame.

**Needs a live device to confirm (first run may need iteration):**
* Whether Windows/WinRT link-layer pairing (`BleakClient.pair()`) is required
  first — the code attempts it and ignores failure.
* The negotiated ATT MTU on Windows must be ≥ ~185 so each handshake message is a
  single frame (WinRT usually gives 247 → DMTU 242, which is fine). If a device
  gives a small MTU, the pairing messages need the multi-frame path.
* Exact timing/flow-control tolerances of the glasses.

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
same identifier the official app used (e.g. the phone MAC from your capture).

## Layer 5 — RunAsOne relay / SuperMessage (`tlv.py`, `relay.py`)

After the ECDH bond and the ability handshake, the glasses still show "Open MYVU
AR App" until they receive properly **sequenced** app messages on the relay
layer. Diagnosis (see git history / decompiled `com.upuphone.runasone`):

* **Not account/server-gated.** No server/cloud/token calls exist in the connect
  path; the account/active-state messages are analytics only. The whole
  handshake is local.
* The app messages ride a reliable, sequenced channel (`ChannelImpl` +
  `ChannelMessage` + `TlvBox`). Wire format of one frame:

  ```
  0x01  TlvBox{ 112 category, 113 payload=TlvBox{
            100 msgType(3=data,4=ack), 101 msgId(int32 SEQUENCE),
            103 needCallback, 109 appUniteCode, 105 msgBody(app StMessage) } }
  ```
* **Why the naive replay failed:** `ChannelImpl.input` tracks `lastReceiveRequestId`
  (0 on a fresh connect) and treats a message whose `msgId` is far above it as a
  huge out-of-order gap — it buffers instead of delivering. Replaying the
  capture's stale `msgId` (0x44b+) meant the glasses ACKed at the transport layer
  but never delivered to the app. The fix is a **fresh sequence from 1**.

`relay.RelaySequencer` owns the outgoing counter, builds data/ACK frames, and the
client auto-ACKs the glasses' inbound data messages. `send_init_burst()` rebuilds
the captured init messages through this layer with msgIds 1..N.

## Layer 6 — two-phase ability auth (StreamReq)

The `0x02`-class messages are `StreamReq` protobufs (runasone_api.proto), where
field 1 is `StreamType`. The ability handshake is **two-phase** and BOTH are
required for the glasses to engage:
1. `AUTH` (type 0) — initial ability announcement (`build_ability_message`)
2. `AUTH_SUCCESS` (type 12) — confirm sent after the glasses' reply
   (`build_auth_success_message`). **Without this the glasses ACK data but never
   engage the app layer.**

## Layer 7 — driving features (`applayer.py`)

Incoming app messages are parsed automatically (`_on_app_message`, JSON body in
field 4 of the StMessage envelope). To **send** commands, the StMessage envelope
is `{2:srcPkg, 3:dstPkg, 4:json, 6:msgId}` — built by `send_action()`.

Interactive REPL (`run.py`, starts automatically after connect):

```
myvu> notify <text>              push a notification card to the lens
myvu> notify <title> | <body>    notification with a separate title
myvu> tici <text>                open the teleprompter with this text
myvu> hl <index>                 scroll/highlight teleprompter paragraph
myvu> raw <json>                 send a raw app-action JSON
```

Programmatic: `client.push_notification(title, content)`,
`client.open_teleprompter(text)`, `client.teleprompter_highlight(index)`,
`client.send_action(json_str, target_pkg=...)`.

## FINAL STATUS

**BLE client: fully working, confirmed live.** Pairing (ECDH), the ability/relay
handshake, and app commands all work — a notification pushed from this Python
client **displayed on the lens** with no phone or official app involved. The
glasses answer every query (`device_info`, `language`, battery, AI version) and
stream their live event telemetry indefinitely.

**Known limitation:** the glasses' own home-screen text stays on
"Connecting…"/"Open MYVU AR App" because that specific UI state is tied to a
**second, classic-Bluetooth (SPP/RFCOMM) connection** that runs in parallel with
BLE on the real phone (see below) — not something BLE alone can satisfy. This
does not block any feature; it's cosmetic on the glasses' side.

## Classic-Bluetooth (RFCOMM) investigation — PARKED, DO NOT PURSUE CASUALLY

`btsnoop` analysis showed the official app also opens a **classic-BT SPP
connection** (RFCOMM channel 13, plus A2DP audio) alongside BLE, carrying the
identical StarryNet protocol wrapped in a small frame:
`eaca9353(magic) + len:4BE + 0002(const) + <same relay/StreamReq payload as BLE>`.
This is fully implemented and offline-validated against captured bytes:
`rfcomm.py` (transport + framing), `rfcomm_client.py`, `run_rfcomm.py`,
`probe_rfcomm.py`.

**It requires BR/EDR (classic) bonding first, and this bonding attempt crashed
the glasses** (repeated spontaneous reboots) when done via Windows' native
pairing UI — likely because Windows chose the "Numeric Comparison" SSP method,
which is a more complex negotiation path that cheap embedded BT stacks often
handle worse than "Just Works". Windows also aggressively auto-retries
connecting to paired-but-broken devices, which turned one failed pairing into a
repeating crash loop.

**If you ever revisit this:** the safer next experiment (not yet tried) is
pairing via BlueZ (Linux — a live-boot USB is easiest, no driver/kernel work
needed since it uses the PC's built-in adapter directly) with the agent forced
to `NoInputNoOutput` (`bluetoothctl` → `agent NoInputNoOutput` → `default-agent`
→ `pair <MAC>`), which forces Just Works instead of Numeric Comparison. This is
not guaranteed to avoid a crash. **Do not attempt classic-BT pairing from
Windows again** — it has already crashed the glasses more than once.

If the glasses ever get stuck in a reboot loop after a pairing attempt: turn off
Bluetooth on the host machine (or forget/remove the device) immediately to stop
it from auto-retrying, and give the glasses a few minutes untouched to settle.

"""MYVU glasses BLE client: connect, negotiate, ECDH-pair, then listen.

Orchestrates the layers (packets -> channel -> crypto -> linkproto) into the
exact sequence performed by the app's clientCreateBond() path:

  1. version negotiation   (internal char, FAST_CTR pkgType 17, JSON {"i","v","e","m","b","c"})
  2. WRITE_SWITCH_KEY      (internal char, SinglePacket pkgType 16, our ECDH pubkey)
  3. <- WRITE_SWITCH_KEY   (glasses pubkey||iv + AES(DeviceInfo))
  4. WRITE_SWITCH_INFO     (our AES(AES(DeviceInfo)))  -> bond established
  5. subscribe to external char and stream application JSON.

Requires: bleak, cryptography  (see requirements.txt)
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
from typing import Dict, List, Optional, Tuple

from bleak import BleakClient, BleakScanner

from . import channel as chan
from . import crypto, linkproto, packets, relay, session, tlv, uuids
from .applayer import AppLayerMixin

log = logging.getLogger("myvu")

# values taken from the captured phone handshake
CONNECT_VERSION = 3
BLE_VERSION = 2
CATEGORY_ID = "9999"
OWN_ENCRYPT_SUPPORT = 5   # bitmask advertised in negotiation ("e")


class MyvuClient(AppLayerMixin):
    def __init__(self, address: str, own_mac: str = "aa:bb:cc:dd:ee:ff",
                 device_name: str = "MyvuPyClient", do_pair: bool = False) -> None:
        self.address = address
        self.own_mac = own_mac
        self.own_id = linkproto.mac_str_to_bytes(own_mac)   # 6-byte identifier
        self.device_name = device_name
        self.do_pair = do_pair
        self._disconnect_reason: Optional[str] = None

        self.ble: Optional[BleakClient] = None
        self.internal_uuid: Optional[str] = None
        self.external_uuid: Optional[str] = None
        self.channels: Dict[str, chan.MessageChannel] = {}

        # crypto session state
        self.keypair: Optional[crypto.KeyPair] = None
        self.secret: Optional[bytes] = None
        self.iv: Optional[bytes] = None
        self.encrypt_mode: int = crypto.SYMMETRIC_V3_GCM
        self.peer_info: dict = {}
        self.seq = relay.RelaySequencer()   # RunAsOne relay msgId sequencing

    # ----------------------------------------------------------- discovery
    @staticmethod
    async def scan(timeout: float = 8.0):
        """Return devices advertising the StarryNet service UUID."""
        found = await BleakScanner.discover(timeout=timeout)
        # found = await BleakScanner.discover(timeout=timeout, service_uuids=[uuids.SERVICE_UUID])
        return found

    # ------------------------------------------------------------- connect
    def _safe_mtu(self) -> int:
        """WinRT exposes mtu_size as a property that *raises* until the GATT
        session is ready, so getattr()'s default is not enough."""
        try:
            return int(self.ble.mtu_size)
        except Exception:  # noqa: BLE001
            return 23  # ATT default

    def _on_disconnect(self, _client) -> None:
        self._disconnect_reason = "device closed the BLE link"
        log.warning("!! disconnected by peer")

    async def connect(self) -> None:
        self.ble = BleakClient(self.address, disconnected_callback=self._on_disconnect)
        await self.ble.connect()
        log.info("connected to %s (is_connected=%s)", self.address, self.ble.is_connected)

        # Catch an immediate peer-initiated drop (e.g. glasses rejecting an
        # unknown central because their phone is still connected).
        await asyncio.sleep(1.0)
        if not self.ble.is_connected:
            raise RuntimeError(
                "glasses dropped the link ~1s after connecting. They most likely "
                "only accept their currently-bonded phone. Turn Bluetooth OFF on "
                "that phone (or 'disconnect' the glasses in the MYVU app) and retry.")

        # Link-layer SMP pairing is OFF by default: on several stacks it bounces
        # the connection and the glasses don't require it (they do their own
        # app-layer ECDH). Enable with do_pair=True only if a device demands it.
        if self.do_pair:
            try:
                await self.ble.pair()
                log.info("link-layer pair() ok")
            except Exception as e:  # noqa: BLE001
                log.warning("pair() failed: %s", e)

        if not self.ble.is_connected:
            raise RuntimeError(
                "device dropped the link right after connecting. Likely causes: "
                "(1) it only accepts its already-bonded phone — turn Bluetooth "
                "OFF on the phone and retry; (2) it needs classic-BT bonding "
                "first. See README troubleshooting.")

        self._select_channels()

        async def write(char: str, data: bytes) -> None:
            await self.ble.write_gatt_char(char, data, response=False)

        # Create channels with a provisional DMTU; subscribing exercises the
        # GATT session so the real MTU becomes readable afterwards.
        self.channels[self.internal_uuid] = chan.MessageChannel(
            self.internal_uuid, write, 18)
        self.channels[self.external_uuid] = chan.MessageChannel(
            self.external_uuid, write, 18)

        await self._subscribe(self.internal_uuid)
        await self._subscribe(self.external_uuid)

        dmtu = max(18, self._safe_mtu() - 5)
        for c in self.channels.values():
            c.dmtu = dmtu
        log.info("channel internal=%s external=%s MTU=%d DMTU=%d",
                 self.internal_uuid, self.external_uuid, self._safe_mtu(), dmtu)

    def _select_channels(self) -> None:
        services = self.ble.services
        have = {c.uuid.lower() for s in services for c in s.characteristics}
        for internal, external in uuids.CHANNEL_PAIRS:
            if internal.lower() in have and external.lower() in have:
                self.internal_uuid, self.external_uuid = internal, external
                return
        raise RuntimeError(
            "no known StarryNet channel pair found on device; characteristics="
            + ", ".join(sorted(have)))

    async def _subscribe(self, char_uuid: str) -> None:
        if not self.ble.is_connected:
            raise RuntimeError(
                f"link dropped before subscribing ({self._disconnect_reason}). "
                "Turn off Bluetooth on the phone that owns these glasses and retry.")
        def handler(_sender, data: bytearray, cu=char_uuid):
            asyncio.create_task(self.channels[cu].feed(bytes(data)))
        await self.ble.start_notify(char_uuid, handler)

    # ---------------------------------------------------------- handshake
    async def pair(self) -> dict:
        """Run the full negotiation + ECDH bond. Returns the glasses DeviceInfo."""
        await self._negotiate_version()
        await self._exchange_keys()
        await self._send_device_info()
        log.info("BOND ESTABLISHED with %s", self.peer_info)
        return self.peer_info

    async def _negotiate_version(self) -> None:
        ic = self.channels[self.internal_uuid]
        own = {
            "i": self.own_id.hex(),
            "v": CONNECT_VERSION,
            "e": OWN_ENCRYPT_SUPPORT,
            "m": 512,
            "b": BLE_VERSION,
            "c": CATEGORY_ID,
        }
        payload = json.dumps(own, separators=(",", ":")).encode()
        log.info("-> version %s", own)
        await ic.send_fast(payload, packets.PKG_STARRY_DATA_INIT)

        _pkg, data = await ic.recv(timeout=8.0)
        peer = json.loads(data.decode("utf-8", "replace"))
        log.info("<- version %s", peer)
        self.encrypt_mode = int(peer.get("e", crypto.SYMMETRIC_V3_GCM))
        self.peer_info["negotiation"] = peer

    async def _exchange_keys(self) -> None:
        ic = self.channels[self.internal_uuid]
        self.keypair = crypto.generate_ec_keypair()

        # WriteSwitchKey{ key = our SPKI pubkey, info = our MAC bytes }
        wsk = linkproto.write_switch_key(
            self.keypair.public_spki_der, self.own_id)
        msg = linkproto.link_protocol(
            self.own_id, linkproto.CMD_WRITE_SWITCH_KEY, wsk)
        log.info("-> WRITE_SWITCH_KEY (%d B)", len(msg))
        status = await ic.send_single_acked(msg, packets.PKG_STARRY_DATA)
        if status != packets.ACK_SUCCESS:
            raise RuntimeError(f"key write not acked (status={status})")

        # glasses reply: LinkProtocol{cmd=WRITE_SWITCH_KEY, data=WriteSwitchKey}
        _pkg, raw = await ic.recv(timeout=8.0)
        reply = linkproto.parse_link_protocol(raw)
        if reply.cmd != linkproto.CMD_WRITE_SWITCH_KEY:
            raise RuntimeError(f"unexpected reply cmd={reply.cmd}")
        peer_key_field, enc_info = linkproto.parse_write_switch_key(reply.data)

        # key field = peer SPKI pubkey || 16-byte IV
        peer_pub = peer_key_field[:-16]
        self.iv = peer_key_field[-16:]
        self.secret = crypto.ecdh_shared_secret(peer_pub, self.keypair)
        log.info("derived shared secret (%d B), iv=%s", len(self.secret), self.iv)

        # decrypt the glasses' DeviceInfo to prove the handshake worked
        info_bytes = crypto.decrypt(enc_info, self.secret, self.iv, self.encrypt_mode)
        self.peer_info["device"] = linkproto.parse_device_info(info_bytes)
        log.info("<- glasses DeviceInfo %s", self.peer_info["device"])

    async def _send_device_info(self) -> None:
        ic = self.channels[self.internal_uuid]
        info = linkproto.device_info(
            bt_mac=self.own_mac.upper(), company_id="", category_id=CATEGORY_ID,
            model_id="", name=self.device_name, battery=100, bt_status=0)
        # double encryption, per generateDeviceInfoSwitchData()
        inner = crypto.encrypt(info, self.secret, self.iv, self.encrypt_mode)
        wsi = linkproto.write_switch_info(inner)
        outer = crypto.encrypt(wsi, self.secret, self.iv, self.encrypt_mode)
        msg = linkproto.link_protocol(
            self.own_id, linkproto.CMD_WRITE_SWITCH_INFO, outer)
        log.info("-> WRITE_SWITCH_INFO (%d B)", len(msg))
        status = await ic.send_single_acked(msg, packets.PKG_STARRY_DATA)
        if status != packets.ACK_SUCCESS:
            raise RuntimeError(f"info write not acked (status={status})")

    # -------------------------------------------------- app session (RunAsOne)
    async def establish_session(self) -> None:
        """Send the RunAsOne ability/session handshake on the (plaintext)
        external channel. This is what flips the glasses out of the
        'Open MYVU AR App' state into 'connected'."""
        ec = self.channels[self.external_uuid]
        sess = str(int.from_bytes(self.own_id[-2:], "big"))  # short numeric id
        msg = session.build_ability_message(
            device_id_hex=self.own_id.hex(),
            device_name=self.device_name,
            session=sess)
        log.info("-> ability/session handshake (%d B, session=%s)", len(msg), sess)
        await ec.send(msg, packets.PKG_COMMON_DATA, ack=False)

        try:
            _pkg, reply = await ec.recv(timeout=6.0)
        except asyncio.TimeoutError:
            log.warning("no ability reply yet; glasses may still accept it. "
                        "Watch for the 'connected' state on the lens.")
            return
        info = session.parse_ability_reply(reply)
        log.info("<- ability reply from %s: %s", info.get("deviceId"),
                 info.get("authBean"))
        self.peer_info["session"] = info

        # Phase 2: AUTH_SUCCESS confirm (StreamReq type=12). Without this the
        # glasses ack our data but never engage the app layer (capture f509).
        sess = str(int.from_bytes(self.own_id[-2:], "big"))
        confirm = session.build_auth_success_message(
            device_id_hex=self.own_id.hex(), device_name=self.device_name,
            session=sess)
        log.info("-> AUTH_SUCCESS confirm (%d B)", len(confirm))
        await ec.send(confirm, packets.PKG_COMMON_DATA, ack=False)

    @staticmethod
    def _load_init_script() -> List[Tuple[str, str, bytes]]:
        path = os.path.join(os.path.dirname(os.path.dirname(__file__)),
                            "captured_init.txt")
        out = []
        with open(path) as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                frame, kind, hexdata = line.split("\t")
                out.append((frame, kind, bytes.fromhex(hexdata)))
        return out

    async def _transport_send(self, frame: bytes) -> None:
        await self.channels[self.external_uuid].send(frame, packets.PKG_COMMON_DATA)

    async def send_init_burst(self, delay: float = 0.2) -> None:
        """Rebuild the captured init messages through the relay layer with fresh
        SEQUENTIAL msgIds (1,2,3...). This is the fix: the glasses discard the
        capture's stale high msgIds as out-of-order, but accept a clean sequence.
        Data messages (msgType=3) are resent; captured ACKs are skipped (we ACK
        the glasses' live messages dynamically instead)."""
        script = self._load_init_script()
        sent = 0
        for frame, _kind, content in script:
            if not self.ble.is_connected:
                log.error("LINK DROPPED before f%s", frame)
                return
            m = relay.parse_frame(content)
            if m is None or m.msg_type != tlv.MSG_SEND:
                continue  # skip non-data (e.g. the one captured ACK)
            mid = await self.send_relay_data(
                m.msg_body, m.need_callback, m.category, m.app_unite_code)
            body = m.msg_body
            j = body.find(b"{")
            label = body[j:j + 46].decode("utf-8", "replace") if j >= 0 else ""
            log.info("   -> msgId=%d (f%s, %dB) %s", mid, frame, len(m.msg_body), label)
            sent += 1
            await asyncio.sleep(delay)
        log.info("sent %d relay messages (link %s); watch the lens.", sent,
                 "up" if self.ble.is_connected else "DOWN")

    # ------------------------------------------------------------- listen
    def start_drains(self) -> None:
        """Launch background tasks that print inbound messages on both channels.
        Safe to call before the replay so responses/disconnects show live."""
        async def drain_external():
            ec = self.channels[self.external_uuid]
            while True:
                try:
                    _pkg, payload = await ec.recv()
                except asyncio.CancelledError:
                    return
                try:
                    await self._on_relay_frame(payload)
                except Exception as e:  # noqa: BLE001
                    log.debug("relay parse skipped: %s", e)

        async def drain_internal():
            ic = self.channels[self.internal_uuid]
            while True:
                try:
                    _pkg, payload = await ic.recv()
                except asyncio.CancelledError:
                    return
                log.info("DEV/internal <- (%d B)", len(payload))

        self._drain_tasks = [
            asyncio.create_task(drain_external()),
            asyncio.create_task(drain_internal()),
        ]

    async def listen_external(self) -> None:
        """Keep the session alive and print a heartbeat so you can tell
        'idle but connected' from 'disconnected'."""
        if not getattr(self, "_drain_tasks", None):
            self.start_drains()
        log.info("listening. Interact with the glasses to generate traffic. "
                 "Ctrl-C to stop.")
        n = 0
        while True:
            await asyncio.sleep(5)
            n += 1
            if not self.ble.is_connected:
                log.warning("link is DOWN (%s)", self._disconnect_reason)
                return
            log.info("[%ds] still connected, idle...", n * 5)

    @property
    def is_connected(self) -> bool:
        return bool(self.ble and self.ble.is_connected)

    async def close(self) -> None:
        if self.ble and self.ble.is_connected:
            await self.ble.disconnect()

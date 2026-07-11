"""MYVU glasses classic-Bluetooth (RFCOMM/SPP) client.

Unlike the BLE client, this transport carries NO app-layer crypto handshake:
BR/EDR's own SSP-derived link-layer encryption covers security, so we go
straight into the ability/AUTH handshake (verified against the capture: the
very first RFCOMM channel-13 frame from the phone *is* the ability message).

Prerequisite: the glasses must already be BR/EDR-bonded to this PC (Windows
Settings > Bluetooth > pair the glasses) -- classic RFCOMM sockets refuse to
connect otherwise. IMPORTANT: don't have a BLE session open to the glasses at
the same time as attempting the classic-BT pairing dialog -- concurrent
connection attempts have been observed to reset the glasses' BT stack.
"""
from __future__ import annotations

import asyncio
import logging

from . import linkproto, relay, rfcomm, session
from .applayer import AppLayerMixin

log = logging.getLogger("myvu")


class MyvuRfcommClient(AppLayerMixin):
    def __init__(self, address: str, own_mac: str = "aa:bb:cc:dd:ee:ff",
                 device_name: str = "MyvuPyClient",
                 channel: int = rfcomm.DEFAULT_CHANNEL) -> None:
        self.address = address
        self.own_mac = own_mac
        self.own_id = linkproto.mac_str_to_bytes(own_mac)
        self.device_name = device_name
        self.transport = rfcomm.RfcommTransport(address, channel)
        self.seq = relay.RelaySequencer()
        self.peer_info: dict = {}
        self._drain_task = None

    async def connect(self) -> None:
        await self.transport.connect()
        log.info("RFCOMM connected to %s channel %d",
                 self.address, self.transport.channel)

    async def establish_session(self) -> dict:
        """Send the ability/AUTH handshake + AUTH_SUCCESS confirm. No ECDH
        pairing step here -- BR/EDR bonding already secures the link."""
        sess = str(int.from_bytes(self.own_id[-2:], "big"))
        own = session.build_ability_message(
            device_id_hex=self.own_id.hex(), device_name=self.device_name,
            session=sess)
        log.debug("-> ability/session handshake (%d B, session=%s)", len(own), sess)
        await self.transport.send(own)

        try:
            reply = await self.transport.recv(timeout=8.0)
        except asyncio.TimeoutError:
            log.warning("no ability reply from glasses over RFCOMM")
            return {}
        info = session.parse_ability_reply(reply)
        log.debug("<- ability reply from %s: %s", info.get("deviceId"),
                  info.get("authBean"))
        self.peer_info["session"] = info

        confirm = session.build_auth_success_message(
            device_id_hex=self.own_id.hex(), device_name=self.device_name,
            session=sess)
        log.debug("-> AUTH_SUCCESS confirm (%d B)", len(confirm))
        await self.transport.send(confirm)
        log.info("Session established.")
        return info

    async def _transport_send(self, frame: bytes) -> None:
        await self.transport.send(frame)

    def start_drains(self) -> None:
        async def drain():
            while True:
                try:
                    payload = await self.transport.recv()
                except asyncio.CancelledError:
                    return
                try:
                    await self._on_relay_frame(payload)
                except Exception as e:  # noqa: BLE001
                    log.debug("relay parse skipped: %s", e)

        self._drain_task = asyncio.create_task(drain())

    async def listen(self) -> None:
        if not self._drain_task:
            self.start_drains()
        log.info("listening over RFCOMM. Ctrl-C to stop.")
        n = 0
        while True:
            await asyncio.sleep(5)
            n += 1
            if not self.transport.connected:
                log.warning("RFCOMM link is DOWN")
                return
            log.debug("[%ds] still connected, idle...", n * 5)

    @property
    def is_connected(self) -> bool:
        return self.transport.connected

    async def close(self) -> None:
        self.transport.close()

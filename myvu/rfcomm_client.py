"""MYVU glasses classic-Bluetooth (RFCOMM/SPP) client.

Unlike the BLE client, this transport carries NO app-layer crypto handshake:
BR/EDR's own SSP-derived link-layer encryption covers security, so we go
straight into the ability/AUTH handshake (verified against the capture: the
very first RFCOMM channel-13 frame from the phone *is* the ability message).

Prerequisite: the glasses must already be BR/EDR-bonded to this PC (use
pair_glasses.py -- NOT Windows' Settings UI, which has a real crash history,
see README.md's "Classic-Bluetooth (RFCOMM)" section).

For the real app-relay channel (not just channel 13's ability handshake),
you need a live BLE session at the same time -- the glasses generate the
relay channel's UUID randomly per session and only sync it over BLE (see
linkproto.CMD_SPP_SERVER_UUID_SYNC), so classic-BT here is meant to run
*alongside* an active BLE session, not instead of one. See run_glasses.py,
which does both together plus the HFP handshake `tici` also needs.
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
                 channel: int = rfcomm.DEFAULT_CHANNEL,
                 service_uuid: str | None = None) -> None:
        """If `service_uuid` is given, connect via WinRT SDP-by-UUID
        resolution (myvu.rfcomm_winrt) -- the correct way to reach the real,
        per-session app-relay channel (see linkproto.CMD_SPP_SERVER_UUID_SYNC).
        Otherwise falls back to a raw channel-number socket (myvu.rfcomm),
        which only ever reaches the fixed ability/handshake channel."""
        self.address = address
        self.own_mac = own_mac
        self.own_id = linkproto.mac_str_to_bytes(own_mac)
        self.device_name = device_name
        if service_uuid:
            from . import rfcomm_winrt
            self.transport = rfcomm_winrt.WinRtRfcommTransport(address, service_uuid)
        else:
            self.transport = rfcomm.RfcommTransport(address, channel)
        self.seq = relay.RelaySequencer()
        self.peer_info: dict = {}
        self._drain_task = None

    async def connect(self) -> None:
        await self.transport.connect()
        log.info("RFCOMM connected to %s channel %s",
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
        result = self.transport.close()
        if asyncio.iscoroutine(result):
            await result

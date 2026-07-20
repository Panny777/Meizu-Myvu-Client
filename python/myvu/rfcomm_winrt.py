"""Classic-BT RFCOMM transport that connects by SERVICE UUID (real SDP
resolution) instead of a raw channel number.

Why this exists: the app-relay channel is NOT a fixed channel (see the
"channel 13" note in rfcomm.py/README) -- the glasses generate a random
per-session UUID and sync it to the phone over BLE (linkproto.
CMD_SPP_SERVER_UUID_SYNC). Python's raw socket.AF_BLUETOOTH on Windows can
only connect by channel *number*; it has no SDP-by-UUID resolution, unlike
Android's BluetoothDevice.createRfcommSocketToServiceRecord(uuid). WinRT's
Windows.Devices.Bluetooth.Rfcomm namespace is the Windows-native equivalent
-- BluetoothDevice.get_rfcomm_services_for_id_async(service_id) does the real
SDP lookup and resolves straight to a connectable host/service name pair.

All API names below were verified against the installed `winsdk` package by
introspection (not guessed) -- see the conversation history that produced
this file for the exact `dir()` output checked.

Requires: pip install winsdk (already a dependency for rfcomm_pair.py).
"""
from __future__ import annotations

import asyncio
import logging
import uuid as uuid_mod
from typing import Optional

from .rfcomm import FrameReassembler, encode_frame

log = logging.getLogger("myvu.rfcomm_winrt")


def _mac_to_int(mac: str) -> int:
    return int(mac.replace(":", "").replace("-", ""), 16)


class WinRtRfcommTransport:
    """Same public shape as rfcomm.RfcommTransport (connect/send/recv/close/
    connected/channel), so it's a drop-in replacement in MyvuRfcommClient."""

    def __init__(self, address: str, service_uuid: str) -> None:
        self.address = address
        self.service_uuid = service_uuid
        self.channel = service_uuid  # for parity with RfcommTransport's log messages
        self._socket = None
        self._writer = None
        self._reader = None
        self._reassembler = FrameReassembler()
        self.inbox: "asyncio.Queue[bytes]" = asyncio.Queue()
        self._recv_task: Optional[asyncio.Task] = None
        self.connected = False

    async def connect(self, timeout: float = 15.0) -> None:
        import winsdk.windows.devices.bluetooth as bt
        import winsdk.windows.devices.bluetooth.rfcomm as wrfcomm
        import winsdk.windows.networking.sockets as sockets
        import winsdk.windows.storage.streams as streams

        device = await bt.BluetoothDevice.from_bluetooth_address_async(_mac_to_int(self.address))
        if device is None:
            raise RuntimeError(f"no classic-BT device found for {self.address}")

        service_id = wrfcomm.RfcommServiceId.from_uuid(uuid_mod.UUID(self.service_uuid))
        result = await asyncio.wait_for(
            device.get_rfcomm_services_for_id_async(service_id), timeout=timeout)
        if result.error != 0:
            raise RuntimeError(f"SDP lookup failed: error={result.error} "
                               f"uuid={self.service_uuid} address={self.address}")
        services = list(result.services)
        if not services:
            raise RuntimeError(
                f"SDP lookup found no RFCOMM service for uuid={self.service_uuid} "
                f"on {self.address} -- the glasses may not be offering it right "
                f"now (the UUID is per-session; reconnect BLE to get a fresh one)")
        service = services[0]
        log.info("SDP resolved uuid=%s -> host=%s service=%s", self.service_uuid,
                 service.connection_host_name, service.connection_service_name)

        socket = sockets.StreamSocket()
        await asyncio.wait_for(
            socket.connect_async(service.connection_host_name, service.connection_service_name),
            timeout=timeout)
        self._socket = socket
        self._writer = streams.DataWriter(socket.output_stream)
        self._reader = streams.DataReader(socket.input_stream)
        self._reader.input_stream_options = streams.InputStreamOptions.PARTIAL
        self.connected = True
        log.info("WinRT RFCOMM connected to %s (uuid=%s)", self.address, self.service_uuid)
        self._recv_task = asyncio.create_task(self._recv_loop())

    async def _recv_loop(self) -> None:
        try:
            while self.connected:
                n = await self._reader.load_async(4096)
                if n == 0:
                    log.warning("WinRT RFCOMM peer closed the connection")
                    break
                buf = bytes(bytearray(self._reader.read_buffer(n)))
                for frame in self._reassembler.feed(buf):
                    await self.inbox.put(frame)
        except Exception as e:  # noqa: BLE001
            log.warning("WinRT RFCOMM recv error: %s", e)
        finally:
            self.connected = False

    async def send(self, payload: bytes) -> None:
        if not self._writer:
            raise RuntimeError("not connected")
        framed = encode_frame(payload)
        self._writer.write_bytes(framed)
        await self._writer.store_async()

    async def recv(self, timeout: Optional[float] = None) -> bytes:
        if timeout is None:
            return await self.inbox.get()
        return await asyncio.wait_for(self.inbox.get(), timeout)

    async def close(self) -> None:
        self.connected = False
        if self._recv_task:
            self._recv_task.cancel()
            try:
                await self._recv_task
            except (asyncio.CancelledError, Exception):  # noqa: BLE001
                pass
        if self._socket:
            try:
                self._socket.close()
            except Exception:  # noqa: BLE001
                pass

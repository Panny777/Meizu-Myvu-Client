"""Minimal Hands-Free Profile (HFP) Audio-Gateway-role responder.

Why this exists: the glasses appear to gate some app-layer functionality
(confirmed: the teleprompter) on more than just the app-relay channel being
connected -- the real phone also establishes HFP (and A2DP) alongside the
relay channel (confirmed both by capture ordering -- HFP/A2DP connect BEFORE
the relay channel -- and by the decompiled app's BrEdrMasterManager.
connectBrEdr(), which always connects both). This is a standard, spec'd
profile (unlike the proprietary relay protocol), so no reverse-engineering
is needed -- just replaying the real phone's own captured responses.

Roles: the GLASSES are the Hands-Free unit (HF) -- they send AT commands.
The PHONE is the Audio Gateway (AG) -- it answers them. So *we* (acting as
the phone) connect out to the glasses' HF service (SDP UUID 0x111E) and then
answer whatever AT commands arrive, using the exact reply bytes captured
from a real phone (see BT_HCI_2026_07_13_12_16.log, RFCOMM DLCI 6 / server
channel 3 -- identical across two independent sessions in that capture, so
this is a fixed handshake, not session-randomized like the relay channel's
UUID).

This implements just enough of the AG role to complete the initial
handshake and hold the channel open -- not full call-control / audio
streaming (no SCO/eSCO audio path here, no A2DP).
"""
from __future__ import annotations

import asyncio
import logging
from typing import Optional

log = logging.getLogger("myvu.hfp")

HFP_HF_SERVICE_UUID = "0000111e-0000-1000-8000-00805f9b34fb"

# Captured verbatim from a real phone's AG-role replies (BT_HCI capture,
# identical in two independent sessions). Keyed by the AT command line
# (without \r), value is the exact reply bytes to send back.
_REPLIES: dict[str, bytes] = {
    "AT+BRSF=767": b"\r\n+BRSF: 3943\r\n\r\nOK\r\n",
    "AT+BAC=1,2": b"\r\nOK\r\n",
    "AT+CIND=?": (
        b'\r\n+CIND: ("call",(0,1)),("callsetup",(0-3)),("service",(0-1)),'
        b'("signal",(0-5)),("roam",(0,1)),("battchg",(0-5)),("callheld",(0-2))'
        b"\r\n\r\nOK\r\n"
    ),
    "AT+CIND?": b"\r\n+CIND: 0,0,1,5,0,4,0\r\n\r\nOK\r\n",
    "AT+CMER=3,0,0,1": b"\r\nOK\r\n",
    "AT+CHLD=?": b"\r\n+CHLD: (0,1,2,3)\r\n\r\nOK\r\n",
    "AT+CLIP=1": b"\r\nOK\r\n",
    "AT+CCWA=1": b"\r\nOK\r\n",
    "AT+COPS=3,0": b"\r\nOK\r\n",
    "AT+CMEE=1": b"\r\nOK\r\n",
    "AT+XAPL=0000-0000-0100,3": b"\r\n+XAPL=iPhone,2\r\n\r\nOK\r\n",
}


def _mac_to_int(mac: str) -> int:
    return int(mac.replace(":", "").replace("-", ""), 16)


class HfpAgResponder:
    """Connects to the glasses' HF service and answers AT commands with the
    real phone's captured replies. Plain-text RFCOMM -- no eaca9353 framing
    (that's specific to the app-relay channel, see rfcomm_winrt.py)."""

    def __init__(self, address: str) -> None:
        self.address = address
        self._socket = None
        self._writer = None
        self._reader = None
        self._recv_task: Optional[asyncio.Task] = None
        self.connected = False
        self.handshake_done = asyncio.Event()

    async def connect(self, timeout: float = 15.0) -> None:
        import winsdk.windows.devices.bluetooth as bt
        import winsdk.windows.devices.bluetooth.rfcomm as wrfcomm
        import winsdk.windows.networking.sockets as sockets
        import winsdk.windows.storage.streams as streams
        import uuid as uuid_mod

        device = await bt.BluetoothDevice.from_bluetooth_address_async(_mac_to_int(self.address))
        if device is None:
            raise RuntimeError(f"no classic-BT device found for {self.address}")

        service_id = wrfcomm.RfcommServiceId.from_uuid(uuid_mod.UUID(HFP_HF_SERVICE_UUID))
        result = await asyncio.wait_for(
            device.get_rfcomm_services_for_id_async(service_id), timeout=timeout)
        if result.error != 0:
            raise RuntimeError(f"SDP lookup failed for HFP HF service: error={result.error}")
        services = list(result.services)
        if not services:
            raise RuntimeError(
                "no HFP Hands-Free service found via SDP -- the glasses may not "
                "advertise it, or it's not currently available")
        service = services[0]
        log.info("HFP SDP resolved -> host=%s service=%s",
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
        log.info("HFP connected to %s", self.address)
        self._recv_task = asyncio.create_task(self._recv_loop())

    async def _recv_loop(self) -> None:
        buf = bytearray()
        seen = set()
        try:
            while self.connected:
                n = await self._reader.load_async(4096)
                if n == 0:
                    log.warning("HFP peer closed the connection")
                    break
                chunk = bytes(bytearray(self._reader.read_buffer(n)))
                buf += chunk
                while b"\r" in buf:
                    line, _, rest = buf.partition(b"\r")
                    buf = bytearray(rest)
                    text = line.decode("ascii", "replace").strip()
                    if not text:
                        continue
                    log.info("HFP <- %r", text)
                    reply = _REPLIES.get(text)
                    if reply is not None:
                        await self._send_raw(reply)
                        log.debug("HFP -> %r", reply)
                    else:
                        log.warning("HFP <- unrecognized command %r (no reply sent)", text)
                    seen.add(text)
                    if "AT+XAPL" in text or len(seen) >= len(_REPLIES):
                        if not self.handshake_done.is_set():
                            log.info("HFP handshake looks complete (%d/%d known commands seen)",
                                     len(seen), len(_REPLIES))
                            self.handshake_done.set()
        except Exception as e:  # noqa: BLE001
            log.warning("HFP recv error: %s", e)
        finally:
            self.connected = False

    async def _send_raw(self, data: bytes) -> None:
        self._writer.write_bytes(data)
        await self._writer.store_async()

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

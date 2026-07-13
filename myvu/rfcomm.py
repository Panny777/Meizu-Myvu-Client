"""Classic-Bluetooth (RFCOMM/SPP) transport.

Reverse-engineered from btsnoop RFCOMM channel 13 traffic. Unlike BLE, the
glasses put NO app-layer crypto handshake on this transport -- it starts
straight into the ability/AUTH handshake, relying on BR/EDR's own SSP-derived
link-layer encryption for security (confirmed: RFCOMM channel 13 frame 807 in
the capture *is* the ability message, with no ECDH exchange before it).

Channel 13 confirmed against a second, independent capture (a fresh phone
pairing + a force-stop/reopen reconnect, 2026-07-13): the phone opens THREE
RFCOMM channels on the same multiplexer, and only one of them is this
protocol -- channel 0 is the standard mux control channel, channel 3 carries
plain Hands-Free Profile AT commands (`AT+BRSF=`, `AT+CIND=?`, ...; unrelated
audio-gateway wiring, ignore it), and channel 13's SABM/UA handshake completes
15ms before the first `eaca9353`-framed relay message appears -- i.e. don't
assume "the first non-control RFCOMM channel you see" is this one. The
classic-BT connection itself starts independently of, and slightly before,
the BLE session finishing (not triggered by any particular BLE/JSON message),
matching the timing already documented in the top-level README.

Frame format (confirmed byte-for-byte against the capture):

    eaca9353            4-byte magic
    <length:4 BE>       length of everything that follows (prefix + payload)
    00 02               2-byte constant prefix (seen in every captured frame)
    <payload>           IDENTICAL to what we already build for the BLE
                         external channel: relay.build_frame() (0x01-class)
                         or session._build_stream_req() (0x02-class) output,
                         unfragmented -- RFCOMM is already a reliable stream
                         so none of BLE's Single/Mix/CTR packet layer is used.

Requires: Python 3.12+ on Windows, which has native socket.AF_BLUETOOTH /
socket.BTPROTO_RFCOMM support (no extra library needed). The remote device
must already be BR/EDR-bonded (use pair_glasses.py) -- classic RFCOMM sockets
refuse to connect to an unbonded device.

IMPORTANT caveat (confirmed 2026-07-13): channel 13 answers the ability/AUTH
handshake, but does NOT reliably carry the actual app-relay traffic
afterward -- sending real app commands (notifications, tici, ...) here gets
zero ACKs. The real app-relay channel is a DIFFERENT, randomly-generated
per-session RFCOMM service (see linkproto.CMD_SPP_SERVER_UUID_SYNC), reached
via SDP-by-UUID resolution in rfcomm_winrt.py, not this fixed-channel
transport. Use this module only for probing/debugging channel 13 itself;
use run_glasses.py (BLE + rfcomm_winrt.py + hfp.py) for anything that needs
to actually work.
"""
from __future__ import annotations

import asyncio
import logging
import socket
import struct
import threading
from typing import Optional

log = logging.getLogger("myvu.rfcomm")

MAGIC = bytes.fromhex("eaca9353")
PREFIX = bytes.fromhex("0002")
DEFAULT_CHANNEL = 13


def encode_frame(payload: bytes) -> bytes:
    body = PREFIX + payload
    return MAGIC + struct.pack(">I", len(body)) + body


class FrameReassembler:
    """Feed raw stream bytes in; get complete (prefix, payload) frames out."""

    def __init__(self) -> None:
        self._buf = bytearray()

    def feed(self, data: bytes) -> list[bytes]:
        self._buf += data
        out = []
        while True:
            idx = self._buf.find(MAGIC)
            if idx < 0:
                if len(self._buf) > len(MAGIC):
                    del self._buf[:-len(MAGIC)]
                break
            if idx > 0:
                log.debug("dropping %d bytes of junk before magic", idx)
                del self._buf[:idx]
            if len(self._buf) < 8:
                break
            (length,) = struct.unpack_from(">I", self._buf, 4)
            total = 8 + length
            if len(self._buf) < total:
                break
            frame = bytes(self._buf[8:total])
            del self._buf[:total]
            # frame = PREFIX(2) + payload
            out.append(frame[2:])
        return out


class RfcommTransport:
    """Blocking classic-BT socket bridged onto the asyncio loop via a thread."""

    def __init__(self, address: str, channel: int = DEFAULT_CHANNEL) -> None:
        self.address = address
        self.channel = channel
        self._sock: Optional[socket.socket] = None
        self._reassembler = FrameReassembler()
        self.inbox: "asyncio.Queue[bytes]" = asyncio.Queue()
        self._thread: Optional[threading.Thread] = None
        self._stop = threading.Event()
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self.connected = False

    async def connect(self, timeout: float = 10.0) -> None:
        self._loop = asyncio.get_event_loop()
        sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM,
                             socket.BTPROTO_RFCOMM)
        sock.settimeout(timeout)
        await self._loop.run_in_executor(
            None, sock.connect, (self.address, self.channel))
        sock.settimeout(None)
        self._sock = sock
        self.connected = True
        log.info("RFCOMM connected to %s channel %d", self.address, self.channel)
        self._thread = threading.Thread(target=self._recv_loop, daemon=True)
        self._thread.start()

    def _recv_loop(self) -> None:
        assert self._sock is not None and self._loop is not None
        while not self._stop.is_set():
            try:
                data = self._sock.recv(4096)
            except OSError as e:
                if not self._stop.is_set():
                    log.warning("RFCOMM recv error: %s", e)
                break
            if not data:
                log.warning("RFCOMM peer closed the connection")
                break
            for frame in self._reassembler.feed(data):
                self._loop.call_soon_threadsafe(self.inbox.put_nowait, frame)
        self.connected = False

    async def send(self, payload: bytes) -> None:
        if not self._sock:
            raise RuntimeError("not connected")
        framed = encode_frame(payload)
        await self._loop.run_in_executor(None, self._sock.sendall, framed)

    async def recv(self, timeout: Optional[float] = None) -> bytes:
        if timeout is None:
            return await self.inbox.get()
        return await asyncio.wait_for(self.inbox.get(), timeout)

    def close(self) -> None:
        self._stop.set()
        if self._sock:
            try:
                self._sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            self._sock.close()
        self.connected = False

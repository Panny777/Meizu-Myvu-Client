"""Reliable message channel over one GATT characteristic.

Port of the send/receive behaviour of
  com.upuphone.starrynet.core.ble.channel.Channel  (+ ChannelManager)

Observed on the wire (btsnoop):
  * INTERNAL / pairing channel  -> SinglePacket (type 2), pkgType 16, ACKed with
    a SingleACK. Responses from the glasses are also single SinglePackets.
  * EXTERNAL / app channel       -> SINGLE_NO_ACK (type 9) for one-frame messages
    and MIX_CTR (type 8) + data frames for larger ones, pkgType 0.

Fragmentation unit DMTU = negotiated_ATT_MTU - 5  (ClientChannelManager.getDMTU;
falls back to 18 for the 23-byte default MTU).

This implementation covers every path needed to complete the handshake and to
exchange application messages. It is deliberately linear/awaitable rather than
the app's callback state-machine, but produces byte-identical wire output.
"""
from __future__ import annotations

import asyncio
from typing import Awaitable, Callable, Dict, Optional

from . import packets

WriteFn = Callable[[str, bytes], Awaitable[None]]


class Reassembler:
    """Rebuilds one logical message from control + data packets."""

    def __init__(self) -> None:
        self.reset()

    def reset(self) -> None:
        self.frame_count = 0
        self.pkg_type = -1
        self.header = b""          # MIX_CTR first chunk
        self.frames: Dict[int, bytes] = {}
        self.active = False

    def start(self, frame_count: int, pkg_type: int, header: bytes = b"") -> None:
        self.reset()
        self.frame_count = frame_count
        self.pkg_type = pkg_type
        self.header = header
        self.active = True

    def add(self, seq: int, payload: bytes) -> Optional[bytes]:
        """Add a data fragment; return the full message when complete."""
        self.frames[seq] = payload
        if self.frame_count and len(self.frames) >= self.frame_count:
            out = bytearray(self.header)
            for i in range(1, self.frame_count + 1):
                out += self.frames.get(i, b"")
            self.active = False
            return bytes(out)
        return None


class MessageChannel:
    def __init__(self, char_uuid: str, write: WriteFn, dmtu: int = 18) -> None:
        self.char = char_uuid
        self._write = write
        self.dmtu = dmtu
        self._rx = Reassembler()
        self.inbox: "asyncio.Queue[tuple[int, bytes]]" = asyncio.Queue()
        # events used to await ACKs on the pairing channel
        self._single_ack: "asyncio.Queue[int]" = asyncio.Queue()
        self._ack: "asyncio.Queue[int]" = asyncio.Queue()   # CTR-path ACK status

    # ---------------------------------------------------------------- send
    async def send_single_acked(self, payload: bytes, pkg_type: int,
                                timeout: float = 6.0) -> int:
        """Send an ACK'd message on the pairing channel.

        If it fits in one frame -> SinglePacket (type 2) + SingleACK, exactly as
        the app does over a large MTU. If it does not fit (small/unknown MTU) ->
        fall back to the multi-frame CTR path the app uses in that case."""
        if len(payload) > self.dmtu:
            return await self.send_ctr_acked(payload, pkg_type, timeout)
        await self._write(self.char, packets.single_packet(pkg_type, payload))
        try:
            return await asyncio.wait_for(self._single_ack.get(), timeout)
        except asyncio.TimeoutError:
            return packets.ACK_TIMEOUT

    async def send_ctr_acked(self, payload: bytes, pkg_type: int,
                             timeout: float = 6.0) -> int:
        """CTR (type 0) multi-frame send: CTR -> wait ACK(READY) -> data frames
        -> wait ACK(SUCCESS). Mirrors Channel.mRecvACKHandler."""
        frame_count = max(1, (len(payload) + self.dmtu - 1) // self.dmtu)
        await self._write(self.char, packets.ctr_packet(frame_count, pkg_type))
        try:
            status = await asyncio.wait_for(self._ack.get(), timeout)
            if status != packets.ACK_READY:
                return status
            for idx in range(frame_count):
                seq = idx + 1
                chunk = payload[idx * self.dmtu:(idx + 1) * self.dmtu]
                await self._write(self.char, packets.data_packet(seq, chunk))
            return await asyncio.wait_for(self._ack.get(), timeout)
        except asyncio.TimeoutError:
            return packets.ACK_TIMEOUT

    async def send_single_no_ack(self, payload: bytes, pkg_type: int) -> None:
        """SINGLE_NO_ACK (type 9) — fire and forget, one frame."""
        await self._write(self.char, packets.single_no_ack_packet(pkg_type, payload))

    async def send_fast(self, payload: bytes, pkg_type: int) -> None:
        """FAST_CTR (type 6) + data frames, sent back-to-back with no wait.

        This is the exact form the app uses for the very first version-
        negotiation message (pkgType 17 / STARRY_DATA_INIT)."""
        frame_count = max(1, (len(payload) + self.dmtu - 1) // self.dmtu)
        await self._write(self.char, packets.fast_ctr_packet(frame_count, pkg_type))
        for idx in range(frame_count):
            seq = idx + 1
            chunk = payload[idx * self.dmtu:(idx + 1) * self.dmtu]
            await self._write(self.char, packets.data_packet(seq, chunk))

    async def send_mix(self, payload: bytes, pkg_type: int) -> None:
        """MIX_CTR (type 8): first chunk inline, remainder as data frames."""
        first = payload[: self.dmtu - 4]
        rest = payload[self.dmtu - 4:]
        frame_count = (len(rest) + self.dmtu - 1) // self.dmtu if rest else 0
        await self._write(self.char, packets.mix_ctr_packet(frame_count, pkg_type, first))
        for idx in range(frame_count):
            seq = idx + 1
            chunk = rest[idx * self.dmtu:(idx + 1) * self.dmtu]
            await self._write(self.char, packets.data_packet(seq, chunk))

    async def send(self, payload: bytes, pkg_type: int, ack: bool = False) -> None:
        """Convenience: pick the smallest wire form that fits."""
        if ack:
            await self.send_single_acked(payload, pkg_type)
        elif len(payload) <= self.dmtu:
            await self.send_single_no_ack(payload, pkg_type)
        else:
            await self.send_mix(payload, pkg_type)

    # ------------------------------------------------------------- receive
    async def feed(self, raw: bytes) -> None:
        """Handle one inbound GATT notification for this characteristic."""
        p = packets.parse(raw)

        if p.is_data:
            full = self._rx.add(p.sn, p.value)
            if full is not None:
                await self._deliver(self._rx.pkg_type, full)
            return

        t = p.type
        if t == packets.TYPE_SINGLE_CMD:                 # 2: whole msg + needs ACK
            await self._write(self.char, packets.single_ack_packet(packets.ACK_SUCCESS))
            await self._deliver(p.pkg_type, p.value)
        elif t == packets.TYPE_SINGLE_CMD_NO_ACK:        # 9: whole msg, no ACK
            await self._deliver(p.pkg_type, p.value)
        elif t == packets.TYPE_SINGLE_ACK:               # 3: response to our single
            await self._single_ack.put(p.ack_status)
        elif t == packets.TYPE_CMD:                      # 0: CTR -> reply READY ACK
            self._rx.start(p.frame_count, p.pkg_type)
            await self._write(self.char, packets.ack_packet(packets.ACK_READY))
        elif t == packets.TYPE_FAST_CTR:                 # 6: fast start (no ack back)
            self._rx.start(p.frame_count, p.pkg_type)
        elif t == packets.TYPE_MIX_CTR:                  # 8: first chunk inline
            done = None
            self._rx.start(p.frame_count, p.pkg_type, header=p.value)
            if p.frame_count == 0:
                done = p.value
                self._rx.active = False
            if done is not None:
                await self._deliver(p.pkg_type, done)
        elif t == packets.TYPE_ACK:                      # 1: peer ack (READY/SUCCESS)
            await self._ack.put(p.ack_status)
        # FAST_ACK / others: not expected inbound on the client

    async def _deliver(self, pkg_type: int, payload: bytes) -> None:
        await self.inbox.put((pkg_type, payload))

    async def recv(self, timeout: Optional[float] = None) -> tuple[int, bytes]:
        if timeout is None:
            return await self.inbox.get()
        return await asyncio.wait_for(self.inbox.get(), timeout)

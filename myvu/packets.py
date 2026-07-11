"""BLE transport packet codec.

Faithful port of com.upuphone.starrynet.core.ble.channel.packet.*

Every packet is little-endian and begins with a 2-byte "sn" (sequence) field:

  * sn == 0  -> a FLOW/CONTROL packet. Byte[2] = type, byte[3] = command,
               followed by type-specific parameters.
  * sn != 0  -> a DATA packet (fragment number `sn`), payload = bytes[2:].

Control packet types (Packet.TYPE_*):
    0 CTR         [00 00] 00 <pkgType> <frameCount:2>
    1 ACK         [00 00] 01 <status>  [<lostSeq:2>...]
    2 SINGLE_CMD  [00 00] 02 <pkgType> <payload...>        (whole msg, one write)
    3 SINGLE_ACK  [00 00] 03 <status>
    6 FAST_CTR    [00 00] 06 <pkgType> <frameCount:2>
    7 FAST_ACK    [00 00] 07 <status>
    8 MIX_CTR     [00 00] 08 <pkgType> <frameCount:2> <firstChunk...>
    9 SINGLE_NO_ACK[00 00] 09 <pkgType> <payload...>

Package types (CTRPacket.CMD_*):
    0  COMMON_DATA
    1  COMMON_DATA_CRC32   (adds trailing CRC32 -- client never uses this)
    16 COMMON_STARRY_DATA        (0x10)  <- normal messages
    17 COMMON_STARRY_DATA_INIT   (0x11)  <- first/negotiation message
"""
from __future__ import annotations

import struct
from dataclasses import dataclass, field
from typing import List, Optional

# control types
TYPE_CMD = 0
TYPE_ACK = 1
TYPE_SINGLE_CMD = 2
TYPE_SINGLE_ACK = 3
TYPE_MNG = 4
TYPE_MNG_ACK = 5
TYPE_FAST_CTR = 6
TYPE_FAST_ACK = 7
TYPE_MIX_CTR = 8
TYPE_SINGLE_CMD_NO_ACK = 9

# package types
PKG_COMMON_DATA = 0        # external/app channel messages
PKG_STARRY_DATA = 16       # pairing channel messages
PKG_STARRY_DATA_INIT = 17  # first/negotiation message

# ACK status
ACK_SUCCESS = 0
ACK_READY = 1
ACK_BUSY = 2
ACK_TIMEOUT = 3
ACK_CANCEL = 4
ACK_SYNC = 5


# ---------------------------------------------------------------- encoders
def data_packet(seq: int, payload: bytes) -> bytes:
    """DataPacket.toBytes(): [seq:2][payload].  seq must be >= 1."""
    return struct.pack("<H", seq) + payload


def ctr_packet(frame_count: int, pkg_type: int) -> bytes:
    return struct.pack("<HBBH", 0, TYPE_CMD, pkg_type, frame_count)


def fast_ctr_packet(frame_count: int, pkg_type: int) -> bytes:
    return struct.pack("<HBBH", 0, TYPE_FAST_CTR, pkg_type, frame_count)


def mix_ctr_packet(frame_count: int, pkg_type: int, first_chunk: bytes) -> bytes:
    return struct.pack("<HBBH", 0, TYPE_MIX_CTR, pkg_type, frame_count) + first_chunk


def single_packet(pkg_type: int, payload: bytes) -> bytes:
    return struct.pack("<HBB", 0, TYPE_SINGLE_CMD, pkg_type) + payload


def single_no_ack_packet(pkg_type: int, payload: bytes) -> bytes:
    return struct.pack("<HBB", 0, TYPE_SINGLE_CMD_NO_ACK, pkg_type) + payload


def ack_packet(status: int, lost_seqs: Optional[List[int]] = None) -> bytes:
    out = struct.pack("<HBB", 0, TYPE_ACK, status)
    if lost_seqs:
        out += b"".join(struct.pack("<H", s) for s in lost_seqs)
    return out


def fast_ack_packet(status: int) -> bytes:
    return struct.pack("<HBB", 0, TYPE_FAST_ACK, status)


def single_ack_packet(status: int) -> bytes:
    return struct.pack("<HBB", 0, TYPE_SINGLE_ACK, status)


# ---------------------------------------------------------------- decoder
@dataclass
class ParsedPacket:
    sn: int
    type: Optional[int] = None       # only for control packets (sn == 0)
    command: Optional[int] = None    # byte[3] -> packageType (or ack status)
    params: List[int] = field(default_factory=list)  # trailing shorts
    value: bytes = b""               # payload bytes (fragment data / mix data)

    @property
    def is_data(self) -> bool:
        return self.sn != 0

    @property
    def pkg_type(self) -> int:
        return self.command if self.command is not None else -1

    @property
    def frame_count(self) -> int:
        return self.params[0] if self.params else 0

    @property
    def ack_status(self) -> int:
        # for ACK/FAST_ACK/SINGLE_ACK the "command" byte carries the status
        return self.command if self.command is not None else -1


def parse(raw: bytes) -> ParsedPacket:
    """Port of Packet.parse() + getPacket()."""
    if len(raw) < 2:
        return ParsedPacket(sn=0)
    (sn,) = struct.unpack_from("<H", raw, 0)
    if sn != 0:
        # DataPacket: fragment `sn`, payload is the remainder
        return ParsedPacket(sn=sn, value=raw[2:])

    p = ParsedPacket(sn=0)
    if len(raw) < 4:
        return p
    p.type = raw[2]
    p.command = raw[3]
    if p.type == TYPE_MIX_CTR:
        (frame,) = struct.unpack_from("<H", raw, 4)
        p.params.append(frame)
        p.value = raw[6:]
    else:
        off = 4
        while off + 2 <= len(raw):
            (s,) = struct.unpack_from("<H", raw, off)
            p.params.append(s)
            off += 2
        # SinglePacket / SingleNoAck carry raw payload after the 2-byte header,
        # starting at offset 4 (ByteUtils.get(value, 4) in the app).
        if p.type in (TYPE_SINGLE_CMD, TYPE_SINGLE_CMD_NO_ACK):
            p.value = raw[4:]
    return p

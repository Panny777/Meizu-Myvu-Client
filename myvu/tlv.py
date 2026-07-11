"""TlvBox codec.

Faithful port of com.upuphone.runasone.host.core.api.util.TlvBox

Wire format (BIG-ENDIAN): a concatenation of entries, each:
    [tag: 1 byte][length: 2 bytes][value: <length> bytes]

Integers are fixed-width big-endian (int=4, long=8, short=2). Nested TlvBox
values are just their own serialize() bytes. Tag numbers come from
com.upuphone.runasone.host.core.api.myconst.TlvCodeConst.
"""
from __future__ import annotations

import struct
from typing import Dict

# TlvCodeConst tag numbers
TAG_MSG_TYPE = 100      # 0x64
TAG_MSG_ID = 101        # 0x65  (== ChannelMessage.requestId / sequence)
TAG_ERROR_CODE = 102    # 0x66
TAG_NEED_CALLBACK = 103  # 0x67
TAG_OPEN_TYPE = 104     # 0x68
TAG_MSG_BODY = 105      # 0x69
TAG_HOST = 106          # 0x6a
TAG_VERSION = 107       # 0x6b
TAG_DEST_ID = 108       # 0x6c
TAG_APP_UNITE_CODE = 109  # 0x6d
TAG_LISTENER_ID = 110   # 0x6e
TAG_SELF_ID = 111       # 0x6f
TAG_CATEGORY = 112      # 0x70
TAG_PAYLOAD = 113       # 0x71

# msgType values (TlvCodeConst)
MSG_OPEN = 1
MSG_CLOSE = 2
MSG_SEND = 3            # data message
MSG_SEND_SUCCESS = 4   # ACK
MSG_SEND_FAIL = 5
MSG_OPEN_SUCCESS = 6
MSG_OPEN_FAIL = 7
MSG_OPEN_PAGE = 8


class TlvBox:
    def __init__(self) -> None:
        self.values: Dict[int, bytes] = {}

    # ---- put helpers (ordered dict preserves insertion order like the app) ---
    def put_bytes(self, tag: int, val: bytes) -> "TlvBox":
        self.values[tag] = val
        return self

    def put_byte(self, tag: int, val: int) -> "TlvBox":
        self.values[tag] = bytes([val & 0xFF])
        return self

    def put_int(self, tag: int, val: int) -> "TlvBox":
        self.values[tag] = struct.pack(">i", val)
        return self

    def put_str(self, tag: int, val: str) -> "TlvBox":
        self.values[tag] = val.encode("utf-8")
        return self

    def put_box(self, tag: int, box: "TlvBox") -> "TlvBox":
        self.values[tag] = box.serialize()
        return self

    # ---- get helpers ---------------------------------------------------------
    def get_bytes(self, tag: int) -> bytes | None:
        return self.values.get(tag)

    def get_byte(self, tag: int) -> int | None:
        v = self.values.get(tag)
        return v[0] if v else None

    def get_int(self, tag: int) -> int | None:
        v = self.values.get(tag)
        return struct.unpack(">i", v)[0] if v and len(v) == 4 else None

    def get_box(self, tag: int) -> "TlvBox | None":
        v = self.values.get(tag)
        return TlvBox.parse(v) if v is not None else None

    def contains(self, tag: int) -> bool:
        return tag in self.values

    # ---- (de)serialize -------------------------------------------------------
    def serialize(self) -> bytes:
        out = bytearray()
        for tag, val in self.values.items():
            out.append(tag & 0xFF)
            out += struct.pack(">H", len(val))
            out += val
        return bytes(out)

    @classmethod
    def parse(cls, data: bytes) -> "TlvBox":
        box = cls()
        i = 0
        n = len(data)
        while i + 3 <= n:
            tag = data[i]
            (length,) = struct.unpack_from(">H", data, i + 1)
            i += 3
            box.values[tag] = data[i:i + length]
            i += length
        return box

"""RunAsOne relay / SuperMessage layer (external channel, plaintext).

This is the layer that flips the glasses from 'Open MYVU AR App' to 'connected'.

Wire format of one routed frame (verified against btsnoop):

    0x01                                  # frame prefix (constant)
    TlvBox {
        112 category: <1 byte>            # ability/route code (3 for launcher/air)
        113 payload:  TlvBox {            # == ChannelMessage
            100 msgType:      <1 byte>    # 3=sendMsg(data), 4=sendMsgSuccess(ACK)
            101 msgId:        <int32>     # requestId / SEQUENCE, must be 1,2,3...
            103 needCallback: <1 byte>    # 1 => peer should ACK
            109 appUniteCode: <1 byte>
            105 msgBody:      <bytes>     # inner StMessage protobuf (the app JSON)
        }
    }

Why sequencing matters (ChannelImpl.input): the receiver tracks
lastReceiveRequestId (0 on a fresh connect). A message whose msgId is far above
that is treated as a large out-of-order gap and buffered, never delivered. So a
fresh session MUST start msgId at 1 and increment by 1 with no gaps. Replaying
the capture's stale msgIds (0x44b+) is exactly why the earlier attempt failed.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from . import tlv

FRAME_PREFIX = 0x01
DEFAULT_CATEGORY = 3


@dataclass
class RelayMessage:
    category: int
    msg_type: int
    msg_id: int
    need_callback: int
    app_unite_code: int
    msg_body: bytes


def parse_frame(raw: bytes) -> Optional[RelayMessage]:
    """Decode a 0x01-class routed frame. Returns None if it isn't one."""
    if not raw or raw[0] != FRAME_PREFIX:
        return None
    outer = tlv.TlvBox.parse(raw[1:])
    cat = outer.get_byte(tlv.TAG_CATEGORY)
    payload = outer.get_bytes(tlv.TAG_PAYLOAD)
    if payload is None:
        return None
    inner = tlv.TlvBox.parse(payload)
    return RelayMessage(
        category=cat if cat is not None else DEFAULT_CATEGORY,
        msg_type=inner.get_byte(tlv.TAG_MSG_TYPE) or 0,
        msg_id=inner.get_int(tlv.TAG_MSG_ID) or 0,
        need_callback=inner.get_byte(tlv.TAG_NEED_CALLBACK) or 0,
        app_unite_code=inner.get_byte(tlv.TAG_APP_UNITE_CODE) or 0,
        msg_body=inner.get_bytes(tlv.TAG_MSG_BODY) or b"",
    )


def build_frame(category: int, msg_type: int, msg_id: int, need_callback: int,
                app_unite_code: int, msg_body: bytes) -> bytes:
    inner = tlv.TlvBox()
    inner.put_byte(tlv.TAG_MSG_TYPE, msg_type)
    inner.put_int(tlv.TAG_MSG_ID, msg_id)
    inner.put_byte(tlv.TAG_NEED_CALLBACK, need_callback)
    inner.put_byte(tlv.TAG_APP_UNITE_CODE, app_unite_code)
    if msg_body:
        inner.put_bytes(tlv.TAG_MSG_BODY, msg_body)
    outer = tlv.TlvBox()
    outer.put_byte(tlv.TAG_CATEGORY, category)
    outer.put_box(tlv.TAG_PAYLOAD, inner)
    return bytes([FRAME_PREFIX]) + outer.serialize()


class RelaySequencer:
    """Owns the outgoing msgId counter (starts at 1) and builds/ACKs frames."""

    def __init__(self) -> None:
        self.out_id = 0            # last assigned outgoing msgId
        self.last_recv_id = 0      # highest msgId seen from the peer

    def next_id(self) -> int:
        self.out_id += 1
        return self.out_id

    def data_frame(self, msg_body: bytes, category: int = DEFAULT_CATEGORY,
                   need_callback: int = 1, app_unite_code: int = 1) -> bytes:
        return build_frame(category, tlv.MSG_SEND, self.next_id(),
                           need_callback, app_unite_code, msg_body)

    def ack_frame(self, for_msg: RelayMessage) -> bytes:
        """Build a sendMsgSuccess (ACK) for a received data message. The ACK
        carries the peer's msgId echoed back, under our own outgoing counter."""
        inner = tlv.TlvBox()
        inner.put_byte(tlv.TAG_MSG_TYPE, tlv.MSG_SEND_SUCCESS)
        inner.put_int(tlv.TAG_MSG_ID, for_msg.msg_id)
        outer = tlv.TlvBox()
        outer.put_byte(tlv.TAG_CATEGORY, for_msg.category)
        outer.put_box(tlv.TAG_PAYLOAD, inner)
        return bytes([FRAME_PREFIX]) + outer.serialize()

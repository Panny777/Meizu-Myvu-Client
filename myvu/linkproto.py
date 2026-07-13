"""StarryNet LinkProtocol (protobuf) + message builders.

Ports:
  Starry.StarryLinkEncrypt  (the .proto)
  com.upuphone.starrynet.strategy.encrypt.StarryNetEncryptHelper
  com.upuphone.starrynet.strategy.utils.BleUtil.dealDeviceId

A tiny hand-rolled protobuf codec is used so there is no build-time dependency
on protoc.
"""
from __future__ import annotations

import struct
from dataclasses import dataclass
from typing import Dict, Tuple

from . import crypto

# ---- COMMAND enum (from starry_link_encrypt.proto, confirmed against the
# decompiled official app's Starry.StarryLinkEncrypt.COMMAND enum) --------
CMD_INIT = 0
CMD_ENSURE = 1
CMD_UN_BONDED = 2
CMD_READ_SWITCH_KEY = 10
CMD_WRITE_SWITCH_KEY = 11
CMD_READ_SWITCH_INFO = 12
CMD_WRITE_SWITCH_INFO = 13
CMD_BOND_MSG_CHANGE = 14
CMD_AUTH_STATUE = 18
CMD_AUTH_MESSAGE = 19
# The classic-BT (RFCOMM) app-relay channel is NOT a fixed channel number --
# the glasses generate a random 16-bit UUID per session and sync it to the
# phone over BLE via CMD_SPP_SERVER_UUID_SYNC before any classic-BT SPP
# connect is attempted (com.upuphone.starrynet.strategy.channel.spp.negotiate.
# SPPNegotiateProtocolManager.handleServerUUIDSync in the decompiled app).
# The captured "channel 13" was just whatever channel Android's SDP happened
# to assign that one session -- not a stable protocol constant.
CMD_SPP_SERVER_UUID_SYNC = 70
CMD_SPP_SERVER_REQUEST_CONNECT = 71
CMD_SPP_SERVER_REQUEST_STATE_OPEN = 72
CMD_SPP_SERVER_REQUEST_STATE_CLOSE = 73


def spp_short_uuid_to_str(data: bytes) -> str:
    """Decode a CMD_SPP_SERVER_UUID_SYNC payload (4-byte LITTLE-endian int,
    the 'short' 16-bit UUID) into the full Bluetooth Base UUID string,
    matching UUIDUtils.makeUUID(int) in the decompiled app. Confirmed
    little-endian empirically: a captured payload of bytes 21 91 00 00
    only fits ByteUtils' expected range (SecureRandom.nextInt(65535)) when
    read little-endian (0x9121=37153); big-endian gives 0x21910000, far out
    of range."""
    short = int.from_bytes(data[:4], "little")
    return f"0000{short:04x}-0000-1000-8000-00805f9b34fb"

# ---- BTSTATUS enum (starry_link_encrypt.proto, DeviceInfo.btStatus) -----
# Classic-BT bond/connection state the phone reports to the glasses in the
# DeviceInfo message. We currently always send DEFAULT(0); the real phone's
# actual value can't be recovered from a passive capture (WRITE_SWITCH_INFO
# is AES-encrypted with an ECDH-derived key we never captured the private
# side of). NOBOND is the best-guess value to try for "please open classic-BT
# pairing for the MAC I just gave you" -- unverified against real hardware.
BTSTATUS_DEFAULT = 0
BTSTATUS_BOND = 1
BTSTATUS_BONDING = 2
BTSTATUS_NOBOND = 3
BTSTATUS_CONNECTED_ACL = 4
BTSTATUS_CONNECTED_HFP = 5
BTSTATUS_CONNECTED_A2DP = 6
BTSTATUS_DISCONNECTED = 7
BTSTATUS_NO_CONNECTED_BT = 8
BTSTATUS_EXIST_CONNECTED_BT = 9
BTSTATUS_CONNECT_FAIL = 10
BTSTATUS_BOND_CANCEL_OR_TIMEOUT = 11


# ---- minimal protobuf ---------------------------------------------------
def _varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _tag(field: int, wire: int) -> bytes:
    return _varint((field << 3) | wire)


def pb_bytes(field: int, val: bytes) -> bytes:
    return _tag(field, 2) + _varint(len(val)) + val


def pb_varint(field: int, val: int) -> bytes:
    return _tag(field, 0) + _varint(val)


def pb_string(field: int, val: str) -> bytes:
    return pb_bytes(field, val.encode("utf-8"))


def pb_parse(data: bytes) -> Dict[int, list]:
    """Parse into {field_number: [values]}; length-delimited -> bytes,
    varint -> int. Enough for the messages we handle."""
    out: Dict[int, list] = {}
    i = 0
    while i < len(data):
        key, i = _read_varint(data, i)
        field = key >> 3
        wire = key & 7
        if wire == 0:
            val, i = _read_varint(data, i)
        elif wire == 2:
            ln, i = _read_varint(data, i)
            val = data[i:i + ln]
            i += ln
        elif wire == 5:
            val = data[i:i + 4]
            i += 4
        elif wire == 1:
            val = data[i:i + 8]
            i += 8
        else:
            raise ValueError(f"unsupported wire type {wire}")
        out.setdefault(field, []).append(val)
    return out


def _read_varint(data: bytes, i: int) -> Tuple[int, int]:
    shift = 0
    result = 0
    while True:
        b = data[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, i
        shift += 7


# ---- device id ----------------------------------------------------------
def deal_device_id(identifier: bytes) -> bytes:
    """BleUtil.dealDeviceId: reverse the byte order AND bitwise-NOT each byte.

    Verified against capture: dealDeviceId(7ca375d094f1) == 0e6b2f8a5c83.
    """
    return bytes((~b) & 0xFF for b in reversed(identifier))


def mac_str_to_bytes(mac: str) -> bytes:
    """Utils.getBytesFromAddress('AA:BB:..') -> raw 6 bytes."""
    return bytes.fromhex(mac.replace(":", ""))


# ---- LinkProtocol -------------------------------------------------------
def link_protocol(identifier: bytes, cmd: int, data: bytes = b"") -> bytes:
    """LinkProtocol{device_id=dealDeviceId(id), cmd, data}."""
    out = pb_bytes(1, deal_device_id(identifier))
    out += pb_varint(2, cmd)
    if data:
        out += pb_bytes(3, data)
    return out


@dataclass
class LinkMessage:
    device_id: bytes
    cmd: int
    data: bytes


def parse_link_protocol(raw: bytes) -> LinkMessage:
    f = pb_parse(raw)
    return LinkMessage(
        device_id=f.get(1, [b""])[0],
        cmd=f.get(2, [0])[0],
        data=f.get(3, [b""])[0],
    )


# ---- sub-messages -------------------------------------------------------
def write_switch_key(key: bytes, info: bytes) -> bytes:
    """WriteSwitchKey{key, info}."""
    return pb_bytes(1, key) + pb_bytes(2, info)


def parse_write_switch_key(raw: bytes) -> Tuple[bytes, bytes]:
    f = pb_parse(raw)
    return f.get(1, [b""])[0], f.get(2, [b""])[0]


def write_switch_info(info: bytes, code: int = 0) -> bytes:
    """WriteSwitchInfo{code, info}."""
    out = b""
    if code:
        out += pb_varint(1, code)
    out += pb_bytes(2, info)
    return out


def parse_write_switch_info(raw: bytes) -> bytes:
    return pb_parse(raw).get(2, [b""])[0]


def device_info(bt_mac: str, company_id: str, category_id: str, model_id: str,
                name: str, battery: int, bt_status: int = 0) -> bytes:
    """DeviceInfo{btMac, companyId, categoryId, modelId, name, battery, btStatus}."""
    out = pb_string(1, bt_mac)
    out += pb_string(2, company_id)
    out += pb_string(3, category_id)
    out += pb_string(4, model_id)
    out += pb_bytes(5, name.encode("utf-8"))
    if battery:
        out += pb_varint(6, battery)
    if bt_status:
        out += pb_varint(7, bt_status)
    return out


def parse_device_info(raw: bytes) -> dict:
    f = pb_parse(raw)

    def s(n):
        v = f.get(n)
        return v[0].decode("utf-8", "replace") if v else ""

    return {
        "btMac": s(1),
        "companyId": s(2),
        "categoryId": s(3),
        "modelId": s(4),
        "name": s(5),
        "battery": f.get(6, [0])[0],
        "btStatus": f.get(7, [0])[0],
    }

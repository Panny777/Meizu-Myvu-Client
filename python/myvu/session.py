"""RunAsOne application-session layer (external channel, plaintext).

After the ECDH bond, the glasses stay in "open the app" state until they receive
the RunAsOne `ability`/session handshake on the EXTERNAL characteristic. Unlike
the pairing channel, the external channel is NOT encrypted, so this is just an
envelope + JSON.

Auth (ability) message wire format, reconstructed from the capture
(frames 499+500 on handle 0x0026):

    0x02                              # message class = AUTH
    protobuf {
        3: bytes  deviceId (hex string of our 6-byte identifier)
        4: bytes  AuthBean JSON  (the ability negotiation)
        7: bytes  "1.2"          (protocol version tag)
        9: bytes  "timestamp-<epoch_ms>"
    }

The AuthBean JSON template is taken verbatim from the captured phone so the
glasses see an ability set they already accept; only identity fields are patched.
"""
from __future__ import annotations

import json
import logging
import time

from . import linkproto

AUTH_CLASS_BYTE = 0x02

# StreamReq.StreamType values (runasone_api.proto)
STREAM_AUTH = 0
STREAM_AUTH_SUCCESS = 12

# ability attributes exactly as the official app advertised them
_ABILITY_ATTRS = {
    "abilityAttributes": {
        "abilityRelay": json.dumps({
            "agreementType": 0,
            "json": json.dumps(
                {"isSupportMapping": False, "metaInfo": [], "metaMap": {}}),
            "supportTlv": True,
        }),
        "abilityAir": json.dumps({
            "agreementType": 0,
            "json": json.dumps({"airMapping": {
                "1": "com.upuphone.star.launcher",
                "2": "com.upuphone.thanos.sdk_test",
            }}),
            "supportTlv": True,
        }),
    }
}


def build_auth_bean(device_id_hex: str, device_name: str, session: str,
                    version: str = "2.40.51", weight: int = 233333) -> dict:
    return {
        "ability": ["abilityRelay", "abilityRelayBypass", "abilityAir", "abilityShare"],
        "abilityAttributes": _ABILITY_ATTRS,
        "agreementType": 0,
        "deviceId": device_id_hex,
        "deviceName": device_name,
        "session": session,
        "supportTlv": True,
        "supportVirtual": False,
        "version": version,
        "weight": weight,
    }


def _build_stream_req(stream_type: int, device_id_hex: str, device_name: str,
                      session: str) -> bytes:
    """0x02-class StreamReq (runasone_api.proto) carrying the AuthBean.

    Fields: 1=type, 3=deviceId, 4=reqInfo(AuthBean JSON), 7=protocolVersion,
    9=timeStamp, 12=deltaSysTime.
    """
    bean = build_auth_bean(device_id_hex, device_name, session)
    bean_json = json.dumps(bean, separators=(",", ":")).encode("utf-8")
    now_ms = int(time.time() * 1000)
    ts = f"timestamp-{now_ms}".encode("ascii")

    body = b""
    if stream_type:  # type 0 (AUTH) is the proto default and is omitted
        body += linkproto.pb_varint(1, stream_type)
    body += linkproto.pb_bytes(3, device_id_hex.encode("ascii"))
    body += linkproto.pb_bytes(4, bean_json)
    body += linkproto.pb_bytes(7, b"1.2")
    body += linkproto.pb_bytes(9, ts)
    if stream_type == STREAM_AUTH_SUCCESS:
        body += linkproto.pb_varint(12, now_ms)
    return bytes([AUTH_CLASS_BYTE]) + body


def build_ability_message(device_id_hex: str, device_name: str,
                          session: str) -> bytes:
    """Phase 1: StreamReq type=AUTH (the initial ability handshake)."""
    return _build_stream_req(STREAM_AUTH, device_id_hex, device_name, session)


def build_auth_success_message(device_id_hex: str, device_name: str,
                               session: str) -> bytes:
    """Phase 2: StreamReq type=AUTH_SUCCESS. Sent after the glasses reply with
    their AuthBean; this confirm is what makes the glasses fully engage and
    start streaming app data (mirrors capture frame 509)."""
    return _build_stream_req(STREAM_AUTH_SUCCESS, device_id_hex, device_name, session)


def parse_ability_reply(payload: bytes) -> dict:
    """Decode a glasses auth reply (same 0x02-class envelope). Best-effort: an
    unexpected/misaligned frame (e.g. telemetry arriving where the reply was
    expected) must not crash the handshake -- we send a static AUTH_SUCCESS
    afterward regardless, so on a parse failure we just return an empty dict."""
    if payload and payload[0] == AUTH_CLASS_BYTE:
        payload = payload[1:]
    try:
        f = linkproto.pb_parse(payload)
    except Exception as exc:  # noqa: BLE001
        logging.getLogger("myvu.session").warning(
            "could not parse ability reply (%s); continuing handshake", exc)
        return {"deviceId": ""}
    out = {"deviceId": ""}
    if 3 in f:
        out["deviceId"] = f[3][0].decode("utf-8", "replace")
    if 4 in f:
        try:
            out["authBean"] = json.loads(f[4][0].decode("utf-8", "replace"))
        except Exception:  # noqa: BLE001
            out["authBean"] = f[4][0].decode("utf-8", "replace")
    return out

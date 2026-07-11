"""Transport-agnostic app layer: relay sequencing, StMessage envelopes, and
the feature helpers (notifications, teleprompter). Shared by the BLE client
(client.py) and the classic-BT RFCOMM client (rfcomm_client.py) -- both carry
byte-identical relay/StMessage payloads, only the underlying transport differs.

A subclass must set self.seq (relay.RelaySequencer), self.own_id (6 bytes),
self.device_name, self.peer_info (dict), and implement `_transport_send`.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from typing import List

from . import linkproto, relay, tlv

log = logging.getLogger("myvu")


def _find_json_objects(s: str) -> List[str]:
    """Balanced-brace scan for embedded {...} objects (handles nesting)."""
    out: List[str] = []
    depth = 0
    start = -1
    for i, ch in enumerate(s):
        if ch == "{":
            if depth == 0:
                start = i
            depth += 1
        elif ch == "}" and depth > 0:
            depth -= 1
            if depth == 0 and start >= 0:
                out.append(s[start:i + 1])
    return out


class AppLayerMixin:
    TICI_PKG = "com.upuphone.ar.tici"

    async def _transport_send(self, frame: bytes) -> None:
        raise NotImplementedError

    # --------------------------------------------------------------- relay
    async def send_relay_data(self, msg_body: bytes, need_callback: int = 1,
                              category: int = relay.DEFAULT_CATEGORY,
                              app_unite_code: int = 1) -> int:
        """Send one app message through the relay layer with the next msgId."""
        frame = self.seq.data_frame(msg_body, category, need_callback, app_unite_code)
        await self._transport_send(frame)
        return self.seq.out_id

    async def send_action(self, action_json: str,
                          target_pkg: str = "com.upuphone.star.launcher",
                          source_pkg: str = "com.upuphone.star.launcher") -> int:
        """Wrap an app-action JSON in the StMessage envelope {2:src,3:dst,4:json,
        6:id} and send it through the relay layer. This is how you drive the
        glasses (notifications, teleprompter, AI, system commands)."""
        self.app_msg_id = getattr(self, "app_msg_id", 5000) + 1
        body = linkproto.pb_string(2, source_pkg)
        body += linkproto.pb_string(3, target_pkg)
        body += linkproto.pb_string(4, action_json)
        body += linkproto.pb_varint(6, self.app_msg_id)
        mid = await self.send_relay_data(body)
        log.debug("ACTION -> msgId=%d %s", mid, action_json)  # full detail -> file
        summary = action_json if len(action_json) <= 70 else action_json[:70] + "..."
        log.info("ACTION -> msgId=%d %s", mid, summary)       # short summary -> console+file
        return mid

    # --------------------------------------------------------- teleprompter
    async def open_teleprompter(self, text: str, title: str = "Prompter") -> None:
        """Open the teleprompter on the glasses and load `text` (mirrors the
        capture's open_app scene + tici send_content flow)."""
        file_key = f"1/{title}"
        self.tici_file_key = file_key
        ext = {
            "blockNotification": True, "currentPage": 0, "fileKey": file_key,
            "msgId": str(uuid.uuid4()), "nextTotalParagraphSize": 0,
            "paragraphIndex": 0, "prevTotalParagraphSize": 0, "screenLocation": 0,
            "sourceByteSize": len(text.encode("utf-8")), "sourceTextOffset": 0,
            "ticiMode": 0, "ticiSpeed": 10000, "totalPage": 1, "totalPart": 1,
            "totalTextLength": len(text), "version": 2,
        }
        open_msg = {"action": "app", "data": {
            "launchMode": "scene", "action": "open_app", "pkg": self.TICI_PKG,
            "app_name": self.TICI_PKG, "ext": json.dumps(ext)}}
        await self.send_action(json.dumps(open_msg, separators=(",", ":")),
                               source_pkg=self.TICI_PKG)
        await asyncio.sleep(0.4)
        content = {"currentPage": 0, "fileKey": file_key,
                   "msgId": str(uuid.uuid4()), "part": 0, "sourceText": text}
        send_msg = {"action": "tici", "data": {
            "action": "send_content", "value": json.dumps(content)}}
        await self.send_action(json.dumps(send_msg, separators=(",", ":")),
                               source_pkg=self.TICI_PKG)

    async def teleprompter_highlight(self, index: int) -> None:
        """Scroll/highlight the teleprompter to paragraph `index`."""
        fk = getattr(self, "tici_file_key", "1/Prompter")
        hl = {"action": "tici", "data": {"action": "highlight_index",
              "value": json.dumps({"index": index, "fileKey": fk})}}
        await self.send_action(json.dumps(hl, separators=(",", ":")),
                               source_pkg=self.TICI_PKG)

    # -------------------------------------------------------------- system
    async def set_volume(self, value: int, stream_type: int = 3) -> None:
        """Set the glasses' volume (0-15). streamType 3 matches the value
        observed in captured telemetry; SuperMessageManger.z0() in the
        official app sends the same shape."""
        payload = {"action": "system", "data": {
            "action": "set_volume", "value": str(value),
            "streamType": stream_type, "needReply": False}}
        await self.send_action(json.dumps(payload, separators=(",", ":")))

    async def set_brightness(self, value: int) -> None:
        """Set the glasses' screen brightness (observed range roughly 0-10).
        Matches SuperMessageManger.n0() in the official app."""
        payload = {"action": "system", "data": {
            "action": "set_brightness", "value": str(value)}}
        await self.send_action(json.dumps(payload, separators=(",", ":")))

    # ------------------------------------------------------------- notify
    async def push_notification(self, title: str, content: str,
                                app_name: str = "ARIA") -> None:
        """Show a notification on the lens."""
        payload = {
            "action": "notification",
            "data": {
                "notificationAction": "SHOW_NOTIFICATION",
                "data": [{
                    "appName": app_name, "title": title, "content": content,
                    "canReply": False, "type": "MSG_TYPE_NORMAL",
                    "id": f"phone-python-{int(time.time())}",
                    "packageName": "com.python.client",
                    "crateTime": int(time.time() * 1000), "extra": "{}",
                }],
            },
        }
        await self.send_action(json.dumps(payload, separators=(",", ":")))

    # -------------------------------------------------------------- receive
    async def _on_relay_frame(self, payload: bytes) -> None:
        """Handle an inbound app message: decode the relay frame, ACK the
        glasses' data messages, and print the app JSON."""
        m = relay.parse_frame(payload)
        if m is None:
            self._on_app_message(payload)
            return
        if m.msg_type == tlv.MSG_SEND_SUCCESS:
            log.debug("ACK <- glasses acked our msgId=%d", m.msg_id)
            return
        if m.msg_type == tlv.MSG_SEND:
            if m.msg_id > self.seq.last_recv_id:
                self.seq.last_recv_id = m.msg_id
            if m.need_callback:
                await self._transport_send(self.seq.ack_frame(m))
            log.debug("GLASSES msg#%d:", m.msg_id)
            self._on_app_message(m.msg_body)
            return
        log.debug("relay <- msgType=%d msgId=%d", m.msg_type, m.msg_id)

    def _on_app_message(self, payload: bytes) -> None:
        """Log any JSON carried in an inbound message. This is high-volume
        telemetry (key presses, battery stats, event tracking, ...) as well
        as real command replies, and there's no reliable way to tell them
        apart automatically -- so it all goes to the log file (DEBUG) only.
        Check myvu.log if you need to see what the glasses sent back."""
        text = payload.decode("utf-8", "replace")
        objs = _find_json_objects(text)
        if objs:
            for o in objs:
                if len(o) > 4:
                    log.debug("APP <- %s", o)
        else:
            log.debug("APP <- (raw %d B) %s", len(payload), payload.hex())

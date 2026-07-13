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
import os
import time
import uuid
from typing import List

from . import linkproto, relay, tlv

try:
    import anthropic
except ImportError:  # optional dependency, only needed for ask_ai()
    anthropic = None

log = logging.getLogger("myvu")

AI_PKG = "com.upuphone.ai.assistant"


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

    @staticmethod
    def _load_init_script() -> List["tuple[str, str, bytes]"]:
        path = os.path.join(os.path.dirname(os.path.dirname(__file__)),
                            "captured_init.txt")
        out = []
        with open(path) as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                frame, kind, hexdata = line.split("\t")
                out.append((frame, kind, bytes.fromhex(hexdata)))
        return out

    async def send_init_burst(self, delay: float = 0.2) -> None:
        """Rebuild the captured init messages through the relay layer with fresh
        SEQUENTIAL msgIds (1,2,3...). This is the fix: the glasses discard the
        capture's stale high msgIds as out-of-order, but accept a clean sequence.
        Data messages (msgType=3) are resent; captured ACKs are skipped (we ACK
        the glasses' live messages dynamically instead). Required on every
        transport (BLE and classic-BT/RFCOMM alike) -- without it the glasses'
        relay dispatcher never fully wakes up and silently drops later app
        messages (no ACK, no visible effect) even though the channel itself is
        connected and the ability handshake succeeds."""
        script = self._load_init_script()
        sent = 0
        for frame, _kind, content in script:
            if not self.is_connected:
                log.error("LINK DROPPED before f%s", frame)
                return
            m = relay.parse_frame(content)
            if m is None or m.msg_type != tlv.MSG_SEND:
                continue  # skip non-data (e.g. the one captured ACK)
            mid = await self.send_relay_data(
                m.msg_body, m.need_callback, m.category, m.app_unite_code)
            body_text = m.msg_body.decode("utf-8", "replace")
            log.debug("   -> msgId=%d (f%s, %dB) %s", mid, frame, len(m.msg_body), body_text)
            sent += 1
            await asyncio.sleep(delay)
        log.info("Initialized (%d messages sent).", sent)
        log.debug("init burst done, link %s",
                  "up" if self.is_connected else "DOWN")

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

    async def query(self, sub_action: str) -> None:
        """Send any no-argument 'system' query (e.g. get_device_info,
        get_language, get_zen_mode, get_air_mode, get_screen_off_time,
        get_wear_detection_mode, get_music_tp_control_mode, get_network_valid,
        request_wifi_list, request_phone_battery, get_glass_log,
        get_standby_widget_lists). The reply lands in myvu.log under the next
        'GLASSES msg#N' / 'APP <-' line -- there's no synchronous return here."""
        payload = {"action": "system", "data": {"action": sub_action}}
        await self.send_action(json.dumps(payload, separators=(",", ":")))

    async def toggle_wifi(self, enable: bool) -> None:
        """Turn the glasses' own WiFi radio on/off.
        Matches SuperMessageManger.B0() in the official app."""
        payload = {"action": "system", "data": {
            "action": "toggle_wifi", "value": enable}}
        await self.send_action(json.dumps(payload, separators=(",", ":")))

    async def set_standby_position(self, position: int) -> None:
        """Set the field-of-view position of the standby widgets shown while
        the glasses are idle/on standby. Confirmed range: 0-3. Matches
        SuperMessageManger.v0() in the official app."""
        payload = {"action": "system", "data": {
            "action": "set_standby_position", "value": {"standby_position": position}}}
        await self.send_action(json.dumps(payload, separators=(",", ":")))

    async def set_fov_pos_type(self, value: int) -> None:
        """Set the field-of-view display position type (enum meaning not
        reverse-engineered). Matches SuperMessageManger.u0() in the official
        app."""
        payload = {"action": "system", "data": {
            "action": "set_fov_pos_type", "value": {"fov_pos": value}}}
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

    # ----------------------------------------------------------------- AI
    async def _generate_ai_answer(self, question: str) -> str:
        """Call the Claude API for a short, speakable answer. Requires the
        'anthropic' package and an API key (ANTHROPIC_API_KEY env var, or an
        `ant auth login` profile)."""
        if anthropic is None:
            raise RuntimeError(
                "ask_ai needs the 'anthropic' package: pip install anthropic")
        client = getattr(self, "_ai_client", None)
        if client is None:
            client = anthropic.AsyncAnthropic()
            self._ai_client = client
        response = await client.messages.create(
            model="claude-haiku-4-5",
            max_tokens=1024,
            system=(
                "You are a voice assistant embedded in AR smart glasses, standing "
                "in for the glasses' real AI assistant. Answer the user's question "
                "the way a spoken assistant would: conversational and concise "
                "(1-3 sentences). No markdown, no headers, no bullet lists -- this "
                "text is displayed as an on-screen caption."
            ),
            messages=[{"role": "user", "content": question}],
        )
        return "".join(b.text for b in response.content if b.type == "text").strip()

    async def ask_ai(self, question: str) -> str:
        """Drive the glasses AI-assistant UI using the real protocol observed in
        the BT capture (btcsnoop_hci_full_session.log).

        Real sequence confirmed from capture (phone→glasses unless noted):
          code:104 {type:1, sessionId}        — VAD start (mic detected speech)
          code:101 {id, text, type:0} × N     — ASR partials (streaming words)
          code:104 {type:2, sessionId}        — VAD end (speech finished)
          code:101 {id, text, type:1}         — ASR final result
          code:5   {ttsData:{text}}  ← FROM GLASSES (real NLU result; we send ours)
          code:6   {id:"", playState:1}       — TTS playing
          code:6   {id:"", playState:2}       — TTS done

        Key differences from the old (broken) implementation:
        - code:104 type:1/2 (VAD start/end) are REQUIRED — missing them leaves
          the glasses UI stuck waiting and showing 'service error'.
        - code:5 (TTS content) is sent by us with our generated answer text.
        - id field in code:6 is empty string "" (not a UUID) as seen in capture.
        - code:101 type:1 (final ASR) is sent before calling Claude so the
          glasses exit 'listening' state immediately.
        """
        session_id = str(uuid.uuid4())

        async def send_code(code: int, payload) -> None:
            msg = {"code": code, "payload": payload}
            await self.send_action(json.dumps(msg, separators=(",", ":")),
                                   source_pkg=AI_PKG, target_pkg=AI_PKG)

        # 1. VAD start — mic detected speech beginning
        await send_code(104, {"type": 1, "sessionId": session_id})
        await asyncio.sleep(0.1)

        # 2. ASR final result — send immediately so glasses exit 'listening' UI
        #    (type:1 = final; the glasses need this before their internal timeout)
        await send_code(101, {"id": session_id, "isOfflineResult": False,
                              "text": question, "type": 1})
        await asyncio.sleep(0.1)

        # 3. VAD end — speech finished, NLU processing begins
        await send_code(104, {"type": 2, "sessionId": session_id})

        # 4. Generate answer while glasses show the question text
        answer = await self._generate_ai_answer(question)

        # 5. TTS content — we send our generated text as the AI response
        await send_code(5, {"id": "", "isContinuous": False, "isMulti": False,
                            "isWakeup": False,
                            "wakeupControl": {"control": 6, "muteTimeout": 2000,
                                              "scene": "", "extra": ""},
                            "ttsData": {"text": answer, "isChatGpt": False,
                                        "nextStep": 0}})
        await asyncio.sleep(0.1)

        # 6. TTS playState:1 (playing) then playState:2 (done)
        await send_code(6, {"id": "", "isContinuous": False, "isMulti": False,
                            "isWakeup": False, "playState": 1})
        await asyncio.sleep(0.2)
        await send_code(6, {"id": "", "isContinuous": False, "isMulti": False,
                            "isWakeup": False, "playState": 2})
        await asyncio.sleep(0.2)

        # 7. Back to idle
        await send_code(107, {"control": 4, "isOffline": False})
        return answer

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
        Check myvu.log if you need to see what the glasses sent back.

        Special case: code:3 control:1 is the AI-button trigger sent by the
        glasses when the user presses the dedicated AI key. We surface it at
        INFO level and fire self._ai_button_callback() if one is registered.
        """
        text = payload.decode("utf-8", "replace")
        objs = _find_json_objects(text)
        if objs:
            for o in objs:
                if len(o) > 4:
                    log.debug("APP <- %s", o)
                    self._check_ai_trigger(o)
        else:
            log.debug("APP <- (raw %d B) %s", len(payload), payload.hex())

    def _check_ai_trigger(self, json_str: str) -> None:
        """Detect code:3 control:1 (AI button pressed on glasses) and fire
        the registered callback, if any. code:3 control:0 means the glasses
        stopped listening (timeout or button released)."""
        try:
            msg = json.loads(json_str)
        except (json.JSONDecodeError, ValueError):
            return
        if msg.get("code") != 3:
            return
        control = msg.get("payload", {}).get("control")
        if control == 1:
            log.info("AI button pressed on glasses -- use 'ask <question>' or set a callback")
            cb = getattr(self, "_ai_button_callback", None)
            if cb is not None:
                asyncio.create_task(cb())
        elif control == 0:
            log.debug("AI button released / glasses stopped listening (code:3 control:0)")

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

    async def sync_time(self) -> None:
        """Push the local wall-clock time + UTC offset to the glasses so their
        clock matches this PC. Mirrors SuperMessageManger.sendOffsetTimeToGlass
        (k0) in the official app: action 'SyncOffSetTime' with data
        {syncTimeData: epoch-millis-as-string, timeZoneOffSet: offset-in-millis}.
        The glasses request this themselves (they send 'SyncOffSetTime' with no
        data on connect), but sending it proactively also works."""
        import time as _time
        now = _time.time()
        epoch_ms = int(now * 1000)
        # local UTC offset in milliseconds (e.g. UTC+3 -> 10800000)
        offset_ms = -_time.timezone * 1000
        if _time.localtime(now).tm_isdst and _time.daylight:
            offset_ms = -_time.altzone * 1000
        payload = {"action": "SyncOffSetTime", "data": {
            "syncTimeData": str(epoch_ms), "timeZoneOffSet": offset_ms}}
        await self.send_action(json.dumps(payload, separators=(",", ":")))
        log.info("synced time to glasses: %s (offset %+d min)",
                 _time.strftime("%Y-%m-%d %H:%M:%S", _time.localtime(now)),
                 offset_ms // 60000)

    async def set_brightness(self, value: int) -> None:
        """Set the glasses' screen brightness (observed range roughly 0-10).
        Matches SuperMessageManger.n0() in the official app."""
        payload = {"action": "system", "data": {
            "action": "set_brightness", "value": str(value)}}
        await self.send_action(json.dumps(payload, separators=(",", ":")))

    async def _system_set(self, action: str, value) -> None:
        """Send a launcher 'system' setting whose params nest under a 'value'
        object: {"action":"system","data":{"action":<action>,"value":<value>}}.
        This is the shape ControlUtils uses for the settings below (RESPONSE_VALUE
        == "value"). NB: set_volume/set_brightness use a *flat* string value and
        are handled separately -- don't route those through here."""
        payload = {"action": "system", "data": {"action": action, "value": value}}
        await self.send_action(json.dumps(payload, separators=(",", ":")))

    async def set_language(self, language: str, country: str) -> None:
        """Set the glasses' language/country (ControlUtils.set_language). e.g.
        language='en', country='US' or language='zh', country='CN'."""
        await self._system_set("set_language",
                               {"language": language, "country": country})

    async def set_device_name(self, name: str) -> None:
        """Rename the glasses (ControlUtils.set_device_name)."""
        await self._system_set("set_device_name", {"device_name": name})

    async def set_screen_off_time(self, seconds: int) -> None:
        """Set the display auto-off timeout in seconds
        (ControlUtils.set_screen_off_time)."""
        await self._system_set("set_screen_off_time",
                               {"screen_off_time": seconds})

    async def set_zen_mode(self, on: bool) -> None:
        """Toggle do-not-disturb / zen mode (ControlUtils.set_zen_mode)."""
        await self._system_set("set_zen_mode", {"zen_mode": on})

    async def set_air_mode(self, on: bool) -> None:
        """Toggle 'Air Mode' (ControlUtils.set_air_mode) -- MYVU's minimal
        mode. Per the app's own confirm dialog, enabling it CLOSES ALL APPS
        and may restrict certain functions (a stripped-back low-power HUD),
        not airplane mode."""
        await self._system_set("set_air_mode", {"air_mode": on})

    async def set_wear_detection(self, on: bool) -> None:
        """Toggle auto on/off when the glasses are worn/removed
        (ControlUtils.set_wear_detection_mode)."""
        await self._system_set("set_wear_detection_mode",
                               {"wear_detection_mode": on})

    async def set_music_tp_control(self, on: bool) -> None:
        """Toggle music touch-panel control mode
        (ControlUtils.set_music_tp_control_mode)."""
        await self._system_set("set_music_tp_control_mode",
                               {"music_tp_control_mode": on})

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
        # Pre-generate so the whole code: sequence fires without a gap (a Claude
        # call mid-flow leaves a hole that trips the glasses' timeout).
        answer = await self._generate_ai_answer(question)
        await self.ai_session_ack(session_id)
        await self.ai_send_recognized(session_id, question)
        await self.ai_send_answer(answer)
        return answer

    async def _ai_send_code(self, code: int, payload) -> None:
        """Send one AI-assistant code: message through the relay (ai.assistant
        source and dest, matching the capture)."""
        msg = {"code": code, "payload": payload}
        await self.send_action(json.dumps(msg, separators=(",", ":")),
                               source_pkg=AI_PKG, target_pkg=AI_PKG)

    async def ai_session_ack(self, session_id: str) -> None:
        """code:4 -- the phone's response to the glasses' AI-button press. THE
        message the glasses wait for; without it they show 'service error'.
        Reconstructed from btcsnoop_hci_full_session.log. Send this promptly
        after the button press (before doing slow work like recording/STT) so
        the session stays alive."""
        await self._ai_send_code(4, {"hasNetwork": True, "message": "唤醒成功",
                                     "sessionId": session_id, "success": True})
        await asyncio.sleep(0.1)

    async def ai_send_recognized(self, session_id: str, text: str,
                                 stream: bool = True,
                                 word_delay: float = 0.35) -> None:
        """VAD start -> streaming ASR partials -> final -> VAD end.

        The real glasses render ASR as a *growing* caption: a series of code:101
        type:0 partial results, each with a longer prefix of the recognized
        text, while you're still speaking, then one type:1 final. If we instead
        send the whole sentence as a single partial+final back-to-back, the
        caption just flashes and disappears. Since Groq gives us the full text
        at once, we simulate the stream by emitting growing word-by-word
        partials with a small delay so the caption stays visible and builds up.
        Set stream=False for the old one-shot behavior."""
        await self._ai_send_code(104, {"type": 1, "sessionId": session_id})
        await asyncio.sleep(0.1)
        words = text.split()
        if stream and len(words) > 1:
            partial = ""
            for w in words:
                partial = f"{partial} {w}".strip()
                await self._ai_send_code(101, {"id": session_id,
                                               "isOfflineResult": False,
                                               "text": partial, "type": 0})
                await asyncio.sleep(word_delay)
        else:
            await self._ai_send_code(101, {"id": session_id,
                                           "isOfflineResult": False,
                                           "text": text, "type": 0})
            await asyncio.sleep(0.1)
        await self._ai_send_code(101, {"id": session_id, "isOfflineResult": False,
                                       "text": text, "type": 1})
        await asyncio.sleep(0.2)
        await self._ai_send_code(104, {"type": 2, "sessionId": session_id})
        await asyncio.sleep(0.1)

    async def ai_send_answer(self, answer: str, speak=None) -> None:
        """Deliver the AI answer. The real glasses SPEAK it over A2DP (confirmed
        from the capture -- only ASR captions + play-state come over the relay,
        no answer text). So if `speak` is given (an async callable that plays
        TTS out the glasses' A2DP speaker), we send code:6 playState:1, await
        `speak` (the answer is spoken), then playState:2 -- matching the real
        flow. If `speak` is None we fall back to a notification (text stand-in),
        unless ai_answer_as_notification is False.

        code:5 (ChatGPT-style card) is still sent first in case the glasses
        render a text card too; it's harmless if they don't."""
        await self._ai_send_code(5, {"id": "", "isContinuous": False, "isMulti": False,
                                     "isWakeup": False,
                                     "wakeupControl": {"control": 6, "muteTimeout": 2000,
                                                       "scene": "", "extra": ""},
                                     "ttsData": {"text": answer, "isChatGpt": True,
                                                 "nextStep": 0}})
        await asyncio.sleep(0.05)
        await self._ai_send_code(6, {"id": "", "isContinuous": False, "isMulti": False,
                                     "isWakeup": False, "playState": 1})
        spoke = False
        if speak is not None:
            try:
                spoke = bool(await speak(answer))
            except Exception as e:  # noqa: BLE001
                log.warning("TTS playback failed: %s", e)
        else:
            await asyncio.sleep(0.2)
        await self._ai_send_code(6, {"id": "", "isContinuous": False, "isMulti": False,
                                     "isWakeup": False, "playState": 2})
        await asyncio.sleep(0.2)
        await self._ai_send_code(107, {"control": 4, "isOffline": False})
        # If we couldn't speak it, fall back to the visible notification.
        if not spoke and getattr(self, "ai_answer_as_notification", True):
            await asyncio.sleep(0.3)
            await self.push_notification("AI", answer, app_name="AI")

    # ----------------------------------------------------------- mic capture
    async def capture_mic(self, seconds: float = 6.0,
                          out_path: str = "mic_capture.bin") -> dict:
        """Capture raw microphone audio streamed by the glasses.

        The glasses only stream mic audio after the AI button is pressed AND
        the phone acks the session with code:4. So this arms an audio collector,
        temporarily makes the AI-button press send *only* code:4 (session ack,
        no ASR faking) so the glasses start streaming, waits `seconds`, then
        saves the raw audio.

        The mic audio arrives as code:109 StMessages with the binary chunk in
        protobuf field 5 (~242 B each, ~50ms apart) -- see _maybe_capture_audio.
        The user must PRESS THE AI BUTTON and speak during the window.

        Returns {frames, bytes, path}; also logs the first frame's head so the
        codec can be identified from the concatenated output file.
        """
        # Arm the collector on THIS client and on a sibling client if one is
        # registered (run_glasses.py sets rf._sibling = ble): the glasses may
        # stream the mic audio over the BLE channel rather than the classic-BT
        # relay, and we don't know which up front -- so listen on both. Both
        # point at the SAME list so frames land in one buffer regardless.
        frames_buf: list = []
        self._mic_capture = frames_buf
        sibling = getattr(self, "_sibling", None)
        if sibling is not None:
            sibling._mic_capture = frames_buf
        session_id = str(uuid.uuid4())
        prev_cb = getattr(self, "_ai_button_callback", None)

        async def _capture_button_cb():
            msg = {"code": 4, "payload": {"hasNetwork": True, "message": "唤醒成功",
                                          "sessionId": session_id, "success": True}}
            await self.send_action(json.dumps(msg, separators=(",", ":")),
                                   source_pkg=AI_PKG, target_pkg=AI_PKG)
            log.info("capture: sent code:4 session ack -- glasses should start "
                     "streaming mic audio now; keep speaking")

        self._ai_button_callback = _capture_button_cb
        sib_prev_cb = getattr(sibling, "_ai_button_callback", None) if sibling else None
        if sibling is not None:
            sibling._ai_button_callback = _capture_button_cb
        try:
            log.info(">>> PRESS THE AI BUTTON AND SPEAK NOW (%.0fs window) <<<", seconds)
            await asyncio.sleep(seconds)
        finally:
            self._ai_button_callback = prev_cb
            self._mic_capture = None
            if sibling is not None:
                sibling._ai_button_callback = sib_prev_cb
                sibling._mic_capture = None
            frames = frames_buf

        audio = b"".join(frames)
        with open(out_path, "wb") as fh:
            fh.write(audio)
        sizes = [len(x) for x in frames]
        if frames:
            log.info("captured %d frames, %d bytes -> %s  (sizes min/max/avg=%d/%d/%.0f)",
                     len(frames), len(audio), out_path,
                     min(sizes), max(sizes), sum(sizes) / len(sizes))
            log.info("first frame head (hex): %s", frames[0][:32].hex())
            log.info("second frame head (hex): %s",
                     frames[1][:32].hex() if len(frames) > 1 else "(only one frame)")
        else:
            log.warning("captured NO audio frames -- did the AI button get pressed? "
                        "did code:4 go out? is the connection the classic-BT relay?")
        return {"frames": len(frames), "bytes": len(audio), "path": out_path}

    def _maybe_capture_audio(self, payload: bytes) -> bool:
        """If a mic capture is armed and `payload` is a code:109 mic-audio
        StMessage, extract the binary audio (protobuf field 5) into the buffer.
        Returns True if this was a code:109 audio frame (so the caller can skip
        the normal JSON logging -- there are hundreds of these per utterance)."""
        cap = getattr(self, "_mic_capture", None)
        if cap is None:
            return False
        try:
            f = linkproto.pb_parse(payload)
        except Exception:  # noqa: BLE001
            return False
        json_field = f.get(4, [b""])[0]
        if b'"code":109' not in json_field:
            return False
        audio = f.get(5, [None])[0]
        if audio:
            cap.append(audio)
        return True

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
        if self._maybe_capture_audio(payload):
            return  # code:109 mic-audio frame -- collected, skip JSON logging
        text = payload.decode("utf-8", "replace")
        objs = _find_json_objects(text)
        if objs:
            for o in objs:
                if len(o) > 4:
                    log.debug("APP <- %s", o)
                    self._check_ai_trigger(o)
                    self._check_time_sync_request(o)
        else:
            log.debug("APP <- (raw %d B) %s", len(payload), payload.hex())

    def _check_time_sync_request(self, json_str: str) -> None:
        """The glasses ask for the wall-clock time by sending a launcher action
        'SyncOffSetTime' with no data (SuperMessageManger case -1932804357 ->
        k0() in the official app). Reply with the real time. We ignore messages
        that already carry 'syncTimeData' so we never echo our own reply."""
        try:
            msg = json.loads(json_str)
        except (json.JSONDecodeError, ValueError):
            return
        if msg.get("action") != "SyncOffSetTime":
            return
        data = msg.get("data")
        if isinstance(data, dict) and data.get("syncTimeData"):
            return  # this is a time payload (ours), not a request
        log.info("glasses requested time sync -- replying")
        asyncio.create_task(self.sync_time())

    def _check_ai_trigger(self, json_str: str) -> None:
        """Fire the assistant callback when the glasses ask to start listening.

        Two triggers, both handled the same way:
          * code:3 (CODE_START_VR_REQ) control:1 -- the AI button was pressed.
            control:0 means they stopped listening (timeout / button released).
          * code:7 (CODE_VOICE_WAKEUP_VR_REQ) -- the low-power WAKE WORD
            ("小溪小溪") fired on the glasses' own DSP. The real phone re-verifies
            this with a chipset SoundTrigger engine we can't run on Windows, so
            we just trust the glasses' first-stage detection and start.
        """
        try:
            msg = json.loads(json_str)
        except (json.JSONDecodeError, ValueError):
            return
        code = msg.get("code")
        control = msg.get("payload", {}).get("control")
        if code == 7:  # wake word -- start, or close (control:0)
            if control == 0:
                rcb = getattr(self, "_ai_button_release_callback", None)
                if rcb is not None:
                    asyncio.create_task(rcb())
                return
            cb = getattr(self, "_ai_button_callback", None)
            log.info("wake word detected on glasses (code:7, callback=%s)",
                     "set" if cb is not None else "NONE -- no handler on this channel")
            if cb is not None:
                asyncio.create_task(cb())
            return
        if code != 3:
            return
        if control == 1:
            cb = getattr(self, "_ai_button_callback", None)
            log.info("AI button pressed on glasses (callback=%s)",
                     "set" if cb is not None else "NONE -- no handler on this channel")
            if cb is not None:
                asyncio.create_task(cb())
        elif control == 0:
            log.debug("AI button released / glasses stopped listening (code:3 control:0)")
            rcb = getattr(self, "_ai_button_release_callback", None)
            if rcb is not None:
                asyncio.create_task(rcb())

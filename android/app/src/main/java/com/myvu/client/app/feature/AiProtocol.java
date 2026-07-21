package com.myvu.client.app.feature;

import com.myvu.client.app.AppLayer;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The AI-assistant wire protocol (com.xjsd.ai.assistant.protocol).
 *
 * Every message is {"code":N,"payload":...}, sourced from AND addressed to
 * com.upuphone.ai.assistant.
 *
 * THE TIMING RULES ARE LOAD-BEARING. The glasses run their own state machine
 * with real timeouts, and violating the order below makes the session die
 * mid-answer or show "service error":
 *
 *  1. CODE_START_VR_RES (4) must be sent IMMEDIATELY on the button press,
 *     before any slow work. It arms an 8s TIMEOUT_LISTENING on the glasses.
 *  2. Only VAD start (104 type:1) clears that timeout -- so it must be fired
 *     from speech onset, not after speech recognition returns.
 *  3. VR_PROCESSION (106 payload 7) must come AFTER the final caption (101
 *     type:1), never before, or the glasses drop the caption frames.
 *  4. Open the LLM scene with 102, commit the answer with 122 status 1 then 2,
 *     and bracket phone-side speech with 6 playState:1 then playState:2.
 *
 * None of these builders declare JSONException: every payload is assembled from
 * literals, so it is unreachable, and declaring it would clutter the call sites
 * -- which read as an ordered protocol sequence and are clearer without it.
 */
public final class AiProtocol {
    private AiProtocol() {}

    public static final String PKG = AppLayer.PKG_AI;

    // ---- codes (CmdCode.java) ----
    public static final int CODE_ASSISTANT_CONFIG = 2;    // phone -> glasses: capability flags
    public static final int CODE_START_VR_REQ = 3;        // glasses -> phone: button
    public static final int CODE_START_VR_RES = 4;        // phone -> glasses: session ack
    public static final int CODE_TTS_PLAY_RES = 6;        // phone -> glasses: play state
    public static final int CODE_VOICE_WAKEUP_VR_REQ = 7; // glasses -> phone: wake word
    public static final int CODE_ASR_TRANS = 101;         // phone -> glasses: caption
    public static final int CODE_VUI = 102;               // phone -> glasses: open LLM scene
    public static final int CODE_VAD_EVENT = 104;         // phone -> glasses: speech bounds
    public static final int CODE_SYNC_VR_STATE = 106;     // phone -> glasses: VrState
    public static final int CODE_HOT_WORD_MANAGER = 107;  // phone -> glasses: end of turn
    public static final int CODE_RECORD_DATA_TRANS = 109; // glasses -> phone: mic audio
    public static final int CODE_CHAT_GPT_RESPONSE = 122; // phone -> glasses: answer text

    // ---- VrState values (protocol/VrState.java) ----
    public static final int VR_CLOSE = 0;
    /** Turn boundary: sent between answers, just before listening again. */
    public static final int VR_MULTI_WAKEUP = 1;
    public static final int VR_TTS_PLAY_START = 3;
    public static final int VR_TTS_PLAY_END = 4;
    public static final int VR_PROCESSION = 7;
    public static final int VR_LISTENING_TIMEOUT = 8;

    // ---- TTS play states ----
    public static final int PLAY_STATE_START = 1;
    public static final int PLAY_STATE_END = 2;

    /** The glasses' own listening timeout, armed by code:4. */
    public static final long TIMEOUT_LISTENING_MS = 8000;

    /**
     * Tells the glasses which assistant capabilities are on -- crucially
     * isContinuousDialogueEnable (multi-turn) and isChatGptCardDisplayEnable,
     * without which the glasses' ChatGPT card scene is never configured for
     * follow-ups and crashes when a second answer is appended.
     *
     * Field names and values are taken verbatim from a btsnoop of the official
     * app, which sends this once and the glasses retain it. We send it at the
     * start of each conversation, which is harmless to repeat.
     */
    public static String assistantConfig() {
        try {
            return message(CODE_ASSISTANT_CONFIG, new JSONObject()
                    .put("hasWakeupVoicePrint", false)
                    .put("isAsrResultScreenEnable", true)
                    .put("isChatGptCardDisplayEnable", true)
                    .put("isChatGptTTSPlayEnable", true)
                    .put("isContinuousDialogueEnable", true)
                    .put("isLowPowerWakeupEnable", true)
                    .put("isLowPowerWakeupScreenOffEnable", true)
                    .put("isNetworkAvailable", true)
                    .put("isWakeupVoiceRecording", false)
                    .put("ttsTimbreValue", 0));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String message(int code, Object payload) {
        try {
            return new JSONObject().put("code", code).put("payload", payload).toString();
        } catch (JSONException e) {
            throw new IllegalStateException("AI payload could not be built", e);
        }
    }

    /**
     * The message the glasses wait for after the AI button. Without it they show
     * "service error". Send it before any slow work.
     */
    public static String sessionAck(String sessionId) {
        try {
            return message(CODE_START_VR_RES, new JSONObject()
                    .put("hasNetwork", true)
                    // The literal string the real app sends ("wakeup succeeded").
                    .put("message", "唤醒成功")
                    .put("sessionId", sessionId)
                    .put("success", true));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 104 type:1 -- speech detected. The ONLY thing that clears the 8s timeout. */
    public static String vadStart(String sessionId) {
        return vadEvent(1, sessionId);
    }

    /** 104 type:2 -- end of speech. */
    public static String vadEnd(String sessionId) {
        return vadEvent(2, sessionId);
    }

    private static String vadEvent(int type, String sessionId) {
        try {
            return message(CODE_VAD_EVENT, new JSONObject()
                    .put("type", type)
                    .put("sessionId", sessionId));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A caption frame. Partials ({@code isFinal=false}) must GROW -- the glasses
     * render a building caption, and sending the whole sentence as one partial
     * makes it flash and vanish.
     */
    public static String asrResult(String sessionId, String text, boolean isFinal) {
        try {
            return message(CODE_ASR_TRANS, new JSONObject()
                    .put("id", sessionId)
                    .put("isOfflineResult", false)
                    .put("text", text)
                    .put("type", isFinal ? 1 : 0));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Opens the LLM answer scene for the recognized question. */
    public static String chatQuery(String sessionId, String query) {
        try {
            JSONObject emptyUtterance = new JSONObject()
                    .put("id", "")
                    .put("screen", "")
                    .put("speech", "");
            return message(CODE_VUI, new JSONObject()
                    .put("header", new JSONObject()
                            .put("name", "default")
                            .put("namespace", "llm")
                            .put("specialCmdInChatGptScene", false))
                    .put("metadata", new JSONObject().put("msgId", ""))
                    .put("payload", new JSONObject()
                            .put("isSoundOpened", true)
                            .put("query", query)
                            .put("isNextRecorded", false)
                            .put("utterance", new JSONObject()
                                    .put("speech", "")
                                    .put("screen", "")
                                    .put("id", "")))
                    .put("source", 0)
                    .put("utterance", emptyUtterance)
                    .put("sessionId", sessionId));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Commits answer text to the LLM scene; status 1 precedes final status 2. */
    public static String chatAnswer(String sessionId, String answer, int baseStatus) {
        try {
            return message(CODE_CHAT_GPT_RESPONSE, new JSONObject()
                    .put("answer", answer)
                    .put("base_status", baseStatus)
                    .put("isCmd", false)
                    .put("sessionId", sessionId)
                    .put("version_code", ""));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Playback state. Note {@code id} is the empty string, not a session id. */
    public static String playState(int state) {
        try {
            return message(CODE_TTS_PLAY_RES, new JSONObject()
                    .put("id", "")
                    .put("isContinuous", false)
                    .put("isMulti", false)
                    .put("isWakeup", false)
                    .put("playState", state));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 106 -- payload is a BARE INT, not an object. */
    public static String vrState(int state) {
        return message(CODE_SYNC_VR_STATE, state);
    }

    /** 107 -- ends the turn. */
    public static String endTurn() {
        try {
            return message(CODE_HOT_WORD_MANAGER, new JSONObject()
                    .put("control", 4)
                    .put("isOffline", false));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}

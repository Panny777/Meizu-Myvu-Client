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
 *  4. Answers go 5 -> 6 playState:1 -> speak -> 6 playState:2 -> 107.
 *
 * None of these builders declare JSONException: every payload is assembled from
 * literals, so it is unreachable, and declaring it would clutter the call sites
 * -- which read as an ordered protocol sequence and are clearer without it.
 */
public final class AiProtocol {
    private AiProtocol() {}

    public static final String PKG = AppLayer.PKG_AI;

    // ---- codes (CmdCode.java) ----
    public static final int CODE_START_VR_REQ = 3;        // glasses -> phone: button
    public static final int CODE_START_VR_RES = 4;        // phone -> glasses: session ack
    public static final int CODE_TTS_PLAY_REQ = 5;        // phone -> glasses: answer card
    public static final int CODE_TTS_PLAY_RES = 6;        // phone -> glasses: play state
    public static final int CODE_VOICE_WAKEUP_VR_REQ = 7; // glasses -> phone: wake word
    public static final int CODE_ASR_TRANS = 101;         // phone -> glasses: caption
    public static final int CODE_VAD_EVENT = 104;         // phone -> glasses: speech bounds
    public static final int CODE_SYNC_VR_STATE = 106;     // phone -> glasses: VrState
    public static final int CODE_HOT_WORD_MANAGER = 107;  // phone -> glasses: end of turn
    public static final int CODE_RECORD_DATA_TRANS = 109; // glasses -> phone: mic audio

    // ---- VrState values (protocol/VrState.java) ----
    public static final int VR_CLOSE = 0;
    public static final int VR_TTS_PLAY_START = 3;
    public static final int VR_TTS_PLAY_END = 4;
    public static final int VR_PROCESSION = 7;
    public static final int VR_LISTENING_TIMEOUT = 8;

    // ---- TTS play states ----
    public static final int PLAY_STATE_START = 1;
    public static final int PLAY_STATE_END = 2;

    /** The glasses' own listening timeout, armed by code:4. */
    public static final long TIMEOUT_LISTENING_MS = 8000;

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

    /** The answer card. Sent before playback begins. */
    public static String answerCard(String answer) {
        try {
            return message(CODE_TTS_PLAY_REQ, new JSONObject()
                    .put("id", "")
                    .put("isContinuous", false)
                    .put("isMulti", false)
                    .put("isWakeup", false)
                    .put("wakeupControl", new JSONObject()
                            .put("control", 6)
                            .put("muteTimeout", 2000)
                            .put("scene", "")
                            .put("extra", ""))
                    .put("ttsData", new JSONObject()
                            .put("text", answer)
                            .put("isChatGpt", true)
                            .put("nextStep", 0)));
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

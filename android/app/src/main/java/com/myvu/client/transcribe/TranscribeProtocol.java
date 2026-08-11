package com.myvu.client.transcribe;

import com.myvu.client.protocol.Pb;
import com.myvu.client.protocol.PbValue;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The glasses' live-captioning wire protocol (Transcribe / 听记 and Translate).
 *
 * This is a SEPARATE stack from the {@code code:N} AI assistant in
 * {@link com.myvu.client.app.feature.AiProtocol}. Instead of one shared
 * assistant channel it is a point-to-point link between a phone-side package and
 * its glasses-side twin, e.g.
 * {@code com.upuphone.ar.transcribe.phone} <-> {@code com.upuphone.ar.transcribe.glasses},
 * riding the ordinary RunAsOne relay. Every message is
 *
 *   {"msgCmd":&lt;int&gt;,"data":"&lt;escaped json&gt;"}
 *
 * in StMessage field 4, with any binary payload in field 5 -- the same slot the
 * assistant's mic audio uses. {@code data} is a STRING holding escaped JSON, not
 * a nested object; the official app builds it with a second Gson pass and the
 * glasses parse it the same way, so a nested object silently fails to bind.
 *
 * The recognition itself is entirely phone-side: the glasses only capture audio,
 * render subtitles, and report their own UI state. That is what makes this
 * implementable at all -- we substitute our own speech recognition (Deepgram)
 * for the vendor cloud and the glasses cannot tell the difference.
 *
 * Constants are lifted from the decompiled
 * {@code com.upuphone.ar.transcribe.interconnect.annotation} package and were
 * cross-checked against a btsnoop capture of a real Translate session.
 */
public final class TranscribeProtocol {
    private TranscribeProtocol() {}

    // --------------------------------------------------------------- peers

    /**
     * The two feature twins that speak this protocol.
     *
     * They are byte-for-byte the same protocol with different package names, so
     * one implementation drives both. TRANSLATE is the one verified against a
     * real capture on international firmware; TRANSCRIBE is the note-taking app
     * and may not be installed on every build, in which case the launcher
     * simply never brings it up.
     */
    public enum Peer {
        TRANSCRIBE("com.upuphone.ar.transcribe.phone",
                "com.upuphone.ar.transcribe.glasses", "Transcribe"),
        TRANSLATE("com.upuphone.ar.translation.phone",
                "com.upuphone.ar.translation.glasses", "Translator");

        public final String phonePkg;
        public final String glassesPkg;
        /** Shown by the launcher while the scene opens. */
        public final String appName;

        Peer(String phonePkg, String glassesPkg, String appName) {
            this.phonePkg = phonePkg;
            this.glassesPkg = glassesPkg;
            this.appName = appName;
        }

        public static Peer fromId(String id) {
            for (Peer p : values()) {
                if (p.name().equalsIgnoreCase(id)) return p;
            }
            return TRANSLATE;
        }

        /**
         * The peer a RunAsOne launch-app request is asking for, or null if the
         * request is for something else (nav, music, …).
         *
         * The appId is {@code <launcher>@<phonePkg>.<ServiceName>}, so matching
         * on the phone package is exact -- the two captioning packages share no
         * prefix with each other or with any other app's.
         */
        public static Peer forLaunchAppId(String appId) {
            if (appId == null) return null;
            for (Peer p : values()) {
                if (appId.contains(p.phonePkg)) return p;
            }
            return null;
        }

        /**
         * The peer that owns a package name, either end of the pair.
         *
         * Used to attribute an INBOUND message, whose source is the glasses-side
         * package -- the launch-request form above sees the phone-side one.
         */
        public static Peer forPackage(String pkg) {
            if (pkg == null) return null;
            for (Peer p : values()) {
                if (pkg.contains(p.glassesPkg) || pkg.contains(p.phonePkg)) return p;
            }
            return null;
        }
    }

    public static final String PKG_LAUNCHER = "com.upuphone.star.launcher";

    // ------------------------------------------------- glasses -> phone cmds

    /** The glasses' own start button. Carries a StartTrans payload. */
    public static final int G2P_CLICK_START = 1;
    /** Mic audio, near field. Raw Opus in field 5. */
    public static final int G2P_PROXIMAL_AUDIO = 3;
    /** Mic audio, far field. Raw Opus in field 5. This is the one in practice. */
    public static final int G2P_REMOTE_AUDIO = 4;
    /** The user closed the scene on the glasses. */
    public static final int G2P_EXIT = 7;
    public static final int G2P_SERVER_START_COMPLETE = 11;
    public static final int G2P_DIALOG_DISMISS = 12;
    public static final int G2P_ASSISTANT_OPEN = 14;
    public static final int G2P_ASSISTANT_EXIT = 15;
    public static final int G2P_SCREEN_STATE = 21;

    // ------------------------------------------------- phone -> glasses cmds

    /** Recorder lifecycle; {@link #REC_START} is what makes audio start flowing. */
    public static final int P2G_RECORD_STATE = 51;
    /** Session state + an "ext code" carrying hints and errors. */
    public static final int P2G_TRANS_STATE = 52;
    /** A caption for the far-field speaker. The one the subtitle view renders. */
    public static final int P2G_REMOTE_RESULT = 53;
    /** A caption for the wearer. */
    public static final int P2G_PROXIMAL_RESULT = 54;
    public static final int P2G_SERVER_RUNNING_STATE = 55;
    public static final int P2G_SUBTITLE_SIZE_TYPE = 57;
    public static final int P2G_SUBTITLE_SET_TYPE = 58;
    public static final int P2G_LANGUAGE_SET = 60;
    public static final int P2G_TRANS_START = 61;
    public static final int P2G_DIALOG_DISMISS = 63;
    public static final int P2G_SERVER_EXIT = 65;
    public static final int P2G_SAVE_SUCCESS = 66;
    public static final int P2G_EXIT_ACK = 68;

    // ----------------------------------------------------------- state codes

    public static final int STATE_NOT_START = 1;
    public static final int STATE_END = 2;
    public static final int STATE_PREPARING = 3;
    public static final int STATE_RUNNING = 4;
    public static final int STATE_LISTENING = 5;
    public static final int STATE_STOPPING = 6;

    /** "Nothing special" -- the ext code that accompanies a plain state change. */
    public static final int EXT_NONE = -1;
    public static final int EXT_CONNECT_ON = 10;
    public static final int EXT_CONNECT_OFF = 11;
    public static final int EXT_CONNECT_LOSS = 12;
    public static final int EXT_CONNECT_GOOD = 13;
    public static final int EXT_MUTE_TWO_SECS = 14;
    public static final int EXT_MUTE_FIVE_MINS = 15;
    /** "Connecting..." spinner while the recogniser comes up. */
    public static final int EXT_SHOW_WAITING = 16;
    /** "Say something" hint once the recogniser is live. */
    public static final int EXT_HINT_WAITING = 17;
    public static final int EXT_CONNECT_CHANNEL_ERROR = 18;
    public static final int EXT_MUTE_FIVE_SECS = 19;
    public static final int EXT_VOICE_NOT_MUTE = 20;

    public static final int REC_INIT = 1;
    public static final int REC_START = 2;
    public static final int REC_STOP = 3;
    public static final int REC_RELEASE = 4;

    /** Plain captioning. 4 is the two-party dialogue mode, which we do not do. */
    public static final int TRANS_TYPE_TRANSCRIBE = 1;

    public static final int SUBTITLE_MY_HIDE = 1;
    public static final int SUBTITLE_MY_ORIGINAL_SHOW = 2;
    public static final int SUBTITLE_OTHER_SHOW = 3;
    public static final int SUBTITLE_OTHER_TRANSLATION_SHOW = 4;

    public static final int SUBTITLE_SIZE_SMALL = 1;
    public static final int SUBTITLE_SIZE_MIDDLE = 2;
    public static final int SUBTITLE_SIZE_BIG = 3;

    /** {@code src.type}: a growing partial. */
    public static final int RESULT_PARTIAL = 1;
    /** {@code src.type}: the committed line. Only these are persisted. */
    public static final int RESULT_FINAL = 0;

    // ------------------------------------------------------------- outbound

    /** {@code {"msgCmd":N}} -- for the commands that carry no payload. */
    public static String message(int msgCmd) {
        return message(msgCmd, null);
    }

    /**
     * {@code {"msgCmd":N,"data":"<escaped json>"}}.
     *
     * {@code dataJson} really is a string field. Putting a JSONObject here
     * produces valid JSON that the glasses' Gson binds to a null String.
     */
    public static String message(int msgCmd, String dataJson) {
        try {
            JSONObject o = new JSONObject().put("msgCmd", msgCmd);
            if (dataJson != null) o.put("data", dataJson);
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("transcribe message could not be built", e);
        }
    }

    /**
     * The launcher request that brings the glasses-side app up.
     *
     * Goes to {@link #PKG_LAUNCHER}, not to the peer -- the peer does not exist
     * yet. {@code ext} is the pre-session config the scene reads before it has
     * asked us anything.
     */
    public static String openScene(Peer peer, String srcLang, String dstLang,
                                   int subtitleSetType) {
        try {
            // Field-for-field what the vendor sends. Notably NO subtitleSizeType
            // -- the size lives on msgCmd 57, and an unexpected key here is not
            // worth the risk on a scene we cannot debug from the inside.
            String ext = new JSONObject()
                    .put("transType", TRANS_TYPE_TRANSCRIBE)
                    .put("src", srcLang)
                    .put("dst", dstLang)
                    .put("subtitleSetType", subtitleSetType)
                    // 1 = sync results to the phone, 2 = don't. We keep our own
                    // transcript, so ask for them.
                    .put("saveResult", 1)
                    // 2 = international build. Firmware picks its language list
                    // from this; 1 (domestic) offers Chinese-only pairs.
                    .put("transVersionCode", 2)
                    .put("versionCode", 1)
                    .toString();
            return new JSONObject()
                    .put("action", "app")
                    .put("data", new JSONObject()
                            .put("action", "open_app")
                            .put("launchMode", "scene")
                            .put("pkg", peer.glassesPkg)
                            .put("app_name", peer.appName)
                            .put("ext", ext))
                    .toString();
        } catch (JSONException e) {
            throw new IllegalStateException("open_app could not be built", e);
        }
    }

    public static String transState(int state, int extCode) {
        return message(P2G_TRANS_STATE, obj("transState", state, "extCode", extCode));
    }

    public static String recordState(int recordState) {
        return message(P2G_RECORD_STATE, obj("recordState", recordState));
    }

    public static String subtitleSetType(int type) {
        return message(P2G_SUBTITLE_SET_TYPE, obj("subtitleSetType", type));
    }

    /** msgCmd 61 -- "start now"; 60 -- "these are the languages". Same payload. */
    public static String startTrans(String srcLang, String dstLang) {
        return message(P2G_TRANS_START, startTransPayload(srcLang, dstLang));
    }

    public static String languageSet(String srcLang, String dstLang) {
        return message(P2G_LANGUAGE_SET, startTransPayload(srcLang, dstLang));
    }

    private static String startTransPayload(String srcLang, String dstLang) {
        try {
            return new JSONObject()
                    .put("transType", TRANS_TYPE_TRANSCRIBE)
                    .put("src", srcLang)
                    .put("dst", dstLang)
                    // False: the glasses use this to decide whether the phone is
                    // already mid-session and it must not re-enter its start flow.
                    .put("phoneInitFinish", false)
                    .put("callState", 0)
                    // The translation twin's StartTrans carries this and the
                    // transcribe one does not; 0 is what the vendor sends, and
                    // Gson defaults the absent case to the same thing.
                    .put("versionCode", 0)
                    .put("wechatReply", false)
                    .toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The "the recogniser is alive" notice (msgCmd 55).
     *
     * Shape and the Chinese {@code illustrate} string are copied from the wire;
     * the glasses only look at {@code code}, but the vendor app sends the whole
     * envelope and matching it costs nothing.
     */
    public static String serverRunning() {
        try {
            String data = new JSONObject()
                    .put("code", "0")
                    .put("action", "sync_audio_info_success")
                    .put("desc", "success")
                    .put("illustrate", "成功")
                    .put("processDesc", "")
                    .toString();
            return message(P2G_SERVER_RUNNING_STATE, data);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * One caption frame.
     *
     * Partials ({@code type = }{@link #RESULT_PARTIAL}) must GROW: the glasses
     * render a building line and replace it wholesale, so shrinking text reads
     * as a flicker. {@code end} stays 1 for every frame of a live session -- it
     * means "the request is still open", not "the sentence finished".
     *
     * @param startMs when this line's speech began, ms since the session started
     * @param endMs   the audio timestamp this text is current as of
     */
    public static String asrResult(int msgCmd, String requestId, String recognizeId,
                                   String content, int type, long startMs, long endMs) {
        try {
            JSONObject src = new JSONObject()
                    .put("type", type)
                    .put("content", content)
                    .put("end", 1)
                    .put("startTime", startMs)
                    .put("endTime", endMs)
                    .put("partIndex", 0)
                    .put("partNum", 0);
            String data = new JSONObject()
                    .put("src", src)
                    .put("ext", new JSONObject()
                            .put("requestId", requestId)
                            .put("recognizeId", recognizeId))
                    .toString();
            return message(msgCmd, data);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String obj(String k, int v) {
        try {
            return new JSONObject().put(k, v).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String obj(String k1, int v1, String k2, int v2) {
        try {
            return new JSONObject().put(k1, v1).put(k2, v2).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // -------------------------------------------------------------- inbound

    /** One decoded glasses -> phone message. */
    public static final class Inbound {
        public final String srcPkg;
        public final String dstPkg;
        public final int msgCmd;
        /** The inner JSON, still a string; null when the message carries none. */
        public final String data;
        /** StMessage field 5: raw Opus for the audio commands, else empty. */
        public final byte[] audio;

        Inbound(String srcPkg, String dstPkg, int msgCmd, String data, byte[] audio) {
            this.srcPkg = srcPkg;
            this.dstPkg = dstPkg;
            this.msgCmd = msgCmd;
            this.data = data;
            this.audio = audio;
        }

        public boolean isAudio() {
            return msgCmd == G2P_PROXIMAL_AUDIO || msgCmd == G2P_REMOTE_AUDIO;
        }
    }

    /** StMessage fields: 2 = source pkg, 3 = target pkg, 4 = JSON, 5 = binary. */
    private static final int FIELD_SRC_PKG = 2;
    private static final int FIELD_DST_PKG = 3;
    private static final int FIELD_JSON = 4;
    private static final int FIELD_BINARY = 5;

    /**
     * ASCII for the marker that identifies a message of this protocol.
     *
     * Matched on raw bytes, like the assistant's {@code "code":109} probe: audio
     * frames arrive ~17 times a second carrying a 240-byte Opus packet each, and
     * decoding the envelope of every relay message just to classify it is the
     * cost we are avoiding. The glasses pretty-print their JSON, hence the
     * tolerance for whitespace after the colon.
     */
    private static final byte[] MARKER = "\"msgCmd\"".getBytes(StandardCharsets.US_ASCII);

    /** Cheap pre-filter: true if this relay body could be a transcribe message. */
    public static boolean isTranscribeFrame(byte[] body) {
        return indexOf(body, MARKER) >= 0;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (haystack == null || haystack.length < needle.length) return -1;
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** Decodes a relay body, or null if it is not one of ours. */
    public static Inbound parse(byte[] relayBody) {
        try {
            Map<Integer, List<PbValue>> fields = Pb.parse(relayBody);
            String json = Pb.firstString(fields, FIELD_JSON, null);
            if (json == null) return null;
            JSONObject o = new JSONObject(json);
            if (!o.has("msgCmd")) return null;
            byte[] audio = Pb.firstBytes(fields, FIELD_BINARY, new byte[0]);
            return new Inbound(
                    Pb.firstString(fields, FIELD_SRC_PKG, ""),
                    Pb.firstString(fields, FIELD_DST_PKG, ""),
                    o.getInt("msgCmd"),
                    o.optString("data", null),
                    audio);
        } catch (Exception e) {
            // Inbound radio data: a malformed frame must never propagate.
            return null;
        }
    }
}

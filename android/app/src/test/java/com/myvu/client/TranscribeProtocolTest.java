package com.myvu.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.myvu.client.protocol.Pb;
import com.myvu.client.transcribe.Languages;
import com.myvu.client.transcribe.TranscribeProtocol;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Pins the live-caption wire shapes against a btsnoop capture of a working
 * vendor session.
 *
 * The expectations here are transcribed from real frames, not from the
 * decompiled source, because the two disagree in one important way: the source
 * suggests a nested {@code data} object while the wire carries an ESCAPED
 * STRING. Getting that wrong produces valid JSON that the glasses accept and
 * then ignore, which is the worst possible failure mode.
 */
public class TranscribeProtocolTest {

    /** Verbatim from the capture, minus the ids: the caption the lens rendered. */
    private static final String CAPTURED_RESULT =
            "{\"ext\":{\"requestId\":\"b3f376908ec64bf89fa41dbc3df0a9e8\","
            + "\"recognizeId\":\"563b3fd8c2a5148a9872fcc15943683c\"},"
            + "\"src\":{\"end\":1,\"startTime\":2990,\"endTime\":4920,\"partIndex\":0,"
            + "\"type\":0,\"content\":\"What have you been doing today?\",\"partNum\":0}}";

    @Test
    public void dataIsAStringNotANestedObject() throws Exception {
        JSONObject m = new JSONObject(TranscribeProtocol.recordState(
                TranscribeProtocol.REC_START));
        assertEquals(51, m.getInt("msgCmd"));
        // get() rather than getString(): if this ever became an object the test
        // must fail, not quietly stringify it.
        assertTrue(m.get("data") instanceof String);
        assertEquals(2, new JSONObject(m.getString("data")).getInt("recordState"));
    }

    @Test
    public void payloadlessCommandsOmitDataEntirely() throws Exception {
        JSONObject m = new JSONObject(
                TranscribeProtocol.message(TranscribeProtocol.P2G_SAVE_SUCCESS));
        assertEquals(66, m.getInt("msgCmd"));
        assertFalse(m.has("data"));
    }

    @Test
    public void transStateCarriesBothStateAndExtCode() throws Exception {
        JSONObject m = new JSONObject(TranscribeProtocol.transState(
                TranscribeProtocol.STATE_PREPARING, TranscribeProtocol.EXT_SHOW_WAITING));
        assertEquals(52, m.getInt("msgCmd"));
        JSONObject data = new JSONObject(m.getString("data"));
        assertEquals(3, data.getInt("transState"));
        assertEquals(16, data.getInt("extCode"));
    }

    /**
     * The scene request goes to the LAUNCHER and carries its config as escaped
     * JSON in {@code ext} -- the glasses-side app does not exist yet, so this is
     * the only chance to configure it before it draws its first frame.
     */
    @Test
    public void openSceneMatchesTheCapturedLauncherRequest() throws Exception {
        JSONObject m = new JSONObject(TranscribeProtocol.openScene(
                TranscribeProtocol.Peer.TRANSLATE, "en", "en",
                TranscribeProtocol.SUBTITLE_TRANSLATION_ONLY,
                TranscribeProtocol.TRANS_TYPE_TRANSCRIBE));
        assertEquals("app", m.getString("action"));

        JSONObject data = m.getJSONObject("data");
        assertEquals("open_app", data.getString("action"));
        assertEquals("scene", data.getString("launchMode"));
        assertEquals("com.upuphone.ar.translation.glasses", data.getString("pkg"));
        // "app_name", not "appName": the key is snake_case only here.
        assertEquals("Translator", data.getString("app_name"));

        JSONObject ext = new JSONObject(data.getString("ext"));
        assertEquals(1, ext.getInt("transType"));
        assertEquals("en", ext.getString("src"));
        assertEquals(4, ext.getInt("subtitleSetType"));
        assertEquals(1, ext.getInt("saveResult"));
        assertEquals(2, ext.getInt("transVersionCode"));
        // The vendor's ext has exactly these seven keys; an unexpected one on a
        // scene we cannot debug from the inside is not worth the risk.
        assertEquals(7, ext.length());
        assertFalse(ext.has("subtitleSizeType"));
    }

    /**
     * The translation twin's StartTrans carries versionCode and the transcribe
     * one does not. We drive both, so the superset goes out -- Gson ignores a
     * key the target class lacks, but a missing one it wants would bind to junk.
     */
    @Test
    public void startTransMatchesTheCapturedPayload() throws Exception {
        JSONObject m = new JSONObject(TranscribeProtocol.startTrans(
                "en", "en", TranscribeProtocol.TRANS_TYPE_TRANSCRIBE));
        assertEquals(61, m.getInt("msgCmd"));
        JSONObject data = new JSONObject(m.getString("data"));
        assertEquals(1, data.getInt("transType"));
        assertEquals("en", data.getString("src"));
        assertEquals("en", data.getString("dst"));
        assertFalse(data.getBoolean("phoneInitFinish"));
        assertEquals(0, data.getInt("callState"));
        assertEquals(0, data.getInt("versionCode"));
        assertFalse(data.getBoolean("wechatReply"));
        assertEquals(7, data.length());

        // 60 is the same payload under a different command.
        JSONObject langSet = new JSONObject(TranscribeProtocol.languageSet(
                "en", "en", TranscribeProtocol.TRANS_TYPE_TRANSCRIBE));
        assertEquals(60, langSet.getInt("msgCmd"));
        assertEquals(data.length(),
                new JSONObject(langSet.getString("data")).length());
    }

    @Test
    public void captionFrameMatchesTheCapturedFinalResult() throws Exception {
        String built = TranscribeProtocol.asrResult(
                TranscribeProtocol.P2G_REMOTE_RESULT,
                "b3f376908ec64bf89fa41dbc3df0a9e8",
                "563b3fd8c2a5148a9872fcc15943683c",
                "What have you been doing today?",
                TranscribeProtocol.RESULT_FINAL, 2990, 4920);

        JSONObject m = new JSONObject(built);
        assertEquals(53, m.getInt("msgCmd"));

        JSONObject expected = new JSONObject(CAPTURED_RESULT);
        JSONObject actual = new JSONObject(m.getString("data"));
        // Field-by-field rather than string equality: org.json does not preserve
        // key order, and neither does the glasses' Gson care about it.
        assertEquals(expected.getJSONObject("src").length(),
                actual.getJSONObject("src").length());
        for (String key : new String[] { "end", "startTime", "endTime", "partIndex",
                                         "type", "partNum" }) {
            assertEquals(key, expected.getJSONObject("src").getInt(key),
                    actual.getJSONObject("src").getInt(key));
        }
        assertEquals(expected.getJSONObject("src").getString("content"),
                actual.getJSONObject("src").getString("content"));
        assertEquals(expected.getJSONObject("ext").getString("recognizeId"),
                actual.getJSONObject("ext").getString("recognizeId"));
    }

    /** {@code end} is "the request is still open", not "the sentence ended". */
    @Test
    public void captionEndStaysOneForPartialsAndFinalsAlike() throws Exception {
        for (int type : new int[] { TranscribeProtocol.RESULT_PARTIAL,
                                    TranscribeProtocol.RESULT_FINAL }) {
            JSONObject m = new JSONObject(TranscribeProtocol.asrResult(
                    TranscribeProtocol.P2G_REMOTE_RESULT, "r", "g", "hi", type, 0, 100));
            assertEquals(1, new JSONObject(m.getString("data"))
                    .getJSONObject("src").getInt("end"));
        }
    }

    // ------------------------------------------------------------- inbound

    /** Builds an StMessage the way the glasses do: JSON in 4, Opus in 5. */
    private static byte[] envelope(String src, String dst, String json, byte[] binary) {
        byte[] body = Pb.string(2, src);
        body = Pb.concat(body, Pb.string(3, dst));
        body = Pb.concat(body, Pb.string(4, json));
        if (binary != null) body = Pb.concat(body, Pb.bytes(5, binary));
        return Pb.concat(body, Pb.varintField(6, 42));
    }

    @Test
    public void audioFrameIsRecognisedAndItsOpusExtracted() {
        // The glasses pretty-print, so the marker scan must tolerate whitespace.
        byte[] opus = new byte[] { 0x5b, 0x41, (byte) 0xa0, (byte) 0xc0 };
        byte[] frame = envelope("com.upuphone.ar.translation.glasses",
                "com.upuphone.ar.translation.phone",
                "{\n\t\"msgCmd\":\t4\n}", opus);

        assertTrue(TranscribeProtocol.isTranscribeFrame(frame));
        TranscribeProtocol.Inbound msg = TranscribeProtocol.parse(frame);
        assertNotNull(msg);
        assertEquals(4, msg.msgCmd);
        assertTrue(msg.isAudio());
        assertEquals(4, msg.audio.length);
        assertEquals(0x5b, msg.audio[0] & 0xFF);
        assertNull(msg.data);
    }

    @Test
    public void nonTranscribeTrafficIsLeftAlone() {
        byte[] frame = envelope("com.upuphone.star.launcher", "com.upuphone.star.launcher",
                "{\"action\":\"system\",\"data\":{\"action\":\"get_language\"}}", null);
        assertFalse(TranscribeProtocol.isTranscribeFrame(frame));
        assertNull(TranscribeProtocol.parse(frame));
    }

    /** A truncated or garbled frame must return null rather than throw. */
    @Test
    public void malformedFramesAreRejectedQuietly() {
        assertNull(TranscribeProtocol.parse("not protobuf".getBytes(StandardCharsets.UTF_8)));
        assertNull(TranscribeProtocol.parse(new byte[0]));
    }

    /**
     * The launch-app request is how "the user opened it ON the glasses" reaches
     * us -- but nav, music and the teleprompter send the same request, and
     * starting a caption session because someone opened the map would be worse
     * than not having the feature.
     */
    @Test
    public void launchRequestsMapToTheRightPeerAndIgnoreOtherApps() {
        assertEquals(TranscribeProtocol.Peer.TRANSLATE,
                TranscribeProtocol.Peer.forLaunchAppId("com.upuphone.star.launcher@"
                        + "com.upuphone.ar.translation.phone.TranslationService"));
        assertEquals(TranscribeProtocol.Peer.TRANSCRIBE,
                TranscribeProtocol.Peer.forLaunchAppId("com.upuphone.star.launcher@"
                        + "com.upuphone.ar.transcribe.phone.TranscribeService"));

        assertNull(TranscribeProtocol.Peer.forLaunchAppId("com.upuphone.star.launcher@"
                + "com.upuphone.ar.navi.lite.NaviService"));
        assertNull(TranscribeProtocol.Peer.forLaunchAppId("com.upuphone.ar.tici"));
        assertNull(TranscribeProtocol.Peer.forLaunchAppId(""));
        assertNull(TranscribeProtocol.Peer.forLaunchAppId(null));
    }

    /**
     * Attributing an INBOUND message needs the glasses-side name; the
     * launch-request form only ever sees the phone-side one, so the two lookups
     * are not interchangeable.
     */
    @Test
    public void inboundPackagesResolveFromEitherEndOfThePair() {
        assertEquals(TranscribeProtocol.Peer.TRANSLATE,
                TranscribeProtocol.Peer.forPackage("com.upuphone.ar.translation.glasses"));
        assertEquals(TranscribeProtocol.Peer.TRANSCRIBE,
                TranscribeProtocol.Peer.forPackage("com.upuphone.ar.transcribe.glasses"));
        assertEquals(TranscribeProtocol.Peer.TRANSLATE,
                TranscribeProtocol.Peer.forPackage("com.upuphone.ar.translation.phone"));
        assertNull(TranscribeProtocol.Peer.forPackage("com.upuphone.star.launcher"));
        assertNull(TranscribeProtocol.Peer.forPackage(null));
        // The glasses-side name must NOT resolve through the launch form.
        assertNull(TranscribeProtocol.Peer.forLaunchAppId(
                "com.upuphone.ar.translation.glasses"));
    }

    /**
     * The firmware takes a short vendor list and nothing else. "zh" or "en-US"
     * are accepted silently and then label the subtitle wrongly, so an
     * unsupported target has to be caught before the session starts.
     */
    @Test
    public void onlyTheFirmwaresOwnLanguageCodesAreAccepted() {
        assertTrue(Languages.isSupported("en"));
        assertTrue(Languages.isSupported("cn"));
        assertTrue(Languages.isSupported("ja"));

        assertFalse(Languages.isSupported("zh"));
        assertFalse(Languages.isSupported("en-US"));
        assertFalse(Languages.isSupported(""));
        assertFalse(Languages.isSupported(null));
    }

    /**
     * The names feed a prompt, so an unknown code must not silently become a
     * language -- that would translate into something nobody asked for.
     */
    @Test
    public void languageNamesAreSpelledOutForThePromptAndNeverGuessed() {
        assertEquals("Simplified Chinese", Languages.nameOf("cn"));
        assertEquals("Japanese", Languages.nameOf("ja"));
        // The vendor's mixed cn+en recognition mode; only English as a target.
        assertEquals("English", Languages.nameOf("cnen"));
        assertEquals("kl", Languages.nameOf("kl"));
    }

    @Test
    public void peerIdsRoundTripAndFallBackToTranslate() {
        assertEquals(TranscribeProtocol.Peer.TRANSCRIBE,
                TranscribeProtocol.Peer.fromId("TRANSCRIBE"));
        assertEquals(TranscribeProtocol.Peer.TRANSLATE,
                TranscribeProtocol.Peer.fromId("nonsense"));
        assertEquals("com.upuphone.ar.transcribe.phone",
                TranscribeProtocol.Peer.TRANSCRIBE.phonePkg);
    }
}

package com.myvu.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.myvu.client.app.feature.AiProtocol;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The AI protocol's shapes are easy to get subtly wrong and the failures are
 * opaque -- "service error" on the lens, or a session that dies mid-answer.
 * These pin the details that carry meaning.
 */
public class AiProtocolTest {

    private static JSONObject parse(String json) throws Exception {
        return new JSONObject(json);
    }

    @Test
    public void sessionAckCarriesSuccessAndSessionId() throws Exception {
        JSONObject m = parse(AiProtocol.sessionAck("sess-1"));
        assertEquals(4, m.getInt("code"));

        JSONObject p = m.getJSONObject("payload");
        assertTrue(p.getBoolean("success"));
        assertTrue(p.getBoolean("hasNetwork"));
        assertEquals("sess-1", p.getString("sessionId"));
    }

    @Test
    public void vadStartAndEndDifferOnlyByType() throws Exception {
        assertEquals(1, parse(AiProtocol.vadStart("s")).getJSONObject("payload").getInt("type"));
        assertEquals(2, parse(AiProtocol.vadEnd("s")).getJSONObject("payload").getInt("type"));
        assertEquals(104, parse(AiProtocol.vadStart("s")).getInt("code"));
    }

    @Test
    public void asrPartialAndFinalUseTypeZeroAndOne() throws Exception {
        JSONObject partial = parse(AiProtocol.asrResult("s", "how is", false));
        JSONObject fin = parse(AiProtocol.asrResult("s", "how is the weather", true));

        assertEquals(101, partial.getInt("code"));
        assertEquals(0, partial.getJSONObject("payload").getInt("type"));
        assertEquals(1, fin.getJSONObject("payload").getInt("type"));
        assertEquals("how is the weather", fin.getJSONObject("payload").getString("text"));
        assertFalse(fin.getJSONObject("payload").getBoolean("isOfflineResult"));
    }

    /**
     * code:106's payload is a BARE INT, unlike every other message here, which
     * wraps an object. Sending {"state":7} instead is silently ignored.
     */
    @Test
    public void vrStatePayloadIsABareInt() throws Exception {
        JSONObject m = parse(AiProtocol.vrState(AiProtocol.VR_PROCESSION));
        assertEquals(106, m.getInt("code"));
        assertEquals(7, m.getInt("payload"));
    }

    @Test
    public void answerCardCarriesTheTextAndMuteTimeout() throws Exception {
        JSONObject p = parse(AiProtocol.answerCard("It is sunny."))
                .getJSONObject("payload");

        assertEquals("It is sunny.",
                p.getJSONObject("ttsData").getString("text"));
        assertTrue(p.getJSONObject("ttsData").getBoolean("isChatGpt"));
        assertEquals(2000, p.getJSONObject("wakeupControl").getInt("muteTimeout"));
        assertEquals(6, p.getJSONObject("wakeupControl").getInt("control"));
    }

    /** The id here is the EMPTY STRING, not the session id. */
    @Test
    public void playStateUsesAnEmptyId() throws Exception {
        JSONObject p = parse(AiProtocol.playState(AiProtocol.PLAY_STATE_START))
                .getJSONObject("payload");
        assertEquals("", p.getString("id"));
        assertEquals(1, p.getInt("playState"));

        assertEquals(2, parse(AiProtocol.playState(AiProtocol.PLAY_STATE_END))
                .getJSONObject("payload").getInt("playState"));
    }

    @Test
    public void endTurnUsesControlFour() throws Exception {
        JSONObject m = parse(AiProtocol.endTurn());
        assertEquals(107, m.getInt("code"));
        assertEquals(4, m.getJSONObject("payload").getInt("control"));
        assertFalse(m.getJSONObject("payload").getBoolean("isOffline"));
    }

    @Test
    public void constantsMatchTheDeviceEnums() {
        // From CmdCode.java / VrState.java in the decompiled app.
        assertEquals(3, AiProtocol.CODE_START_VR_REQ);
        assertEquals(7, AiProtocol.CODE_VOICE_WAKEUP_VR_REQ);
        assertEquals(0, AiProtocol.VR_CLOSE);
        assertEquals(7, AiProtocol.VR_PROCESSION);
        // The listening timeout we are racing against.
        assertEquals(8000, AiProtocol.TIMEOUT_LISTENING_MS);
    }
}

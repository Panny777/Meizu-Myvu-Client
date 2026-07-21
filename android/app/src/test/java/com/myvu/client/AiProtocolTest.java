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
    public void chatQueryOpensTheLlmSceneWithTheQuestion() throws Exception {
        JSONObject message = parse(AiProtocol.chatQuery("sess-1", "Will it rain?"));
        assertEquals(102, message.getInt("code"));

        JSONObject payload = message.getJSONObject("payload");
        assertEquals("sess-1", payload.getString("sessionId"));
        assertEquals("llm", payload.getJSONObject("header").getString("namespace"));
        assertEquals("Will it rain?",
                payload.getJSONObject("payload").getString("query"));
        assertFalse(payload.getJSONObject("payload").getBoolean("isNextRecorded"));
    }

    @Test
    public void chatAnswerCarriesStreamingAndFinalStatus() throws Exception {
        JSONObject streaming = parse(AiProtocol.chatAnswer("sess-1", "It is sunny.", 1));
        JSONObject complete = parse(AiProtocol.chatAnswer("sess-1", "It is sunny.", 2));

        assertEquals(122, streaming.getInt("code"));
        assertEquals("It is sunny.", streaming.getJSONObject("payload").getString("answer"));
        assertEquals("sess-1", streaming.getJSONObject("payload").getString("sessionId"));
        assertEquals(1, streaming.getJSONObject("payload").getInt("base_status"));
        assertEquals(2, complete.getJSONObject("payload").getInt("base_status"));
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

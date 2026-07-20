package com.myvu.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.myvu.client.app.AppLayer;
import com.myvu.client.app.InboundRouter;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The inbound router is what makes glasses-initiated features work at all --
 * without the launch-app ack the nav app ignores every frame we send and
 * re-asks forever.
 */
public class InboundRouterTest {

    private static class Sent {
        final String json;
        final String target;
        final String source;

        Sent(String json, String target, String source) {
            this.json = json;
            this.target = target;
            this.source = source;
        }
    }

    private List<Sent> sent;
    private InboundRouter router;

    @Before
    public void setUp() {
        sent = new ArrayList<>();
        router = new InboundRouter(new InboundRouter.Sender() {
            @Override
            public void send(String actionJson, String targetPkg, String sourcePkg) {
                sent.add(new Sent(actionJson, targetPkg, sourcePkg));
            }
        });
    }

    // ------------------------------------------------ balanced-brace scan

    @Test
    public void findsEmbeddedJsonInsideProtobufNoise() {
        // Relay bodies are protobuf with JSON buried inside, so the scanner has
        // to tolerate arbitrary leading/trailing bytes.
        String body = "com.upuphone.star.launcher*"
                + "{\"action\":\"volume\",\"value\":12}0";
        List<String> found = InboundRouter.findJsonObjects(body);
        assertEquals(1, found.size());
        assertEquals("{\"action\":\"volume\",\"value\":12}", found.get(0));
    }

    @Test
    public void handlesNestedBraces() {
        String body = "junk{\"a\":{\"b\":{\"c\":1}}}more";
        List<String> found = InboundRouter.findJsonObjects(body);
        assertEquals(1, found.size());
        assertEquals("{\"a\":{\"b\":{\"c\":1}}}", found.get(0));
    }

    @Test
    public void unbalancedBracesAreIgnored() {
        assertTrue(InboundRouter.findJsonObjects("{\"a\":1").isEmpty());
        // A stray closing brace must not desynchronise the scan.
        assertEquals(1, InboundRouter.findJsonObjects("}{\"a\":1}").size());
    }

    // ------------------------------------------------- launch-app handshake

    @Test
    public void launchAppRequestIsAckedOnTheInterconnectChannel() throws Exception {
        router.handle("{\"type\":11,\"data\":{\"appId\":\"com.upuphone.ar.navi.glass\","
                + "\"menuId\":\"m1\",\"requestId\":\"r7\",\"code\":0,\"success\":false}}");

        assertEquals(1, sent.size());
        Sent s = sent.get(0);
        // Must ride the interconnect inner channel, not the launcher.
        assertEquals(AppLayer.PKG_INTERCONNECT, s.target);
        assertEquals(AppLayer.PKG_INTERCONNECT, s.source);

        JSONObject reply = new JSONObject(s.json);
        assertEquals(12, reply.getInt("type"));
        JSONObject data = reply.getJSONObject("data");
        assertEquals("com.upuphone.ar.navi.glass", data.getString("appId"));
        assertEquals(200, data.getInt("code"));
        assertTrue(data.getBoolean("success"));
        // The echoed identifiers are how the glasses correlate the reply.
        assertEquals("m1", data.getString("menuId"));
        assertEquals("r7", data.getString("requestId"));
    }

    @Test
    public void launchAppRequestWithoutAppIdIsIgnored() {
        router.handle("{\"type\":11,\"data\":{\"menuId\":\"m1\"}}");
        assertTrue(sent.isEmpty());
    }

    @Test
    public void ourOwnType12AckIsNotEchoedBack() {
        router.handle("{\"type\":12,\"data\":{\"appId\":\"x\",\"code\":200}}");
        assertTrue(sent.isEmpty());
    }

    // -------------------------------------------------------- time sync

    @Test
    public void timeSyncRequestIsAnswered() throws Exception {
        router.handle("{\"action\":\"SyncOffSetTime\"}");

        assertEquals(1, sent.size());
        JSONObject reply = new JSONObject(sent.get(0).json);
        assertEquals("SyncOffSetTime", reply.getString("action"));
        assertNotNull(reply.getJSONObject("data").getString("syncTimeData"));
        assertEquals(AppLayer.PKG_LAUNCHER, sent.get(0).target);
    }

    @Test
    public void aTimePayloadIsNotTreatedAsARequest() {
        // Messages that already carry syncTimeData are time reports (possibly
        // our own echo); answering them would loop forever.
        router.handle("{\"action\":\"SyncOffSetTime\",\"data\":"
                + "{\"syncTimeData\":\"1784485077995\",\"timeZoneOffSet\":10800000}}");
        assertTrue(sent.isEmpty());
    }

    // ------------------------------------------------------ AI triggers

    @Test
    public void aiButtonAndWakeWordAreSurfaced() {
        final List<Integer> codes = new ArrayList<>();
        router.setAiTriggerListener(new InboundRouter.AiTriggerListener() {
            @Override
            public void onAiTrigger(int code, JSONObject payload) {
                codes.add(code);
            }
        });

        router.handle("{\"code\":3,\"payload\":{\"control\":1}}");  // button
        router.handle("{\"code\":7,\"payload\":{}}");                // wake word
        router.handle("{\"code\":101,\"payload\":{}}");              // ASR: not a trigger

        assertEquals(2, codes.size());
        assertEquals(Integer.valueOf(3), codes.get(0));
        assertEquals(Integer.valueOf(7), codes.get(1));
        // Triggers are notifications only; nothing is auto-sent.
        assertTrue(sent.isEmpty());
    }

    @Test
    public void malformedBodiesDoNotThrow() {
        router.handle("");
        router.handle("no json here at all");
        router.handle("{not valid json}");
        assertTrue(sent.isEmpty());
    }
}

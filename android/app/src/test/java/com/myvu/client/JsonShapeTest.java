package com.myvu.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.myvu.client.app.AppLayer;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The glasses parse these payloads with Gson against fixed bean classes, so the
 * exact key names matter more than they look. Several are easy to "correct"
 * while porting and thereby break silently -- these tests pin them down.
 */
public class JsonShapeTest {

    /**
     * REGRESSION: passing Android's StatusBarNotification.getKey() as the id --
     * pipe-delimited, e.g. "0|com.android.shell|1|tag|10289" -- made the glasses
     * REBOOT on every mirrored notification. The only accepted format is
     * phone-<packageName>-<numericId>, confirmed from a captured DISMISS payload
     * ("phone-com.android.settings-17301632").
     */
    @Test
    public void notificationIdUsesTheCapturedFormat() throws Exception {
        assertEquals("phone-com.android.settings-17301632",
                com.myvu.client.app.feature.Notifications
                        .notificationId("com.android.settings", 17301632));
    }

    @Test
    public void notificationIdNeverContainsPipes() throws Exception {
        JSONObject entry = com.myvu.client.app.feature.Notifications.entry(
                "com.whatsapp", 42, "Title", "Body", "WhatsApp", 1784488002000L, false);
        String id = entry.getString("id");
        assertEquals("phone-com.whatsapp-42", id);
        assertFalse("a pipe in the id crashes the glasses", id.contains("|"));
    }

    @Test
    public void notificationTextIsSanitisedAndBounded() throws Exception {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 2000; i++) huge.append('x');

        JSONObject entry = com.myvu.client.app.feature.Notifications.entry(
                "com.example", 1, "Multi\nline\ttitle", huge.toString(),
                "Example", 1L, false);

        assertFalse("newlines must not reach the lens renderer",
                entry.getString("title").contains("\n"));
        assertTrue("oversized content must be truncated",
                entry.getString("content").length() <= 500);
    }

    @Test
    public void notificationKeepsTheCrateTimeTypo() throws Exception {
        JSONObject action = new JSONObject(
                AppLayer.buildNotificationAction("Title", "Body"));
        JSONObject entry = action.getJSONObject("data").getJSONArray("data").getJSONObject(0);

        // ArNotificationModel.crateTime -- the misspelling is the device's, and
        // "fixing" it to createTime means the field silently never binds.
        assertTrue("crateTime is the real (misspelled) field name", entry.has("crateTime"));
        assertFalse(entry.has("createTime"));
    }

    @Test
    public void notificationHasTheFullEnvelope() throws Exception {
        JSONObject action = new JSONObject(
                AppLayer.buildNotificationAction("Hello", "World", "ARIA"));

        assertEquals("notification", action.getString("action"));
        JSONObject data = action.getJSONObject("data");
        assertEquals("SHOW_NOTIFICATION", data.getString("notificationAction"));

        // data.data is an ARRAY of notifications, not a single object.
        JSONObject entry = data.getJSONArray("data").getJSONObject(0);
        assertEquals("Hello", entry.getString("title"));
        assertEquals("World", entry.getString("content"));
        assertEquals("ARIA", entry.getString("appName"));
        assertEquals("MSG_TYPE_NORMAL", entry.getString("type"));
        assertFalse(entry.getBoolean("canReply"));
        // extra is a JSON *string*, not an object.
        assertEquals("{}", entry.getString("extra"));
    }

    @Test
    public void stMessageEnvelopeCarriesBothPackagesAndAnId() {
        // {2:src, 3:dst, 4:json, 6:msgId} -- verify the packages appear and the
        // body is embedded verbatim.
        byte[] body = new AppLayer().buildSendActionBody("{\"action\":\"ping\"}");
        String raw = new String(body, java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(raw.contains("com.upuphone.star.launcher"));
        assertTrue(raw.contains("{\"action\":\"ping\"}"));
    }

    @Test
    public void appMsgIdAdvancesPerMessage() {
        // The glasses treat a repeated app msgId as a duplicate.
        AppLayer layer = new AppLayer();
        byte[] a = layer.buildSendActionBody("{}");
        byte[] b = layer.buildSendActionBody("{}");
        assertFalse("consecutive StMessages must not be byte-identical",
                java.util.Arrays.equals(a, b));
        assertEquals(5002, layer.lastAppMsgId());
    }

    @Test
    public void appMsgIdIsPerInstanceNotStatic() {
        // A reconnect builds a fresh AppLayer; its counter must restart rather
        // than carrying over from the previous session.
        assertEquals(5001, firstIdOf(new AppLayer()));
        assertEquals(5001, firstIdOf(new AppLayer()));
    }

    private static int firstIdOf(AppLayer layer) {
        layer.buildSendActionBody("{}");
        return layer.lastAppMsgId();
    }
}

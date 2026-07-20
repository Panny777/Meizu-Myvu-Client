package com.myvu.client.app.feature;

import com.myvu.client.app.AppLayer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The notification action family, ported from applayer.push_notification and
 * the official app's StarryMessageHelper / StarryNotificationBase.
 *
 * Envelope: {"action":"notification","data":{"notificationAction":<SUB>,"data":<payload>}}
 */
public final class Notifications {
    private Notifications() {}

    public static final String SHOW = "SHOW_NOTIFICATION";
    public static final String DISMISS = "DISMISS_NOTIFICATION";

    /**
     * Longest title/content we will send. The glasses render on a small lens and
     * have shown themselves to be fragile about malformed notification payloads,
     * so oversized text is truncated rather than trusted to their renderer.
     */
    private static final int MAX_TITLE = 100;
    private static final int MAX_CONTENT = 500;

    private static JSONObject envelope(String subAction, Object payload) throws JSONException {
        return new JSONObject()
                .put("action", "notification")
                .put("data", new JSONObject()
                        .put("notificationAction", subAction)
                        .put("data", payload));
    }

    /**
     * Builds the notification id in the ONLY format the glasses accept:
     * {@code phone-<packageName>-<numericId>}, confirmed from a captured
     * DISMISS payload ("phone-com.android.settings-17301632").
     *
     * This matters more than it looks. Passing Android's own
     * StatusBarNotification.getKey() here -- which is pipe-delimited, e.g.
     * "0|com.android.shell|1|tag|10289" -- made the glasses REBOOT on every
     * mirrored notification. Keep this format; do not substitute the platform key.
     */
    public static String notificationId(String packageName, int numericId) {
        return "phone-" + packageName + "-" + numericId;
    }

    /** Trims and strips control characters that could confuse their parser. */
    private static String sanitize(String s, int max) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && sb.length() < max; i++) {
            char c = s.charAt(i);
            // Keep newline out too: the lens renders a single flowed block.
            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(' ');
            } else if (c >= 0x20) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /**
     * One notification entry (ArNotificationModel).
     *
     * The id MUST come from {@link #notificationId} -- see the warning there.
     */
    public static JSONObject entry(String packageName, int numericId, String title,
                                   String content, String appName, long postTime,
                                   boolean canReply) throws JSONException {
        return new JSONObject()
                .put("appName", sanitize(appName, MAX_TITLE))
                .put("title", sanitize(title, MAX_TITLE))
                .put("content", sanitize(content, MAX_CONTENT))
                .put("canReply", canReply)
                .put("type", "MSG_TYPE_NORMAL")
                .put("id", notificationId(packageName, numericId))
                .put("packageName", packageName)
                // "crateTime" is the device's own misspelling. Correcting it to
                // createTime means the field silently never binds.
                .put("crateTime", postTime)
                .put("extra", "{}");
    }

    /** data.data is an ARRAY, so several notifications can be pushed at once. */
    public static String buildShow(JSONObject... entries) throws JSONException {
        JSONArray arr = new JSONArray();
        for (JSONObject e : entries) arr.put(e);
        return envelope(SHOW, arr).toString();
    }

    public static String buildShow(String title, String content) throws JSONException {
        long now = System.currentTimeMillis();
        // Synthetic numeric id, so the wire format matches a real notification's.
        return buildShow(entry(AppLayer.PKG_SELF, (int) (now / 1000) & 0x7FFFFFFF,
                title, content, "ARIA", now, false));
    }

    /** Dismisses previously shown notifications by id. */
    public static String buildDismiss(String... ids) throws JSONException {
        JSONArray arr = new JSONArray();
        for (String id : ids) arr.put(id);
        JSONObject payload = new JSONObject()
                .put("type", 0)
                .put("ids", arr);
        return envelope(DISMISS, payload).toString();
    }
}

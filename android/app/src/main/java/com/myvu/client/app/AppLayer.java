package com.myvu.client.app;

import com.myvu.client.protocol.Pb;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The StMessage envelope and the action-JSON builders, ported from
 * myvu_client/myvu/applayer.py.
 *
 * Wire shape: {"action":"<name>","data":{...}} as a JSON string, wrapped in a
 * protobuf envelope {2:srcPkg, 3:dstPkg, 4:json, 6:appMsgId}. The glasses parse
 * the JSON with Gson against fixed bean classes, so key names are load-bearing
 * -- including the misspelled "crateTime", which is theirs.
 *
 * The msgId counter is per-connection instance state rather than static: the
 * glasses treat a repeated id as a duplicate, and a static counter would leak
 * across reconnects.
 */
public class AppLayer {

    public static final String PKG_LAUNCHER = "com.upuphone.star.launcher";
    public static final String PKG_TICI = "com.upuphone.ar.tici";
    public static final String PKG_AI = "com.upuphone.ai.assistant";
    public static final String PKG_NAV_GLASS = "com.upuphone.ar.navi.glass";
    public static final String PKG_NAV_PHONE = "com.upuphone.ar.navi.lite";
    public static final String PKG_INTERCONNECT = "com.upuphone.xr.interconnect";
    /** Our own package, as it appears in notification payloads. */
    public static final String PKG_SELF = "com.myvu.client";

    private static final String DEFAULT_APP_NAME = "ARIA";

    /** Matches the Python client, which sends 5001 first. */
    private int appMsgId = 5000;

    // -------------------------------------------------------- StMessage

    public byte[] buildSendActionBody(String actionJson) {
        return buildSendActionBody(actionJson, PKG_LAUNCHER, PKG_LAUNCHER);
    }

    /** StMessage envelope: {2:src, 3:dst, 4:json, 6:id}. */
    public byte[] buildSendActionBody(String actionJson, String targetPkg, String sourcePkg) {
        appMsgId += 1;
        byte[] body = Pb.string(2, sourcePkg);
        body = Pb.concat(body, Pb.string(3, targetPkg));
        body = Pb.concat(body, Pb.string(4, actionJson));
        body = Pb.concat(body, Pb.varintField(6, appMsgId));
        return body;
    }

    public int lastAppMsgId() {
        return appMsgId;
    }

    // --------------------------------------------------- action builders

    public static String buildNotificationAction(String title, String content)
            throws JSONException {
        return buildNotificationAction(title, content, DEFAULT_APP_NAME);
    }

    public static String buildNotificationAction(String title, String content, String appName)
            throws JSONException {
        long now = System.currentTimeMillis();
        JSONObject entry = new JSONObject()
                .put("appName", appName)
                .put("title", title)
                .put("content", content)
                .put("canReply", false)
                .put("type", "MSG_TYPE_NORMAL")
                .put("id", "phone-android-" + (now / 1000))
                .put("packageName", PKG_SELF)
                // "crateTime" is the device's own misspelling (ArNotificationModel);
                // correcting it to createTime means the field silently never binds.
                .put("crateTime", now)
                .put("extra", "{}");
        return new JSONObject()
                .put("action", "notification")
                .put("data", new JSONObject()
                        .put("notificationAction", "SHOW_NOTIFICATION")
                        .put("data", new JSONArray().put(entry)))
                .toString();
    }
}

package com.myvu.client.app.feature;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.TimeZone;

/**
 * Clock sync, ported from applayer.sync_time.
 *
 * Bidirectional: the glasses also ASK for the time by sending a SyncOffSetTime
 * with no syncTimeData field, and expect the phone to answer with one that has
 * it. See InboundRouter.
 */
public final class ClockSync {
    private ClockSync() {}

    public static final String ACTION = "SyncOffSetTime";

    public static String build() throws JSONException {
        long now = System.currentTimeMillis();
        // Android's getOffset already accounts for DST at this instant, which
        // is simpler than Python's timezone/altzone branch.
        int offsetMs = TimeZone.getDefault().getOffset(now);

        return new JSONObject()
                .put("action", ACTION)
                .put("data", new JSONObject()
                        // syncTimeData is a STRING on the wire, not a number.
                        .put("syncTimeData", String.valueOf(now))
                        .put("timeZoneOffSet", offsetMs))
                .toString();
    }

    /**
     * True when an inbound SyncOffSetTime is a REQUEST (no syncTimeData) rather
     * than the glasses reporting their own time.
     */
    public static boolean isRequest(JSONObject action) {
        if (!ACTION.equals(action.optString("action"))) return false;
        JSONObject data = action.optJSONObject("data");
        return data == null || !data.has("syncTimeData");
    }
}

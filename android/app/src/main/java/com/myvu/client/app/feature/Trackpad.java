package com.myvu.client.app.feature;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds "phonepad" (trackpad) messages -- the phone acting as a remote touchpad
 * for the glasses' launcher, letting you navigate the lens UI by touch.
 *
 * Reverse-engineered from the official app's TouchpadUtil: every event is JSON
 * wrapped as {"action":"phonepad","data":{...}} and sent to the launcher
 * (com.upuphone.star.launcher). Taps are click/doubleClick/longPress; a swipe is
 * "gestureMode" whose actionType is the Android key code for the direction.
 */
public final class Trackpad {
    private Trackpad() {}

    // Swipe directions ARE Android KeyEvent codes -- the values the glasses expect
    // (TouchpadFragment.Q0: dx>0 -> RIGHT(22)/LEFT(21); dy>0 -> DOWN(20)/UP(19)).
    public static final int SWIPE_UP = 19;
    public static final int SWIPE_DOWN = 20;
    public static final int SWIPE_LEFT = 21;
    public static final int SWIPE_RIGHT = 22;

    /** Signals the glasses to enter phone-pad mode. Sent when the pad opens. */
    public static String start()       { return simple("start"); }
    /** Leaves phone-pad mode. Sent when the pad closes. */
    public static String stop()        { return simple("stop"); }
    public static String click()       { return simple("click"); }
    public static String doubleClick() { return simple("doubleClick"); }
    public static String longPress()   { return simple("longPress"); }

    public static String swipe(int direction, float startX, float startY,
                               float endX, float endY, float speedX, float speedY) {
        try {
            JSONObject data = new JSONObject();
            data.put("action", "gestureMode");
            data.put("actionType", direction);
            data.put("startX", startX);
            data.put("startY", startY);
            data.put("endX", endX);
            data.put("endY", endY);
            data.put("speedX", speedX);
            data.put("speedY", speedY);
            data.put("time", System.currentTimeMillis());
            return wrap(data);
        } catch (JSONException e) {
            throw new IllegalStateException(e); // JSONObject never throws here
        }
    }

    private static String simple(String action) {
        try {
            JSONObject data = new JSONObject();
            data.put("action", action);
            data.put("time", System.currentTimeMillis());
            return wrap(data);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String wrap(JSONObject data) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("action", "phonepad");
        o.put("data", data);
        return o.toString();
    }
}

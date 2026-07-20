package com.myvu.client.app.feature;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The launcher "system" action family, ported from applayer.py.
 *
 * THERE ARE TWO PAYLOAD SHAPES and mixing them up is a silent no-op:
 *
 *  FLAT  -- {"action":"system","data":{"action":"set_volume","value":"7",...}}
 *           used by set_volume / set_brightness / toggle_wifi. Note that
 *           volume and brightness send their value as a STRING.
 *
 *  NESTED -- {"action":"system","data":{"action":"set_zen_mode",
 *                                       "value":{"zen_mode":false}}}
 *           used by the ControlUtils family, where the value is an object
 *           whose single key repeats the setting name.
 *
 * Both are unit-tested in SystemSettingsTest so a future edit cannot quietly
 * collapse them into one.
 */
public final class SystemSettings {
    private SystemSettings() {}

    /** {"action":"system","data":{"action":<a>, ...extras}} */
    private static JSONObject envelope(String subAction) throws JSONException {
        return new JSONObject()
                .put("action", "system")
                .put("data", new JSONObject().put("action", subAction));
    }

    /** The NESTED form: value is an object keyed by the setting name. */
    private static String nested(String subAction, String key, Object value)
            throws JSONException {
        JSONObject env = envelope(subAction);
        env.getJSONObject("data").put("value", new JSONObject().put(key, value));
        return env.toString();
    }

    // ------------------------------------------------------- flat-value form

    /** Volume 0-15. streamType 3 matches captured telemetry. */
    public static String setVolume(int value, int streamType) throws JSONException {
        JSONObject env = envelope("set_volume");
        env.getJSONObject("data")
                .put("value", String.valueOf(value)) // string, not int
                .put("streamType", streamType)
                .put("needReply", false);
        return env.toString();
    }

    public static String setVolume(int value) throws JSONException {
        return setVolume(value, 3);
    }

    /** Screen brightness; observed range roughly 0-10. */
    public static String setBrightness(int value) throws JSONException {
        JSONObject env = envelope("set_brightness");
        env.getJSONObject("data").put("value", String.valueOf(value)); // string
        return env.toString();
    }

    /** Turns the glasses' own WiFi radio on/off. Boolean value, flat. */
    public static String toggleWifi(boolean enable) throws JSONException {
        JSONObject env = envelope("toggle_wifi");
        env.getJSONObject("data").put("value", enable);
        return env.toString();
    }

    // ----------------------------------------------------- nested-value form

    public static String setLanguage(String language, String country) throws JSONException {
        JSONObject env = envelope("set_language");
        env.getJSONObject("data").put("value", new JSONObject()
                .put("language", language)
                .put("country", country));
        return env.toString();
    }

    public static String setDeviceName(String name) throws JSONException {
        return nested("set_device_name", "device_name", name);
    }

    /** Display auto-off timeout, in seconds. */
    public static String setScreenOffTime(int seconds) throws JSONException {
        return nested("set_screen_off_time", "screen_off_time", seconds);
    }

    /** Do-not-disturb. */
    public static String setZenMode(boolean on) throws JSONException {
        return nested("set_zen_mode", "zen_mode", on);
    }

    /**
     * MYVU's stripped-back low-power HUD -- NOT airplane mode. The app's own
     * confirm dialog warns that enabling it CLOSES ALL APPS.
     */
    public static String setAirMode(boolean on) throws JSONException {
        return nested("set_air_mode", "air_mode", on);
    }

    /** Auto on/off when the glasses are worn or removed. */
    public static String setWearDetection(boolean on) throws JSONException {
        return nested("set_wear_detection_mode", "wear_detection_mode", on);
    }

    public static String setMusicTpControl(boolean on) throws JSONException {
        return nested("set_music_tp_control_mode", "music_tp_control_mode", on);
    }

    /** Field-of-view position of the idle standby widgets. Confirmed range 0-3. */
    public static String setStandbyPosition(int position) throws JSONException {
        return nested("set_standby_position", "standby_position", position);
    }

    /** Field-of-view position type. The enum's meaning was never established. */
    public static String setFovPosType(int value) throws JSONException {
        return nested("set_fov_pos_type", "fov_pos", value);
    }

    // ------------------------------------------------------------- queries

    /**
     * Any no-argument "system" query, e.g. get_device_info, get_language,
     * get_brightness, get_zen_mode, request_wifi_list, request_phone_battery.
     *
     * Replies are ASYNCHRONOUS -- they arrive later as ordinary inbound relay
     * messages, so there is no return value to wait on here.
     */
    public static String query(String subAction) throws JSONException {
        return envelope(subAction).toString();
    }
}

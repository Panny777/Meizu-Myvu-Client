package com.myvu.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.myvu.client.app.feature.ClockSync;
import com.myvu.client.app.feature.SystemSettings;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The "system" family has two incompatible payload shapes and the glasses
 * silently ignore the wrong one. These tests pin both down so a later cleanup
 * cannot collapse them.
 */
public class SystemSettingsTest {

    private static JSONObject dataOf(String actionJson) throws Exception {
        JSONObject env = new JSONObject(actionJson);
        assertEquals("system", env.getString("action"));
        return env.getJSONObject("data");
    }

    @Test
    public void volumeUsesFlatStringValue() throws Exception {
        JSONObject data = dataOf(SystemSettings.setVolume(7));
        assertEquals("set_volume", data.getString("action"));
        // A STRING, not a number -- and flat, not nested under an object.
        assertEquals("7", data.getString("value"));
        assertEquals(3, data.getInt("streamType"));
        assertFalse(data.getBoolean("needReply"));
    }

    @Test
    public void brightnessUsesFlatStringValue() throws Exception {
        JSONObject data = dataOf(SystemSettings.setBrightness(10));
        assertEquals("set_brightness", data.getString("action"));
        assertEquals("10", data.getString("value"));
    }

    @Test
    public void toggleWifiUsesFlatBoolean() throws Exception {
        JSONObject data = dataOf(SystemSettings.toggleWifi(true));
        assertEquals("toggle_wifi", data.getString("action"));
        assertTrue(data.getBoolean("value"));
    }

    @Test
    public void zenModeNestsUnderAValueObject() throws Exception {
        JSONObject data = dataOf(SystemSettings.setZenMode(false));
        assertEquals("set_zen_mode", data.getString("action"));
        // Nested: {"value":{"zen_mode":false}}
        assertFalse(data.getJSONObject("value").getBoolean("zen_mode"));
    }

    @Test
    public void wearDetectionNestsUnderItsOwnKeyName() throws Exception {
        JSONObject data = dataOf(SystemSettings.setWearDetection(true));
        assertEquals("set_wear_detection_mode", data.getString("action"));
        // The nested key repeats the setting name, including the _mode suffix.
        assertTrue(data.getJSONObject("value").getBoolean("wear_detection_mode"));
    }

    @Test
    public void screenOffTimeNestsAnInteger() throws Exception {
        JSONObject data = dataOf(SystemSettings.setScreenOffTime(10));
        assertEquals(10, data.getJSONObject("value").getInt("screen_off_time"));
    }

    @Test
    public void languageNestsTwoKeysInOneObject() throws Exception {
        JSONObject value = dataOf(SystemSettings.setLanguage("en", "US")).getJSONObject("value");
        assertEquals("en", value.getString("language"));
        assertEquals("US", value.getString("country"));
    }

    @Test
    public void queryCarriesNoValueAtAll() throws Exception {
        JSONObject data = dataOf(SystemSettings.query("get_device_info"));
        assertEquals("get_device_info", data.getString("action"));
        assertFalse("queries must not carry a value key", data.has("value"));
    }

    @Test
    public void clockSyncSendsMillisAsAString() throws Exception {
        JSONObject env = new JSONObject(ClockSync.build());
        assertEquals("SyncOffSetTime", env.getString("action"));

        JSONObject data = env.getJSONObject("data");
        // syncTimeData is a string on the wire even though it holds a number.
        String millis = data.getString("syncTimeData");
        assertTrue(Long.parseLong(millis) > 1_600_000_000_000L);
        // The offset is in milliseconds, so it must be a whole number of minutes.
        assertEquals(0, data.getInt("timeZoneOffSet") % 60000);
    }

    @Test
    public void clockSyncRequestIsDetectedByTheMissingField() throws Exception {
        // The glasses ask for the time by sending the action with no data.
        assertTrue(ClockSync.isRequest(new JSONObject("{\"action\":\"SyncOffSetTime\"}")));
        assertTrue(ClockSync.isRequest(
                new JSONObject("{\"action\":\"SyncOffSetTime\",\"data\":{}}")));
        // Our own outbound message is not a request.
        assertFalse(ClockSync.isRequest(new JSONObject(ClockSync.build())));
        assertFalse(ClockSync.isRequest(new JSONObject("{\"action\":\"volume\"}")));
    }
}

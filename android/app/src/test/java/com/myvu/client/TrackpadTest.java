package com.myvu.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.myvu.client.app.feature.Trackpad;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Pins the "phonepad" wire format against the official app's TouchpadUtil:
 * {"action":"phonepad","data":{...}} to the launcher, taps as click/doubleClick/
 * longPress, swipes as "gestureMode" with actionType = the direction key code.
 */
public class TrackpadTest {

    private static JSONObject dataOf(String actionJson) throws Exception {
        JSONObject env = new JSONObject(actionJson);
        assertEquals("phonepad", env.getString("action"));
        return env.getJSONObject("data");
    }

    @Test
    public void clickShape() throws Exception {
        JSONObject d = dataOf(Trackpad.click());
        assertEquals("click", d.getString("action"));
        assertTrue("carries a timestamp", d.has("time"));
    }

    @Test
    public void doubleClickAndLongPress() throws Exception {
        assertEquals("doubleClick", dataOf(Trackpad.doubleClick()).getString("action"));
        assertEquals("longPress", dataOf(Trackpad.longPress()).getString("action"));
    }

    @Test
    public void startAndStop() throws Exception {
        assertEquals("start", dataOf(Trackpad.start()).getString("action"));
        assertEquals("stop", dataOf(Trackpad.stop()).getString("action"));
    }

    @Test
    public void directionsAreKeyCodes() {
        assertEquals(19, Trackpad.SWIPE_UP);
        assertEquals(20, Trackpad.SWIPE_DOWN);
        assertEquals(21, Trackpad.SWIPE_LEFT);
        assertEquals(22, Trackpad.SWIPE_RIGHT);
    }

    @Test
    public void swipeShape() throws Exception {
        JSONObject d = dataOf(Trackpad.swipe(Trackpad.SWIPE_UP, 10f, 20f, 30f, 40f, 0.5f, 0.6f));
        assertEquals("gestureMode", d.getString("action"));
        assertEquals(19, d.getInt("actionType"));
        assertEquals(10.0, d.getDouble("startX"), 0.001);
        assertEquals(20.0, d.getDouble("startY"), 0.001);
        assertEquals(30.0, d.getDouble("endX"), 0.001);
        assertEquals(40.0, d.getDouble("endY"), 0.001);
        assertEquals(0.5, d.getDouble("speedX"), 0.001);
        assertEquals(0.6, d.getDouble("speedY"), 0.001);
        assertTrue(d.has("time"));
    }
}

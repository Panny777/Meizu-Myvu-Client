package com.myvu.client.app.feature;

import com.myvu.client.app.AppLayer;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * AR navigation HUD frames, ported from applayer.py (which in turn ports
 * com.upuphone.ar.navi.lite's NotifyUtils / ProtocolUtils).
 *
 * The glasses render the overlay themselves from structured data -- we launch
 * the nav app and then stream navi_info frames at ~1Hz.
 *
 * IMPORTANT: none of this works until the phone answers the glasses' launch-app
 * request (type:11 -> type:12) -- see InboundRouter. Without that ack the nav
 * app ignores every frame and re-asks forever.
 *
 * The short keys are the app's own:
 *   ic  maneuver icon        pd   total route distance (m)
 *   prd remaining distance   prt  remaining time (s)
 *   nrn next road name       nrd  distance to the next turn (m)
 *   ns  speed text           rdd  distance ridden (m)
 *   gs  GPS fix (1 = ok)     hsr  road class
 *   bts auto-brightness      ack  epoch millis
 */
public final class NavCommands {
    private NavCommands() {}

    /**
     * ROUTING IS NOT UNIFORM ACROSS THESE MESSAGES, and getting it wrong makes
     * navigation silently do nothing:
     *
     *  - open/start are open_app requests, so they go to the LAUNCHER -- it is
     *    the launcher that opens apps. Addressing them to the nav app itself
     *    means nothing acts on them.
     *  - navi_info/navi_event are HUD data, so they go to the nav app directly.
     *
     * Both are always SOURCED from the phone-side nav package.
     */
    public static final String SOURCE_PKG = AppLayer.PKG_NAV_PHONE;
    /** Target for buildOpen/buildStart. */
    public static final String LAUNCH_TARGET_PKG = AppLayer.PKG_LAUNCHER;
    /** Target for buildNaviInfo/buildEvent/buildStop. */
    public static final String FRAME_TARGET_PKG = AppLayer.PKG_NAV_GLASS;

    /** Launches the AR nav app with no initial data. */
    public static String buildOpen() throws JSONException {
        return openApp("");
    }

    /**
     * Launches the HUD AND starts navigation in one go -- the phone-initiated
     * path (NaviFragment.openAndStartGlass). The ext carries the initial pre-nav
     * frame as a JSON string, without the "identity" wrapper that navi_info uses.
     * The glasses reply navi_start_rsp; after that, stream navi_info frames.
     */
    public static String buildStart(int ic, int pathDistanceM, int remainingM,
                                    int remainingS, String nextRoadName,
                                    int nextRoadDistanceM, String speed,
                                    int rideDistanceM, int gpsStatus, int roadClass,
                                    int naviMode, int displayPos, boolean maskMsg,
                                    boolean brightness) throws JSONException {
        JSONObject ext = new JSONObject()
                .put("naviMode", naviMode)
                .put("displayPos", displayPos)
                .put("maskMsg", maskMsg ? 1 : 0)
                .put("brightness", brightness ? 1 : 0)
                .put("ic", ic)
                .put("pd", pathDistanceM)
                .put("prd", remainingM)
                .put("prt", remainingS)
                .put("nrn", nextRoadName)
                .put("nrd", nextRoadDistanceM)
                .put("ns", speed)
                .put("rdd", rideDistanceM)
                .put("gs", gpsStatus)
                .put("hsr", roadClass)
                .put("ack", System.currentTimeMillis());
        return openApp(ext.toString());
    }

    private static String openApp(String ext) throws JSONException {
        return new JSONObject()
                .put("action", "app")
                .put("data", new JSONObject()
                        .put("launchMode", "scene")
                        .put("action", "open_app")
                        .put("pkg", AppLayer.PKG_NAV_GLASS)
                        .put("show_status_bar", false)
                        // ext is a JSON STRING (empty when just opening).
                        .put("ext", ext)
                        .put("app_name", "Navigation"))
                .toString();
    }

    /** One turn-by-turn frame. Sent nav-phone -> nav-glasses, not via the launcher. */
    public static String buildNaviInfo(int ic, int pathDistanceM, int remainingM,
                                       int remainingS, String nextRoadName,
                                       int nextRoadDistanceM, String speed,
                                       int rideDistanceM, int gpsStatus, int roadClass,
                                       int brightness) throws JSONException {
        return new JSONObject()
                .put("identity", "navi_info")
                .put("ic", ic)
                .put("pd", pathDistanceM)
                .put("prd", remainingM)
                .put("prt", remainingS)
                .put("nrn", nextRoadName)
                .put("nrd", nextRoadDistanceM)
                .put("ns", speed)
                .put("rdd", rideDistanceM)
                .put("gs", gpsStatus)
                .put("hsr", roadClass)
                .put("bts", brightness)
                .put("ack", System.currentTimeMillis())
                .toString();
    }

    /** e.g. event="navi_stop" to end navigation on the glasses. */
    public static String buildEvent(String event, int naviMode) throws JSONException {
        return new JSONObject()
                .put("identity", "navi_event")
                .put("naviMode", naviMode)
                .put("data", event)
                .put("ack", System.currentTimeMillis())
                .toString();
    }

    public static String buildStop() throws JSONException {
        return buildEvent("navi_stop", 0);
    }
}

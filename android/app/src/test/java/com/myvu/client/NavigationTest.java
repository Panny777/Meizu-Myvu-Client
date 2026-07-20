package com.myvu.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.myvu.client.app.feature.NavCommands;
import com.myvu.client.nav.Geo;
import com.myvu.client.nav.IcMap;
import com.myvu.client.nav.Route;
import com.myvu.client.nav.RouteTracker;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NavigationTest {

    // ---------------------------------------------------------------- geo

    @Test
    public void haversineMatchesAKnownDistance() {
        // Dar es Salaam -> Dodoma is roughly 380 km.
        double d = Geo.haversine(-6.7924, 39.2083, -6.1630, 35.7516);
        assertTrue("got " + d, d > 380_000 && d < 400_000);
    }

    @Test
    public void haversineIsZeroForTheSamePoint() {
        assertEquals(0.0, Geo.haversine(-6.7924, 39.2083, -6.7924, 39.2083), 0.001);
    }

    @Test
    public void haversineIsSymmetric() {
        double a = Geo.haversine(-6.79, 39.20, -6.80, 39.21);
        double b = Geo.haversine(-6.80, 39.21, -6.79, 39.20);
        assertEquals(a, b, 0.001);
    }

    // --------------------------------------------------------------- icons

    @Test
    public void maneuverTypeTakesPriorityOverModifier() {
        // A roundabout with a "right" modifier is still a roundabout icon.
        assertEquals(9, IcMap.forManeuver("roundabout", "right"));
        assertEquals(2, IcMap.forManeuver("turn", "right"));
    }

    @Test
    public void unknownManeuversFallBackToStraight() {
        assertEquals(IcMap.DEFAULT_IC, IcMap.forManeuver("no-such-type", "no-such-modifier"));
        assertEquals(IcMap.DEFAULT_IC, IcMap.forManeuver(null, null));
    }

    // -------------------------------------------------------------- tracker

    private static Route straightRoute() {
        // Four vertices ~111m apart along a meridian, with a turn at ~222m.
        List<Route.Vertex> verts = new ArrayList<>();
        verts.add(new Route.Vertex(0.000, 0.0, 0));
        verts.add(new Route.Vertex(0.001, 0.0, 111));
        verts.add(new Route.Vertex(0.002, 0.0, 222));
        verts.add(new Route.Vertex(0.003, 0.0, 333));

        List<Route.Step> steps = Arrays.asList(
                new Route.Step(1, "First Road", 222, 30, "depart", "", 0),
                new Route.Step(2, "Second Road", 111, 15, "turn", "right", 222),
                new Route.Step(15, "", 0, 0, "arrive", "", 333));

        return new Route(steps, 333, 45, verts);
    }

    @Test
    public void trackerReportsProgressAndTheNextManeuver() {
        RouteTracker.State s = new RouteTracker(straightRoute()).update(0.001, 0.0);

        assertEquals(111, s.travelledM, 1.0);
        assertEquals(222, s.remainingM, 1.0);
        assertFalse(s.offRoute);
        assertNotNull(s.nextStep);
        assertEquals("the turn ahead, not the one behind", 2, s.nextStep.ic);
        assertEquals(111, s.distToNextM, 1.0);
    }

    @Test
    public void trackerDetectsBeingOffRoute() {
        // ~1.1km east of the polyline, far beyond the 45m threshold.
        RouteTracker.State s = new RouteTracker(straightRoute()).update(0.001, 0.01);
        assertTrue(s.offRoute);
        assertTrue(s.deviationM > RouteTracker.OFF_ROUTE_M);
    }

    @Test
    public void trackerReturnsNoNextStepAtTheEnd() {
        RouteTracker.State s = new RouteTracker(straightRoute()).update(0.003, 0.0);
        assertNull("past the last maneuver", s.nextStep);
        assertEquals(0, s.remainingM, 1.0);
    }

    @Test
    public void trackerSurvivesAnEmptyRoute() {
        Route empty = new Route(new ArrayList<Route.Step>(), 0, 0,
                new ArrayList<Route.Vertex>());
        RouteTracker.State s = new RouteTracker(empty).update(1.0, 1.0);
        assertEquals(0.0, s.travelledM, 0.001);
        assertFalse(s.offRoute);
    }

    // ------------------------------------------------------- wire payloads

    @Test
    public void naviInfoUsesTheAppsShortKeys() throws Exception {
        JSONObject f = new JSONObject(NavCommands.buildNaviInfo(
                2, 5000, 3000, 400, "Samora Avenue", 250, "48", 2000, 1, 0, 0));

        assertEquals("navi_info", f.getString("identity"));
        assertEquals(2, f.getInt("ic"));
        assertEquals(5000, f.getInt("pd"));
        assertEquals(3000, f.getInt("prd"));
        assertEquals(400, f.getInt("prt"));
        assertEquals("Samora Avenue", f.getString("nrn"));
        assertEquals(250, f.getInt("nrd"));
        // Speed is a STRING on the wire, not a number.
        assertEquals("48", f.getString("ns"));
        assertEquals(1, f.getInt("gs"));
        assertTrue(f.getLong("ack") > 1_600_000_000_000L);
    }

    @Test
    public void startCarriesExtAsAJsonString() throws Exception {
        JSONObject env = new JSONObject(NavCommands.buildStart(
                1, 1000, 1000, 120, "Ali Hassan Mwinyi Road", 300,
                "0", 0, 1, 0, 0, 0, false, false));
        JSONObject data = env.getJSONObject("data");

        assertEquals("open_app", data.getString("action"));
        assertEquals("com.upuphone.ar.navi.glass", data.getString("pkg"));
        assertFalse(data.getBoolean("show_status_bar"));

        // ext must be a STRING containing JSON, not a nested object.
        String ext = data.getString("ext");
        JSONObject parsed = new JSONObject(ext);
        assertEquals(1, parsed.getInt("ic"));
        assertEquals("Ali Hassan Mwinyi Road", parsed.getString("nrn"));
        // The initial frame omits the navi_info "identity" wrapper.
        assertFalse(parsed.has("identity"));
    }

    @Test
    public void openSendsAnEmptyExt() throws Exception {
        JSONObject data = new JSONObject(NavCommands.buildOpen()).getJSONObject("data");
        assertEquals("", data.getString("ext"));
    }

    @Test
    public void stopIsANaviEvent() throws Exception {
        JSONObject e = new JSONObject(NavCommands.buildStop());
        assertEquals("navi_event", e.getString("identity"));
        assertEquals("navi_stop", e.getString("data"));
    }

    /**
     * REGRESSION: open_app was addressed to the nav app, so nothing ever acted
     * on it and navigation silently never started. open/start must go to the
     * LAUNCHER (it is what opens apps); only HUD data goes to the nav app.
     */
    @Test
    public void launchAndFrameMessagesTargetDifferentPackages() {
        assertEquals("open_app must go to the launcher",
                "com.upuphone.star.launcher", NavCommands.LAUNCH_TARGET_PKG);
        assertEquals("HUD frames go straight to the nav app",
                "com.upuphone.ar.navi.glass", NavCommands.FRAME_TARGET_PKG);
        assertEquals("everything is sourced from the phone-side nav package",
                "com.upuphone.ar.navi.lite", NavCommands.SOURCE_PKG);
        assertFalse("the two targets must not be the same",
                NavCommands.LAUNCH_TARGET_PKG.equals(NavCommands.FRAME_TARGET_PKG));
    }
}

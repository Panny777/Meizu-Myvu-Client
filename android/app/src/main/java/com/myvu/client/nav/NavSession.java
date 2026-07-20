package com.myvu.client.nav;

import android.content.Context;
import android.os.Handler;

import com.myvu.client.app.AppLayer;
import com.myvu.client.app.feature.NavCommands;
import com.myvu.client.core.LogBus;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives turn-by-turn navigation on the lens.
 *
 * Lifecycle: route -> open the HUD with the first frame -> stream navi_info at
 * 1Hz from live position -> stop.
 *
 * THREADING: routing is blocking HTTP and runs on its own executor, never on the
 * connection thread (which would stall the relay). Location fixes arrive on the
 * main looper and are handed straight to the connection thread, where all
 * protocol state lives.
 */
public class NavSession {

    /** How far off-route before we re-route. Re-routing is rate-limited. */
    private static final long REROUTE_COOLDOWN_MS = 15000;

    public interface Sender {
        /** Sends an action with explicit routing packages. */
        void send(String actionJson, String targetPkg, String sourcePkg);
    }

    private final Context context;
    private final Handler conn;
    private final Sender sender;
    private final LocationSource locationSource;
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    private Route route;
    private RouteTracker tracker;
    private volatile boolean active;

    private double destLat;
    private double destLon;
    private double lastLat;
    private double lastLon;
    private long lastRerouteAt;
    private double rideDistanceM;

    public NavSession(Context context, Handler conn, Sender sender, LocationSource source) {
        this.context = context.getApplicationContext();
        this.conn = conn;
        this.sender = sender;
        this.locationSource = source;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Routes from the current position to {@code destination} (a place name or
     * "lat,lon") and starts the HUD. Returns immediately; progress is logged.
     */
    public void start(final String destination) {
        if (active) {
            LogBus.warn("navigation already running -- stop it first");
            return;
        }
        active = true;
        rideDistanceM = 0;

        // We need one fix before we can route, so start location first and
        // kick off routing on the first position we get.
        locationSource.start(new LocationSource.Listener() {
            private boolean routed;

            @Override
            public void onFix(double lat, double lon, float speedMps, float bearing) {
                if (!active) return;
                if (!routed) {
                    routed = true;
                    beginRouting(lat, lon, destination);
                }
                onPosition(lat, lon, speedMps);
            }

            @Override
            public void onUnavailable(String reason) {
                LogBus.warn("navigation cannot start: " + reason);
                stop();
            }
        });
    }

    private void beginRouting(final double lat, final double lon, final String destination) {
        LogBus.log("routing to \"" + destination + "\"...");
        net.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    double[] dest = Osrm.parsePoint(destination);
                    final Route r = Osrm.route(lat, lon, dest[0], dest[1], "driving");
                    destLat = dest[0];
                    destLon = dest[1];
                    conn.post(new Runnable() {
                        @Override
                        public void run() {
                            adoptRoute(r, true);
                        }
                    });
                } catch (Exception e) {
                    LogBus.error("routing failed", e);
                    stop();
                }
            }
        });
    }

    private void adoptRoute(Route r, boolean openHud) {
        route = r;
        tracker = new RouteTracker(r);
        if (!openHud) {
            LogBus.log("re-routed: " + r.totalDistanceM + "m remaining");
            return;
        }
        try {
            Route.Step first = r.steps.isEmpty() ? null : r.steps.get(0);
            String actionJson = NavCommands.buildStart(
                    first != null ? first.ic : IcMap.DEFAULT_IC,
                    r.totalDistanceM,
                    r.totalDistanceM,
                    (int) r.totalDurationS,
                    first != null ? first.road : "",
                    first != null ? (int) first.atM : 0,
                    "0", 0, 1, 0, 0, 0, false, false);
            // An open_app request goes to the LAUNCHER -- it is the launcher
            // that opens apps. Addressing it to the nav app means nothing acts
            // on it and navigation silently never starts.
            sender.send(actionJson, NavCommands.LAUNCH_TARGET_PKG, NavCommands.SOURCE_PKG);
            LogBus.log("navigation started: " + r.totalDistanceM + "m, "
                    + Math.round(r.totalDurationS / 60) + " min, " + r.steps.size() + " steps");
        } catch (Exception e) {
            LogBus.error("could not start navigation", e);
        }
    }

    /** Called on every fix; posts the HUD update onto the connection thread. */
    private void onPosition(final double lat, final double lon, final float speedMps) {
        conn.post(new Runnable() {
            @Override
            public void run() {
                if (!active || tracker == null) return;

                if (lastLat != 0 || lastLon != 0) {
                    rideDistanceM += Geo.haversine(lastLat, lastLon, lat, lon);
                }
                lastLat = lat;
                lastLon = lon;

                RouteTracker.State s = tracker.update(lat, lon);
                if (s.offRoute) {
                    maybeReroute(lat, lon, s.deviationM);
                    return;
                }
                pushFrame(s, speedMps);
            }
        });
    }

    private void pushFrame(RouteTracker.State s, float speedMps) {
        try {
            Route.Step next = s.nextStep;
            // Remaining time scaled from the original estimate by progress; the
            // glasses only display it, so a proportional estimate is adequate.
            double fraction = route.totalDistanceM > 0
                    ? s.remainingM / route.totalDistanceM : 0;
            int remainingS = (int) (route.totalDurationS * fraction);

            String speedText = speedMps >= 0
                    ? String.valueOf(Math.round(speedMps * 3.6)) : "0";

            String actionJson = NavCommands.buildNaviInfo(
                    next != null ? next.ic : IcMap.DEFAULT_IC,
                    route.totalDistanceM,
                    (int) s.remainingM,
                    remainingS,
                    next != null ? next.road : "",
                    (int) s.distToNextM,
                    speedText,
                    (int) rideDistanceM,
                    1, 0, 0);
            sender.send(actionJson, NavCommands.FRAME_TARGET_PKG, NavCommands.SOURCE_PKG);

            if (s.remainingM < 20) {
                LogBus.log("destination reached");
                stop();
            }
        } catch (Exception e) {
            LogBus.error("could not send a nav frame", e);
        }
    }

    private void maybeReroute(final double lat, final double lon, double deviation) {
        long now = System.currentTimeMillis();
        if (now - lastRerouteAt < REROUTE_COOLDOWN_MS) return;
        lastRerouteAt = now;

        LogBus.log(String.format(Locale.US,
                "off route by %.0fm -- recalculating", deviation));
        net.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Route r = Osrm.route(lat, lon, destLat, destLon, "driving");
                    conn.post(new Runnable() {
                        @Override
                        public void run() {
                            if (active) adoptRoute(r, false);
                        }
                    });
                } catch (Exception e) {
                    LogBus.error("re-routing failed", e);
                }
            }
        });
    }

    public void stop() {
        if (!active) return;
        active = false;
        locationSource.stop();
        try {
            sender.send(NavCommands.buildStop(),
                    NavCommands.FRAME_TARGET_PKG, NavCommands.SOURCE_PKG);
        } catch (Exception e) {
            LogBus.error("could not send navi_stop", e);
        }
        route = null;
        tracker = null;
        lastLat = 0;
        lastLon = 0;
        LogBus.log("navigation stopped");
    }

    public void shutdown() {
        stop();
        net.shutdownNow();
    }

    /**
     * Sends a single navi_info frame with an arbitrary icon value, for
     * calibrating the provisional IcMap against what the lens actually draws.
     */
    public void sendCalibrationFrame(int ic, String roadName) {
        try {
            sender.send(
                    NavCommands.buildNaviInfo(ic, 1000, 1000, 120, roadName, 300,
                            "0", 0, 1, 0, 0),
                    NavCommands.FRAME_TARGET_PKG, NavCommands.SOURCE_PKG);
            LogBus.log("calibration frame sent with ic=" + ic);
        } catch (Exception e) {
            LogBus.error("calibration frame failed", e);
        }
    }
}

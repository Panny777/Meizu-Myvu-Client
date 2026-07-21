package com.myvu.client.weather;

import android.content.Context;
import android.os.Handler;

import com.myvu.client.app.feature.Weather;
import com.myvu.client.core.LogBus;
import com.myvu.client.core.Prefs;
import com.myvu.client.nav.LocationSource;
import com.myvu.client.nav.Osrm;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the glasses' weather panel up to date.
 *
 * Cadence mirrors the official app exactly: push on connect, then refresh every
 * 30 minutes, and retry after 30 seconds on failure.
 *
 * Location comes from one of two places. If the user typed a place in Settings
 * we geocode that (reusing nav/Osrm's Nominatim call); otherwise we take a
 * SINGLE fix from the LocationSource and immediately stop it -- a weather sync
 * every half hour has no business holding a 1 Hz GPS stream open.
 *
 * THREADING: refresh() may be called from the connection thread; all network
 * and location work happens off it, and the send is posted back onto it.
 */
public class WeatherSync {

    /** How often to refresh, matching the official app's WeatherMonitor. */
    private static final long REFRESH_MS = 30 * 60 * 1000L;
    /** Its retry delay after a failed query. */
    private static final long RETRY_MS = 30 * 1000L;
    /** Bound on waiting for a location fix before giving up on this round. */
    private static final long FIX_TIMEOUT_MS = 20 * 1000L;

    public interface Sender {
        void send(String actionJson);
    }

    private final Context context;
    private final Handler conn;
    private final Sender sender;
    private final LocationSource locationSource;
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    private boolean running;
    /** Guards against two overlapping rounds (timer firing while one is in flight). */
    private boolean inFlight;

    public WeatherSync(Context context, Handler conn, Sender sender, LocationSource locationSource) {
        this.context = context.getApplicationContext();
        this.conn = conn;
        this.sender = sender;
        this.locationSource = locationSource;
    }

    /**
     * Begins the cycle AND pushes immediately. Safe to call repeatedly.
     *
     * It deliberately does not bail out when already running. applyDefaults()
     * calls this on every connect, including a relay reconnect where the sync
     * object survives -- and the glasses expect fresh state then, exactly like
     * the clock and the settings around it. An early return there left them
     * showing whatever the weather was when the app last started.
     *
     * Re-entry is harmless: refresh() has its own in-flight guard, and done()
     * clears the pending timer before scheduling the next one, so no duplicate
     * timers accumulate.
     */
    public void start() {
        running = true;
        refresh();
    }

    public void stop() {
        running = false;
        conn.removeCallbacks(refreshTick);
        locationSource.stop();
    }

    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };

    /** Runs one round now, and schedules the next. */
    public void refresh() {
        if (!Prefs.weatherEnabled(context)) {
            LogBus.trace("weather sync is switched off");
            return;
        }
        if (inFlight) return;
        inFlight = true;

        String place = Prefs.weatherPlace(context).trim();
        if (!place.isEmpty()) {
            fetchForPlace(place);
        } else {
            fetchForCurrentLocation();
        }
    }

    // ------------------------------------------------------------ location

    private void fetchForPlace(final String place) {
        net.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    double[] p = Osrm.parsePoint(place); // accepts "lat,lon" or a place name
                    fetchAndSend(p[0], p[1], place);
                } catch (Exception e) {
                    fail("could not resolve \"" + place + "\"", e);
                }
            }
        });
    }

    private void fetchForCurrentLocation() {
        final boolean[] done = { false };
        // If no fix arrives we must still release inFlight and retry later,
        // otherwise a permission-less device would wedge the cycle forever.
        final Runnable timeout = new Runnable() {
            @Override
            public void run() {
                if (done[0]) return;
                done[0] = true;
                locationSource.stop();
                fail("no location fix for weather", null);
            }
        };
        conn.postDelayed(timeout, FIX_TIMEOUT_MS);

        locationSource.start(new LocationSource.Listener() {
            @Override
            public void onFix(final double lat, final double lon, float speedMps, float bearing) {
                if (done[0]) return;
                done[0] = true;
                conn.removeCallbacks(timeout);
                // One fix is all weather needs -- don't keep the GPS running.
                locationSource.stop();
                net.execute(new Runnable() {
                    @Override
                    public void run() {
                        fetchAndSend(lat, lon, null);
                    }
                });
            }

            @Override
            public void onUnavailable(String reason) {
                if (done[0]) return;
                done[0] = true;
                conn.removeCallbacks(timeout);
                fail("location unavailable for weather: " + reason, null);
            }
        });
    }

    // --------------------------------------------------------------- fetch

    /** Runs on the net executor. */
    private void fetchAndSend(double lat, double lon, String areaName) {
        try {
            final Weather.Reading r = OpenMeteo.fetch(lat, lon, areaName);
            final String json = Weather.build(r);
            conn.post(new Runnable() {
                @Override
                public void run() {
                    sender.send(json);
                    LogBus.log("weather synced: " + r.condition + " " + r.temp + "°C"
                            + (r.areaName == null ? "" : " (" + r.areaName + ")"));
                    done(REFRESH_MS);
                }
            });
        } catch (Exception e) {
            fail("weather fetch failed", e);
        }
    }

    private void fail(final String message, final Exception e) {
        conn.post(new Runnable() {
            @Override
            public void run() {
                if (e != null) LogBus.warn(message + ": " + e);
                else LogBus.warn(message);
                done(RETRY_MS);
            }
        });
    }

    /** Releases the in-flight guard and schedules the next round. */
    private void done(long nextInMs) {
        inFlight = false;
        conn.removeCallbacks(refreshTick);
        if (running) conn.postDelayed(refreshTick, nextInMs);
    }
}

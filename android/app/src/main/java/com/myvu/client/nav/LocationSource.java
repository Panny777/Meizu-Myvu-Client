package com.myvu.client.nav;

/**
 * A stream of position fixes.
 *
 * Abstracted so the Play Services dependency stays swappable: FusedLocationSource
 * is the default, but LocationManagerSource is a drop-in that needs no
 * dependencies at all, which matters if this ever has to build without Google
 * Play Services.
 *
 * Replaces the Python client's pyserial NMEA reader (gps.py) entirely.
 */
public interface LocationSource {

    interface Listener {
        /**
         * @param speedMps  metres per second, or -1 when unknown
         * @param bearing   degrees from north, or -1 when unknown
         */
        void onFix(double lat, double lon, float speedMps, float bearing);

        void onUnavailable(String reason);
    }

    /** Begins delivering fixes at roughly 1Hz. */
    void start(Listener listener);

    void stop();
}

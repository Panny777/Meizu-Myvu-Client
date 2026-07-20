package com.myvu.client.nav;

/** Great-circle geometry. Pure maths, so unit-testable off-device. */
public final class Geo {
    private Geo() {}

    private static final double EARTH_RADIUS_M = 6371000.0;

    /** Distance between two lat/lon points, in metres. */
    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}

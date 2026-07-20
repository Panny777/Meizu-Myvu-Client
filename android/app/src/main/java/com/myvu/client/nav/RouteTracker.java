package com.myvu.client.nav;

/**
 * Snaps a live position onto a {@link Route} and reports progress plus the
 * upcoming maneuver. Port of navigation.RouteTracker.
 *
 * Nearest-vertex snapping: coarse, but robust and cheap, and accurate enough for
 * a HUD at typical OSRM vertex spacing. It does not project onto segments, so
 * expect a few metres of quantisation.
 */
public class RouteTracker {

    /** How far off the polyline before we call it off-route. */
    public static final double OFF_ROUTE_M = 45.0;
    /** A maneuver within this distance behind us is considered already taken. */
    private static final double PASSED_MARGIN_M = 5.0;

    public static class State {
        public final double travelledM;
        public final double remainingM;
        public final boolean offRoute;
        /** Distance from the nearest route vertex. */
        public final double deviationM;
        /** The maneuver ahead, or null once past the last one. */
        public final Route.Step nextStep;
        public final double distToNextM;

        State(double travelledM, double remainingM, boolean offRoute, double deviationM,
              Route.Step nextStep, double distToNextM) {
            this.travelledM = travelledM;
            this.remainingM = remainingM;
            this.offRoute = offRoute;
            this.deviationM = deviationM;
            this.nextStep = nextStep;
            this.distToNextM = distToNextM;
        }
    }

    private final Route route;

    public RouteTracker(Route route) {
        this.route = route;
    }

    public State update(double lat, double lon) {
        double bestDistance = Double.MAX_VALUE;
        double travelled = 0.0;

        for (Route.Vertex v : route.vertices) {
            double d = Geo.haversine(lat, lon, v.lat, v.lon);
            if (d < bestDistance) {
                bestDistance = d;
                travelled = v.cumulativeM;
            }
        }
        if (route.vertices.isEmpty()) bestDistance = 0.0;

        Route.Step next = null;
        double distToNext = 0.0;
        for (Route.Step s : route.steps) {
            if (s.atM > travelled + PASSED_MARGIN_M) {
                next = s;
                distToNext = s.atM - travelled;
                break;
            }
        }

        return new State(
                travelled,
                Math.max(0.0, route.totalDistanceM - travelled),
                bestDistance > OFF_ROUTE_M,
                bestDistance,
                next,
                distToNext);
    }
}

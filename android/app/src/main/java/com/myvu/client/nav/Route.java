package com.myvu.client.nav;

import java.util.List;

/** A routed path: maneuver steps plus the polyline used for map-matching. */
public class Route {

    /** One maneuver along the route. */
    public static class Step {
        /** Glasses arrow-icon value (see IcMap -- provisional). */
        public final int ic;
        /** Road you travel AFTER this maneuver. */
        public final String road;
        public final int distanceM;
        public final double durationS;
        /** Raw OSRM maneuver type/modifier, kept for icon calibration. */
        public final String type;
        public final String modifier;
        /** Cumulative distance along the route at which this maneuver occurs. */
        public final double atM;

        public Step(int ic, String road, int distanceM, double durationS,
                    String type, String modifier, double atM) {
            this.ic = ic;
            this.road = road;
            this.distanceM = distanceM;
            this.durationS = durationS;
            this.type = type;
            this.modifier = modifier;
            this.atM = atM;
        }
    }

    /** A polyline point with its cumulative distance from the origin. */
    public static class Vertex {
        public final double lat;
        public final double lon;
        public final double cumulativeM;

        public Vertex(double lat, double lon, double cumulativeM) {
            this.lat = lat;
            this.lon = lon;
            this.cumulativeM = cumulativeM;
        }
    }

    public final List<Step> steps;
    public final int totalDistanceM;
    public final double totalDurationS;
    public final List<Vertex> vertices;

    public Route(List<Step> steps, int totalDistanceM, double totalDurationS,
                 List<Vertex> vertices) {
        this.steps = steps;
        this.totalDistanceM = totalDistanceM;
        this.totalDurationS = totalDurationS;
        this.vertices = vertices;
    }
}

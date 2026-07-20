package com.myvu.client.nav;

import com.myvu.client.core.LogBus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Routing and geocoding, ported from myvu_client/myvu/navigation.py.
 *
 * Uses plain HttpURLConnection for the same reason the Python version uses
 * urllib: these are two simple GETs and pulling in an HTTP stack for them is not
 * worth the dependency.
 *
 * The public OSRM demo server has no SLA or rate guarantee; swap in a
 * self-hosted instance for anything beyond experimenting. Nominatim asks for a
 * descriptive User-Agent and light usage.
 *
 * Every method here blocks and MUST be called off the connection thread.
 */
public final class Osrm {
    private Osrm() {}

    private static final String OSRM_BASE = "https://router.project-osrm.org";
    private static final String NOMINATIM_BASE = "https://nominatim.openstreetmap.org";
    private static final String USER_AGENT = "myvu-android-client/1.0";
    private static final int TIMEOUT_MS = 20000;

    /** Resolves "lat,lon" directly, otherwise geocodes the text. */
    public static double[] parsePoint(String s) throws IOException {
        String t = s.trim();
        String[] parts = t.split(",");
        if (parts.length == 2) {
            try {
                return new double[] {
                        Double.parseDouble(parts[0].trim()),
                        Double.parseDouble(parts[1].trim()) };
            } catch (NumberFormatException ignored) {
                // Not coordinates; fall through to geocoding.
            }
        }
        return geocode(t);
    }

    /** Nominatim forward geocode. Returns {lat, lon}. */
    public static double[] geocode(String place) throws IOException {
        String url = NOMINATIM_BASE + "/search?"
                + "q=" + URLEncoder.encode(place, "UTF-8")
                + "&format=json&limit=1";
        try {
            JSONArray results = new JSONArray(get(url));
            if (results.length() == 0) {
                throw new IOException("no geocode result for \"" + place + "\"");
            }
            JSONObject first = results.getJSONObject(0);
            return new double[] {
                    Double.parseDouble(first.getString("lat")),
                    Double.parseDouble(first.getString("lon")) };
        } catch (org.json.JSONException e) {
            throw new IOException("unparseable geocode response: " + e.getMessage(), e);
        }
    }

    /** Fetches a turn-by-turn route with geometry, for map-matching. */
    public static Route route(double originLat, double originLon,
                              double destLat, double destLon, String profile)
            throws IOException {
        // OSRM takes lon,lat -- the opposite order to everything else here.
        String coords = String.format(Locale.US, "%f,%f;%f,%f",
                originLon, originLat, destLon, destLat);
        String url = OSRM_BASE + "/route/v1/" + profile + "/" + coords
                + "?overview=full&geometries=geojson&steps=true&annotations=false";

        try {
            JSONObject data = new JSONObject(get(url));
            String code = data.optString("code");
            if (!"Ok".equals(code) || data.optJSONArray("routes") == null) {
                throw new IOException("OSRM returned " + code + ": "
                        + data.optString("message", "no route"));
            }

            JSONObject r = data.getJSONArray("routes").getJSONObject(0);
            List<Route.Vertex> vertices = buildVertices(
                    r.getJSONObject("geometry").getJSONArray("coordinates"));
            List<Route.Step> steps = buildSteps(r.getJSONArray("legs"), vertices);

            int distance = (int) r.optDouble("distance", 0);
            double duration = r.optDouble("duration", 0);
            LogBus.log("route: " + steps.size() + " steps, " + distance + "m, "
                    + Math.round(duration) + "s");
            return new Route(steps, distance, duration, vertices);
        } catch (org.json.JSONException e) {
            throw new IOException("unparseable OSRM response: " + e.getMessage(), e);
        }
    }

    private static List<Route.Vertex> buildVertices(JSONArray coordinates)
            throws org.json.JSONException {
        List<Route.Vertex> out = new ArrayList<>(coordinates.length());
        double acc = 0.0;
        double prevLat = 0, prevLon = 0;
        for (int i = 0; i < coordinates.length(); i++) {
            JSONArray c = coordinates.getJSONArray(i);
            double lon = c.getDouble(0); // GeoJSON is lon,lat
            double lat = c.getDouble(1);
            if (i > 0) acc += Geo.haversine(prevLat, prevLon, lat, lon);
            out.add(new Route.Vertex(lat, lon, acc));
            prevLat = lat;
            prevLon = lon;
        }
        return out;
    }

    private static List<Route.Step> buildSteps(JSONArray legs, List<Route.Vertex> vertices)
            throws org.json.JSONException {
        List<Route.Step> steps = new ArrayList<>();
        for (int li = 0; li < legs.length(); li++) {
            JSONArray legSteps = legs.getJSONObject(li).getJSONArray("steps");
            for (int si = 0; si < legSteps.length(); si++) {
                JSONObject st = legSteps.getJSONObject(si);
                JSONObject man = st.optJSONObject("maneuver");

                String type = man != null ? man.optString("type", "") : "";
                String modifier = man != null ? man.optString("modifier", "") : "";

                double at = 0.0;
                JSONArray loc = man != null ? man.optJSONArray("location") : null;
                if (loc != null && loc.length() >= 2) {
                    at = nearestCumulative(vertices, loc.getDouble(1), loc.getDouble(0));
                }

                steps.add(new Route.Step(
                        IcMap.forManeuver(type, modifier),
                        st.optString("name", ""),
                        (int) st.optDouble("distance", 0),
                        st.optDouble("duration", 0),
                        type, modifier, at));
            }
        }
        return steps;
    }

    private static double nearestCumulative(List<Route.Vertex> vertices, double lat, double lon) {
        double bestCum = 0.0;
        double bestDistance = Double.MAX_VALUE;
        for (Route.Vertex v : vertices) {
            double d = Geo.haversine(lat, lon, v.lat, v.lon);
            if (d < bestDistance) {
                bestDistance = d;
                bestCum = v.cumulativeM;
            }
        }
        return bestCum;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(in);
            if (status >= 400) {
                throw new IOException("HTTP " + status + " from " + url + ": "
                        + body.substring(0, Math.min(200, body.length())));
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}

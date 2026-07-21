package com.myvu.client.weather;

import com.myvu.client.app.feature.Weather;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Fetches the forecast from Open-Meteo and shapes it into the glasses' model.
 *
 * Open-Meteo was chosen over OpenWeatherMap because it needs NO API key: the
 * assistant already asks the user for two keys, and a third one just to see the
 * temperature would be a poor trade. It takes lat/lon directly, which is what
 * our LocationSource already provides.
 *
 * The official app can't help us here -- it pulls from Meizu's own backend,
 * which we have no access to. Only the OUTPUT shape has to match, and that is
 * pinned by Weather/ArWeatherModel.
 *
 * Uses plain HttpURLConnection, like nav/Osrm, for the same reason: one GET is
 * not worth an HTTP dependency. Blocks -- call it off the connection thread.
 */
public final class OpenMeteo {
    private OpenMeteo() {}

    private static final String BASE = "https://api.open-meteo.com/v1/forecast";
    private static final String USER_AGENT = "myvu-android-client/1.0";
    private static final int TIMEOUT_MS = 20000;
    /** The model carries a 7-day futureDay list. */
    private static final int FORECAST_DAYS = 7;

    /**
     * @param areaName shown on the lens; pass null to leave it off the wire.
     */
    public static Weather.Reading fetch(double lat, double lon, String areaName)
            throws IOException, JSONException {
        String url = BASE
                + "?latitude=" + fmt(lat)
                + "&longitude=" + fmt(lon)
                + "&current=temperature_2m,weather_code"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset"
                // timezone=auto makes sunrise/sunset LOCAL to the queried point,
                // which is what the glasses display.
                + "&timezone=auto"
                + "&forecast_days=" + FORECAST_DAYS;

        JSONObject root = new JSONObject(get(url));
        Weather.Reading r = new Weather.Reading();
        r.areaName = areaName;
        r.lastUpdate = Weather.timestamp(System.currentTimeMillis());

        JSONObject current = root.optJSONObject("current");
        if (current != null) {
            r.temp = (int) Math.round(current.optDouble("temperature_2m", 0));
            WeatherCodes.Condition c = WeatherCodes.of(current.optInt("weather_code", -1));
            r.iconCode = c.iconCode;
            r.condition = c.text;
        }

        JSONObject daily = root.optJSONObject("daily");
        if (daily != null) {
            JSONArray dates = daily.optJSONArray("time");
            JSONArray max = daily.optJSONArray("temperature_2m_max");
            JSONArray min = daily.optJSONArray("temperature_2m_min");
            JSONArray codes = daily.optJSONArray("weather_code");
            JSONArray sunrise = daily.optJSONArray("sunrise");
            JSONArray sunset = daily.optJSONArray("sunset");

            // Index 0 is today: it supplies the headline high/low and the
            // sun times the glasses also use for auto-brightness.
            if (max != null && max.length() > 0) r.dayTempMax = (int) Math.round(max.optDouble(0));
            if (min != null && min.length() > 0) r.dayTempMin = (int) Math.round(min.optDouble(0));
            if (sunrise != null && sunrise.length() > 0) r.sunriseTime = isoToStamp(sunrise.optString(0));
            if (sunset != null && sunset.length() > 0) r.sunsetTime = isoToStamp(sunset.optString(0));

            // The rest are the forecast. The official app's own payload starts
            // futureDay at today, so we match that.
            int n = dates == null ? 0 : dates.length();
            for (int i = 0; i < n; i++) {
                Weather.Day d = new Weather.Day();
                d.date = dates.optString(i);
                if (max != null && i < max.length()) d.tempMax = (int) Math.round(max.optDouble(i));
                if (min != null && i < min.length()) d.tempMin = (int) Math.round(min.optDouble(i));
                if (codes != null && i < codes.length()) {
                    WeatherCodes.Condition c = WeatherCodes.of(codes.optInt(i, -1));
                    d.iconCode = c.iconCode;
                    d.condition = c.text;
                }
                r.futureDay.add(d);
            }
        }
        return r;
    }

    /** "2024-04-11T05:31" -> "2024-04-11 05:31:00", the format the model wants. */
    private static String isoToStamp(String iso) {
        if (iso == null || iso.isEmpty()) return null;
        String s = iso.replace('T', ' ');
        // Open-Meteo omits seconds; the glasses' parser expects them.
        return s.length() == 16 ? s + ":00" : s;
    }

    private static String fmt(double d) {
        return String.format(Locale.US, "%.4f", d);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("weather HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}

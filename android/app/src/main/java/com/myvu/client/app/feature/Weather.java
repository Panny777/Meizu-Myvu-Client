package com.myvu.client.app.feature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Weather sync, reverse-engineered from the official app's WeatherWorker /
 * ArWeatherModel.
 *
 * Wire format is {"action":"weather","data":{ …ArWeatherModel… }} sent to the
 * launcher. Unlike the "system" family there is NO nested data.action -- the
 * data object IS the model.
 *
 * Field names below are the model's Java field names verbatim: the official app
 * serialises with plain Gson and no @SerializedName, so the wire keys are
 * exactly the field names. Gson also OMITS null fields by default, which is the
 * behaviour the glasses were built against -- so an unknown value is left out
 * entirely rather than sent as JSON null.
 *
 * Temperatures are integer degrees CELSIUS. The official app hardcodes
 * unit=metric in its backend query and there is no unit flag anywhere in the
 * payload, so there is nothing to negotiate.
 */
public final class Weather {
    private Weather() {}

    public static final String ACTION = "weather";

    /**
     * The glasses ask for a refresh with {"action":"syncWeather"}. Curiously the
     * official app parses this and then drops it -- its handler callback is
     * never assigned -- so in the real app the refresh only ever comes from its
     * own 30-minute timer. We answer it, which is strictly better.
     */
    public static final String SYNC_REQUEST_ACTION = "syncWeather";

    /** The model's timestamp format, confirmed against the app's parser. */
    private static final String TS_FORMAT = "yyyy-MM-dd HH:mm:ss";

    /** One entry of the 7-day forecast (ArFutureDay). All fields optional. */
    public static final class Day {
        public String date;        // "yyyy-MM-dd"
        public Integer tempMax;
        public Integer tempMin;
        public String condition;   // -> "weather"
        public String iconCode;
    }

    /** ArWeatherModel. Null fields are omitted from the wire. */
    public static final class Reading {
        public Integer temp;
        public String condition;      // -> "weather", human-readable
        public Integer dayTempMax;
        public Integer dayTempMin;
        public String areaName;
        /** NOT nullable in the model -- always send something. */
        public String iconCode = "0";
        public String lastUpdate;
        public String sunriseTime;
        public String sunsetTime;
        /** Primitive in the model, so always present; 0 when unknown. */
        public int aqi;
        /** Not nullable in the model; "" when unknown. */
        public String quality = "";
        /** Not nullable in the model; may be empty. */
        public final List<Day> futureDay = new ArrayList<>();
    }

    public static String build(Reading r) throws JSONException {
        JSONObject data = new JSONObject();
        putIfPresent(data, "temp", r.temp);
        putIfPresent(data, "weather", r.condition);
        putIfPresent(data, "dayTempMax", r.dayTempMax);
        putIfPresent(data, "dayTempMin", r.dayTempMin);
        putIfPresent(data, "areaName", r.areaName);
        data.put("iconCode", r.iconCode == null ? "0" : r.iconCode);
        putIfPresent(data, "lastUpdate", r.lastUpdate);
        putIfPresent(data, "sunriseTime", r.sunriseTime);
        putIfPresent(data, "sunsetTime", r.sunsetTime);
        data.put("aqi", r.aqi);
        data.put("quality", r.quality == null ? "" : r.quality);

        JSONArray days = new JSONArray();
        for (Day d : r.futureDay) {
            JSONObject o = new JSONObject();
            putIfPresent(o, "date", d.date);
            putIfPresent(o, "dayTempMax", d.tempMax);
            putIfPresent(o, "dayTempMin", d.tempMin);
            putIfPresent(o, "weather", d.condition);
            putIfPresent(o, "iconCode", d.iconCode);
            days.put(o);
        }
        data.put("futureDay", days);

        return new JSONObject()
                .put("action", ACTION)
                .put("data", data)
                .toString();
    }

    /** Formats an epoch millisecond value the way the model expects. */
    public static String timestamp(long epochMs) {
        return new SimpleDateFormat(TS_FORMAT, Locale.US).format(new Date(epochMs));
    }

    /** True when the glasses are asking us to push fresh weather. */
    public static boolean isSyncRequest(JSONObject action) {
        return action != null && SYNC_REQUEST_ACTION.equals(action.optString("action"));
    }

    /** Mirrors Gson's default: a null value is left off the wire entirely. */
    private static void putIfPresent(JSONObject o, String key, Object value) throws JSONException {
        if (value != null) o.put(key, value);
    }
}

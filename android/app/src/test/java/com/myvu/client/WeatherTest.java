package com.myvu.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.myvu.client.app.feature.Weather;
import com.myvu.client.weather.WeatherCodes;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Pins the weather wire format against ArWeatherModel as decompiled from the
 * official app. The key names here are the model's Java field names verbatim --
 * it serialises with plain Gson and no @SerializedName -- so a rename in this
 * test is a protocol break, not a refactor.
 */
public class WeatherTest {

    private static JSONObject dataOf(String actionJson) throws Exception {
        JSONObject env = new JSONObject(actionJson);
        // No nested data.action: unlike the "system" family, data IS the model.
        assertEquals("weather", env.getString("action"));
        return env.getJSONObject("data");
    }

    private static Weather.Reading sample() {
        Weather.Reading r = new Weather.Reading();
        r.temp = 21;
        r.condition = "Partly cloudy";
        r.iconCode = "1";
        r.dayTempMax = 21;
        r.dayTempMin = 15;
        r.areaName = "Dar es Salaam";
        r.lastUpdate = "2024-04-11 13:55:08";
        r.sunriseTime = "2024-04-11 05:31:00";
        r.sunsetTime = "2024-04-11 18:20:00";
        return r;
    }

    @Test
    public void envelopeAndFieldNames() throws Exception {
        JSONObject d = dataOf(Weather.build(sample()));
        assertEquals(21, d.getInt("temp"));
        assertEquals("Partly cloudy", d.getString("weather"));
        assertEquals("1", d.getString("iconCode"));
        assertEquals(21, d.getInt("dayTempMax"));
        assertEquals(15, d.getInt("dayTempMin"));
        assertEquals("Dar es Salaam", d.getString("areaName"));
        assertEquals("2024-04-11 13:55:08", d.getString("lastUpdate"));
        assertEquals("2024-04-11 05:31:00", d.getString("sunriseTime"));
        assertEquals("2024-04-11 18:20:00", d.getString("sunsetTime"));
    }

    /** iconCode, aqi, quality and futureDay are non-null in the model. */
    @Test
    public void nonNullableFieldsAreAlwaysPresent() throws Exception {
        JSONObject d = dataOf(Weather.build(new Weather.Reading()));
        assertTrue("iconCode is @NotNull in the model", d.has("iconCode"));
        assertEquals(0, d.getInt("aqi"));
        assertEquals("", d.getString("quality"));
        assertEquals(0, d.getJSONArray("futureDay").length());
    }

    /**
     * Gson omits nulls by default, so that is the shape the glasses were built
     * against -- an unknown value must be ABSENT, not JSON null.
     */
    @Test
    public void unknownOptionalFieldsAreOmittedNotNull() throws Exception {
        JSONObject d = dataOf(Weather.build(new Weather.Reading()));
        assertFalse(d.has("temp"));
        assertFalse(d.has("weather"));
        assertFalse(d.has("areaName"));
        assertFalse(d.has("dayTempMax"));
        assertFalse(d.has("sunriseTime"));
    }

    @Test
    public void futureDayEntries() throws Exception {
        Weather.Reading r = sample();
        Weather.Day day = new Weather.Day();
        day.date = "2024-04-12";
        day.tempMax = 19;
        day.tempMin = 15;
        day.condition = "Light rain";
        day.iconCode = "7";
        r.futureDay.add(day);

        JSONObject d0 = dataOf(Weather.build(r)).getJSONArray("futureDay").getJSONObject(0);
        assertEquals("2024-04-12", d0.getString("date"));
        assertEquals(19, d0.getInt("dayTempMax"));
        assertEquals(15, d0.getInt("dayTempMin"));
        assertEquals("Light rain", d0.getString("weather"));
        assertEquals("7", d0.getString("iconCode"));
    }

    @Test
    public void timestampFormatMatchesTheModelsParser() {
        // "yyyy-MM-dd HH:mm:ss" -- SuperMessageManger parses exactly this.
        assertTrue(Weather.timestamp(System.currentTimeMillis())
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    public void detectsTheGlassesSyncRequest() throws Exception {
        assertTrue(Weather.isSyncRequest(new JSONObject("{\"action\":\"syncWeather\"}")));
        assertFalse(Weather.isSyncRequest(new JSONObject("{\"action\":\"weather\"}")));
    }

    /** The three icon codes actually attested in the official app's own payload. */
    @Test
    public void verifiedIconCodes() {
        assertEquals("1", WeatherCodes.of(2).iconCode);   // partly cloudy -> 多云
        assertEquals("2", WeatherCodes.of(3).iconCode);   // overcast      -> 阴
        assertEquals("7", WeatherCodes.of(61).iconCode);  // slight rain   -> 小雨
    }

    @Test
    public void unknownWmoCodeFallsBackToSomethingSafe() {
        WeatherCodes.Condition c = WeatherCodes.of(12345);
        assertEquals("1", c.iconCode); // cloudy: visually neutral
        assertTrue(c.text.length() > 0);
    }
}

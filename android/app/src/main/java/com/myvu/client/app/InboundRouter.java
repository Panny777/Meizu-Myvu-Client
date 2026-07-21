package com.myvu.client.app;

import com.myvu.client.app.feature.ClockSync;
import com.myvu.client.app.feature.Weather;
import com.myvu.client.core.LogBus;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles messages the glasses send US, ported from the _check_* helpers in
 * applayer.py.
 *
 * Several glasses-side features do not work at all unless the phone answers a
 * request first -- most importantly the launch-app handshake, without which the
 * navigation app ignores every frame we stream and re-asks forever.
 *
 * Relay bodies are protobuf with JSON embedded inside, so rather than decode the
 * envelope precisely we scan for balanced {...} runs, exactly as the Python
 * client does.
 */
public class InboundRouter {

    public interface Sender {
        /** Sends an action with explicit routing packages. */
        void send(String actionJson, String targetPkg, String sourcePkg);
    }

    /** Fired when the glasses' AI button or wake word triggers. */
    public interface AiTriggerListener {
        void onAiTrigger(int code, JSONObject payload);
    }

    /** Fired when the glasses ask for a fresh weather push. */
    public interface WeatherRequestListener {
        void onWeatherRequested();
    }

    private final Sender sender;
    private AiTriggerListener aiListener;
    private WeatherRequestListener weatherListener;

    public InboundRouter(Sender sender) {
        this.sender = sender;
    }

    public void setAiTriggerListener(AiTriggerListener listener) {
        this.aiListener = listener;
    }

    public void setWeatherRequestListener(WeatherRequestListener listener) {
        this.weatherListener = listener;
    }

    /** Inspects one inbound relay body and answers anything that needs answering. */
    public void handle(String body) {
        for (String candidate : findJsonObjects(body)) {
            JSONObject obj;
            try {
                obj = new JSONObject(candidate);
            } catch (JSONException e) {
                continue; // not a complete object; skip
            }
            checkLaunchAppRequest(obj);
            checkTimeSyncRequest(obj);
            checkWeatherRequest(obj);
            checkAiTrigger(obj);
        }
    }

    /**
     * The glasses ask for fresh weather with {"action":"syncWeather"}.
     *
     * The official app parses this and then throws it away -- its handler
     * callback is never assigned anywhere in the APK -- so on a stock phone the
     * glasses only ever get weather from the app's own 30-minute timer. We
     * actually answer it, which is strictly better behaviour.
     */
    private void checkWeatherRequest(JSONObject msg) {
        if (!Weather.isSyncRequest(msg)) return;
        LogBus.log("<- the glasses asked for weather");
        if (weatherListener != null) weatherListener.onWeatherRequested();
    }

    /**
     * Answers the RunAsOne "open StarryNet app" request.
     *
     * Opening e.g. the nav app on the glasses makes them ask the phone to launch
     * a companion service via {"type":11,...}. A real phone replies type:12 with
     * success. We cannot start their service, but the glasses only need the ack
     * -- we stream the actual data ourselves -- and WITHOUT it the glasses'
     * app never proceeds and re-sends the request indefinitely.
     */
    private void checkLaunchAppRequest(JSONObject msg) {
        if (msg.optInt("type", -1) != 11) return;
        JSONObject data = msg.optJSONObject("data");
        if (data == null) return;
        String appId = data.optString("appId", "");
        if (appId.isEmpty()) return;

        try {
            JSONObject response = new JSONObject()
                    .put("type", 12)
                    .put("data", new JSONObject()
                            .put("appId", appId)
                            .put("code", 200)
                            .put("menuId", data.opt("menuId") == null ? "" : data.opt("menuId"))
                            .put("requestId", data.opt("requestId") == null
                                    ? "" : data.opt("requestId"))
                            .put("success", true));
            LogBus.log("glasses asked to launch " + appId + " -- acking type:12");
            // Must go out on the interconnect inner channel, not the launcher.
            sender.send(response.toString(),
                    AppLayer.PKG_INTERCONNECT, AppLayer.PKG_INTERCONNECT);
        } catch (JSONException e) {
            LogBus.error("could not build the launch-app ack", e);
        }
    }

    /**
     * The glasses ask for wall-clock time by sending SyncOffSetTime with no
     * syncTimeData. Messages that already carry it are time payloads (possibly
     * our own), so answering those would loop.
     */
    private void checkTimeSyncRequest(JSONObject msg) {
        if (!ClockSync.isRequest(msg)) return;
        try {
            LogBus.log("glasses requested a time sync -- replying");
            sender.send(ClockSync.build(), AppLayer.PKG_LAUNCHER, AppLayer.PKG_LAUNCHER);
        } catch (JSONException e) {
            LogBus.error("could not build the time sync reply", e);
        }
    }

    /**
     * AI assistant triggers: code 3 is the hardware button, code 7 the wake
     * word. Phase 8 drives the conversation from here; for now the trigger is
     * surfaced to whoever is listening.
     */
    private void checkAiTrigger(JSONObject msg) {
        if (!msg.has("code")) return;
        int code = msg.optInt("code", -1);
        if (code != 3 && code != 7) return;

        JSONObject payload = msg.optJSONObject("payload");
        LogBus.log("AI trigger: code=" + code
                + (code == 3 ? " (button)" : " (wake word)"));
        if (aiListener != null) aiListener.onAiTrigger(code, payload);
    }

    /**
     * Balanced-brace scan for embedded {...} objects, handling nesting. Port of
     * applayer._find_json_objects.
     */
    public static List<String> findJsonObjects(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(s.substring(start, i + 1));
                }
            }
        }
        return out;
    }
}

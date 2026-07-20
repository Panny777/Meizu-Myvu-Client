package com.myvu.client.app.feature;

import com.myvu.client.app.AppLayer;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Teleprompter ("tici"), ported from applayer.open_teleprompter.
 *
 * Opening it takes TWO messages ~400ms apart -- an open_app scene launch, then
 * the content -- both sent with sourcePkg = com.upuphone.ar.tici rather than
 * the launcher. The gap is load-bearing: the content message is dropped if the
 * app has not finished coming up.
 *
 * Note the nesting: several fields here are JSON encoded as a STRING inside the
 * outer JSON ("ext" and "value"), not nested objects. Sending them as objects
 * silently fails to bind on the device.
 */
public final class Teleprompter {
    private Teleprompter() {}

    public static final long OPEN_TO_CONTENT_DELAY_MS = 400;
    private static final String DEFAULT_TITLE = "Prompter";

    public static String fileKeyFor(String title) {
        return "1/" + title;
    }

    /** Message 1: launch the teleprompter app with the document's metadata. */
    public static String buildOpen(String text, String title) throws JSONException {
        String fileKey = fileKeyFor(title);
        JSONObject ext = new JSONObject()
                .put("blockNotification", true)
                .put("currentPage", 0)
                .put("fileKey", fileKey)
                .put("msgId", UUID.randomUUID().toString())
                .put("nextTotalParagraphSize", 0)
                .put("paragraphIndex", 0)
                .put("prevTotalParagraphSize", 0)
                .put("screenLocation", 0)
                .put("sourceByteSize", text.getBytes(StandardCharsets.UTF_8).length)
                .put("sourceTextOffset", 0)
                .put("ticiMode", 0)
                .put("ticiSpeed", 10000)
                .put("totalPage", 1)
                .put("totalPart", 1)
                .put("totalTextLength", text.length())
                .put("version", 2);

        return new JSONObject()
                .put("action", "app")
                .put("data", new JSONObject()
                        .put("launchMode", "scene")
                        .put("action", "open_app")
                        .put("pkg", AppLayer.PKG_TICI)
                        .put("app_name", AppLayer.PKG_TICI)
                        // ext is a JSON STRING, not an object.
                        .put("ext", ext.toString()))
                .toString();
    }

    public static String buildOpen(String text) throws JSONException {
        return buildOpen(text, DEFAULT_TITLE);
    }

    /** Message 2: the actual script text. */
    public static String buildContent(String text, String title) throws JSONException {
        JSONObject content = new JSONObject()
                .put("currentPage", 0)
                .put("fileKey", fileKeyFor(title))
                .put("msgId", UUID.randomUUID().toString())
                .put("part", 0)
                .put("sourceText", text);

        return new JSONObject()
                .put("action", "tici")
                .put("data", new JSONObject()
                        .put("action", "send_content")
                        // value is a JSON STRING, not an object.
                        .put("value", content.toString()))
                .toString();
    }

    public static String buildContent(String text) throws JSONException {
        return buildContent(text, DEFAULT_TITLE);
    }

    /** Scrolls/highlights the prompter to a paragraph index. */
    public static String buildHighlight(int index, String title) throws JSONException {
        JSONObject value = new JSONObject()
                .put("index", index)
                .put("fileKey", fileKeyFor(title));

        return new JSONObject()
                .put("action", "tici")
                .put("data", new JSONObject()
                        .put("action", "highlight_index")
                        .put("value", value.toString()))
                .toString();
    }

    public static String buildHighlight(int index) throws JSONException {
        return buildHighlight(index, DEFAULT_TITLE);
    }
}

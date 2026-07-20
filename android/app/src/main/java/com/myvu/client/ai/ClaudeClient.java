package com.myvu.client.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Answers a spoken question via the Claude Messages API.
 *
 * Non-streaming on purpose: the glasses' flow needs the whole answer up front,
 * because playback is gated between code:6 playState 1 and 2 -- there is nothing
 * useful to do with partial tokens.
 *
 * Blocking; must be called off the connection thread.
 */
public class ClaudeClient {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final String MODEL = "claude-haiku-4-5-20251001";
    private static final int MAX_TOKENS = 1024;
    private static final int TIMEOUT_MS = 30000;

    /**
     * Answers are spoken aloud on a pair of glasses, so length and formatting
     * matter more than usual.
     */
    private static final String SYSTEM_PROMPT =
            "You are a voice assistant built into a pair of AR glasses. Answer in "
            + "one or two short sentences that sound natural read aloud. No "
            + "markdown, no lists, no code blocks, no emoji. If you do not know "
            + "something, say so briefly rather than guessing.";

    private final String apiKey;

    public ClaudeClient(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /** Returns the answer text, or throws with a message worth showing. */
    public String ask(String question) throws IOException {
        if (!hasKey()) {
            throw new IOException("no Claude API key set -- add one in the app first");
        }

        String body;
        try {
            body = new JSONObject()
                    .put("model", MODEL)
                    .put("max_tokens", MAX_TOKENS)
                    .put("system", SYSTEM_PROMPT)
                    .put("messages", new JSONArray().put(new JSONObject()
                            .put("role", "user")
                            .put("content", question)))
                    .toString();
        } catch (org.json.JSONException e) {
            throw new IOException("could not build the request: " + e.getMessage(), e);
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("content-type", "application/json");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", API_VERSION);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);

            OutputStream out = conn.getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();

            int status = conn.getResponseCode();
            String response = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (status >= 400) {
                throw new IOException("Claude API returned " + status + ": "
                        + extractError(response));
            }
            return extractText(response);
        } finally {
            conn.disconnect();
        }
    }

    /** The answer lives in content[] as one or more text blocks. */
    private static String extractText(String response) throws IOException {
        try {
            JSONArray content = new JSONObject(response).getJSONArray("content");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.getJSONObject(i);
                if ("text".equals(block.optString("type"))) {
                    sb.append(block.optString("text"));
                }
            }
            String text = sb.toString().trim();
            if (text.isEmpty()) throw new IOException("Claude returned an empty answer");
            return text;
        } catch (org.json.JSONException e) {
            throw new IOException("unparseable Claude response: " + e.getMessage(), e);
        }
    }

    private static String extractError(String response) {
        try {
            JSONObject error = new JSONObject(response).optJSONObject("error");
            if (error != null) return error.optString("message", response);
        } catch (org.json.JSONException ignored) {
            // Fall through to the raw body.
        }
        return response.substring(0, Math.min(200, response.length()));
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

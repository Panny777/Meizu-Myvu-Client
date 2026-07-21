package com.myvu.client.ai;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Shared plumbing for the provider clients: one JSON POST, one JSON answer.
 * Subclasses supply only what actually differs -- endpoint, auth headers,
 * request body and where the answer sits in the response.
 *
 * Non-streaming on purpose: the glasses' flow needs the whole answer up front,
 * because playback is gated between code:6 playState 1 and 2 -- there is nothing
 * useful to do with partial tokens.
 *
 * Blocking; must be called off the connection thread.
 */
public abstract class AiHttpClient implements AiClient {

    private static final int TIMEOUT_MS = 30000;

    protected final AiProvider provider;
    protected final String apiKey;
    /** Never blank: a blank Settings override falls back to the provider default. */
    protected final String model;
    protected final String systemPrompt;

    protected AiHttpClient(AiProvider provider, String apiKey, String model,
                           String systemPrompt) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.model = (model == null || model.trim().isEmpty())
                ? provider.defaultModel : model.trim();
        this.systemPrompt = (systemPrompt == null || systemPrompt.trim().isEmpty())
                ? DEFAULT_SYSTEM_PROMPT : systemPrompt.trim();
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /** Full URL to POST to. {@link #model} is set, for APIs that put it in the path. */
    protected abstract String endpoint();

    /** Sets the provider's auth header(s) on the request. */
    protected abstract void authorize(HttpURLConnection conn);

    /** The request JSON: model, system prompt and the one user question. */
    protected abstract String buildBody(String question) throws JSONException;

    /** Digs the answer text out of a 2xx response body. */
    protected abstract String extractText(String response) throws JSONException;

    @Override
    public String ask(String question) throws IOException {
        if (!isConfigured()) {
            throw new IOException(provider.label + " is not fully configured");
        }

        String body;
        try {
            body = buildBody(question);
        } catch (JSONException e) {
            throw new IOException("could not build the request: " + e.getMessage(), e);
        }

        return HttpRetry.execute(provider.label, new HttpRetry.Request<String>() {
            @Override
            public String execute() throws IOException {
                return askOnce(body);
            }
        });
    }

    private String askOnce(String body) throws IOException {
        URL url = HttpEndpoint.parse(endpoint(), provider.label + " endpoint");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("content-type", "application/json");
            authorize(conn);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);

            OutputStream out = conn.getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();

            int status = conn.getResponseCode();
            String response = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (status >= 400) {
                throw HttpRetry.statusError(status, provider.label + " API returned " + status
                        + ": " + extractError(response));
            }

            String text;
            try {
                text = extractText(response).trim();
            } catch (JSONException e) {
                throw new IOException("unparseable " + provider.label + " response: "
                        + e.getMessage(), e);
            }
            if (text.isEmpty()) {
                throw new IOException(provider.label + " returned an empty answer");
            }
            return text;
        } finally {
            conn.disconnect();
        }
    }

    /** The supported JSON APIs put failures under error.message. */
    private static String extractError(String response) {
        try {
            JSONObject error = new JSONObject(response).optJSONObject("error");
            if (error != null) return error.optString("message", response);
        } catch (JSONException ignored) {
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

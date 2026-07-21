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

/** Downloads WAV speech from an OpenAI-compatible audio-speech endpoint. */
public final class HttpTtsClient {
    private static final int TIMEOUT_MS = 60000;
    private static final int MAX_AUDIO_BYTES = 25 * 1024 * 1024;

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final String voice;

    public HttpTtsClient(String endpoint, String apiKey, String model, String voice) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.voice = voice == null ? "" : voice.trim();
    }

    public byte[] synthesize(String text) throws IOException {
        final String body = buildBody(text);
        return HttpRetry.execute("TTS", new HttpRetry.Request<byte[]>() {
            @Override
            public byte[] execute() throws IOException {
                return synthesizeOnce(body);
            }
        });
    }

    private byte[] synthesizeOnce(String body) throws IOException {
        URL url = HttpEndpoint.parse(endpoint, "TTS endpoint");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("content-type", "application/json");
            if (!apiKey.isEmpty()) {
                conn.setRequestProperty("authorization", "Bearer " + apiKey);
            }
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);

            OutputStream out = conn.getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();

            int status = conn.getResponseCode();
            if (status >= 400) {
                String error = readAll(conn.getErrorStream(), 8192);
                throw HttpRetry.statusError(status, "TTS API returned " + status + ": "
                        + error.substring(0, Math.min(500, error.length())));
            }
            byte[] audio = readBytes(conn.getInputStream(), MAX_AUDIO_BYTES);
            if (audio.length == 0) throw new IOException("TTS API returned empty audio");
            return audio;
        } finally {
            conn.disconnect();
        }
    }

    private String buildBody(String text) throws IOException {
        try {
            JSONObject body = new JSONObject()
                    .put("input", text)
                    .put("response_format", "wav");
            if (!model.isEmpty()) body.put("model", model);
            if (!voice.isEmpty()) body.put("voice", voice);
            return body.toString();
        } catch (JSONException e) {
            throw new IOException("could not build the TTS request: " + e.getMessage(), e);
        }
    }

    private static String readAll(InputStream in, int maxBytes) throws IOException {
        return new String(readBytes(in, maxBytes), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream in, int maxBytes) throws IOException {
        if (in == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) > 0) {
            if (out.size() + count > maxBytes) {
                throw new IOException("HTTP response exceeded " + maxBytes + " bytes");
            }
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
}

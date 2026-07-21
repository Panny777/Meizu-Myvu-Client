package com.myvu.client.ai;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Uploads a WAV file to an OpenAI-compatible audio-transcription endpoint. */
public final class OpenAiTranscriptionClient {
    private static final String BOUNDARY = "----myvuclientboundary";
    private static final int TIMEOUT_MS = 30000;
    private static final int MIN_PCM_BYTES = 16000;

    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final String serviceLabel;

    public OpenAiTranscriptionClient(String endpoint, String model, String apiKey,
                                     String serviceLabel) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.model = model == null ? "" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.serviceLabel = serviceLabel;
    }

    public boolean isConfigured() {
        return !endpoint.isEmpty() && !model.isEmpty();
    }

    public String transcribe(byte[] pcm, int sampleRate, int channels) throws IOException {
        if (!isConfigured()) {
            throw new IOException(serviceLabel + " is not fully configured");
        }
        if (pcm.length < MIN_PCM_BYTES) return "";

        final byte[] wav = OpusStream.toWav(pcm, sampleRate, channels);
        return HttpRetry.execute(serviceLabel, new HttpRetry.Request<String>() {
            @Override
            public String execute() throws IOException {
                return transcribeOnce(wav);
            }
        });
    }

    private String transcribeOnce(byte[] wav) throws IOException {
        URL url = HttpEndpoint.parse(endpoint, serviceLabel + " endpoint");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            if (!apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);

            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            writeFilePart(out, "file", "speech.wav", "audio/wav", wav);
            writeTextPart(out, "model", model);
            writeTextPart(out, "language", "en");
            writeTextPart(out, "response_format", "json");
            out.writeBytes("--" + BOUNDARY + "--\r\n");
            out.flush();
            out.close();

            int status = conn.getResponseCode();
            String body = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (status >= 400) {
                throw HttpRetry.statusError(status, serviceLabel + " returned " + status + ": "
                        + body.substring(0, Math.min(500, body.length())));
            }
            return extractText(body);
        } finally {
            conn.disconnect();
        }
    }

    private static String extractText(String body) throws IOException {
        try {
            return new JSONObject(body).optString("text", "").trim();
        } catch (JSONException e) {
            throw new IOException("unparseable transcription response: " + e.getMessage(), e);
        }
    }

    private static void writeFilePart(DataOutputStream out, String name, String filename,
                                      String contentType, byte[] data) throws IOException {
        out.writeBytes("--" + BOUNDARY + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + filename + "\"\r\n");
        out.writeBytes("Content-Type: " + contentType + "\r\n\r\n");
        out.write(data);
        out.writeBytes("\r\n");
    }

    private static void writeTextPart(DataOutputStream out, String name, String value)
            throws IOException {
        out.writeBytes("--" + BOUNDARY + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int count;
        while ((count = in.read(buf)) > 0) out.write(buf, 0, count);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}

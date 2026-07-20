package com.myvu.client.ai;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Speech-to-text over Groq's Whisper endpoint.
 *
 * Used instead of Android's SpeechRecognizer because the audio comes from the
 * GLASSES, as a decoded PCM stream. SpeechRecognizer cannot be fed a stream
 * below API 33, and the test device is API 31.
 *
 * Multipart is assembled by hand -- it is one file field and two text fields,
 * which does not justify an HTTP library.
 *
 * Blocking; call it off the connection thread.
 */
public class GroqWhisper {

    private static final String ENDPOINT =
            "https://api.groq.com/openai/v1/audio/transcriptions";
    private static final String MODEL = "whisper-large-v3-turbo";
    private static final String BOUNDARY = "----myvuclientboundary";
    private static final int TIMEOUT_MS = 30000;

    /** Below this the "audio" is almost certainly silence or a stray click. */
    private static final int MIN_PCM_BYTES = 16000; // 0.5s at 16 kHz mono 16-bit

    private final String apiKey;

    public GroqWhisper(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    public String transcribe(byte[] pcm) throws IOException {
        return transcribe(pcm, OpusStream.SAMPLE_RATE, OpusStream.CHANNELS);
    }

    /**
     * Returns the transcript, or "" when the audio holds no speech.
     *
     * The sample rate must be the decoder's real output rate -- mislabelling it
     * stretches the audio and Whisper transcribes something else entirely.
     */
    public String transcribe(byte[] pcm, int sampleRate, int channels) throws IOException {
        if (!hasKey()) {
            throw new IOException("no Groq API key set -- add one in the app first");
        }
        if (pcm.length < MIN_PCM_BYTES) {
            return "";
        }

        byte[] wav = OpusStream.toWav(pcm, sampleRate, channels);
        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + BOUNDARY);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);

            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            writeFilePart(out, "file", "speech.wav", "audio/wav", wav);
            writeTextPart(out, "model", MODEL);
            // Pinning the language stops Whisper hallucinating a translation
            // from a short or noisy clip.
            writeTextPart(out, "language", "en");
            writeTextPart(out, "response_format", "json");
            out.writeBytes("--" + BOUNDARY + "--\r\n");
            out.flush();
            out.close();

            int status = conn.getResponseCode();
            String body = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (status >= 400) {
                throw new IOException("Groq returned " + status + ": "
                        + body.substring(0, Math.min(200, body.length())));
            }
            return new JSONObject(body).optString("text", "").trim();
        } catch (org.json.JSONException e) {
            throw new IOException("unparseable Groq response: " + e.getMessage(), e);
        } finally {
            conn.disconnect();
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
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}

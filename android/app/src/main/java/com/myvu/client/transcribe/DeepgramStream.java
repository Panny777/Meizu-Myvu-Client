package com.myvu.client.transcribe;

import android.os.Handler;
import android.os.HandlerThread;

import com.myvu.client.core.LogBus;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Live speech-to-text over Deepgram's streaming WebSocket.
 *
 * Audio goes up as raw little-endian 16-bit PCM at 16 kHz mono -- exactly what
 * the glasses' Opus decoder produces, so no resampling anywhere in the path.
 * Results come back as {@code Results} messages carrying an interim or final
 * transcript plus its audio timestamps.
 *
 * The three-way split of a Deepgram result is what the glasses' caption model
 * needs, so it is worth being precise about:
 * <ul>
 *   <li>{@code is_final=false} -- an interim guess for the current segment. It
 *       may shrink or change wording on the next frame.</li>
 *   <li>{@code is_final=true, speech_final=false} -- that segment is settled,
 *       but the speaker has not stopped; more segments will follow in the same
 *       breath.</li>
 *   <li>{@code speech_final=true} -- endpointing fired; the utterance is done.</li>
 * </ul>
 * {@link TranscribeSession} turns that into the glasses' grow-then-commit
 * caption by accumulating settled segments and only committing on the last one.
 *
 * Everything runs on one private thread. Callbacks are delivered on it, so the
 * consumer must not block them.
 */
public class DeepgramStream {

    private static final String HOST = "wss://api.deepgram.com/v1/listen";

    /** Deepgram closes an idle socket after ~12s; stay comfortably inside that. */
    private static final long KEEPALIVE_MS = 7000;

    private static final int CONNECT_TIMEOUT_MS = 10000;

    public interface Listener {
        /** The socket is up; audio sent from now on will be recognised. */
        void onOpen();

        /**
         * One recognition result.
         *
         * @param transcript  the text for this segment; may be empty
         * @param isFinal     the segment is settled and will not change
         * @param speechFinal endpointing fired -- the utterance ended here
         * @param startMs     segment start, ms since the stream opened
         * @param endMs       segment end, ms since the stream opened
         */
        void onResult(String transcript, boolean isFinal, boolean speechFinal,
                      long startMs, long endMs);

        /**
         * Deepgram's VAD heard speech begin.
         *
         * This is the signal the glasses need in order to leave their
         * "waiting for speech" state, and it arrives well before any transcript
         * -- which is the point: waiting for text would leave the lens blank for
         * a second or more every time someone starts talking.
         */
        void onSpeechStarted();

        /**
         * A gap long enough to call the utterance over, independent of
         * endpointing. {@code speech_final} does not fire reliably in a room
         * with background noise, so without this the caption accumulates
         * several sentences into one ever-growing line.
         */
        void onUtteranceEnd();

        /** Terminal: the socket closed or never opened. */
        void onClosed(String reason);

        void onError(String message);
    }

    private final String apiKey;
    private final String model;
    private final String language;
    private final Listener listener;

    private final HandlerThread thread;
    private final Handler handler;

    private WebSocketClient socket;
    /** Set once closed by us, so a socket callback cannot resurrect the stream. */
    private volatile boolean stopped;
    private volatile boolean open;
    private long lastSendMs;

    public DeepgramStream(String apiKey, String model, String language, Listener listener) {
        this.apiKey = apiKey;
        this.model = model;
        this.language = language;
        this.listener = listener;
        this.thread = new HandlerThread("deepgram");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    /**
     * Builds the query string.
     *
     * {@code interim_results} is not optional here: without it Deepgram only
     * speaks at the end of an utterance and the lens shows nothing while you
     * talk, which is the entire point of the feature. {@code endpointing} is the
     * silence, in ms, that closes an utterance -- 300ms matches the cadence the
     * vendor recogniser produced in the reference capture.
     */
    private String url() {
        Map<String, String> q = new java.util.LinkedHashMap<>();
        q.put("model", model);
        q.put("language", language);
        q.put("encoding", "linear16");
        q.put("sample_rate", "16000");
        q.put("channels", "1");
        q.put("interim_results", "true");
        q.put("punctuate", "true");
        q.put("smart_format", "true");
        q.put("endpointing", "300");
        // SpeechStarted events: the glasses gate their subtitle view on "voice
        // detected", so we need it as an event, not inferred from a transcript.
        q.put("vad_events", "true");
        // Closes an utterance on a 1s gap even when endpointing does not fire.
        q.put("utterance_end_ms", "1000");
        StringBuilder sb = new StringBuilder(HOST);
        char sep = '?';
        for (Map.Entry<String, String> e : q.entrySet()) {
            sb.append(sep).append(e.getKey()).append('=').append(encode(e.getValue()));
            sep = '&';
        }
        return sb.toString();
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Opens the socket. {@link Listener#onOpen} fires when it is usable. */
    public void start() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (stopped || socket != null) return;
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Token " + apiKey);
                try {
                    socket = new WebSocketClient(new URI(url()), headers) {
                        @Override
                        public void onOpen(ServerHandshake handshake) {
                            open = true;
                            lastSendMs = System.currentTimeMillis();
                            LogBus.log("Deepgram connected (" + model + "/" + language + ")");
                            handler.post(keepAlive);
                            listener.onOpen();
                        }

                        @Override
                        public void onMessage(String message) {
                            handleMessage(message);
                        }

                        @Override
                        public void onMessage(ByteBuffer bytes) {
                            // Deepgram never sends binary on this endpoint.
                        }

                        @Override
                        public void onClose(int code, String reason, boolean remote) {
                            open = false;
                            if (stopped) return;
                            listener.onClosed("code " + code
                                    + (reason == null || reason.isEmpty() ? "" : ": " + reason));
                        }

                        @Override
                        public void onError(Exception ex) {
                            LogBus.error("Deepgram socket error", ex);
                            if (!stopped) listener.onError(String.valueOf(ex.getMessage()));
                        }
                    };
                    socket.setConnectionLostTimeout(0); // we run our own keep-alive
                    socket.connect();
                    // connect() is async; a failure surfaces through onError/onClose.
                    handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
                } catch (Exception e) {
                    LogBus.error("could not open the Deepgram socket", e);
                    listener.onError(String.valueOf(e.getMessage()));
                }
            }
        });
    }

    private final Runnable connectTimeout = new Runnable() {
        @Override
        public void run() {
            if (stopped || open) return;
            listener.onError("timed out connecting to Deepgram");
        }
    };

    /**
     * Deepgram treats a silent socket as abandoned. Real silence is common here
     * -- the glasses stream continuously but a quiet room still produces audio,
     * so this mostly matters while the recorder is starting or paused.
     */
    private final Runnable keepAlive = new Runnable() {
        @Override
        public void run() {
            if (stopped || socket == null || !open) return;
            if (System.currentTimeMillis() - lastSendMs >= KEEPALIVE_MS) {
                sendText("{\"type\":\"KeepAlive\"}");
            }
            handler.postDelayed(this, KEEPALIVE_MS);
        }
    };

    /** Queues one PCM chunk. Safe to call from any thread; never blocks. */
    public void sendAudio(final byte[] pcm) {
        if (stopped || pcm.length == 0) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (stopped || socket == null || !open) return;
                try {
                    socket.send(pcm);
                    lastSendMs = System.currentTimeMillis();
                } catch (Exception e) {
                    // A closed socket throws; onClose has already been reported.
                    LogBus.trace("Deepgram audio dropped: " + e);
                }
            }
        });
    }

    private void sendText(String text) {
        try {
            if (socket != null && open) socket.send(text);
        } catch (Exception e) {
            LogBus.trace("Deepgram control frame dropped: " + e);
        }
    }

    /**
     * Closes the stream.
     *
     * {@code CloseStream} asks Deepgram to flush whatever it is still holding
     * and finalise, so the tail of the last sentence is not lost; the socket is
     * then closed regardless after a short grace period.
     */
    public void stop() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (stopped) return;
                sendText("{\"type\":\"CloseStream\"}");
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        stopped = true;
                        open = false;
                        if (socket != null) {
                            try {
                                socket.close();
                            } catch (Exception ignored) {
                                // Already gone.
                            }
                            socket = null;
                        }
                        thread.quitSafely();
                    }
                }, 400);
            }
        });
    }

    private void handleMessage(String message) {
        try {
            JSONObject o = new JSONObject(message);
            String type = o.optString("type", "");
            if ("SpeechStarted".equals(type)) {
                listener.onSpeechStarted();
                return;
            }
            if ("UtteranceEnd".equals(type)) {
                listener.onUtteranceEnd();
                return;
            }
            if ("Results".equals(type) || o.has("channel")) {
                JSONObject channel = o.optJSONObject("channel");
                if (channel == null) return;
                JSONArray alts = channel.optJSONArray("alternatives");
                if (alts == null || alts.length() == 0) return;
                String transcript = alts.getJSONObject(0).optString("transcript", "");
                boolean isFinal = o.optBoolean("is_final", false);
                boolean speechFinal = o.optBoolean("speech_final", false);
                double start = o.optDouble("start", 0);
                double duration = o.optDouble("duration", 0);
                listener.onResult(transcript, isFinal, speechFinal,
                        Math.round(start * 1000), Math.round((start + duration) * 1000));
                return;
            }
            if ("Metadata".equals(type)) {
                LogBus.trace("Deepgram metadata: " + message);
                return;
            }
            if (o.has("error") || o.has("err_msg")) {
                String err = o.optString("err_msg", o.optString("error", message));
                LogBus.warn("Deepgram error: " + err);
                listener.onError(err);
            }
        } catch (Exception e) {
            LogBus.trace("unparsed Deepgram frame: " + e);
        }
    }
}

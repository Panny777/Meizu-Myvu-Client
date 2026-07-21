package com.myvu.client.ai;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.myvu.client.core.LogBus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Speaks the assistant's answer.
 *
 * The completion callback is what gates the protocol: the glasses expect
 * code:6 playState:1 -> audio -> code:6 playState:2, so playback finishing has
 * to be observable rather than guessed at with a timer.
 *
 * Audio routes wherever the system sends it. With the glasses connected as an
 * A2DP sink that is their speaker, which is what the real app does. Forcing the
 * route explicitly is deliberately not attempted -- it fights the glasses' own
 * audio focus.
 */
public class TtsPlayer {

    public interface Callback {
        void onSpoken(boolean success);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private TextToSpeech tts;
    private MediaPlayer mediaPlayer;
    private File mediaFile;
    private boolean ready;
    private Callback pending;
    private String pendingText;
    private int requestGeneration;

    public TtsPlayer(Context context) {
        this.context = context;
    }

    /** Initialises the engine; safe to call more than once. */
    public void init() {
        if (tts != null) return;
        tts = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                ready = status == TextToSpeech.SUCCESS;
                if (!ready) {
                    LogBus.warn("text-to-speech unavailable (status " + status + ")");
                    flushPending(false);
                    return;
                }
                tts.setLanguage(Locale.getDefault());
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) { }

                    @Override
                    public void onDone(String utteranceId) {
                        flushPending(true);
                    }

                    @Override
                    public void onError(String utteranceId) {
                        LogBus.warn("text-to-speech failed for " + utteranceId);
                        flushPending(false);
                    }
                });
                // Speak anything queued while we were still initialising.
                if (pendingText != null) {
                    String text = pendingText;
                    pendingText = null;
                    speak(text, pending);
                }
            }
        });
    }

    /** Speaks {@code text}, invoking {@code cb} when playback actually ends. */
    public void speak(String text, Callback cb) {
        if (tts == null) {
            pendingText = text;
            pending = cb;
            init();
            return;
        }
        if (!ready) {
            // Still initialising: hold it rather than dropping it.
            pendingText = text;
            pending = cb;
            return;
        }
        pending = cb;
        String id = UUID.randomUUID().toString();
        int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
        if (result != TextToSpeech.SUCCESS) {
            LogBus.warn("text-to-speech rejected the utterance");
            flushPending(false);
        }
    }

    /** Fetches WAV audio from the configured HTTP service and plays it as media. */
    public void speakHttp(String text, String endpoint, String apiKey, String model,
                          String voice, Callback cb) {
        stopMedia();
        pending = cb;
        final int generation = ++requestGeneration;
        final HttpTtsClient client = new HttpTtsClient(endpoint, apiKey, model, voice);
        network.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] audio = client.synthesize(text);
                    File file = File.createTempFile("myvu-tts-", ".wav", context.getCacheDir());
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        out.write(audio);
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (generation != requestGeneration) {
                                delete(file);
                                return;
                            }
                            playFile(file);
                        }
                    });
                } catch (Exception e) {
                    LogBus.error("HTTP text-to-speech failed", e);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (generation == requestGeneration) flushPending(false);
                        }
                    });
                }
            }
        });
    }

    private void playFile(File file) {
        stopMedia();
        mediaFile = file;
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer player) {
                player.start();
            }
        });
        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer player) {
                stopMedia();
                flushPending(true);
            }
        });
        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer player, int what, int extra) {
                LogBus.warn("HTTP text-to-speech playback failed (what " + what
                        + ", extra " + extra + ")");
                stopMedia();
                flushPending(false);
                return true;
            }
        });
        try {
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.prepareAsync();
        } catch (IOException | IllegalStateException e) {
            LogBus.error("could not prepare HTTP text-to-speech audio", e);
            stopMedia();
            flushPending(false);
        }
    }

    private void flushPending(boolean success) {
        Callback cb = pending;
        pending = null;
        if (cb != null) cb.onSpoken(success);
    }

    private void stopMedia() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (mediaFile != null) {
            delete(mediaFile);
            mediaFile = null;
        }
    }

    private static void delete(File file) {
        if (file.exists() && !file.delete()) {
            LogBus.warn("could not delete temporary TTS audio " + file.getAbsolutePath());
        }
    }

    public void shutdown() {
        requestGeneration++;
        stopMedia();
        network.shutdownNow();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            ready = false;
        }
    }
}

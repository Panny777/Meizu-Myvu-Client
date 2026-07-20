package com.myvu.client.ai;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.myvu.client.core.LogBus;

import java.util.Locale;
import java.util.UUID;

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
    private TextToSpeech tts;
    private boolean ready;
    private Callback pending;
    private String pendingText;

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

    private void flushPending(boolean success) {
        Callback cb = pending;
        pending = null;
        if (cb != null) cb.onSpoken(success);
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            ready = false;
        }
    }
}

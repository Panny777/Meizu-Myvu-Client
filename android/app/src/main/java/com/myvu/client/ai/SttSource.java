package com.myvu.client.ai;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import com.myvu.client.core.LogBus;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Speech recognition via Android's built-in SpeechRecognizer.
 *
 * This is a straight upgrade on the Python client's pipeline. That one recorded
 * to a buffer, uploaded to Groq, got the whole sentence back at once, and then
 * had to FAKE a growing caption with a word-by-word timer -- and had to guess at
 * speech onset. Here the platform gives us real onset and real partials, which
 * is exactly what the glasses' caption expects.
 *
 * MUST be created and driven on the main looper: SpeechRecognizer is
 * looper-affine and throws otherwise.
 */
public class SttSource {

    public interface Listener {
        /** Speech started. Fire VAD start from here -- it clears the 8s timeout. */
        void onSpeechStart();
        /** A growing prefix of what has been recognised so far. */
        void onPartial(String text);
        /** Speech ended (before the final result arrives). */
        void onSpeechEnd();
        /** The final transcript. Empty means nothing was understood. */
        void onFinal(String text);
        void onError(String reason);
    }

    private final Context context;
    private SpeechRecognizer recognizer;
    private Listener listener;
    private boolean speechStarted;

    public SttSource(Context context) {
        this.context = context;
    }

    public static boolean isAvailable(Context context) {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    /** Begins listening. Call on the main thread. */
    public void start(Listener l) {
        this.listener = l;
        this.speechStarted = false;

        if (!isAvailable(context)) {
            l.onError("no speech recognition service on this device");
            return;
        }

        stop();
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                LogBus.trace("stt: ready");
            }

            @Override
            public void onBeginningOfSpeech() {
                speechStarted = true;
                listener.onSpeechStart();
            }

            @Override
            public void onRmsChanged(float rmsdB) { }

            @Override
            public void onBufferReceived(byte[] buffer) { }

            @Override
            public void onEndOfSpeech() {
                listener.onSpeechEnd();
            }

            @Override
            public void onError(int error) {
                listener.onError(describeError(error));
            }

            @Override
            public void onResults(Bundle results) {
                listener.onFinal(firstResult(results));
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                String text = firstResult(partialResults);
                if (!text.isEmpty()) listener.onPartial(text);
            }

            @Override
            public void onEvent(int eventType, Bundle params) { }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                // Partials are what make the glasses' caption build up.
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());

        recognizer.startListening(intent);
    }

    public void stop() {
        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
            } catch (Exception ignored) {
                // Already torn down.
            }
            recognizer = null;
        }
    }

    public boolean speechDetected() {
        return speechStarted;
    }

    private static String firstResult(Bundle bundle) {
        if (bundle == null) return "";
        ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (list == null || list.isEmpty()) ? "" : list.get(0);
    }

    private static String describeError(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "audio recording error";
            case SpeechRecognizer.ERROR_CLIENT: return "client error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "microphone permission not granted";
            case SpeechRecognizer.ERROR_NETWORK: return "network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH: return "nothing recognised";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "recogniser busy";
            case SpeechRecognizer.ERROR_SERVER: return "server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "no speech heard";
            default: return "speech error " + error;
        }
    }
}

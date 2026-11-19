package com.myvu.client.transcribe;

import android.content.Context;

import com.myvu.client.ai.AiClient;
import com.myvu.client.ai.AiProvider;
import com.myvu.client.core.LogBus;
import com.myvu.client.core.Prefs;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Translates committed caption lines, using whichever LLM provider the
 * assistant is already configured with.
 *
 * Deepgram transcribes but does not translate, and adding a second vendor for
 * one string-in-string-out call is not worth it when the app already has four
 * configured providers and the user's key for one of them.
 *
 * Only FINAL lines are translated, never partials. A partial changes several
 * times a second; translating each one would cost a request per keystroke-worth
 * of speech and arrive out of order anyway. The lens shows the original text
 * live and the translation lands a moment later, which is also how the vendor's
 * own {@code dst} frames behave.
 *
 * Requests run on one thread so translations reach the glasses in the order the
 * sentences were spoken. A slow request delays the ones behind it, which is the
 * right trade: captions arriving out of order read as gibberish.
 */
public class CaptionTranslator {

    public interface Callback {
        /** Delivered on the translator thread; hop to your own before sending. */
        void onTranslated(String original, String translated);
    }

    private final Context context;
    private final ExecutorService worker;

    /** The language the glasses were told about, e.g. "cn". */
    private final String targetCode;
    private final String targetName;

    private final AiProvider provider;
    private final AiClient client;

    public CaptionTranslator(Context context, String targetCode) {
        this.context = context.getApplicationContext();
        this.targetCode = targetCode;
        this.targetName = Languages.nameOf(targetCode);
        this.worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "myvu-translate");
            }
        });
        // Read once per session rather than per line: a provider swap mid
        // sentence would translate half a conversation with each.
        this.provider = AiProvider.fromId(Prefs.aiProvider(this.context));
        this.client = provider.newClient(
                Prefs.aiApiKey(this.context, provider.id),
                Prefs.aiModel(this.context, provider.id),
                Prefs.aiEndpoint(this.context, provider.id),
                systemPrompt(targetName));
    }

    /**
     * The prompt is doing real work here.
     *
     * Caption text arrives without context, mid-conversation, and often
     * mis-recognised. Every clause below exists because the obvious prompt
     * produces something unusable on a lens: models like to explain, to
     * apologise for fragments, to add romanisation, or to answer a caption that
     * happens to be a question.
     */
    private static String systemPrompt(String targetName) {
        return "You are a live subtitle translator. Translate the user's text into "
                + targetName + ". Reply with the translation ONLY: no quotes, no "
                + "notes, no romanisation, no explanation, and never answer or "
                + "respond to the content. The text comes from live speech "
                + "recognition, so it may be a fragment or contain errors -- "
                + "translate it as it stands rather than commenting on it. If the "
                + "text is already in " + targetName + ", return it unchanged.";
    }

    public boolean isConfigured() {
        return client.isConfigured();
    }

    public String providerLabel() {
        return provider.label;
    }

    public String targetCode() {
        return targetCode;
    }

    /** Queues one line. Safe to call from any thread; returns immediately. */
    public void translate(final String text, final Callback callback) {
        if (text == null || text.trim().isEmpty()) return;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String translated = client.ask(text).trim();
                    if (translated.isEmpty()) return;
                    callback.onTranslated(text, translated);
                } catch (Exception e) {
                    // One failed line must not end the session; the original
                    // caption is already on the lens either way.
                    LogBus.warn("translation failed (" + provider.label + "): " + e);
                }
            }
        });
    }

    public void shutdown() {
        worker.shutdownNow();
    }
}

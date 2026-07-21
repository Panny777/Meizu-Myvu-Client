package com.myvu.client.ai;

/**
 * The selectable answer backends.
 *
 * Everything the rest of the app needs to know about a provider lives on its
 * constant -- pref id, UI label, key console, shipped model -- so adding a
 * fourth provider is one constant here plus one {@link AiHttpClient} subclass.
 */
public enum AiProvider {

    CLAUDE("claude", "Claude", "console.anthropic.com", "claude-haiku-4-5-20251001"),
    OPENAI("openai", "ChatGPT", "platform.openai.com", "gpt-4.1-mini"),
    GEMINI("gemini", "Gemini", "aistudio.google.com", "gemini-2.5-flash");

    /** Stable id used in SharedPreferences names -- never rename a value. */
    public final String id;
    /** What the user sees in Settings and the log. */
    public final String label;
    /** Where an API key comes from, shown as Settings helper text. */
    public final String console;
    /** Used when the model field in Settings is left blank. */
    public final String defaultModel;

    AiProvider(String id, String label, String console, String defaultModel) {
        this.id = id;
        this.label = label;
        this.console = console;
        this.defaultModel = defaultModel;
    }

    /** Blank model or system prompt fall back to the shipped defaults. */
    public AiClient newClient(String apiKey, String model, String systemPrompt) {
        switch (this) {
            case OPENAI: return new OpenAiClient(apiKey, model, systemPrompt);
            case GEMINI: return new GeminiClient(apiKey, model, systemPrompt);
            default:     return new ClaudeClient(apiKey, model, systemPrompt);
        }
    }

    /** Unknown ids fall back to Claude rather than crashing on a stale pref. */
    public static AiProvider fromId(String id) {
        for (AiProvider p : values()) {
            if (p.id.equals(id)) return p;
        }
        return CLAUDE;
    }
}

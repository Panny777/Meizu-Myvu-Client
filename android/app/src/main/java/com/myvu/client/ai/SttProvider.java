package com.myvu.client.ai;

/** Selectable OpenAI-compatible speech-to-text services. */
public enum SttProvider {
    GROQ(
            "groq",
            "Groq",
            "https://api.groq.com/openai/v1/audio/transcriptions",
            "whisper-large-v3-turbo",
            true),
    LOCAL(
            "local",
            "Local STT",
            "http://10.0.0.2:1235/v1/audio/transcriptions",
            "whisper",
            false);

    public final String id;
    public final String label;
    public final String defaultEndpoint;
    public final String defaultModel;
    public final boolean apiKeyRequired;

    SttProvider(String id, String label, String defaultEndpoint, String defaultModel,
                boolean apiKeyRequired) {
        this.id = id;
        this.label = label;
        this.defaultEndpoint = defaultEndpoint;
        this.defaultModel = defaultModel;
        this.apiKeyRequired = apiKeyRequired;
    }

    public static SttProvider fromId(String id) {
        for (SttProvider provider : values()) {
            if (provider.id.equals(id)) return provider;
        }
        return GROQ;
    }
}

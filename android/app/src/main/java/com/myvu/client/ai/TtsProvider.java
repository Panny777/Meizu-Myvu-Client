package com.myvu.client.ai;

/** Selects Android's speech engine or an OpenAI-compatible HTTP speech API. */
public enum TtsProvider {
    SYSTEM("system", "Device"),
    HTTP("http", "HTTP API");

    public final String id;
    public final String label;

    TtsProvider(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public static TtsProvider fromId(String id) {
        for (TtsProvider provider : values()) {
            if (provider.id.equals(id)) return provider;
        }
        return SYSTEM;
    }
}

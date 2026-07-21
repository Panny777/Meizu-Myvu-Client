package com.myvu.client.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.HttpURLConnection;

/** Answers via the Gemini generateContent API. */
public class GeminiClient extends AiHttpClient {

    private static final String BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";
    /**
     * Roomier than the other providers' 1024: Gemini 2.5 spends its internal
     * "thinking" tokens out of this same budget before any visible text, and a
     * tight cap yields an empty answer rather than a short one.
     */
    private static final int MAX_TOKENS = 4096;

    public GeminiClient(String apiKey, String model, String systemPrompt) {
        super(AiProvider.GEMINI, apiKey, model, systemPrompt);
    }

    /** Gemini addresses the model in the path, not the body. */
    @Override
    protected String endpoint() {
        return BASE + model + ":generateContent";
    }

    @Override
    protected void authorize(HttpURLConnection conn) {
        conn.setRequestProperty("x-goog-api-key", apiKey);
    }

    @Override
    protected String buildBody(String question) throws JSONException {
        return new JSONObject()
                .put("system_instruction", new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject()
                                .put("text", systemPrompt))))
                .put("contents", new JSONArray().put(new JSONObject()
                        .put("role", "user")
                        .put("parts", new JSONArray().put(new JSONObject()
                                .put("text", question)))))
                .put("generationConfig", new JSONObject()
                        .put("maxOutputTokens", MAX_TOKENS))
                .toString();
    }

    /** The answer is candidates[0].content.parts[], one or more text parts. */
    @Override
    protected String extractText(String response) throws JSONException {
        JSONObject candidate = new JSONObject(response)
                .getJSONArray("candidates").getJSONObject(0);
        JSONObject content = candidate.optJSONObject("content");
        JSONArray parts = content != null ? content.optJSONArray("parts") : null;
        if (parts == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            sb.append(parts.getJSONObject(i).optString("text"));
        }
        return sb.toString();
    }
}

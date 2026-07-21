package com.myvu.client.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.HttpURLConnection;

/** Answers via the Claude Messages API. */
public class ClaudeClient extends AiHttpClient {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 1024;

    public ClaudeClient(String apiKey, String model, String systemPrompt) {
        super(AiProvider.CLAUDE, apiKey, model, systemPrompt);
    }

    @Override
    protected String endpoint() {
        return ENDPOINT;
    }

    @Override
    protected void authorize(HttpURLConnection conn) {
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("anthropic-version", API_VERSION);
    }

    @Override
    protected String buildBody(String question) throws JSONException {
        return new JSONObject()
                .put("model", model)
                .put("max_tokens", MAX_TOKENS)
                .put("system", systemPrompt)
                .put("messages", new JSONArray().put(new JSONObject()
                        .put("role", "user")
                        .put("content", question)))
                .toString();
    }

    /** The answer lives in content[] as one or more text blocks. */
    @Override
    protected String extractText(String response) throws JSONException {
        JSONArray content = new JSONObject(response).getJSONArray("content");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.getJSONObject(i);
            if ("text".equals(block.optString("type"))) {
                sb.append(block.optString("text"));
            }
        }
        return sb.toString();
    }
}

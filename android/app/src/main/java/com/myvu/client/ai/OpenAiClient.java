package com.myvu.client.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.HttpURLConnection;

/** Answers via the OpenAI Chat Completions API (what the ChatGPT models speak). */
public class OpenAiClient extends AiHttpClient {

    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final int MAX_TOKENS = 1024;

    public OpenAiClient(String apiKey, String model, String systemPrompt) {
        super(AiProvider.OPENAI, apiKey, model, systemPrompt);
    }

    @Override
    protected String endpoint() {
        return ENDPOINT;
    }

    @Override
    protected void authorize(HttpURLConnection conn) {
        conn.setRequestProperty("authorization", "Bearer " + apiKey);
    }

    @Override
    protected String buildBody(String question) throws JSONException {
        // max_completion_tokens, not the older max_tokens: the model name is
        // user-editable, and reasoning models (o-series, gpt-5) reject max_tokens.
        return new JSONObject()
                .put("model", model)
                .put("max_completion_tokens", MAX_TOKENS)
                .put("messages", new JSONArray()
                        .put(new JSONObject()
                                .put("role", "system")
                                .put("content", systemPrompt))
                        .put(new JSONObject()
                                .put("role", "user")
                                .put("content", question)))
                .toString();
    }

    /** The answer is choices[0].message.content. */
    @Override
    protected String extractText(String response) throws JSONException {
        JSONObject message = new JSONObject(response)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message");
        // content is JSON null when the model answered with something other
        // than text; optString would render that as the literal string "null".
        return message.isNull("content") ? "" : message.optString("content");
    }
}

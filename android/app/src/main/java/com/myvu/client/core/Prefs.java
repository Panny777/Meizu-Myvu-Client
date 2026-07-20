package com.myvu.client.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Small typed wrapper over the default SharedPreferences. */
public final class Prefs {
    private Prefs() {}

    private static final String KEY_MAC = "target_mac";
    private static final String KEY_MIRROR_ENABLED = "mirror_notifications";
    private static final String KEY_MIRROR_BLOCKED = "mirror_blocked_packages";
    private static final String KEY_CLAUDE_KEY = "claude_api_key";
    private static final String KEY_GROQ_KEY = "groq_api_key";
    private static final String KEY_SYSTEM_PROMPT = "ai_system_prompt";

    public static final String DEFAULT_MAC = "2C:6F:4E:00:DC:47";

    /**
     * Packages whose notifications are never mirrored. Ongoing/system chatter
     * would otherwise flood the relay and starve the ACK path.
     */
    private static final Set<String> DEFAULT_BLOCKED = Collections.unmodifiableSet(
            new HashSet<>(java.util.Arrays.asList(
                    "android",
                    "com.android.systemui",
                    "com.myvu.client",            // our own foreground-service notice
                    "com.upuphone.star.launcher.intl")));

    private static SharedPreferences prefs(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext());
    }

    public static String targetMac(Context c) {
        return prefs(c).getString(KEY_MAC, DEFAULT_MAC);
    }

    public static void setTargetMac(Context c, String mac) {
        prefs(c).edit().putString(KEY_MAC, mac).apply();
    }

    public static boolean mirrorEnabled(Context c) {
        return prefs(c).getBoolean(KEY_MIRROR_ENABLED, true);
    }

    public static void setMirrorEnabled(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(KEY_MIRROR_ENABLED, enabled).apply();
    }

    public static Set<String> blockedPackages(Context c) {
        Set<String> stored = prefs(c).getStringSet(KEY_MIRROR_BLOCKED, null);
        return stored != null ? stored : DEFAULT_BLOCKED;
    }

    public static void setBlockedPackages(Context c, Set<String> packages) {
        prefs(c).edit().putStringSet(KEY_MIRROR_BLOCKED, packages).apply();
    }

    /** Never hard-code this: the key is entered by the user at runtime. */
    public static String claudeApiKey(Context c) {
        return prefs(c).getString(KEY_CLAUDE_KEY, "");
    }

    public static void setClaudeApiKey(Context c, String key) {
        prefs(c).edit().putString(KEY_CLAUDE_KEY, key).apply();
    }

    /** Speech-to-text for the glasses' microphone stream. Same rule: runtime only. */
    public static String groqApiKey(Context c) {
        return prefs(c).getString(KEY_GROQ_KEY, "");
    }

    public static void setGroqApiKey(Context c, String key) {
        prefs(c).edit().putString(KEY_GROQ_KEY, key).apply();
    }

    /**
     * The assistant's system prompt, as customised in Settings. Empty means
     * "not customised" -- ClaudeClient then falls back to its own default, so
     * the shipped wording lives with the AI code rather than being copied here.
     * Read fresh on every turn, so an edit applies to the next question.
     */
    public static String systemPrompt(Context c) {
        return prefs(c).getString(KEY_SYSTEM_PROMPT, "");
    }

    public static void setSystemPrompt(Context c, String prompt) {
        prefs(c).edit().putString(KEY_SYSTEM_PROMPT, prompt).apply();
    }
}

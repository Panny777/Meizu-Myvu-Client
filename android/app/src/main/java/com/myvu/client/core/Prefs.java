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
    private static final String KEY_MIRROR_ALLOWED = "mirror_allowed_packages";
    private static final String KEY_AI_PROVIDER = "ai_provider";
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

    /**
     * Apps the user has opted IN to mirroring, by package name.
     *
     * Deliberately an allowlist, not a blocklist: notifications carry OTPs, 2FA
     * codes and private messages, and forwarding everything by default sends all
     * of that to the glasses. Empty therefore means "mirror nothing" -- the safe
     * reading of "the user has not chosen yet".
     */
    public static Set<String> allowedPackages(Context c) {
        Set<String> stored = prefs(c).getStringSet(KEY_MIRROR_ALLOWED, null);
        return stored != null ? stored : Collections.<String>emptySet();
    }

    public static void setAllowedPackages(Context c, Set<String> packages) {
        // Copy: SharedPreferences must not be handed a set the caller keeps mutating.
        prefs(c).edit().putStringSet(KEY_MIRROR_ALLOWED, new HashSet<>(packages)).apply();
    }

    /** True when {@code pkg} is opted in AND not hard-blocked. */
    public static boolean isPackageAllowed(Context c, String pkg) {
        return pkg != null && !blockedPackages(c).contains(pkg)
                && allowedPackages(c).contains(pkg);
    }

    /**
     * Which backend answers questions: an {@code AiProvider.id}. The literal
     * default keeps this class free of a dependency on the ai package --
     * {@code AiProvider.fromId} falls back to Claude for unknown ids anyway.
     */
    public static String aiProvider(Context c) {
        return prefs(c).getString(KEY_AI_PROVIDER, "claude");
    }

    public static void setAiProvider(Context c, String providerId) {
        prefs(c).edit().putString(KEY_AI_PROVIDER, providerId).apply();
    }

    /**
     * Per-provider API key. Never hard-code one: keys are entered by the user
     * at runtime. The pref name derives from the provider id -- "claude" yields
     * claude_api_key, the name that predates provider choice, so keys entered
     * before this setting existed survive.
     */
    public static String aiApiKey(Context c, String providerId) {
        return prefs(c).getString(providerId + "_api_key", "");
    }

    public static void setAiApiKey(Context c, String providerId, String key) {
        prefs(c).edit().putString(providerId + "_api_key", key).apply();
    }

    /** Per-provider model override. Empty means the provider's shipped default. */
    public static String aiModel(Context c, String providerId) {
        return prefs(c).getString("ai_model_" + providerId, "");
    }

    public static void setAiModel(Context c, String providerId, String model) {
        prefs(c).edit().putString("ai_model_" + providerId, model).apply();
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

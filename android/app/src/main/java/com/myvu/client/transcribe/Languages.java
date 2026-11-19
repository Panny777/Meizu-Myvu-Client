package com.myvu.client.transcribe;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The language codes the GLASSES accept, and their English names.
 *
 * The firmware takes a short vendor-specific list and nothing else — sending
 * "en-US" or "zh" where it wants "en" or "cn" is accepted silently and then
 * labels the subtitle wrongly. The set is lifted from the decompiled
 * {@code LanguageUtils}, which builds it per region; this is the union, since
 * we cannot tell which build a given pair of glasses is running.
 *
 * The names exist because the translation prompt needs to say what to translate
 * INTO, and "translate into cn" is not a sentence any model handles well.
 */
public final class Languages {
    private Languages() {}

    private static final Map<String, String> NAMES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("en", "English");
        m.put("cn", "Simplified Chinese");
        // The vendor's "Chinese + English" mixed-recognition mode. As a
        // translation TARGET it only sensibly means English.
        m.put("cnen", "English");
        m.put("ja", "Japanese");
        m.put("ko", "Korean");
        m.put("fr", "French");
        m.put("de", "German");
        m.put("es", "Spanish");
        m.put("it", "Italian");
        m.put("ru", "Russian");
        m.put("vi", "Vietnamese");
        m.put("th", "Thai");
        m.put("ar", "Arabic");
        NAMES = Collections.unmodifiableMap(m);
    }

    /** True if the glasses will understand this code. */
    public static boolean isSupported(String code) {
        return code != null && NAMES.containsKey(code);
    }

    /**
     * The English name for a code, for use in a prompt. Unknown codes come back
     * unchanged rather than defaulting to a language: a wrong guess here would
     * silently translate into something the user never asked for.
     */
    public static String nameOf(String code) {
        String name = NAMES.get(code);
        return name != null ? name : code;
    }

    /** The codes, in menu order. */
    public static String[] codes() {
        return NAMES.keySet().toArray(new String[0]);
    }
}

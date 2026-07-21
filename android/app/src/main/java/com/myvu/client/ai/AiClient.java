package com.myvu.client.ai;

import java.io.IOException;

/**
 * Answers a recognized (or typed) question -- one question in, one answer out.
 *
 * One implementation per provider (see {@link AiProvider}). Clients are cheap,
 * single-turn objects: {@link AiConversation} builds a fresh one for every
 * question, so Settings edits apply without a reconnect.
 */
public interface AiClient {

    /**
     * The shipped default, deliberately provider-neutral. Answers are spoken
     * aloud on a pair of glasses, so length and formatting matter more than
     * usual -- markdown, lists and emoji are read out as literal junk. Public so
     * the Settings screen can show it as the editable text and restore it with
     * "Reset to default".
     */
    String DEFAULT_SYSTEM_PROMPT =
            "You are a voice assistant built into a pair of AR glasses. Answer in "
            + "one or two short sentences that sound natural read aloud. No "
            + "markdown, no lists, no code blocks, no emoji. If you do not know "
            + "something, say so briefly rather than guessing.";

    /** False disables answering because required provider settings are missing. */
    boolean isConfigured();

    /** Returns the answer text, or throws with a message worth showing. */
    String ask(String question) throws IOException;
}

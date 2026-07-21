package com.myvu.client.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.myvu.client.app.feature.AiProtocol;
import com.myvu.client.core.LogBus;
import com.myvu.client.core.Prefs;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The AI assistant, driven by the GLASSES' microphone.
 *
 * The glasses record audio themselves and stream it to the phone as code:109
 * Opus packets; the phone is expected to recognise it and send captions back.
 * That is why there is no SpeechRecognizer here -- it cannot be fed an audio
 * stream below API 33, and the audio is not ours to begin with.
 *
 * The glasses stream CONTINUOUSLY once the assistant is open -- silence
 * included -- so the packet stream carries no end-of-speech signal. Packets are
 * therefore decoded as they arrive and end-of-speech is found by measuring
 * audio energy, which is what the official app does with its native VAD
 * (VadDetector, feeding 512-byte chunks with a 600ms pause).
 *
 * THE ORDER BELOW IS THE PROTOCOL. The glasses run real timers:
 *   code:4            immediately, before any slow work (arms an 8s timeout)
 *   code:104 type:1   on the first audio packet -- the only thing that clears it
 *   code:104 type:2   when the audio stops
 *   code:101 type:0   growing partials, so the caption builds instead of flashing
 *   code:101 type:1   final caption
 *   code:106 (7)      VR_PROCESSION, AFTER the caption or the glasses drop it
 *   code:5 -> 6/1 -> audio -> 6/2 -> 107
 *
 * THREADING: TextToSpeech is looper-affine, so this lives on the main thread.
 * Decoding and the network calls run on a worker; sends hop to the connection
 * thread inside the sender.
 */
public class AiConversation {

    public interface Sender {
        void send(String actionJson, String targetPkg, String sourcePkg);
    }

    /**
     * How long the audio must stay quiet before the utterance is considered
     * over. The official app's VadDetector uses setVadPause(600); a little more
     * is used here because this is a plain energy threshold rather than their
     * native VAD, and cutting someone off mid-sentence is worse than waiting.
     */
    private static final long SILENCE_HOLD_MS = 900;

    /**
     * Mean sample amplitude above which a chunk counts as speech.
     *
     * The glasses stream CONTINUOUSLY while the assistant is open -- silence
     * included -- so there is no gap in the packet stream to detect. Energy is
     * the only signal available without a VAD model.
     */
    private static final double SPEECH_ENERGY = 80.0;

    /**
     * Speech must exceed the measured noise floor by this factor.
     *
     * A fixed threshold fired before anyone spoke, because the absolute level
     * depends on the microphone, its gain and the room. Calibrating against the
     * first moments of each utterance adapts to all three.
     */
    private static final double SPEECH_OVER_NOISE = 3.5;
    /** Chunks sampled at the start of listening to establish the noise floor. */
    private static final int NOISE_CALIBRATION_CHUNKS = 12;
    /**
     * Consecutive speech-level chunks during calibration that count as speech.
     * One loud chunk could be a pop or a breath; a run of them is a word.
     */
    private static final int CALIBRATION_LOUD_STREAK = 3;

    /** Give up if the glasses never send anything loud enough to be speech. */
    private static final long NO_SPEECH_TIMEOUT_MS = 10000;
    /** Hard cap on one utterance, in case the stream never goes quiet. */
    private static final long MAX_UTTERANCE_MS = 20000;
    /** Spacing for the simulated growing caption (Whisper returns text at once). */
    private static final long CAPTION_WORD_MS = 180;

    private static final long DUPLICATE_TRIGGER_MS = 1500;
    /**
     * One initial question plus ONE hands-free follow-up, then the conversation
     * ends cleanly with VR_CLOSE.
     *
     * The official app doesn't cap turns -- its cloud NLU does, via an
     * isNextRecorded flag in each answer that says "expect another turn". We
     * don't get that field (we call Claude/OpenAI/Gemini directly), and in the
     * btsnoop the official conversations run only 1-2 turns anyway. Forcing more
     * than that -- re-arming for a turn the glasses were never told to expect --
     * is what wedged them on turn 3, after turns 1 and 2 completed cleanly.
     * Two is the most we can sustain without the cloud's turn-control signal.
     */
    private static final int MAX_TURNS = 2;

    /**
     * Hands-free spoken follow-ups are OFF: every answer ends the conversation
     * cleanly, and the user presses the AI button again for the next question
     * (the typed path already works this way and has never wedged).
     *
     * This is a deliberate stop, not an unsolved mystery. A btsnoop of the
     * official app was decoded frame by frame, and our per-turn traffic now
     * matches it exactly -- codes, payloads and timing:
     *
     *   4(new sessionId) -> 104,104 -> 101 -> 106:7 -> 102 -> 122,122
     *     -> 6:play1 -> 6:play2 -> 107 -> (loop), close with 106:0
     *
     * plus the code-2 capability config the official app sends first. With all
     * that, turns 1 and 2 complete cleanly; the glasses still wedge on turn 3.
     *
     * The real reason we cannot sustain it: the official app does not decide
     * when to keep listening -- its cloud NLU does, via an isNextRecorded flag in
     * each answer. We call Claude/OpenAI/Gemini directly and never receive that
     * flag, so we can only guess, and a forced turn the glasses were never told
     * to expect is what wedges them. In the whole capture the official app runs
     * only 1-2 turns per conversation for the same reason.
     *
     * Flip to true (and see MAX_TURNS) to re-test if that signal ever becomes
     * available.
     */
    private static final boolean SPOKEN_FOLLOW_UP_TURNS = false;

    private static final String[] STOP_PHRASES = {
            "stop", "goodbye", "good bye", "bye", "exit", "quit",
            "that's all", "thats all", "that is all", "never mind", "nevermind",
            "thank you", "thanks", "cancel", "end",
    };

    private final Context context;
    private final Sender sender;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private final GlassesMicStream mic = new GlassesMicStream();
    private final OpusDecoderStream decoder = new OpusDecoderStream();
    private final TtsPlayer tts;
    /** Decoding runs off the connection thread; audio arrives faster than realtime. */
    private final ExecutorService audio = Executors.newSingleThreadExecutor();

    private volatile long lastSpeechAt;
    private volatile boolean decoding;
    /** Diagnostics for the no-speech case: was there audio, and how loud? */
    private volatile double peakEnergy;
    private volatile int decodedBytes;
    private volatile double noiseFloor;
    private volatile int noiseChunks;
    private volatile int loudStreak;
    private volatile double speechThreshold;

    private volatile boolean active;
    private String sessionId;
    private int turnCount;
    private boolean stopRequested;
    private boolean speechStarted;
    private long lastTriggerAt;
    /** True for a typed ask: no follow-up listening turn after the answer. */
    private boolean textMode;

    private final Runnable silenceTimeout = new Runnable() {
        @Override
        public void run() {
            endUtterance();
        }
    };
    private final Runnable utteranceCap = new Runnable() {
        @Override
        public void run() {
            LogBus.log("AI: utterance hit the length cap");
            endUtterance();
        }
    };

    public AiConversation(Context context, Sender sender) {
        this.context = context.getApplicationContext();
        this.sender = sender;
        this.tts = new TtsPlayer(this.context);
    }

    public boolean isActive() {
        return active;
    }

    // ---------------------------------------------------------- triggers

    /** The AI button (code:3) or wake word (code:7). Safe from any thread. */
    public void onTrigger(final int triggerCode) {
        main.post(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                // The same physical press arrives on both transports.
                if (now - lastTriggerAt < DUPLICATE_TRIGGER_MS) {
                    LogBus.trace("AI trigger ignored -- duplicate of the last press");
                    return;
                }
                lastTriggerAt = now;

                // A genuinely new press always wins: our view of the session goes
                // stale whenever the user quits the AI page on the glasses, and
                // ignoring the press left the assistant dead until a reconnect.
                if (active) {
                    LogBus.log("AI: new press while a turn was open -- restarting");
                    abandon();
                }
                begin(triggerCode);
            }
        });
    }

    /** The glasses reported the AI page closing (control:0). */
    public void onPageClosed() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!active) return;
                stopRequested = true;
                LogBus.trace("AI page closed -- will end after this turn");
            }
        });
    }

    /**
     * Offers a code:109 relay body. Returns true if it held audio, so the caller
     * can skip the usual JSON handling.
     *
     * Called on the connection thread at a high rate -- this must stay cheap.
     */
    public boolean onAudioFrame(byte[] relayBody) {
        if (!mic.offer(relayBody)) return false;
        if (!mic.isCapturing()) return true;

        // A payload can carry more than one Opus frame; decode every one.
        final java.util.List<byte[]> frames = new java.util.ArrayList<>(mic.justAdded());
        if (frames.isEmpty()) return true;

        // Decode off the connection thread: audio arrives faster than realtime
        // and MediaCodec must never block the relay.
        audio.execute(new Runnable() {
            @Override
            public void run() {
                for (byte[] frame : frames) {
                    if (!decoding) return;
                    byte[] pcm = decoder.feed(frame);
                    if (pcm.length == 0) continue;

                    decodedBytes += pcm.length;
                    double level = OpusDecoderStream.energy(pcm);
                    if (level > peakEnergy) peakEnergy = level;

                    consume(level);
                }
            }
        });
        return true;
    }

    /** Applies one decoded chunk's energy to the VAD state machine. Runs on the audio thread. */
    private void consume(double level) {
        // Spend the first chunks learning the room, then set the bar relative
        // to it -- but learn only from QUIET chunks. Averaging every early
        // chunk unconditionally meant that talking straight after the button
        // press folded the speech itself into the "noise floor", and the
        // threshold (3.5x that) sat above the speaker's own level for the rest
        // of the turn: speech was never detected.
        if (noiseChunks < NOISE_CALIBRATION_CHUNKS) {
            if (level < speechThreshold) {
                loudStreak = 0;
                noiseChunks++;
                noiseFloor = ((noiseFloor * (noiseChunks - 1)) + level) / noiseChunks;
                speechThreshold = Math.max(SPEECH_ENERGY, noiseFloor * SPEECH_OVER_NOISE);
                if (noiseChunks == NOISE_CALIBRATION_CHUNKS) {
                    LogBus.trace(String.format(Locale.US,
                            "AI: noise floor %.0f, speech threshold %.0f",
                            noiseFloor, speechThreshold));
                }
                return;
            }
            // Speech-level audio while still calibrating: not the room. Wait
            // for a sustained run, then stop calibrating and let the normal
            // detection below fire on this chunk.
            if (++loudStreak < CALIBRATION_LOUD_STREAK) return;
            noiseChunks = NOISE_CALIBRATION_CHUNKS;
            LogBus.trace(String.format(Locale.US,
                    "AI: speech before calibration finished -- floor %.0f, threshold %.0f",
                    noiseFloor, speechThreshold));
        }
        if (level >= speechThreshold) {
            lastSpeechAt = System.currentTimeMillis();
            if (!speechStarted) {
                speechStarted = true;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!active) return;
                        // Speech arrived, so the no-speech timer must go: left
                        // armed, it chopped any utterance still running at the
                        // 10s mark. The 20s cap still bounds the turn.
                        main.removeCallbacks(silenceTimeout);
                        // The only message that clears the glasses' 8s timeout.
                        send(AiProtocol.vadStart(sessionId));
                        LogBus.log("AI: speech detected");
                    }
                });
            }
        } else if (speechStarted
                && System.currentTimeMillis() - lastSpeechAt > SILENCE_HOLD_MS) {
            // Quiet for long enough after real speech: utterance over.
            decoding = false;
            main.post(new Runnable() {
                @Override
                public void run() { endUtterance(); }
            });
        }
    }

    // ------------------------------------------------------------- turns

    private void begin(int triggerCode) {
        active = true;
        stopRequested = false;
        textMode = false;
        turnCount = 0;
        // Configure the glasses' assistant (continuous dialogue, ChatGPT card)
        // before the first frame -- the config is what a follow-up needs.
        send(AiProtocol.assistantConfig());
        prepareTts();
        startListening(triggerCode == AiProtocol.CODE_START_VR_REQ ? "button" : "wake word");
    }

    private void startListening(String why) {
        sessionId = UUID.randomUUID().toString();
        speechStarted = false;
        lastSpeechAt = 0;
        peakEnergy = 0;
        decodedBytes = 0;
        noiseFloor = 0;
        noiseChunks = 0;
        loudStreak = 0;
        speechThreshold = SPEECH_ENERGY;
        try {
            decoder.start();
            decoding = true;
        } catch (Exception e) {
            LogBus.error("could not start the Opus decoder", e);
            finish();
            return;
        }
        // Capture opens only once the decoder is live: frames offered while
        // MediaCodec was still starting were queued and then dropped by the
        // decoding check, losing the start of the utterance and pushing the
        // calibration window into the speech that followed.
        mic.start();

        // Must be first: this ack is what stops the glasses showing "service
        // error", and it arms their 8s listening timeout.
        send(AiProtocol.sessionAck(sessionId));
        LogBus.log("AI listening (" + why + ")");

        // If nothing loud enough to be speech ever arrives, give up rather
        // than listen forever -- the glasses stream silence indefinitely.
        main.removeCallbacks(silenceTimeout);
        main.postDelayed(silenceTimeout, NO_SPEECH_TIMEOUT_MS);
        main.removeCallbacks(utteranceCap);
        main.postDelayed(utteranceCap, MAX_UTTERANCE_MS);
    }

    /** The audio went quiet: recognise what the decoder has produced. */
    private void endUtterance() {
        if (!active || !mic.isCapturing()) return;
        main.removeCallbacks(silenceTimeout);
        main.removeCallbacks(utteranceCap);
        mic.stop();
        decoding = false;

        if (!speechStarted) {
            // Say WHY: whether audio arrived, whether it decoded, and how loud
            // it got. Those three numbers separate "mic silent" from "decode
            // broken" from "threshold too high".
            LogBus.warn(String.format(Locale.US,
                    "AI: no speech heard -- %d packets in (%d unreadable), sizes %s, "
                            + "%d bytes decoded (%dms), peak energy %.0f vs threshold %.0f",
                    mic.packetCount(), mic.rejectedCount(), mic.observedSizes(),
                    decodedBytes,
                    decodedBytes / 2 * 1000 / Math.max(1, decoder.sampleRate()),
                    peakEnergy, speechThreshold));
            finish();
            return;
        }
        send(AiProtocol.vadEnd(sessionId));

        // Flush the decoder's latency tail before reading, or the last packets
        // of every utterance are lost.
        decoder.finish();
        final byte[] pcm = decoder.allPcm();
        decoder.stop();
        LogBus.log("captured " + (pcm.length / 2) + " samples ("
                + (pcm.length / 2 * 1000 / Math.max(1, decoder.sampleRate())) + "ms @ "
                + decoder.sampleRate() + "Hz) from " + mic.packetCount() + " Opus packets");
        if (pcm.length == 0) {
            LogBus.log("AI: no audio decoded -- ending");
            finish();
            return;
        }
        transcribe(pcm, decoder.sampleRate(), decoder.channels());
    }

    private void transcribe(final byte[] pcm, final int sampleRate, final int channels) {
        final SttProvider provider = SttProvider.fromId(Prefs.sttProvider(context));
        final String apiKey = Prefs.sttApiKey(context, provider.id);
        if (provider.apiKeyRequired && apiKey.trim().isEmpty()) {
            LogBus.warn("no " + provider.label + " API key set -- speech cannot be transcribed");
            finish();
            return;
        }
        String storedModel = Prefs.sttModel(context, provider.id).trim();
        final String model = storedModel.isEmpty() ? provider.defaultModel : storedModel;
        String storedEndpoint = Prefs.sttEndpoint(context, provider.id).trim();
        final String endpoint = storedEndpoint.isEmpty() ? provider.defaultEndpoint : storedEndpoint;
        final OpenAiTranscriptionClient client = new OpenAiTranscriptionClient(
                endpoint, model, apiKey, provider.label);
        if (!client.isConfigured()) {
            LogBus.warn(provider.label + " is not fully configured");
            finish();
            return;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final String text;
                try {
                    text = client.transcribe(pcm, sampleRate, channels);
                } catch (Exception e) {
                    LogBus.error("could not transcribe the glasses audio", e);
                    main.post(new Runnable() {
                        @Override
                        public void run() { finish(); }
                    });
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() { onTranscript(text); }
                });
            }
        });
    }

    private void onTranscript(String text) {
        if (!active) return;
        if (text == null || text.trim().isEmpty()) {
            LogBus.log("AI: nothing understood -- ending the conversation");
            finish();
            return;
        }
        if (isStopPhrase(text)) {
            LogBus.log("AI: stop phrase heard (\"" + text.trim() + "\")");
            finish();
            return;
        }
        LogBus.log("AI heard: " + text);
        sendGrowingCaption(text.trim(), 0);
    }

    /**
     * Builds the caption up word by word.
     *
     * Whisper returns the whole sentence at once, but the glasses expect a
     * series of growing partials -- sending it as one partial makes the caption
     * flash and vanish. Same simulation the Python client uses.
     */
    private void sendGrowingCaption(final String text, final int wordIndex) {
        if (!active) return;
        final String[] words = text.split("\\s+");

        if (wordIndex >= words.length) {
            send(AiProtocol.asrResult(sessionId, text, true));
            // VR_PROCESSION only AFTER the final caption, or the glasses drop
            // the caption frames entirely.
            send(AiProtocol.vrState(AiProtocol.VR_PROCESSION));
            // Open the LLM scene EVERY turn. The btsnoop shows the official app
            // sends 102 on each follow-up too (a fresh 102 per new sessionId),
            // so gating it to the first turn was wrong.
            send(AiProtocol.chatQuery(sessionId, text));
            askAi(text);
            return;
        }

        StringBuilder partial = new StringBuilder();
        for (int i = 0; i <= wordIndex; i++) {
            if (i > 0) partial.append(' ');
            partial.append(words[i]);
        }
        send(AiProtocol.asrResult(sessionId, partial.toString(), false));

        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                sendGrowingCaption(text, wordIndex + 1);
            }
        }, CAPTION_WORD_MS);
    }

    private void askAi(final String question) {
        // Provider, key, model and prompt are all read fresh per turn, so edits
        // in Settings apply to the next question.
        final AiProvider provider = AiProvider.fromId(Prefs.aiProvider(context));
        final AiClient client = provider.newClient(
                Prefs.aiApiKey(context, provider.id),
                Prefs.aiModel(context, provider.id),
                Prefs.aiEndpoint(context, provider.id),
                Prefs.systemPrompt(context));
        if (!client.isConfigured()) {
            LogBus.warn(provider.label + " is not fully configured -- check Settings");
            finish();
            return;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final String answer;
                try {
                    answer = client.ask(question);
                } catch (Exception e) {
                    LogBus.error(provider.label + " request failed", e);
                    main.post(new Runnable() {
                        @Override
                        public void run() { finish(); }
                    });
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() { deliver(answer); }
                });
            }
        });
    }

    private void deliver(String answer) {
        if (!active) return;
        LogBus.log("AI answer: " + answer);

        send(AiProtocol.chatAnswer(sessionId, answer, 1));
        send(AiProtocol.chatAnswer(sessionId, answer, 2));
        // playState:1 = TTS started. A btsnoop of the official app shows it uses
        // ONLY code 6 for play state here -- it does NOT send the 106 VR TTS
        // states (3/4) an earlier guess added, and those extra frames were what
        // wedged the glasses on the follow-up turn.
        send(AiProtocol.playState(AiProtocol.PLAY_STATE_START));

        TtsPlayer.Callback callback = new TtsPlayer.Callback() {
            @Override
            public void onSpoken(boolean success) {
                // Gated on the real completion callback, never a timer.
                send(AiProtocol.playState(AiProtocol.PLAY_STATE_END));
                send(AiProtocol.endTurn());
                if (!success) LogBus.warn("the answer could not be spoken aloud");
                // Both paths are one-shot while follow-ups are disabled; see
                // SPOKEN_FOLLOW_UP_TURNS for why.
                if (textMode || !SPOKEN_FOLLOW_UP_TURNS) finish(); else nextTurn();
            }
        };
        TtsProvider provider = TtsProvider.fromId(Prefs.ttsProvider(context));
        if (provider == TtsProvider.HTTP) {
            tts.speakHttp(
                    answer,
                    Prefs.ttsEndpoint(context),
                    Prefs.ttsApiKey(context),
                    Prefs.ttsModel(context),
                    Prefs.ttsVoice(context),
                    callback);
        } else {
            tts.speak(answer, callback);
        }
    }

    /**
     * Answers a TYPED question (REPL: ask), bypassing the microphone entirely.
     *
     * Reuses the whole answer path -- caption, card, TTS, end -- so the glasses
     * show and speak the reply just as they would for a voice query, but there
     * is no follow-up listening turn afterwards.
     */
    public void askText(final String question) {
        if (question == null || question.trim().isEmpty()) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (active) abandon();
                active = true;
                stopRequested = false;
                textMode = true;
                turnCount = 0;
                send(AiProtocol.assistantConfig());
                prepareTts();
                sessionId = UUID.randomUUID().toString();

                // Bring the glasses' AI page up and show the question as the
                // caption, then hand off to the shared answer path.
                send(AiProtocol.sessionAck(sessionId));
                send(AiProtocol.asrResult(sessionId, question.trim(), true));
                send(AiProtocol.vrState(AiProtocol.VR_PROCESSION));
                // Open the LLM scene, exactly as the spoken path does after its
                // final caption. Without this a typed question's answer (122)
                // is committed to a scene that was never opened.
                send(AiProtocol.chatQuery(sessionId, question.trim()));
                LogBus.log("AI (typed): " + question.trim());
                askAi(question.trim());
            }
        });
    }

    /**
     * Starts a follow-up turn rather than closing the session.
     *
     * VR_CLOSE quits the AI page outright, so sending it after every answer made
     * the page vanish as soon as the reply finished. It belongs only at the end
     * of the whole conversation.
     */
    private void nextTurn() {
        if (!active) return;
        if (stopRequested) {
            LogBus.log("AI: page was closed -- ending the conversation");
            finish();
            return;
        }
        if (++turnCount >= MAX_TURNS) {
            LogBus.log("AI: conversation limit reached");
            finish();
            return;
        }

        // startListening mints a fresh sessionId and sends the code-4 re-arm,
        // which is exactly how the official app opens each follow-up (verified in
        // btsnoop: a new sessionId per turn). Nothing else belongs here -- in
        // particular NOT VR_MULTI_WAKEUP, which the official app never sends.
        startListening("follow-up " + (turnCount + 1));
    }

    // -------------------------------------------------------------- end

    /** Ends the conversation and returns the glasses to idle. */
    private void finish() {
        if (!active) return;
        active = false;
        stopRequested = false;
        mic.stop();
        decoding = false;
        decoder.stop();
        main.removeCallbacks(silenceTimeout);
        main.removeCallbacks(utteranceCap);
        send(AiProtocol.vrState(AiProtocol.VR_CLOSE));
        LogBus.trace("AI conversation ended");
    }

    /** Drops local state without messaging the glasses (they have moved on). */
    private void abandon() {
        active = false;
        stopRequested = false;
        mic.stop();
        decoding = false;
        decoder.stop();
        main.removeCallbacks(silenceTimeout);
        main.removeCallbacks(utteranceCap);
    }

    public void stop() {
        main.post(new Runnable() {
            @Override
            public void run() { finish(); }
        });
    }

    public void shutdown() {
        stop();
        main.post(new Runnable() {
            @Override
            public void run() { tts.shutdown(); }
        });
        worker.shutdownNow();
        audio.shutdownNow();
    }

    private static boolean isStopPhrase(String text) {
        String t = text.trim().toLowerCase(Locale.US).replaceAll("[.!?,]", "");
        for (String phrase : STOP_PHRASES) {
            if (t.equals(phrase)) return true;
        }
        return false;
    }

    private void prepareTts() {
        if (TtsProvider.fromId(Prefs.ttsProvider(context)) == TtsProvider.SYSTEM) {
            tts.init();
        }
    }

    /** All AI messages are sourced from and addressed to the assistant package. */
    private void send(String actionJson) {
        if (actionJson == null) return;
        sender.send(actionJson, AiProtocol.PKG, AiProtocol.PKG);
    }
}

package com.myvu.client.transcribe;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import com.myvu.client.ai.OpusDecoderStream;
import com.myvu.client.app.feature.AiProtocol;
import com.myvu.client.core.LogBus;
import com.myvu.client.core.Prefs;

import java.security.SecureRandom;

import static com.myvu.client.transcribe.TranscribeProtocol.EXT_HINT_WAITING;
import static com.myvu.client.transcribe.TranscribeProtocol.EXT_MUTE_FIVE_MINS;
import static com.myvu.client.transcribe.TranscribeProtocol.EXT_MUTE_FIVE_SECS;
import static com.myvu.client.transcribe.TranscribeProtocol.EXT_MUTE_TWO_SECS;
import static com.myvu.client.transcribe.TranscribeProtocol.EXT_NONE;
import static com.myvu.client.transcribe.TranscribeProtocol.EXT_VOICE_NOT_MUTE;
import static com.myvu.client.transcribe.TranscribeProtocol.EXT_CONNECT_CHANNEL_ERROR;
import static com.myvu.client.transcribe.TranscribeProtocol.EXT_CONNECT_ON;
import static com.myvu.client.transcribe.TranscribeProtocol.EXT_SHOW_WAITING;
import static com.myvu.client.transcribe.TranscribeProtocol.P2G_PROXIMAL_RESULT;
import static com.myvu.client.transcribe.TranscribeProtocol.P2G_REMOTE_RESULT;
import static com.myvu.client.transcribe.TranscribeProtocol.REC_INIT;
import static com.myvu.client.transcribe.TranscribeProtocol.REC_RELEASE;
import static com.myvu.client.transcribe.TranscribeProtocol.REC_START;
import static com.myvu.client.transcribe.TranscribeProtocol.REC_STOP;
import static com.myvu.client.transcribe.TranscribeProtocol.RESULT_FINAL;
import static com.myvu.client.transcribe.TranscribeProtocol.RESULT_PARTIAL;
import static com.myvu.client.transcribe.TranscribeProtocol.STATE_END;
import static com.myvu.client.transcribe.TranscribeProtocol.STATE_NOT_START;
import static com.myvu.client.transcribe.TranscribeProtocol.STATE_PREPARING;
import static com.myvu.client.transcribe.TranscribeProtocol.STATE_RUNNING;

/**
 * Drives a live-captioning session on the glasses, with Deepgram in the place of
 * the vendor's cloud recogniser.
 *
 * The division of labour is the vendor's, not ours: the glasses open a subtitle
 * scene, capture from their own mic array and stream Opus to the phone; the
 * phone recognises and streams caption frames back. We reimplement the phone
 * half. The glasses cannot tell where the text came from.
 *
 * <h3>The sequence is load-bearing</h3>
 * The glasses run their own state machine and the order below was taken from a
 * btsnoop of a working vendor session:
 * <ol>
 *   <li>{@code open_app} to the LAUNCHER brings the scene up. The peer package
 *       does not exist before this, so nothing addressed to it lands.</li>
 *   <li>State 1 (NotStart) + the subtitle config, then state 3 (Preparing).</li>
 *   <li>{@code recordState=INIT} and {@code EXT_SHOW_WAITING} put the lens into
 *       "connecting", which is what covers the recogniser handshake.</li>
 *   <li>Only once the recogniser is actually live: state 4 (Running) and
 *       {@code recordState=START}. <b>START is what makes the glasses begin
 *       streaming audio</b> -- in the reference capture the first Opus packet
 *       arrived 180ms after it. Sending it early wastes audio into a socket
 *       that is not ready.</li>
 * </ol>
 *
 * <h3>Threading</h3>
 * Protocol sends are posted to the connection handler. Opus decoding runs on a
 * private thread because MediaCodec blocks, and stalling the connection thread
 * stalls the relay ACK path with it.
 */
public class TranscribeSession {

    public interface Sender {
        void send(String actionJson, String targetPkg, String sourcePkg);

        /**
         * Whether the classic-BT app relay is up.
         *
         * Captioning must not start on the BLE fallback. The relay coming up
         * mid-sequence splits the session across two transports with two
         * independent msgId runs -- observed live: the scene bring-up went out
         * on BLE, the relay finished its init burst 250ms later, and everything
         * from {@code transState:4} on went out on the relay. The glasses ran
         * the recorder but never initialised the scene's UI.
         */
        boolean isRelayReady();
    }

    /** UI updates. Delivered on the connection thread. */
    public interface Listener {
        void onStatus(String status, boolean running);

        /** The current caption. {@code isFinal} lines are committed to the log. */
        void onCaption(String text, boolean isFinal);

        /** A committed line's translation, which lands after its original. */
        void onTranslation(String original, String translated);
    }

    public enum State { IDLE, PREPARING, RUNNING, STOPPING }

    private final Context context;
    private final Sender sender;
    private final Handler conn;

    private final HandlerThread audioThread;
    private final Handler audio;

    private Listener listener;

    private volatile State state = State.IDLE;
    private TranscribeProtocol.Peer peer = TranscribeProtocol.Peer.TRANSLATE;
    private String srcLang = "en";
    private String dstLang = "en";

    /** Null when translation is off or its provider is not usable. */
    private CaptionTranslator translator;

    /**
     * The scene is always opened in TRANSCRIBE mode with {@code dst == src},
     * translating or not — because that is the only configuration observed to
     * render.
     *
     * Two experiments on hardware, both ending with the lens stuck on its
     * loading screen while perfectly good captions arrived:
     * <pre>
     *   transType 1, dst "cn"   (src "en")   -> never drew
     *   transType 2, dst "cn"   (src "en")   -> never drew
     *   transType 1, dst == src              -> draws
     * </pre>
     * So it is {@code dst != src} that the scene refuses, independently of the
     * mode. The vendor's native two-line layout needs
     * {@link TranscribeProtocol#TRANS_TYPE_SIMUL_INTERPRET} plus something else
     * we have not identified — and the reference capture is a transcription
     * session, so there is nothing to diff against. Finishing it needs a
     * btsnoop of the official app actually translating.
     *
     * Until then the translation is rendered INTO the caption line, which the
     * scene draws happily. See {@link #emit}.
     */
    private int transType = TranscribeProtocol.TRANS_TYPE_TRANSCRIBE;
    private static final int SUBTITLE_SET_TYPE = TranscribeProtocol.SUBTITLE_TRANSLATION_ONLY;

    /** The mode and language pair a glasses-initiated start asked for. */
    private static final class StartRequest {
        final int transType;
        final String src;
        final String dst;

        StartRequest(int transType, String src, String dst) {
            this.transType = transType;
            this.src = src;
            this.dst = dst;
        }
    }

    /** Written on the connection thread, read on the decode thread. */
    private volatile DeepgramStream deepgram;
    /** Decode thread only. */
    private OpusDecoderStream decoder;

    /** Session ids, in the vendor's 32-hex-character shape. */
    private String requestId = "";
    private String recognizeId = "";

    /**
     * Which caption command answers the audio we are receiving: proximal audio
     * (msgCmd 3) is captioned with 54, far-field (msgCmd 4) with 53. In practice
     * a single-speaker session only ever uses 4/53, but honouring whichever
     * arrives costs one field and keeps dialogue mode from captioning the wrong
     * side of the screen.
     */
    private int resultCmd = P2G_REMOTE_RESULT;

    /** Settled text for the utterance in progress; partials are appended to it. */
    private final StringBuilder utterance = new StringBuilder();
    private long utteranceStartMs = -1;

    private int audioPackets;
    private int decodeFailures;

    public TranscribeSession(Context context, Handler conn, Sender sender) {
        this.context = context.getApplicationContext();
        this.conn = conn;
        this.sender = sender;
        this.audioThread = new HandlerThread("myvu-transcribe-audio");
        this.audioThread.start();
        this.audio = new Handler(audioThread.getLooper());
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Opens the scene and brings a session up.
     *
     * @param peer    which glasses app to drive
     * @param srcLang the language tag the GLASSES understand ("en", "cn", "ja").
     *                Deepgram gets its own tag from settings; the two are
     *                deliberately separate because the glasses only accept the
     *                vendor's short list.
     */
    public void start(final TranscribeProtocol.Peer peer, final String srcLang) {
        startAt(peer, srcLang, null, true, 0);
    }

    /**
     * The glasses asked the phone to launch a captioning app's companion
     * service -- i.e. the user opened the scene ON THE GLASSES.
     *
     * Two very different meanings depending on where we already are:
     * <ul>
     *   <li>{@code PREPARING} -- this is the handshake for a start WE began, so
     *       it is the cue to re-issue open_app. Doing it here rather than on a
     *       timer means the second open_app lands right after the ack, exactly
     *       as the vendor app does it, however slow the link happens to be.</li>
     *   <li>{@code IDLE} -- the user started it on the glasses. Bring a session
     *       up for them, WITHOUT open_app: the scene is already opening, and
     *       asking the launcher to open it again restarts it underneath.</li>
     * </ul>
     *
     * This is the only usable "started on the glasses" signal we get. The
     * vendor's own trigger is msgCmd 1 (TRANS_CLICK_START), but the glasses
     * only send that to a phone-side package the interconnect service has
     * registered -- and we cannot register, because we are not that app.
     */
    /**
     * The glasses reported activity for a captioning app -- the user opened it
     * on the lens.
     *
     * Unlike the other two triggers this one DOES send open_app. A scene opened
     * from the glasses' own launcher sits on a loading screen waiting for the
     * phone side, and open_app is what carries the pre-session config it is
     * waiting for; the vendor's phone service sends it on startup for exactly
     * this reason. The msgCmd 1 path skips it because there the scene is
     * already talking to us, and reopening would restart it underneath.
     *
     * Ignored for a few seconds after the scene closes: the same telemetry
     * stream reports the close, and reacting to that would immediately reopen
     * an app the user just dismissed.
     */
    public void onGlassesAppOpened(final TranscribeProtocol.Peer opened) {
        conn.post(new Runnable() {
            @Override
            public void run() {
                if (state != State.IDLE) return;
                long sinceClose = System.currentTimeMillis() - lastSceneClosedAt;
                if (sinceClose < REOPEN_GUARD_MS) {
                    LogBus.trace("transcribe: ignoring app event " + sinceClose
                            + "ms after the scene closed");
                    return;
                }
                // Let the scene finish launching first.
                //
                // This telemetry fires the instant the app is tapped in the
                // dock, and we were answering it in the SAME millisecond -- so
                // open_app landed on a scene the launcher was still bringing up.
                // The phone-started path never hits this because nothing is open
                // when it fires, which is exactly why that one works and this one
                // spins. Short enough not to feel like a delay, long enough that
                // the scene exists: in the logs it announces itself ~220ms in.
                LogBus.log("transcribe: opened on the glasses (" + opened.glassesPkg
                        + ") -- letting the scene settle");
                conn.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (state != State.IDLE) return;
                        start(opened, Prefs.transcribeGlassesLang(context));
                    }
                }, SCENE_SETTLE_MS);
            }
        });
    }

    /**
     * Only long enough to cover the close telemetry that trails msgCmd 7.
     *
     * Was 5s, which swallowed real reopens: closing the scene and opening it
     * again -- exactly what anyone does while testing -- landed inside the
     * window and was logged as ignored. The close events arrive within a few
     * hundred ms of msgCmd 7, so this needs to be short.
     */
    private static final long REOPEN_GUARD_MS = 1200;

    /** Grace for the glasses-side scene to finish launching before we drive it. */
    private static final long SCENE_SETTLE_MS = 1200;
    private long lastSceneClosedAt;

    public void onGlassesLaunchRequest(final TranscribeProtocol.Peer requested) {
        conn.post(new Runnable() {
            @Override
            public void run() {
                if (state == State.PREPARING) {
                    // Beat the timer to it; whichever runs first wins and the
                    // other becomes a no-op.
                    conn.removeCallbacks(reopenScene);
                    reopenScene.run();
                    return;
                }
                if (state != State.IDLE) return;
                LogBus.log("transcribe: started on the glasses (" + requested.glassesPkg + ")");
                startAt(requested, Prefs.transcribeGlassesLang(context), null, false, 0);
            }
        });
    }

    /** How long to wait for the app relay before giving up on a start. */
    private static final int RELAY_WAIT_ATTEMPTS = 20;
    private static final long RELAY_WAIT_INTERVAL_MS = 500;

    private void startAt(final TranscribeProtocol.Peer peer, final String srcLang,
                         final StartRequest requested, final boolean openTheScene,
                         final int attempt) {
        conn.post(new Runnable() {
            @Override
            public void run() {
                if (state != State.IDLE) {
                    LogBus.warn("transcribe already " + state + " -- start ignored");
                    return;
                }
                String key = Prefs.deepgramApiKey(context);
                if (key.isEmpty()) {
                    status("Add a Deepgram API key in Settings first", false);
                    return;
                }
                // Wait rather than refuse: the relay normally comes up within a
                // few seconds of connecting, and the user pressing Start a
                // moment too early is the common case, not an error.
                if (!sender.isRelayReady()) {
                    if (attempt >= RELAY_WAIT_ATTEMPTS) {
                        LogBus.warn("transcribe: app relay never came up -- not starting"
                                + " (captioning cannot run on the BLE fallback)");
                        status("Waiting for the app relay timed out", false);
                        return;
                    }
                    if (attempt == 0) status("Waiting for the app relay...", true);
                    conn.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            startAt(peer, srcLang, requested, openTheScene, attempt + 1);
                        }
                    }, RELAY_WAIT_INTERVAL_MS);
                    return;
                }
                TranscribeSession.this.peer = peer;
                // requested != null means the GLASSES chose the mode and the
                // language pair; otherwise we impose the phone's.
                if (requested == null) {
                    TranscribeSession.this.srcLang = srcLang;
                    TranscribeSession.this.dstLang = srcLang;
                    TranscribeSession.this.transType =
                            TranscribeProtocol.TRANS_TYPE_TRANSCRIBE;
                }
                configureTranslator();
                state = State.PREPARING;
                requestId = randomId();
                recognizeId = randomId();
                utterance.setLength(0);
                utteranceStartMs = -1;
                audioPackets = 0;
                decodeFailures = 0;
                resultCmd = P2G_REMOTE_RESULT;

                LogBus.log("transcribe: " + (openTheScene ? "opening " : "joining ")
                        + peer.glassesPkg + " (" + srcLang + ")");
                status(openTheScene ? "Opening the caption scene..."
                                    : "Joining the scene on the glasses...", true);

                if (openTheScene) {
                    // 1. Bring the glasses-side scene up through the launcher.
                    openScene();
                    // 2. The NotStart block, promptly: the vendor sends it 30ms
                    // after open_app, and it MUST precede the second open_app.
                    // Waiting longer let the launch handshake -- which lands at
                    // ~165ms -- reorder the two.
                    conn.postDelayed(preSession, 40);
                    // 3. The second open_app. Normally pre-empted by the launch
                    // handshake arriving; this is the backstop for when the
                    // glasses do not ask (scene already resident, say).
                    conn.postDelayed(reopenScene, 450);
                } else {
                    // Started on the glasses: the scene is already coming up and
                    // has just handshaked, so skip both open_apps entirely.
                    // Asking the launcher to open it again restarts it underneath.
                    conn.post(preSession);
                    conn.postDelayed(beginSession, 250);
                }
            }
        });
    }

    private void openScene() {
        sendToLauncher(TranscribeProtocol.openScene(
                peer, srcLang, dstLang, SUBTITLE_SET_TYPE, transType));
    }

    private final Runnable preSession = new Runnable() {
        @Override
        public void run() {
            if (state != State.PREPARING) return;
            sendToPeer(TranscribeProtocol.transState(STATE_NOT_START, EXT_NONE));
            sendToPeer(TranscribeProtocol.subtitleSetType(SUBTITLE_SET_TYPE));
            // Twice, as the vendor does. Cheap, and the whole point of this
            // sequence is to differ from a known-good session in nothing.
            sendToPeer(TranscribeProtocol.transState(STATE_NOT_START, EXT_CONNECT_ON));
            sendToPeer(TranscribeProtocol.transState(STATE_NOT_START, EXT_CONNECT_ON));
        }
    };

    /**
     * The second {@code open_app}, and the reason the lens ever shows captions.
     *
     * The first one starts the glasses-side process, which then asks the PHONE
     * to launch its companion service ({@code type:11} on the interconnect
     * channel, answered by InboundRouter with {@code type:12}). Only after that
     * handshake does the scene's UI actually open -- so the vendor app re-sends
     * open_app 14ms after acking it. Sending it once, before the handshake,
     * leaves a scene that runs the recorder and streams audio but never draws:
     * captions arrive on msgCmd 53 and land on a loading screen forever.
     *
     * Normally driven by {@link #onGlassesLaunchRequest} the moment the ack
     * goes out, matching the vendor's 14ms gap; the 450ms scheduling in
     * {@code startAt} is only a backstop for when the glasses never ask.
     */
    private final Runnable reopenScene = new Runnable() {
        @Override
        public void run() {
            if (state != State.PREPARING) return;
            LogBus.log("transcribe: re-opening the scene after the launch handshake");
            openScene();
            // Whichever of the two paths got here first owns the schedule; the
            // other must not queue a second Preparing burst behind it.
            conn.removeCallbacks(beginSession);
            conn.postDelayed(beginSession, 350);
        }
    };

    private final Runnable beginSession = new Runnable() {
        @Override
        public void run() {
            if (state != State.PREPARING) return;
            sendToPeer(TranscribeProtocol.startTrans(srcLang, dstLang, transType));
            sendToPeer(TranscribeProtocol.recordState(REC_INIT));
            sendToPeer(TranscribeProtocol.transState(STATE_PREPARING, EXT_NONE));
            closeAssistant();
            sendToPeer(TranscribeProtocol.languageSet(srcLang, dstLang, transType));
            // The lens now shows "connecting"; it stays there until the
            // recogniser answers, which is exactly the window we need.
            sendToPeer(TranscribeProtocol.transState(STATE_PREPARING, EXT_SHOW_WAITING));
            status("Connecting to Deepgram...", true);
            openRecogniser();
        }
    };

    private void openRecogniser() {
        deepgram = new DeepgramStream(
                Prefs.deepgramApiKey(context),
                Prefs.deepgramModel(context),
                Prefs.deepgramLanguage(context),
                new DeepgramStream.Listener() {
                    @Override
                    public void onOpen() {
                        conn.post(new Runnable() {
                            @Override
                            public void run() {
                                onRecogniserReady();
                            }
                        });
                    }

                    @Override
                    public void onResult(final String transcript, final boolean isFinal,
                                         final boolean speechFinal, final long startMs,
                                         final long endMs) {
                        conn.post(new Runnable() {
                            @Override
                            public void run() {
                                onTranscript(transcript, isFinal, speechFinal, startMs, endMs);
                            }
                        });
                    }

                    @Override
                    public void onSpeechStarted() {
                        conn.post(new Runnable() {
                            @Override
                            public void run() {
                                onVoiceStarted();
                            }
                        });
                    }

                    @Override
                    public void onUtteranceEnd() {
                        conn.post(new Runnable() {
                            @Override
                            public void run() {
                                commitUtterance();
                            }
                        });
                    }

                    @Override
                    public void onClosed(final String reason) {
                        conn.post(new Runnable() {
                            @Override
                            public void run() {
                                if (state == State.IDLE || state == State.STOPPING) return;
                                LogBus.warn("Deepgram closed mid-session: " + reason);
                                failSession("Speech recognition dropped: " + reason);
                            }
                        });
                    }

                    @Override
                    public void onError(final String message) {
                        conn.post(new Runnable() {
                            @Override
                            public void run() {
                                if (state == State.IDLE || state == State.STOPPING) return;
                                failSession("Speech recognition failed: " + message);
                            }
                        });
                    }
                });
        deepgram.start();
    }

    /** The recogniser is live: tell the glasses to start the recorder. */
    private void onRecogniserReady() {
        if (state != State.PREPARING) return;
        state = State.RUNNING;
        startDecoder();
        // START first, then the state change -- the order in the vendor's
        // SUCCESSFUL run. Its one failed attempt had them the other way round.
        sendToPeer(TranscribeProtocol.recordState(REC_START));
        sendToPeer(TranscribeProtocol.transState(STATE_RUNNING, EXT_NONE));
        sendToPeer(TranscribeProtocol.transState(STATE_RUNNING, EXT_HINT_WAITING));
        sendToPeer(TranscribeProtocol.serverRunning());
        status("Listening", true);
        conn.postDelayed(noAudioCheck, NO_AUDIO_TIMEOUT_MS);
        // The vendor app starts this ladder from the moment the recorder does;
        // in the reference capture extCode 14 went out ~2s after recordState 2.
        armSilenceLadder();
    }

    // ------------------------------------------------------------- lens VAD

    /**
     * The glasses gate their subtitle view on a voice-activity state that the
     * PHONE is expected to drive -- the vendor app derives it from its
     * noise-reduction engine's VAD and reports it as ext codes on msgCmd 52.
     *
     * Without this the lens sits on "waiting for speech" forever: captions
     * arrive on msgCmd 53 and are never drawn, which looks exactly like a
     * session that failed to start. The ladder is the vendor's:
     *
     * <pre>
     *   voice detected      → 20  EXT_VOICE_NOT_MUTE
     *   silent for 2s       → 14  EXT_MUTE_TWO_SECS
     *   silent for 5s       → 19  EXT_MUTE_FIVE_SECS
     *   silent for 5min     → 15  EXT_MUTE_FIVE_MINS
     * </pre>
     *
     * Deepgram's {@code SpeechStarted} stands in for the engine's VAD, and the
     * silence steps are plain timers rearmed on every speech event -- which is
     * what the vendor's own mute counter does, just with a different clock.
     */
    private boolean voiceActive;

    private void onVoiceStarted() {
        if (state != State.RUNNING) return;
        cancelSilenceLadder();
        if (!voiceActive) {
            voiceActive = true;
            sendToPeer(TranscribeProtocol.transState(STATE_RUNNING, EXT_VOICE_NOT_MUTE));
        }
        armSilenceLadder();
    }

    private void armSilenceLadder() {
        conn.postDelayed(muteTwoSecs, 2000);
        conn.postDelayed(muteFiveSecs, 5000);
        conn.postDelayed(muteFiveMins, 300000);
    }

    private void cancelSilenceLadder() {
        conn.removeCallbacks(muteTwoSecs);
        conn.removeCallbacks(muteFiveSecs);
        conn.removeCallbacks(muteFiveMins);
    }

    /** Every pending timer, dropped together on any path out of a session. */
    private void stopTimers() {
        conn.removeCallbacks(noAudioCheck);
        // The bring-up steps too: a stop during Preparing would otherwise let a
        // queued open_app reopen the scene we are in the middle of closing.
        conn.removeCallbacks(preSession);
        conn.removeCallbacks(reopenScene);
        conn.removeCallbacks(beginSession);
        cancelSilenceLadder();
        voiceActive = false;
    }

    private final Runnable muteTwoSecs = new Runnable() {
        @Override
        public void run() {
            if (state != State.RUNNING) return;
            // Clearing this is what lets the next speech burst re-announce
            // itself with a fresh 20; the lens ignores a repeated 20.
            voiceActive = false;
            sendToPeer(TranscribeProtocol.transState(STATE_RUNNING, EXT_MUTE_TWO_SECS));
        }
    };

    private final Runnable muteFiveSecs = new Runnable() {
        @Override
        public void run() {
            if (state != State.RUNNING) return;
            sendToPeer(TranscribeProtocol.transState(STATE_RUNNING, EXT_MUTE_FIVE_SECS));
        }
    };

    private final Runnable muteFiveMins = new Runnable() {
        @Override
        public void run() {
            if (state != State.RUNNING) return;
            sendToPeer(TranscribeProtocol.transState(STATE_RUNNING, EXT_MUTE_FIVE_MINS));
        }
    };

    /**
     * A running session with no inbound audio almost always means the scene
     * never came up -- most often because the chosen glasses-side app is not
     * installed on this firmware, which the launcher reports by doing nothing.
     * Worth saying out loud, because everything else looks like it worked.
     */
    private static final long NO_AUDIO_TIMEOUT_MS = 6000;

    private final Runnable noAudioCheck = new Runnable() {
        @Override
        public void run() {
            if (state != State.RUNNING || audioPackets > 0) return;
            LogBus.warn("transcribe: no audio " + (NO_AUDIO_TIMEOUT_MS / 1000)
                    + "s after START -- is " + peer.glassesPkg + " installed on"
                    + " the glasses? Try the other app in Settings.");
            status("No audio from the glasses -- is the scene open?", true);
        }
    };

    /** Recogniser never came up, or died before we had a session. */
    private void failSession(String why) {
        LogBus.warn("transcribe: " + why);
        if (state == State.PREPARING) {
            sendToPeer(TranscribeProtocol.transState(
                    STATE_PREPARING, EXT_CONNECT_CHANNEL_ERROR));
        }
        stopInternal(why);
    }

    /** Ends the session and closes the scene's session state on the glasses. */
    public void stop() {
        conn.post(new Runnable() {
            @Override
            public void run() {
                if (state == State.IDLE) return;
                stopInternal("Stopped");
            }
        });
    }

    private void stopInternal(String status) {
        State was = state;
        state = State.STOPPING;
        stopTimers();

        if (was == State.RUNNING) {
            sendToPeer(TranscribeProtocol.recordState(REC_STOP));
        }
        sendToPeer(TranscribeProtocol.recordState(REC_RELEASE));
        sendToPeer(TranscribeProtocol.transState(STATE_END, EXT_NONE));
        // Tells the lens the phone kept the transcript, which dismisses the
        // "saving..." state rather than leaving it spinning.
        sendToPeer(TranscribeProtocol.message(TranscribeProtocol.P2G_SAVE_SUCCESS));

        if (deepgram != null) {
            deepgram.stop();
            deepgram = null;
        }
        stopTranslator();
        stopDecoder();
        LogBus.log("transcribe: session ended (" + audioPackets + " audio packets"
                + (decodeFailures > 0 ? ", " + decodeFailures + " decode failures" : "") + ")");
        state = State.IDLE;
        status(status, false);
    }

    /** Called when the link drops; tears down locally without touching the wire. */
    public void onConnectionLost() {
        conn.post(new Runnable() {
            @Override
            public void run() {
                if (state == State.IDLE) return;
                LogBus.warn("transcribe: link lost -- ending the session");
                state = State.STOPPING;
                stopTimers();
                if (deepgram != null) {
                    deepgram.stop();
                    deepgram = null;
                }
                stopTranslator();
                stopDecoder();
                state = State.IDLE;
                status("Disconnected", false);
            }
        });
    }

    // --------------------------------------------------------------- inbound

    /**
     * One decoded message from the glasses. Called on the connection thread.
     *
     * Audio is by far the most common: ~17 messages a second while a session
     * runs, each a 60ms Opus packet, so it is handled first and handed straight
     * to the decode thread.
     */
    public void onInbound(TranscribeProtocol.Inbound msg) {
        if (msg.isAudio()) {
            if (state != State.RUNNING || msg.audio.length == 0) return;
            audioPackets++;
            resultCmd = msg.msgCmd == TranscribeProtocol.G2P_PROXIMAL_AUDIO
                    ? P2G_PROXIMAL_RESULT : P2G_REMOTE_RESULT;
            final byte[] packet = msg.audio;
            audio.post(new Runnable() {
                @Override
                public void run() {
                    decodeAndSend(packet);
                }
            });
            return;
        }

        switch (msg.msgCmd) {
            case TranscribeProtocol.G2P_CLICK_START:
                // The glasses' own start button -- the user opened the scene on
                // the lens rather than from the phone. If we are already running
                // this is just the scene re-announcing itself, so ignore it.
                LogBus.log("transcribe: glasses asked to start (" + msg.data + ")");
                if (state == State.IDLE) {
                    // This is the message the mode actually travels in, so it
                    // ends any wait started by the scene opening. No open_app:
                    // the scene is up, in the mode the user picked, and
                    // reopening it would restart it in ours.
                    adoptGlassesRequest(msg.data);
                    startAt(peerFor(msg.srcPkg), srcLang,
                            new StartRequest(transType, srcLang, dstLang), false, 0);
                }
                break;
            case TranscribeProtocol.G2P_EXIT:
                LogBus.log("transcribe: the user closed the scene");
                lastSceneClosedAt = System.currentTimeMillis();
                sendToPeer(TranscribeProtocol.message(TranscribeProtocol.P2G_EXIT_ACK));
                if (state != State.IDLE) {
                    state = State.STOPPING;
                    stopTimers();
                    if (deepgram != null) {
                        deepgram.stop();
                        deepgram = null;
                    }
                    stopTranslator();
                    stopDecoder();
                    state = State.IDLE;
                    status("Closed on the glasses", false);
                }
                break;
            case TranscribeProtocol.G2P_SERVER_START_COMPLETE:
                LogBus.log("transcribe: the glasses-side scene is up");
                break;
            case TranscribeProtocol.G2P_SCREEN_STATE:
                LogBus.trace("transcribe: glasses screen state " + msg.data);
                break;
            default:
                // Deliberately on the visible log, not trace: an unrecognised
                // command from the scene is exactly the clue worth having when
                // the lens is doing something we did not expect.
                LogBus.log("transcribe: unhandled msgCmd " + msg.msgCmd
                        + " data=" + msg.data);
                break;
        }
    }

    /**
     * Decides whether this session translates, and into what.
     *
     * Two ways in. The glasses asking for {@code dst != src} means the user
     * picked a translation in the scene's own picker, and that wins outright --
     * the lens has already built its UI around it. Otherwise the phone's own
     * setting applies, translating into the configured target while leaving the
     * scene in the plain-captioning configuration that renders.
     */
    private void configureTranslator() {
        translator = null;
        boolean glassesAskedToTranslate = !dstLang.equals(srcLang);
        String target = glassesAskedToTranslate ? dstLang : Prefs.translateTarget(context);
        if (!glassesAskedToTranslate && !Prefs.translateEnabled(context)) return;

        if (!Languages.isSupported(target)) {
            LogBus.warn("transcribe: '" + target + "' is not a language the glasses"
                    + " accept -- captioning without translation");
            return;
        }
        CaptionTranslator t = new CaptionTranslator(context, target);
        if (!t.isConfigured()) {
            LogBus.warn("transcribe: translation is on but " + t.providerLabel()
                    + " is not configured -- captioning without it");
            t.shutdown();
            return;
        }
        translator = t;
        LogBus.log("transcribe: translating into " + Languages.nameOf(target)
                + " via " + t.providerLabel()
                + (glassesAskedToTranslate ? " (asked for by the glasses)" : ""));
    }

    /** The peer a message came from, falling back to whatever we are driving. */
    private TranscribeProtocol.Peer peerFor(String srcPkg) {
        TranscribeProtocol.Peer p = TranscribeProtocol.Peer.forPackage(srcPkg);
        return p != null ? p : peer;
    }

    /**
     * Takes the mode and language pair from a glasses-initiated StartTrans.
     *
     * The vendor's phone app adopts these wholesale rather than imposing its
     * own -- {@code InterConnectHelper.handleStartTrans()} reads transType, src
     * and dst straight off the message, persists the mode, and hands the same
     * triple to its channel handler. Which is the sane architecture: the user
     * chose them in the scene's own picker, so the scene has already built its
     * UI around them, and contradicting it is how you end up with a lens that
     * will not leave its loading screen.
     *
     * The 4 -> 3 fold is the vendor's too: telephone translation collapses onto
     * plain dialogue translation, since we are not in a call.
     */
    private void adoptGlassesRequest(String startTransJson) {
        srcLang = Prefs.transcribeGlassesLang(context);
        dstLang = srcLang;
        transType = TranscribeProtocol.TRANS_TYPE_TRANSCRIBE;
        if (startTransJson == null) return;
        try {
            org.json.JSONObject o = new org.json.JSONObject(startTransJson);
            String src = o.optString("src", "");
            String dst = o.optString("dst", "");
            int type = o.optInt("transType", TranscribeProtocol.TRANS_TYPE_TRANSCRIBE);
            if (type == 4) type = 3;
            if (!src.isEmpty()) srcLang = src;
            dstLang = dst.isEmpty() ? srcLang : dst;
            transType = type;
            LogBus.log("transcribe: the glasses asked for mode " + transType
                    + " " + srcLang + " -> " + dstLang);
        } catch (Exception e) {
            LogBus.warn("transcribe: unreadable StartTrans, using our own config: " + e);
        }
    }

    // ----------------------------------------------------------- audio path

    private void startDecoder() {
        audio.post(new Runnable() {
            @Override
            public void run() {
                stopDecoderNow();
                try {
                    // false: we stream the PCM out packet by packet, and a
                    // captioning session can run for an hour.
                    decoder = new OpusDecoderStream(false);
                    decoder.start();
                } catch (Exception e) {
                    LogBus.error("could not start the Opus decoder", e);
                    decoder = null;
                }
            }
        });
    }

    /**
     * Drops the translator and its thread.
     *
     * Cleared before shutdown so an in-flight request that completes during
     * teardown finds {@code translator != t} and discards its result rather
     * than sending a caption into a closed session.
     */
    private void stopTranslator() {
        CaptionTranslator t = translator;
        translator = null;
        if (t != null) t.shutdown();
    }

    private void stopDecoder() {
        audio.post(new Runnable() {
            @Override
            public void run() {
                stopDecoderNow();
            }
        });
    }

    private void stopDecoderNow() {
        if (decoder == null) return;
        decoder.stop();
        decoder = null;
    }

    /** Decode thread only. */
    private void decodeAndSend(byte[] opusPacket) {
        OpusDecoderStream d = decoder;
        DeepgramStream dg = deepgram;
        if (d == null || dg == null) return;
        try {
            byte[] pcm = d.feed(opusPacket);
            if (pcm.length == 0) return;
            dg.sendAudio(PcmConverter.toMono16k(pcm, d.sampleRate(), d.channels()));
        } catch (Exception e) {
            // One bad packet must not take the session down; the glasses will
            // send another in 60ms.
            if (decodeFailures++ == 0) LogBus.error("Opus decode failed", e);
        }
    }

    // -------------------------------------------------------------- captions

    /**
     * Turns Deepgram's segment stream into the caption shape the lens expects.
     *
     * The glasses render partials as a single growing line and replace it
     * wholesale, so a partial must never be SHORTER than the one before it
     * within the same utterance -- hence the accumulator: settled segments are
     * kept and the live interim is appended to them, rather than each segment
     * being sent on its own and visibly resetting the line.
     */
    private void onTranscript(String transcript, boolean isFinal, boolean speechFinal,
                              long startMs, long endMs) {
        if (state != State.RUNNING) return;
        String text = transcript.trim();
        if (text.isEmpty() && !speechFinal) return;
        if (utteranceStartMs < 0) utteranceStartMs = startMs;
        lastResultEndMs = endMs;
        // Belt and braces: if vad_events ever stops arriving, text itself still
        // proves someone spoke, so the lens is not left waiting.
        if (!text.isEmpty()) onVoiceStarted();

        // speechFinal implies isFinal in practice; treating it as merely interim
        // would leave the line uncommitted forever if that ever stopped holding.
        if (!isFinal && !speechFinal) {
            emit(join(utterance.toString(), text), RESULT_PARTIAL, endMs);
            return;
        }

        if (!text.isEmpty()) {
            if (utterance.length() > 0) utterance.append(' ');
            utterance.append(text);
        }
        if (speechFinal) {
            commitUtterance();
            return;
        }
        // Settled, but the speaker is still going: keep it as a partial so the
        // lens goes on building the same line.
        if (utterance.length() > 0) emit(utterance.toString(), RESULT_PARTIAL, endMs);
    }

    /** The audio timestamp of the newest result, for stamping the committed line. */
    private long lastResultEndMs;

    /**
     * Commits the accumulated line as final and starts a fresh one.
     *
     * Reached from {@code speech_final} OR from Deepgram's UtteranceEnd. The
     * second matters more than it looks: with background noise endpointing can
     * decline to fire for a long time, and the whole conversation then arrives
     * as one caption that grows until it is unreadable.
     */
    private void commitUtterance() {
        if (state != State.RUNNING || utterance.length() == 0) return;
        String line = utterance.toString();
        emit(line, RESULT_FINAL, lastResultEndMs);
        translateLine(line, Math.max(0, utteranceStartMs), lastResultEndMs);
        utterance.setLength(0);
        utteranceStartMs = -1;
    }

    /**
     * Replaces a committed caption with its translation.
     *
     * The scene draws ONE subtitle line, from {@code src.content}, so the
     * translation is sent as another final caption for the same utterance
     * rather than as a {@code dst} frame — the lens overwrites the line in
     * place, and the effect is the sentence settling from what was said into
     * what it means. Timestamps are the original's, so this reads as a revision
     * of that utterance and not a new one.
     *
     * Fire-and-forget: the original is already readable, so a slow or failed
     * translation just leaves it standing.
     */
    private void translateLine(final String line, final long startMs, final long endMs) {
        final CaptionTranslator t = translator;
        if (t == null) return;
        t.translate(line, new CaptionTranslator.Callback() {
            @Override
            public void onTranslated(final String original, final String translated) {
                conn.post(new Runnable() {
                    @Override
                    public void run() {
                        // The session can end while a request is in flight.
                        if (state != State.RUNNING || translator != t) return;
                        sendToPeer(TranscribeProtocol.asrResult(
                                resultCmd, requestId, recognizeId,
                                translated, RESULT_FINAL, startMs, endMs));
                        LogBus.log("translated: " + translated);
                        if (listener != null) listener.onTranslation(original, translated);
                    }
                });
            }
        });
    }

    private static String join(String settled, String interim) {
        if (settled.isEmpty()) return interim;
        if (interim.isEmpty()) return settled;
        return settled + " " + interim;
    }

    private void emit(String text, int type, long endMs) {
        sendToPeer(TranscribeProtocol.asrResult(resultCmd, requestId, recognizeId,
                text, type, Math.max(0, utteranceStartMs), endMs));
        if (listener != null) listener.onCaption(text, type == RESULT_FINAL);
        if (type == RESULT_FINAL) LogBus.log("caption: " + text);
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * Dismisses the AI assistant scene, which otherwise owns the lens.
     *
     * The caption scene comes up BEHIND the assistant and sits on its loading
     * screen forever: audio streams, captions arrive on msgCmd 53, and nothing
     * is ever drawn, because the assistant is what the display is showing. The
     * giveaway on the glasses is the assistant still prompting "You can say Hey
     * Aicy" while captioning is supposedly running.
     *
     * The vendor does this from
     * {@code InterConnectHelper.passTranslationState()}, guarded on exactly
     * {@code transState == 3 && extCode == -1}, and a capture shows the frame
     * going out 5ms after that state message -- so it is sent from the same
     * place here rather than at session start.
     */
    private void closeAssistant() {
        sender.send(AiProtocol.vrState(AiProtocol.VR_CLOSE),
                AiProtocol.PKG, AiProtocol.PKG);
    }

    private void sendToPeer(String json) {
        sender.send(json, peer.glassesPkg, peer.phonePkg);
    }

    private void sendToLauncher(String json) {
        sender.send(json, TranscribeProtocol.PKG_LAUNCHER, peer.phonePkg);
    }

    private void status(String text, boolean running) {
        if (listener != null) listener.onStatus(text, running);
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 32 lowercase hex characters, matching the vendor's ids byte for byte. */
    private static String randomId() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : raw) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                             .append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}

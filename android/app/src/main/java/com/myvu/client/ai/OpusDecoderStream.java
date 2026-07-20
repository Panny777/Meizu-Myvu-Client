package com.myvu.client.ai;

import android.media.MediaCodec;
import android.media.MediaFormat;

import com.myvu.client.core.LogBus;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * A stateful Opus decoder that consumes packets as they arrive.
 *
 * The glasses stream audio CONTINUOUSLY while the assistant page is open -- not
 * only while you are speaking -- so there is no gap in the packet stream to
 * detect. The official app handles this by decoding every packet and running a
 * VAD over the PCM (VadDetector, feeding 512-byte chunks with a 600ms pause
 * threshold). This class is the decode half of that: feed packets in, get PCM
 * chunks out, so the caller can measure energy in real time.
 *
 * Not thread-safe; drive it from one worker thread.
 */
public class OpusDecoderStream {

    private static final long DEQUEUE_TIMEOUT_US = 5000;

    private MediaCodec codec;
    /**
     * The rate the decoder ACTUALLY outputs, read from it rather than assumed.
     *
     * Opus always decodes at 48 kHz internally, so a decoder may hand back
     * 48 kHz PCM regardless of the rate declared in OpusHead. Labelling that as
     * 16 kHz in the WAV header plays it at a third speed -- which inflates the
     * apparent noise energy and makes speech recognition return confident
     * nonsense. Both symptoms we saw.
     */
    private int outputSampleRate = OpusStream.SAMPLE_RATE;
    private int outputChannels = OpusStream.CHANNELS;
    private long presentationUs;
    private final ByteArrayOutputStream all = new ByteArrayOutputStream();

    public void start() throws Exception {
        stop();
        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS, OpusStream.SAMPLE_RATE, OpusStream.CHANNELS);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(OpusStream.opusHead()));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(OpusStream.preSkipNs()));
        format.setByteBuffer("csd-2", ByteBuffer.wrap(OpusStream.preSkipNs()));

        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
        codec.configure(format, null, null, 0);
        codec.start();
        presentationUs = 0;
        outputSampleRate = OpusStream.SAMPLE_RATE;
        outputChannels = OpusStream.CHANNELS;
        all.reset();
    }

    /**
     * Decodes one packet. Returns whatever PCM became available, which may be
     * empty -- the codec buffers, so output does not map 1:1 to input.
     *
     * The packet is NEVER dropped. The previous version skipped it whenever an
     * input buffer was not instantly free, and at this arrival rate that lost
     * about half the audio -- garbled, truncated, and mis-transcribed. Now it
     * drains output to free buffers and keeps trying until the packet is queued.
     */
    public byte[] feed(byte[] packet) {
        if (codec == null) return new byte[0];
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean queued = false;
            for (int attempt = 0; attempt < 100 && !queued; attempt++) {
                int inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                if (inIndex >= 0) {
                    ByteBuffer in = codec.getInputBuffer(inIndex);
                    in.clear();
                    in.put(packet);
                    codec.queueInputBuffer(inIndex, 0, packet.length, presentationUs, 0);
                    presentationUs += 20000; // 20ms per packet at 16 kHz
                    queued = true;
                }
                // Draining both makes progress AND frees an input buffer.
                byte[] pcm = drain();
                out.write(pcm, 0, pcm.length);
            }
            if (!queued) LogBus.warn("Opus decoder never freed an input buffer -- packet lost");
            return out.toByteArray();
        } catch (Exception e) {
            LogBus.error("Opus decode failed", e);
            return new byte[0];
        }
    }

    /**
     * Flushes the last packets out of the decoder.
     *
     * MediaCodec has a few packets of latency, so without an explicit
     * end-of-stream drain the tail of every utterance was silently discarded.
     */
    public void finish() {
        if (codec == null) return;
        try {
            int inIndex = codec.dequeueInputBuffer(50000);
            if (inIndex >= 0) {
                codec.queueInputBuffer(inIndex, 0, 0, presentationUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            // Drain until the codec reports end-of-stream or gives up.
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int i = 0; i < 200; i++) {
                int outIndex = codec.dequeueOutputBuffer(info, 20000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
                if (outIndex < 0) {
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    continue;
                }
                if (info.size > 0) {
                    ByteBuffer buf = codec.getOutputBuffer(outIndex);
                    byte[] chunk = new byte[info.size];
                    buf.position(info.offset);
                    buf.get(chunk);
                    all.write(chunk, 0, chunk.length);
                }
                codec.releaseOutputBuffer(outIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        } catch (Exception e) {
            LogBus.error("Opus decoder flush failed", e);
        }
    }


    private byte[] drain() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int outIndex = codec.dequeueOutputBuffer(info, 0);
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat actual = codec.getOutputFormat();
                outputSampleRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                outputChannels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                LogBus.log("Opus decoder output: " + outputSampleRate + "Hz, "
                        + outputChannels + "ch"
                        + (outputSampleRate != OpusStream.SAMPLE_RATE
                           ? " (NOT the declared " + OpusStream.SAMPLE_RATE + "Hz)" : ""));
                continue;
            }
            if (outIndex < 0) break;
            if (info.size > 0) {
                ByteBuffer buf = codec.getOutputBuffer(outIndex);
                byte[] chunk = new byte[info.size];
                buf.position(info.offset);
                buf.get(chunk);
                out.write(chunk, 0, chunk.length);
                all.write(chunk, 0, chunk.length);
            }
            codec.releaseOutputBuffer(outIndex, false);
        }
        return out.toByteArray();
    }

    /** Everything decoded since {@link #start()}. */
    public byte[] allPcm() {
        return all.toByteArray();
    }

    /** The decoder's real output rate; use this for the WAV header, not a guess. */
    public int sampleRate() {
        return outputSampleRate;
    }

    public int channels() {
        return outputChannels;
    }

    public void stop() {
        if (codec == null) return;
        try {
            codec.stop();
        } catch (Exception ignored) {
            // Already stopped.
        }
        try {
            codec.release();
        } catch (Exception ignored) {
            // Already released.
        }
        codec = null;
    }

    /**
     * Mean amplitude of a 16-bit little-endian PCM chunk.
     *
     * A plain RMS-style energy measure, which is what the Python client used to
     * find the end of an utterance. Cruder than the native VAD the official app
     * runs, but it needs no model and no dependency.
     */
    public static double energy(byte[] pcm) {
        if (pcm.length < 2) return 0;
        long sum = 0;
        int samples = pcm.length / 2;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            sum += Math.abs(sample);
        }
        return (double) sum / samples;
    }
}

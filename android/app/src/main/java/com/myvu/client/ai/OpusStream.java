package com.myvu.client.ai;

import android.media.MediaCodec;
import android.media.MediaFormat;

import com.myvu.client.core.LogBus;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Decodes the glasses' Opus packets to 16-bit PCM using MediaCodec.
 *
 * The official app links a native Opus library; MediaCodec gets us the same
 * result with no dependency, since Android has shipped an "audio/opus" decoder
 * since API 21.
 *
 * The awkward part is the codec-specific data. MediaCodec's Opus decoder expects
 * the three buffers an Ogg-Opus container would supply, which a raw packet
 * stream does not carry, so they are synthesised here:
 *   csd-0  a 19-byte OpusHead identification header
 *   csd-1  pre-skip, as a 64-bit little-endian nanosecond value
 *   csd-2  seek pre-roll, likewise
 * Getting these wrong shows up as configure() throwing, or as silence.
 */
public final class OpusStream {
    private OpusStream() {}

    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNELS = 1;

    /** Opus is always decoded at 48 kHz internally; pre-skip is in those samples. */
    private static final int PRE_SKIP_SAMPLES = 3840;
    private static final long NS_PER_48K_SAMPLE = 1000000000L / 48000L;

    private static final long DEQUEUE_TIMEOUT_US = 10000;

    /**
     * Decodes a whole utterance to little-endian 16-bit mono PCM.
     *
     * Runs to completion rather than streaming: an utterance is a few seconds,
     * and a single pass keeps the codec lifecycle simple. Blocking -- call it
     * off the connection thread.
     */
    public static byte[] decode(List<byte[]> packets) throws Exception {
        if (packets.isEmpty()) return new byte[0];

        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, CHANNELS);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(longLe(PRE_SKIP_SAMPLES * NS_PER_48K_SAMPLE)));
        format.setByteBuffer("csd-2", ByteBuffer.wrap(longLe(PRE_SKIP_SAMPLES * NS_PER_48K_SAMPLE)));

        MediaCodec codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        try {
            codec.configure(format, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int index = 0;
            long presentationUs = 0;
            boolean inputDone = false;

            while (true) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIndex);
                        if (index < packets.size()) {
                            byte[] packet = packets.get(index++);
                            in.clear();
                            in.put(packet);
                            codec.queueInputBuffer(inIndex, 0, packet.length, presentationUs, 0);
                            // 20ms per packet at 16 kHz; only used for ordering.
                            presentationUs += 20000;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, 0, presentationUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer out = codec.getOutputBuffer(outIndex);
                        byte[] chunk = new byte[info.size];
                        out.position(info.offset);
                        out.get(chunk);
                        pcm.write(chunk);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    // Nothing left to drain.
                    break;
                }
            }
        } finally {
            try {
                codec.stop();
            } catch (Exception ignored) {
                // Already stopped or never started.
            }
            codec.release();
        }

        byte[] result = pcm.toByteArray();
        LogBus.log("decoded " + packets.size() + " Opus packets -> "
                + (result.length / 2) + " samples ("
                + (result.length / 2 * 1000 / SAMPLE_RATE) + "ms)");
        return result;
    }

    /**
     * The 19-byte OpusHead identification header, per RFC 7845 §5.1.
     * Version 1, mono, no channel mapping.
     */
    static byte[] opusHead() {
        ByteBuffer b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] { 'O', 'p', 'u', 's', 'H', 'e', 'a', 'd' });
        b.put((byte) 1);                       // version
        b.put((byte) CHANNELS);
        b.putShort((short) PRE_SKIP_SAMPLES);
        b.putInt(SAMPLE_RATE);                 // original input rate
        b.putShort((short) 0);                 // output gain
        b.put((byte) 0);                       // channel mapping family
        return b.array();
    }

    static byte[] longLe(long value) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    }

    /** Pre-skip expressed in nanoseconds, as csd-1/csd-2 require. */
    static byte[] preSkipNs() {
        return longLe(PRE_SKIP_SAMPLES * NS_PER_48K_SAMPLE);
    }

    public static byte[] toWav(byte[] pcm) {
        return toWav(pcm, SAMPLE_RATE, CHANNELS);
    }

    /**
     * Wraps raw PCM in a WAV header.
     *
     * The rate must be the decoder's ACTUAL output rate. Declaring 16 kHz for
     * 48 kHz audio stretches it to a third speed, which speech recognition
     * happily transcribes as something entirely different.
     */
    public static byte[] toWav(byte[] pcm, int sampleRate, int channels) {
        int dataLen = pcm.length;
        int byteRate = sampleRate * channels * 2;

        ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes());
        b.putInt(36 + dataLen);
        b.put("WAVE".getBytes());
        b.put("fmt ".getBytes());
        b.putInt(16);                       // PCM header size
        b.putShort((short) 1);              // format: PCM
        b.putShort((short) channels);
        b.putInt(sampleRate);
        b.putInt(byteRate);
        b.putShort((short) (channels * 2)); // block align
        b.putShort((short) 16);             // bits per sample
        b.put("data".getBytes());
        b.putInt(dataLen);
        b.put(pcm);
        return b.array();
    }
}

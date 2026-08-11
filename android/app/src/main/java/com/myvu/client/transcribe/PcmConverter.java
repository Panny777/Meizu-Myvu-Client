package com.myvu.client.transcribe;

/**
 * Normalises decoded PCM to the one format the recogniser is told to expect:
 * 16 kHz, mono, little-endian 16-bit.
 *
 * This exists because Opus always decodes at 48 kHz internally, so MediaCodec is
 * free to hand back 48 kHz frames regardless of the 16 kHz we declare in
 * OpusHead -- and it does, on some devices. Streaming 48 kHz audio to a socket
 * opened as {@code sample_rate=16000} does not fail; it transcribes chipmunk
 * speech into confident nonsense, which is much harder to notice.
 *
 * Linear interpolation is enough here: the source is band-limited speech that
 * was itself encoded from 16 kHz, so there is nothing above 8 kHz to alias.
 */
final class PcmConverter {
    private PcmConverter() {}

    static final int TARGET_RATE = 16000;

    /** Returns {@code pcm} untouched when it is already 16 kHz mono. */
    static byte[] toMono16k(byte[] pcm, int sampleRate, int channels) {
        if (pcm.length < 2) return pcm;
        if (sampleRate == TARGET_RATE && channels == 1) return pcm;

        int frames = pcm.length / 2 / Math.max(1, channels);
        if (frames == 0) return new byte[0];

        // Downmix first: interpolating per channel and then averaging costs more
        // and produces the same result for correlated mic channels.
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                int idx = (i * channels + c) * 2;
                sum += (short) ((pcm[idx] & 0xFF) | (pcm[idx + 1] << 8));
            }
            mono[i] = (short) (sum / channels);
        }
        if (sampleRate == TARGET_RATE) return toBytes(mono, mono.length);

        int outFrames = (int) ((long) frames * TARGET_RATE / sampleRate);
        short[] out = new short[outFrames];
        double step = (double) sampleRate / TARGET_RATE;
        for (int i = 0; i < outFrames; i++) {
            double pos = i * step;
            int j = (int) pos;
            double frac = pos - j;
            short a = mono[Math.min(j, frames - 1)];
            short b = mono[Math.min(j + 1, frames - 1)];
            out[i] = (short) Math.round(a + (b - a) * frac);
        }
        return toBytes(out, outFrames);
    }

    private static byte[] toBytes(short[] samples, int count) {
        byte[] out = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            out[i * 2] = (byte) (samples[i] & 0xFF);
            out[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        return out;
    }
}

package com.myvu.client.transport.ble;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Rebuilds one logical message from a control packet plus its data fragments.
 * Port of channel.Reassembler.
 */
public class BleReassembler {

    private int frameCount;
    private int pkgType = -1;
    /** The MIX_CTR first chunk, which precedes fragment 1. */
    private byte[] header = new byte[0];
    private final Map<Integer, byte[]> frames = new HashMap<>();
    private boolean active;

    public void reset() {
        frameCount = 0;
        pkgType = -1;
        header = new byte[0];
        frames.clear();
        active = false;
    }

    public void start(int frameCount, int pkgType, byte[] header) {
        reset();
        this.frameCount = frameCount;
        this.pkgType = pkgType;
        this.header = header != null ? header : new byte[0];
        this.active = true;
    }

    public void start(int frameCount, int pkgType) {
        start(frameCount, pkgType, null);
    }

    public int pkgType() {
        return pkgType;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /** Adds a fragment; returns the complete message once all have arrived. */
    public byte[] add(int seq, byte[] payload) {
        frames.put(seq, payload);
        if (frameCount > 0 && frames.size() >= frameCount) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(header, 0, header.length);
            // Fragments are 1-indexed and must be concatenated in order, not in
            // arrival order.
            for (int i = 1; i <= frameCount; i++) {
                byte[] f = frames.get(i);
                if (f != null) out.write(f, 0, f.length);
            }
            active = false;
            return out.toByteArray();
        }
        return null;
    }
}

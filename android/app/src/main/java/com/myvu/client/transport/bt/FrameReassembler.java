package com.myvu.client.transport.bt;

import com.myvu.client.protocol.Pb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Feed raw stream bytes in; get complete (post-magic, post-PREFIX) frames out. */
public class FrameReassembler {

    /** magic(4) + length(4). */
    private static final int HEADER = 8;

    /**
     * A frame body must carry at least the 2-byte PREFIX.
     */
    private static final int MIN_FRAME = RfcommFraming.PREFIX.length;

    /**
     * Largest frame body we will accept. Real traffic is small -- app actions,
     * nav frames and 240-byte audio packets -- so this is deliberately generous.
     * Anything bigger on the wire means a corrupt length field, not a real frame.
     */
    public static final int MAX_FRAME = 64 * 1024;

    private byte[] buf = new byte[0];

    public List<byte[]> feed(byte[] data) {
        buf = Pb.concat(buf, data);
        List<byte[]> out = new ArrayList<>();
        while (true) {
            int idx = indexOfMagic(buf);
            if (idx < 0) {
                // Keep only a possible partial magic straddling the read boundary.
                if (buf.length > RfcommFraming.MAGIC.length) {
                    buf = Arrays.copyOfRange(buf, buf.length - RfcommFraming.MAGIC.length, buf.length);
                }
                break;
            }
            if (idx > 0) buf = Arrays.copyOfRange(buf, idx, buf.length);
            if (buf.length < HEADER) break;

            int length = ByteBuffer.wrap(buf, 4, 4).order(ByteOrder.BIG_ENDIAN).getInt();

            // NEVER trust this length. It is attacker- or (far more likely)
            // corruption-controlled, and every unchecked use of it is a fault:
            //   negative / < 2  -> copyOfRange(from > to) throws, killing the rx thread
            //   near MAX_VALUE  -> HEADER + length overflows to negative, same throw
            //   huge but valid  -> the frame never completes, so buf grows on every
            //                      read until OutOfMemoryError (an Error, which the
            //                      rx loop's catch(Exception) would NOT contain)
            // Bounding it here makes `HEADER + length` overflow-safe and caps how
            // much we can ever retain. A bad length means we matched noise that
            // looked like magic, so resync past it rather than stalling forever.
            if (length < MIN_FRAME || length > MAX_FRAME) {
                buf = Arrays.copyOfRange(buf, RfcommFraming.MAGIC.length, buf.length);
                continue;
            }

            int total = HEADER + length; // safe: length is bounded above
            if (buf.length < total) break; // plausible, just incomplete -- wait

            byte[] frame = Arrays.copyOfRange(buf, HEADER, total);
            buf = Arrays.copyOfRange(buf, total, buf.length);
            out.add(Arrays.copyOfRange(frame, MIN_FRAME, frame.length)); // strip PREFIX
        }
        return out;
    }

    private static int indexOfMagic(byte[] data) {
        byte[] magic = RfcommFraming.MAGIC;
        if (data.length < magic.length) return -1;
        outer:
        for (int i = 0; i <= data.length - magic.length; i++) {
            for (int j = 0; j < magic.length; j++) {
                if (data[i + j] != magic[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}

package com.myvu.client.transport.bt;

import com.myvu.client.protocol.Pb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Feed raw stream bytes in; get complete (post-magic, post-PREFIX) frames out. */
public class FrameReassembler {
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
            if (buf.length < 8) break;
            int length = ByteBuffer.wrap(buf, 4, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            int total = 8 + length;
            if (buf.length < total) break;
            byte[] frame = Arrays.copyOfRange(buf, 8, total);
            buf = Arrays.copyOfRange(buf, total, buf.length);
            out.add(Arrays.copyOfRange(frame, 2, frame.length)); // strip the 2-byte PREFIX
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

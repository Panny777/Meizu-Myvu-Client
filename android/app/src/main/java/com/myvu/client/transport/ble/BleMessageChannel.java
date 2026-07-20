package com.myvu.client.transport.ble;

import android.os.Handler;

import com.myvu.client.core.LogBus;

import java.util.Arrays;

/**
 * Reliable message channel over one GATT characteristic. Port of
 * channel.MessageChannel (com.upuphone.starrynet.core.ble.channel.Channel).
 *
 * Observed on the wire:
 *  - INTERNAL/pairing channel: SinglePacket (type 2), pkgType 16, ACKed with a
 *    SingleACK.
 *  - EXTERNAL/app channel: SINGLE_NO_ACK (type 9) for one-frame messages,
 *    MIX_CTR (type 8) + data frames for larger ones, pkgType 0.
 *
 * THREADING: Python could write `await ack_queue.get()`. Java has no such
 * luxury here, so the ACK waits become explicit continuations armed before the
 * write and resolved (or timed out) by feed(). Everything runs on the
 * connection thread, so none of this state needs locking.
 */
public class BleMessageChannel {

    private static final long ACK_TIMEOUT_MS = 6000;
    /** DMTU floor, matching Python's fallback for the 23-byte default MTU. */
    public static final int MIN_DMTU = 18;

    public interface Writer {
        void write(byte[] packet);
    }

    public interface Receiver {
        void onMessage(int pkgType, byte[] payload);
    }

    /** Resolved with an ACK status code (see BlePackets.ACK_*). */
    public interface AckCallback {
        void onAck(int status);
    }

    private final String label;
    private final Writer writer;
    private final Handler conn;
    private final Receiver receiver;
    private final BleReassembler rx = new BleReassembler();

    private int dmtu = MIN_DMTU;
    /** A DMTU change is only applied between messages, never mid-fragmentation. */
    private int pendingDmtu = -1;

    private AckCallback singleAckWaiter;
    private AckCallback ctrAckWaiter;
    private Runnable singleAckTimeout;
    private Runnable ctrAckTimeout;

    public BleMessageChannel(String label, Writer writer, Handler conn, Receiver receiver) {
        this.label = label;
        this.writer = writer;
        this.conn = conn;
        this.receiver = receiver;
    }

    /**
     * Requests a new fragmentation size. Deferred while a multi-frame message is
     * in flight: switching chunk size mid-message would corrupt it.
     */
    public void setDmtu(int newDmtu) {
        int v = Math.max(MIN_DMTU, newDmtu);
        if (rx.isActive()) {
            pendingDmtu = v;
        } else {
            dmtu = v;
        }
    }

    public int dmtu() {
        return dmtu;
    }

    private void applyPendingDmtu() {
        if (pendingDmtu > 0 && !rx.isActive()) {
            dmtu = pendingDmtu;
            pendingDmtu = -1;
        }
    }

    // ---------------------------------------------------------------- send

    /**
     * Pairing-channel send that expects a SingleACK. Falls back to the
     * multi-frame CTR path when the payload does not fit one frame.
     */
    public void sendSingleAcked(byte[] payload, int pkgType, AckCallback cb) {
        if (payload.length > dmtu) {
            sendCtrAcked(payload, pkgType, cb);
            return;
        }
        armSingleAck(cb);
        writer.write(BlePackets.singlePacket(pkgType, payload));
    }

    /**
     * CTR (type 0) multi-frame send: CTR -> await ACK(READY) -> data frames ->
     * await ACK(SUCCESS). Mirrors Channel.mRecvACKHandler. Python expressed this
     * linearly; here it is a two-stage continuation.
     */
    public void sendCtrAcked(final byte[] payload, final int pkgType, final AckCallback cb) {
        final int frameCount = frameCountFor(payload.length);
        armCtrAck(new AckCallback() {
            @Override
            public void onAck(int status) {
                if (status != BlePackets.ACK_READY) {
                    cb.onAck(status);
                    return;
                }
                // Peer is ready: stream the fragments, then await SUCCESS.
                armCtrAck(cb);
                writeFragments(payload, 0, frameCount);
            }
        });
        writer.write(BlePackets.ctrPacket(frameCount, pkgType));
    }

    /** SINGLE_NO_ACK (type 9): fire and forget, one frame. */
    public void sendSingleNoAck(byte[] payload, int pkgType) {
        writer.write(BlePackets.singleNoAckPacket(pkgType, payload));
    }

    /**
     * FAST_CTR (type 6) + data frames back to back with no waiting. This is the
     * exact form the app uses for the first version-negotiation message
     * (pkgType 17 / STARRY_DATA_INIT).
     */
    public void sendFast(byte[] payload, int pkgType) {
        int frameCount = frameCountFor(payload.length);
        writer.write(BlePackets.fastCtrPacket(frameCount, pkgType));
        writeFragments(payload, 0, frameCount);
    }

    /** MIX_CTR (type 8): first chunk inline in the control packet, rest as fragments. */
    public void sendMix(byte[] payload, int pkgType) {
        int firstLen = Math.min(payload.length, Math.max(0, dmtu - 4));
        byte[] first = Arrays.copyOfRange(payload, 0, firstLen);
        byte[] rest = Arrays.copyOfRange(payload, firstLen, payload.length);
        int frameCount = rest.length > 0 ? frameCountFor(rest.length) : 0;

        writer.write(BlePackets.mixCtrPacket(frameCount, pkgType, first));
        writeFragments(rest, 0, frameCount);
    }

    /** Picks the smallest wire form that fits, matching channel.send(). */
    public void send(byte[] payload, int pkgType) {
        if (payload.length <= dmtu) {
            sendSingleNoAck(payload, pkgType);
        } else {
            sendMix(payload, pkgType);
        }
    }

    private void writeFragments(byte[] data, int startIndex, int frameCount) {
        for (int idx = startIndex; idx < frameCount; idx++) {
            int from = idx * dmtu;
            int to = Math.min(data.length, from + dmtu);
            if (from >= to) break;
            writer.write(BlePackets.dataPacket(idx + 1, Arrays.copyOfRange(data, from, to)));
        }
    }

    private int frameCountFor(int length) {
        return Math.max(1, (length + dmtu - 1) / dmtu);
    }

    // ------------------------------------------------------------- receive

    /** Handles one inbound GATT notification for this characteristic. */
    public void feed(byte[] raw) {
        BleParsedPacket p = BlePackets.parse(raw);

        if (p.isData()) {
            byte[] full = rx.add(p.sn, p.value);
            if (full != null) {
                int pkgType = rx.pkgType();
                applyPendingDmtu();
                deliver(pkgType, full);
            }
            return;
        }

        switch (p.type) {
            case BlePackets.TYPE_SINGLE_CMD:
                // Whole message in one packet; the peer expects an ACK back.
                writer.write(BlePackets.singleAckPacket(BlePackets.ACK_SUCCESS));
                deliver(p.pkgType(), p.value);
                break;

            case BlePackets.TYPE_SINGLE_CMD_NO_ACK:
                deliver(p.pkgType(), p.value);
                break;

            case BlePackets.TYPE_SINGLE_ACK:
                resolveSingleAck(p.ackStatus());
                break;

            case BlePackets.TYPE_CMD:
                // Inbound CTR: prepare to reassemble and tell the peer we're ready.
                rx.start(p.frameCount(), p.pkgType());
                writer.write(BlePackets.ackPacket(BlePackets.ACK_READY));
                break;

            case BlePackets.TYPE_FAST_CTR:
                rx.start(p.frameCount(), p.pkgType());
                break;

            case BlePackets.TYPE_MIX_CTR:
                rx.start(p.frameCount(), p.pkgType(), p.value);
                if (p.frameCount() == 0) {
                    // Everything fit in the control packet.
                    rx.setActive(false);
                    applyPendingDmtu();
                    deliver(p.pkgType(), p.value);
                }
                break;

            case BlePackets.TYPE_ACK:
                resolveCtrAck(p.ackStatus());
                break;

            default:
                LogBus.trace(label + " <- unhandled packet type " + p.type);
                break;
        }
    }

    private void deliver(int pkgType, byte[] payload) {
        if (receiver != null) receiver.onMessage(pkgType, payload);
    }

    // ---------------------------------------------------------- ACK waits

    private void armSingleAck(final AckCallback cb) {
        cancelSingleAck();
        singleAckWaiter = cb;
        singleAckTimeout = new Runnable() {
            @Override
            public void run() {
                AckCallback waiter = singleAckWaiter;
                singleAckWaiter = null;
                singleAckTimeout = null;
                if (waiter != null) {
                    LogBus.warn(label + ": single ACK timed out");
                    waiter.onAck(BlePackets.ACK_TIMEOUT);
                }
            }
        };
        conn.postDelayed(singleAckTimeout, ACK_TIMEOUT_MS);
    }

    private void resolveSingleAck(int status) {
        AckCallback cb = singleAckWaiter;
        cancelSingleAck();
        if (cb != null) cb.onAck(status);
    }

    private void cancelSingleAck() {
        if (singleAckTimeout != null) conn.removeCallbacks(singleAckTimeout);
        singleAckTimeout = null;
        singleAckWaiter = null;
    }

    private void armCtrAck(final AckCallback cb) {
        cancelCtrAck();
        ctrAckWaiter = cb;
        ctrAckTimeout = new Runnable() {
            @Override
            public void run() {
                AckCallback waiter = ctrAckWaiter;
                ctrAckWaiter = null;
                ctrAckTimeout = null;
                if (waiter != null) {
                    LogBus.warn(label + ": CTR ACK timed out");
                    waiter.onAck(BlePackets.ACK_TIMEOUT);
                }
            }
        };
        conn.postDelayed(ctrAckTimeout, ACK_TIMEOUT_MS);
    }

    private void resolveCtrAck(int status) {
        AckCallback cb = ctrAckWaiter;
        cancelCtrAck();
        if (cb != null) cb.onAck(status);
    }

    private void cancelCtrAck() {
        if (ctrAckTimeout != null) conn.removeCallbacks(ctrAckTimeout);
        ctrAckTimeout = null;
        ctrAckWaiter = null;
    }

    public void shutdown() {
        cancelSingleAck();
        cancelCtrAck();
        rx.reset();
    }
}

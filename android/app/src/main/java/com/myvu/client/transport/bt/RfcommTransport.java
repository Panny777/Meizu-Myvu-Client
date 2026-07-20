package com.myvu.client.transport.bt;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;

import com.myvu.client.core.LogBus;
import com.myvu.client.transport.Transport;
import com.myvu.client.transport.TransportListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Classic-BT/RFCOMM transport.
 *
 * BluetoothSocket is strictly blocking, so this owns two threads: rfcomm-rx
 * reads and de-frames, rfcomm-tx drains a queue and writes. Neither ever runs
 * protocol logic -- inbound frames are posted to the connection thread, so all
 * protocol state stays single-threaded and lock-free.
 */
public class RfcommTransport implements Transport {

    private final BluetoothDevice device;
    private final UUID serviceUuid;
    private final TransportListener listener;
    private final Handler connHandler;

    private volatile BluetoothSocket socket;
    private volatile boolean connected;
    /** Suppresses the IOException that close() deliberately provokes in rx. */
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final AtomicBoolean disconnectReported = new AtomicBoolean(false);

    private final LinkedBlockingQueue<byte[]> txQueue = new LinkedBlockingQueue<>();
    private static final byte[] TX_POISON = new byte[0];

    private Thread rxThread;
    private Thread txThread;

    /**
     * Connect via SDP by service UUID -- what the official app's SPPConnection
     * does, and the only form that works here. Connecting to a fixed channel
     * number was removed: it answers the handshake but never carries app
     * traffic, so keeping it around only invited its reuse.
     */
    public RfcommTransport(BluetoothDevice device, UUID serviceUuid,
                           TransportListener listener, Handler connHandler) {
        this.device = device;
        this.serviceUuid = serviceUuid;
        this.listener = listener;
        this.connHandler = connHandler;
    }

    @Override
    public String name() {
        return "RFCOMM";
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void connect() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    openSocket();
                } catch (Exception e) {
                    reportDisconnected(e);
                }
            }
        }, "rfcomm-connect");
        t.setDaemon(true);
        t.start();
    }

    private void openSocket() throws Exception {
        // The fallback has to wrap connect(), not socket creation: creating a
        // socket almost never throws -- it is connect() that fails when the
        // link cannot be authenticated -- so wrapping creation alone meant the
        // insecure path could never actually run.
        BluetoothSocket s = connectWithFallback();
        this.socket = s;

        connected = true;
        LogBus.log("RFCOMM connected (uuid=" + serviceUuid + ")");

        startRx(s);
        startTx(s);

        connHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onConnected(RfcommTransport.this);
            }
        });
    }

    /**
     * Secure first, then insecure. The glasses publish an SDP record for the
     * per-session UUID; if the secure variant cannot get an authenticated link
     * we retry unauthenticated rather than failing the whole connection.
     */
    private BluetoothSocket connectWithFallback() throws IOException {
        BluetoothSocket secure = device.createRfcommSocketToServiceRecord(serviceUuid);
        try {
            // connect() blocks; discovery must be off or it will be slow/flaky.
            secure.connect();
            return secure;
        } catch (IOException e) {
            closeQuietly(secure);
            if (closing.get()) throw e;

            // Do NOT retry insecure when the local stack is wedged. Seen in the
            // Bluetooth logs as:
            //   RFCOMM_CreateConnection: already at opened state 1, MCB_state=4
            // The multiplexer for this device is stuck believing the DLCI is
            // open, so a second attempt cannot succeed -- it just allocates
            // another port against the same stuck MCB and deepens the leak.
            // Only a Bluetooth restart clears it.
            if (looksLikeWedgedStack(e)) {
                throw new IOException("the phone's Bluetooth stack has a stale RFCOMM "
                        + "connection for this device (MCB stuck open). Toggle Bluetooth "
                        + "off and on to clear it.", e);
            }
            LogBus.warn("secure RFCOMM connect failed (" + e.getMessage()
                    + ") -- retrying insecure");
        }

        BluetoothSocket insecure =
                device.createInsecureRfcommSocketToServiceRecord(serviceUuid);
        try {
            insecure.connect();
            return insecure;
        } catch (IOException e) {
            closeQuietly(insecure);
            throw e;
        }
    }

    /**
     * Distinguishes "the peer refused us" from "our own stack is broken".
     *
     * Android surfaces both as the same generic IOException, so the message is
     * all we have to go on. Being wrong here only costs one extra attempt.
     */
    private static boolean looksLikeWedgedStack(IOException e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("read failed, socket might closed");
    }

    private static void closeQuietly(BluetoothSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // Already dead; nothing useful to report.
        }
    }

    private void startRx(final BluetoothSocket s) {
        rxThread = new Thread(new Runnable() {
            @Override
            public void run() {
                FrameReassembler reassembler = new FrameReassembler();
                byte[] buf = new byte[4096];
                try {
                    InputStream in = s.getInputStream();
                    while (!closing.get()) {
                        int n = in.read(buf);
                        if (n < 0) throw new IOException("stream closed by peer");
                        if (n == 0) continue;

                        List<byte[]> frames = reassembler.feed(Arrays.copyOfRange(buf, 0, n));
                        for (final byte[] frame : frames) {
                            connHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    listener.onPayload(RfcommTransport.this, frame);
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    if (!closing.get()) reportDisconnected(e);
                }
                // An orderly local close still has to tear the link down.
                if (closing.get()) reportDisconnected(null);
            }
        }, "rfcomm-rx");
        rxThread.setDaemon(true);
        rxThread.start();
    }

    private void startTx(final BluetoothSocket s) {
        txThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    OutputStream out = s.getOutputStream();
                    while (true) {
                        byte[] payload = txQueue.take();
                        if (payload == TX_POISON) return;
                        out.write(RfcommFraming.encodeFrame(payload));
                        out.flush();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    if (!closing.get()) reportDisconnected(e);
                }
            }
        }, "rfcomm-tx");
        txThread.setDaemon(true);
        txThread.start();
    }

    @Override
    public void send(byte[] payload) {
        if (!connected) {
            LogBus.warn("send on a closed RFCOMM link -- dropping " + payload.length + "B");
            return;
        }
        txQueue.offer(payload);
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) return;
        connected = false;
        txQueue.offer(TX_POISON);
        // Closing the socket from this thread is what unblocks the rx read().
        BluetoothSocket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Already torn down; nothing useful to report.
            }
        }
    }

    private void reportDisconnected(final Throwable cause) {
        if (!disconnectReported.compareAndSet(false, true)) return;
        connected = false;
        connHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onDisconnected(RfcommTransport.this, cause);
            }
        });
    }
}

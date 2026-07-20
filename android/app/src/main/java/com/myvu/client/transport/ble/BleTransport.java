package com.myvu.client.transport.ble;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import com.myvu.client.core.LogBus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BLE GATT link to the glasses. Port of the connect/subscribe half of
 * myvu_client/myvu/client.py.
 *
 * This is the transport that must come up FIRST: the glasses' classic-BT radio
 * does not accept a page until BLE has brought them up, and the per-session
 * RFCOMM relay UUID is only learned here (CMD_SPP_SERVER_UUID_SYNC).
 *
 * THREADING: BluetoothGattCallback fires on a binder thread, so every callback
 * body does nothing but hand off -- GATT bookkeeping to the myvu-gatt thread
 * (via GattQueue), protocol payloads to the connection thread.
 */
public class BleTransport {

    /** ATT default; anything smaller than this is not usable. */
    private static final int DEFAULT_MTU = 23;
    private static final int PREFERRED_MTU = 517;
    /** Several stacks race a fresh bond if discovery starts immediately. */
    private static final long DISCOVER_DELAY_MS = 600;
    /** The glasses drop an unwanted central within about a second. */
    private static final long LIVENESS_CHECK_MS = 1500;
    /** Android's catch-all GATT connect failure; usually transient. */
    private static final int GATT_ERROR_133 = 133;
    private static final int MAX_CONNECT_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1000;

    public interface Listener {
        void onReady(BleTransport transport);
        /** A complete message from the link/pairing characteristic. */
        void onInternalMessage(int pkgType, byte[] payload);
        /** A complete message from the application characteristic. */
        void onExternalMessage(int pkgType, byte[] payload);
        void onDisconnected(String reason);
    }

    private final Context context;
    private final BluetoothDevice device;
    private final Listener listener;
    private final Handler conn;

    private final HandlerThread gattThread;
    private final Handler gattHandler;
    private final GattQueue queue;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic internalChar;
    private BluetoothGattCharacteristic externalChar;
    private BluetoothGattCharacteristic urgentChar;

    private BleMessageChannel internalChannel;
    private BleMessageChannel externalChannel;
    private BleHeartbeat heartbeat;

    private volatile boolean connected;
    private volatile boolean ready;
    private boolean disconnectReported;
    private int mtu = DEFAULT_MTU;
    /** Characteristics still awaiting their CCCD write. */
    private int pendingSubscriptions;
    private int connectAttempts;

    public BleTransport(Context context, BluetoothDevice device, Handler conn, Listener listener) {
        this.context = context;
        this.device = device;
        this.conn = conn;
        this.listener = listener;
        this.gattThread = new HandlerThread("myvu-gatt");
        this.gattThread.start();
        this.gattHandler = new Handler(gattThread.getLooper());
        this.queue = new GattQueue(gattHandler);
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isReady() {
        return ready;
    }

    public BleMessageChannel internal() {
        return internalChannel;
    }

    public BleMessageChannel external() {
        return externalChannel;
    }

    public boolean hasUrgentCharacteristic() {
        return urgentChar != null;
    }

    // ------------------------------------------------------------ connect

    public void connect() {
        LogBus.log("BLE connecting to " + device.getAddress() + "...");
        openGatt();
    }

    private void openGatt() {
        // TRANSPORT_LE is mandatory here. These glasses are dual-mode and
        // BR/EDR-bonded, so TRANSPORT_AUTO can route GATT over classic and
        // everything downstream then fails in ways that look like protocol bugs.
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) {
            fail("connectGatt returned null");
            return;
        }
        queue.attach(gatt);
    }

    /**
     * Status 133 is Android's catch-all connect failure and is frequently
     * transient -- it shows up routinely after a previous GATT client was not
     * closed cleanly (e.g. the app was force-stopped). Closing properly and
     * retrying clears it, so treat it as retryable rather than fatal.
     */
    private boolean retryTransientFailure(int status) {
        if (status != GATT_ERROR_133 || connectAttempts >= MAX_CONNECT_ATTEMPTS) return false;
        connectAttempts++;
        LogBus.warn("BLE connect failed with GATT_ERROR (133) -- retrying ("
                + connectAttempts + "/" + MAX_CONNECT_ATTEMPTS + ")");
        queue.clear();
        if (gatt != null) {
            gatt.close(); // releasing the client is what actually clears 133
            gatt = null;
        }
        gattHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                openGatt();
            }
        }, RETRY_DELAY_MS);
        return true;
    }

    public void close() {
        if (heartbeat != null) {
            heartbeat.stop();
            heartbeat = null;
        }
        if (internalChannel != null) internalChannel.shutdown();
        if (externalChannel != null) externalChannel.shutdown();
        connected = false;
        ready = false;
        queue.clear();
        gattHandler.post(new Runnable() {
            @Override
            public void run() {
                if (gatt != null) {
                    gatt.disconnect();
                    gatt.close();
                    gatt = null;
                }
                gattThread.quitSafely();
            }
        });
    }

    // ----------------------------------------------------------- callback

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, final int status, final int newState) {
            gattHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        connected = true;
                        LogBus.log("BLE connected -- requesting MTU " + PREFERRED_MTU);
                        queue.enqueue(GattOp.requestMtu(PREFERRED_MTU));
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        connected = false;
                        ready = false;
                        // Only give up once a transient failure has been retried.
                        if (retryTransientFailure(status)) return;
                        fail(describeDisconnect(status));
                    }
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, final int newMtu, final int status) {
            gattHandler.post(new Runnable() {
                @Override
                public void run() {
                    boolean first = (mtu == DEFAULT_MTU);
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        mtu = newMtu;
                        LogBus.log("BLE MTU = " + mtu + " (DMTU " + dmtu() + ")");
                        applyDmtu();
                    } else {
                        LogBus.warn("MTU request failed (status=" + status
                                + "); staying at " + mtu);
                    }
                    queue.complete(BluetoothGatt.GATT_SUCCESS);
                    // The glasses can renegotiate MTU unsolicited later, so only
                    // the first exchange advances the connect sequence.
                    if (first) {
                        gattHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                queue.enqueue(GattOp.discoverServices());
                            }
                        }, DISCOVER_DELAY_MS);
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, final int status) {
            gattHandler.post(new Runnable() {
                @Override
                public void run() {
                    queue.complete(status);
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail("service discovery failed (status=" + status + ")");
                        return;
                    }
                    selectChannels();
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d,
                                      final int status) {
            gattHandler.post(new Runnable() {
                @Override
                public void run() {
                    queue.complete(status);
                    if (--pendingSubscriptions == 0) onSubscriptionsComplete();
                }
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c,
                                          final int status) {
            gattHandler.post(new Runnable() {
                @Override
                public void run() {
                    queue.complete(status);
                }
            });
        }

        // API 33+ delivers notifications here...
        @Override
        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic c,
                                            byte[] value) {
            dispatchNotification(c.getUuid(), value);
        }

        // ...and below 33 here, reading from the characteristic itself.
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                dispatchNotification(c.getUuid(), c.getValue());
            }
        }
    };

    private void dispatchNotification(final UUID uuid, final byte[] value) {
        if (value == null || value.length == 0) return;
        final byte[] copy = value.clone();
        // Protocol work belongs on the connection thread, never the binder thread.
        conn.post(new Runnable() {
            @Override
            public void run() {
                if (internalChar != null && uuid.equals(internalChar.getUuid())) {
                    internalChannel.feed(copy);
                } else if (externalChar != null && uuid.equals(externalChar.getUuid())) {
                    externalChannel.feed(copy);
                } else {
                    LogBus.trace("notification on unexpected char " + uuid);
                }
            }
        });
    }

    // ------------------------------------------------------ channel setup

    /** Port of client._select_channels: first triple whose pair is present wins. */
    private void selectChannels() {
        List<String> present = new ArrayList<>();
        for (BluetoothGattService s : gatt.getServices()) {
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                present.add(c.getUuid().toString().toLowerCase());
            }
        }

        for (UUID[] set : Uuids.CHANNEL_SETS) {
            BluetoothGattCharacteristic in = findCharacteristic(set[0]);
            BluetoothGattCharacteristic ex = findCharacteristic(set[1]);
            if (in == null || ex == null) continue;

            internalChar = in;
            externalChar = ex;
            urgentChar = findCharacteristic(set[2]); // optional
            LogBus.log("BLE channels: internal=" + GattOp.shortUuid(set[0])
                    + " external=" + GattOp.shortUuid(set[1])
                    + (urgentChar != null ? " urgent=" + GattOp.shortUuid(set[2])
                                          : " urgent=ABSENT"));
            subscribe();
            return;
        }

        fail("no known StarryNet channel pair on this device; characteristics=" + present);
    }

    private BluetoothGattCharacteristic findCharacteristic(UUID uuid) {
        for (BluetoothGattService s : gatt.getServices()) {
            BluetoothGattCharacteristic c = s.getCharacteristic(uuid);
            if (c != null) return c;
        }
        return null;
    }

    private void subscribe() {
        internalChannel = new BleMessageChannel("internal", writerFor(internalChar), conn,
                new BleMessageChannel.Receiver() {
                    @Override
                    public void onMessage(int pkgType, byte[] payload) {
                        listener.onInternalMessage(pkgType, payload);
                    }
                });
        externalChannel = new BleMessageChannel("external", writerFor(externalChar), conn,
                new BleMessageChannel.Receiver() {
                    @Override
                    public void onMessage(int pkgType, byte[] payload) {
                        listener.onExternalMessage(pkgType, payload);
                    }
                });
        applyDmtu();

        pendingSubscriptions = 2;
        queue.enqueue(GattOp.enableNotifications(internalChar));
        queue.enqueue(GattOp.enableNotifications(externalChar));
    }

    private void onSubscriptionsComplete() {
        LogBus.log("BLE subscribed to both channels");
        if (urgentChar != null) {
            heartbeat = new BleHeartbeat(queue, urgentChar, gattHandler);
            heartbeat.start();
        } else {
            LogBus.warn("urgent characteristic (0x2022) absent -- no heartbeat, so "
                    + "the glasses' watchdog may drop the link");
        }

        // Catch a peer-initiated drop: the glasses reject an unknown central
        // about a second in when their own phone still holds them.
        conn.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!connected) return;
                ready = true;
                listener.onReady(BleTransport.this);
            }
        }, LIVENESS_CHECK_MS);
    }

    private BleMessageChannel.Writer writerFor(final BluetoothGattCharacteristic ch) {
        return new BleMessageChannel.Writer() {
            @Override
            public void write(byte[] packet) {
                queue.enqueue(GattOp.write(ch, packet));
            }
        };
    }

    private int dmtu() {
        return Math.max(BleMessageChannel.MIN_DMTU, mtu - 5);
    }

    private void applyDmtu() {
        int d = dmtu();
        if (internalChannel != null) internalChannel.setDmtu(d);
        if (externalChannel != null) externalChannel.setDmtu(d);
    }

    // ------------------------------------------------------------ failure

    private void fail(final String reason) {
        if (disconnectReported) return;
        disconnectReported = true;
        connected = false;
        ready = false;
        conn.post(new Runnable() {
            @Override
            public void run() {
                listener.onDisconnected(reason);
            }
        });
    }

    private static String describeDisconnect(int status) {
        switch (status) {
            case 8:
                return "link supervision timeout (glasses went out of range or powered off)";
            case 19:
                return "glasses closed the BLE link. They most likely only accept their "
                        + "currently-bonded phone -- disconnect the glasses in the MYVU app "
                        + "and retry";
            case 22:
                return "link terminated by the local host";
            case 133:
                return "GATT_ERROR (133) -- generic connect failure; usually means the "
                        + "device is not advertising or is already connected elsewhere";
            case BluetoothGatt.GATT_SUCCESS:
                return "disconnected";
            default:
                return "disconnected (status=" + status + ")";
        }
    }
}

package com.myvu.client.transport.ble;

import android.bluetooth.BluetoothGattCharacteristic;
import android.os.Handler;

import com.myvu.client.core.LogBus;

/**
 * Keep-alive for the BLE link.
 *
 * Bytes and interval are taken verbatim from the app's
 * BleRequestDispatcher.HEART_BEAT_DATA / HEART_BEAT_INTERVAL. Without this the
 * glasses' watchdog drops the link after a few seconds of quiet, which presents
 * as a mysterious "disconnected by peer" partway through the handshake.
 *
 * The writes are queued through GattQueue like any other operation, so they
 * interleave safely with data rather than racing it.
 */
public class BleHeartbeat {

    private static final byte[] HEARTBEAT_DATA = { 0, 0, 9, 16, 0 };
    private static final long INTERVAL_MS = 3000;

    private final GattQueue queue;
    private final BluetoothGattCharacteristic urgentChar;
    private final Handler gatt;

    private boolean running;
    private int count;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            queue.enqueue(GattOp.write(urgentChar, HEARTBEAT_DATA));
            count++;
            if (count == 1) {
                LogBus.log("BLE heartbeat active (every " + (INTERVAL_MS / 1000) + "s)");
            }
            gatt.postDelayed(this, INTERVAL_MS);
        }
    };

    public BleHeartbeat(GattQueue queue, BluetoothGattCharacteristic urgentChar, Handler gatt) {
        this.queue = queue;
        this.urgentChar = urgentChar;
        this.gatt = gatt;
    }

    public void start() {
        if (running) return;
        running = true;
        gatt.postDelayed(tick, INTERVAL_MS);
    }

    public void stop() {
        running = false;
        gatt.removeCallbacks(tick);
    }
}

package com.myvu.client.transport.ble;

import android.bluetooth.BluetoothGatt;
import android.os.Handler;

import com.myvu.client.core.LogBus;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Serializes GATT operations.
 *
 * Android permits exactly ONE outstanding GATT operation per connection; firing
 * a second before the first completes silently drops it. The Python client
 * never had to think about this because bleak hid both the serialization and
 * the CCCD write, so this class has no counterpart in myvu_client -- it is pure
 * Android tax, and it is the single most common cause of a BLE port that
 * "connects fine and receives nothing".
 *
 * Everything here runs on the myvu-gatt thread. BluetoothGattCallback fires on
 * a binder thread and must do nothing but post into this queue.
 */
public class GattQueue {

    /** How long to wait for a completion callback before giving up on an op. */
    private static final long WATCHDOG_MS = 5000;
    /** writeCharacteristic returns false when the stack's buffer is full. */
    private static final int MAX_RETRIES = 5;
    private static final long RETRY_DELAY_MS = 30;

    private final Handler gatt;
    private final Deque<GattOp> queue = new ArrayDeque<>();

    private BluetoothGatt connection;
    private GattOp pending;
    private int retries;
    private boolean closed;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (pending == null) return;
            LogBus.warn("GATT op timed out: " + pending.describe());
            pending = null;
            retries = 0;
            dispatchNext();
        }
    };

    public GattQueue(Handler gattHandler) {
        this.gatt = gattHandler;
    }

    public void attach(BluetoothGatt connection) {
        this.connection = connection;
        this.closed = false;
    }

    /** Queues an operation. Safe to call from any thread. */
    public void enqueue(final GattOp op) {
        gatt.post(new Runnable() {
            @Override
            public void run() {
                if (closed) return;
                queue.addLast(op);
                if (pending == null) dispatchNext();
            }
        });
    }

    /** Called from the GATT callbacks (already posted onto the gatt thread). */
    public void complete(int status) {
        if (pending == null) return;
        gatt.removeCallbacks(watchdog);
        if (status != BluetoothGatt.GATT_SUCCESS) {
            LogBus.warn("GATT op failed (status=" + status + "): " + pending.describe());
        } else {
            LogBus.trace("gatt ok: " + pending.describe());
        }
        pending = null;
        retries = 0;
        dispatchNext();
    }

    private void dispatchNext() {
        if (closed || pending != null) return;
        GattOp op = queue.pollFirst();
        if (op == null) return;

        BluetoothGatt g = connection;
        if (g == null) {
            LogBus.warn("dropping " + op.describe() + ": no GATT connection");
            return;
        }

        pending = op;
        boolean accepted = op.execute(g);
        if (!accepted) {
            // Typically a transient full buffer. Re-run the same op shortly.
            pending = null;
            if (retries < MAX_RETRIES) {
                retries++;
                queue.addFirst(op);
                gatt.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        dispatchNext();
                    }
                }, RETRY_DELAY_MS);
            } else {
                LogBus.warn("giving up on " + op.describe() + " after "
                        + MAX_RETRIES + " retries");
                retries = 0;
                dispatchNext();
            }
            return;
        }
        gatt.postDelayed(watchdog, WATCHDOG_MS);
    }

    public void clear() {
        gatt.post(new Runnable() {
            @Override
            public void run() {
                closed = true;
                gatt.removeCallbacks(watchdog);
                queue.clear();
                pending = null;
                connection = null;
            }
        });
    }
}

package com.myvu.client.transport.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Handler;
import android.os.ParcelUuid;

import com.myvu.client.core.LogBus;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Discovers MYVU glasses over a BLE scan -- the "auto search" path, so the user
 * doesn't have to know the MAC. Mirrors the official app's scan, which filters
 * on the advertised service UUID 0x0bd3 (BleConstants.SERVICE_UUID).
 *
 * We scan UNfiltered and match in the callback (by advertised service UUID OR a
 * MYVU device name) rather than handing a ScanFilter to the stack: some firmware
 * puts the service UUID only in the scan-response, which a hardware filter can
 * miss, and matching the name as a fallback makes discovery robust across
 * generations. A short scan on a foreground screen is cheap.
 *
 * Requires BLUETOOTH_SCAN. getName() additionally needs BLUETOOTH_CONNECT; both
 * are requested by the UI before a connection is attempted.
 */
public class GlassesScanner {

    /** Advertised service UUID the official app filters on (0x0bd3). */
    private static final UUID ADV_SERVICE = uuid16(0x0bd3);
    /** GATT primary service (0x0bd1) -- some builds advertise this instead. */
    private static final UUID GATT_SERVICE = uuid16(0x0bd1);

    private static final long DEFAULT_TIMEOUT_MS = 12000;

    public interface Callback {
        void onFound(BluetoothDevice device, String name);
        void onTimeout();
        void onError(String reason);
    }

    private final BluetoothAdapter adapter;
    private final Handler handler;

    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private Callback callback;
    private boolean active;

    public GlassesScanner(BluetoothAdapter adapter, Handler handler) {
        this.adapter = adapter;
        this.handler = handler;
    }

    public void start(Callback cb) {
        start(cb, DEFAULT_TIMEOUT_MS);
    }

    public void start(final Callback cb, long timeoutMs) {
        this.callback = cb;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            cb.onError("BLE scanning is unavailable (adapter off?)");
            return;
        }
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                if (!active) return;
                if (matches(result)) {
                    BluetoothDevice d = result.getDevice();
                    String name = nameOf(result);
                    LogBus.log("found glasses: " + (name != null ? name : "(no name)")
                            + " " + d.getAddress());
                    stop();
                    callback.onFound(d, name);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                if (!active) return;
                stop();
                callback.onError("BLE scan failed (code " + errorCode + ")");
            }
        };

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        active = true;
        try {
            // Null filters = report everything; we match in the callback.
            scanner.startScan(null, settings, scanCallback);
            LogBus.log("scanning for glasses...");
        } catch (SecurityException e) {
            active = false;
            cb.onError("missing the Bluetooth scan permission");
            return;
        }
        handler.postDelayed(timeoutRunnable, timeoutMs);
    }

    private final Runnable timeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!active) return;
            Callback cb = callback;
            stop();
            if (cb != null) cb.onTimeout();
        }
    };

    public void stop() {
        handler.removeCallbacks(timeoutRunnable);
        if (active && scanner != null && scanCallback != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException | IllegalStateException ignored) {
            }
        }
        active = false;
    }

    // --------------------------------------------------------------- matching

    private boolean matches(ScanResult r) {
        String name = nameOf(r);
        if (name != null) {
            String u = name.toUpperCase(Locale.US);
            // Bonded/advertised name is "MYVU DCxx"; the model reads "Star Air".
            if (u.contains("MYVU")) return true;
        }
        ScanRecord rec = r.getScanRecord();
        if (rec != null) {
            List<ParcelUuid> uuids = rec.getServiceUuids();
            if (uuids != null) {
                for (ParcelUuid pu : uuids) {
                    UUID id = pu.getUuid();
                    if (ADV_SERVICE.equals(id) || GATT_SERVICE.equals(id)) return true;
                }
            }
        }
        return false;
    }

    private static String nameOf(ScanResult r) {
        ScanRecord rec = r.getScanRecord();
        String adv = rec != null ? rec.getDeviceName() : null;
        if (adv != null && !adv.isEmpty()) return adv;
        try {
            return r.getDevice().getName();
        } catch (SecurityException e) {
            return null;
        }
    }

    private static UUID uuid16(int i) {
        return UUID.fromString(String.format(Locale.US,
                "0000%04x-0000-1000-8000-00805f9b34fb", i));
    }
}

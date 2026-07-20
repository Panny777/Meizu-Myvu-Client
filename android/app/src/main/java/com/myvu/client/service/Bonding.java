package com.myvu.client.service;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.myvu.client.core.LogBus;

import java.lang.reflect.Method;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * BR/EDR bonding, mirroring
 * com.upuphone.starrynet.core.bredr.BrEdrDeviceManager from the decompiled app.
 *
 * CURRENTLY UNUSED, deliberately. Measured on hardware: calling createBond()
 * before any BLE contact never completes -- 13s with no ACL, no SSP request and
 * sdp_attempts=0, i.e. a plain page timeout, because the glasses' classic radio
 * does not page-scan until BLE has woken them. Once ConnectionManager brings BLE
 * up and runs the ECDH bond, the BR/EDR bond appears on its own (confirmed:
 * the device shows as bonded [DUAL] afterwards), so RFCOMM needs no explicit
 * bonding step.
 *
 * Kept because it encodes the exact hidden-API call the official app uses, in
 * case a device or firmware turns up that does require an explicit bond.
 */
public class Bonding {

    private static final long BOND_TIMEOUT_SECONDS = 30;

    private final Context context;
    /** Size-1 queue: the first bond outcome wins, later ones are ignored. */
    private volatile BlockingQueue<Boolean> pending;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                LogBus.log("pairing request (variant=" + variant + ") -- auto-confirming, "
                        + "matching the real phone's silent-confirm behaviour");
                try {
                    if (device != null) device.setPairingConfirmation(true);
                } catch (SecurityException e) {
                    LogBus.error("setPairingConfirmation failed", e);
                }
                abortBroadcast();
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                LogBus.log("bond state -> " + describeBondState(state));
                BlockingQueue<Boolean> q = pending;
                if (q != null) {
                    if (state == BluetoothDevice.BOND_BONDED) q.offer(Boolean.TRUE);
                    if (state == BluetoothDevice.BOND_NONE) q.offer(Boolean.FALSE);
                }
            }
        }
    };

    public Bonding(Context context) {
        this.context = context;
    }

    public void register() {
        IntentFilter pairing = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        // Beat the system dialog to the broadcast so we can auto-confirm.
        pairing.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        context.registerReceiver(receiver, pairing);
        context.registerReceiver(receiver,
                new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
    }

    public void unregister() {
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // Not registered; nothing to do.
        }
    }

    /**
     * Ensures a BR/EDR bond, blocking up to 30s. Must not be called from the
     * connection thread -- run it on a worker.
     */
    public boolean ensureBonded(BluetoothDevice device) throws InterruptedException {
        if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
            LogBus.log("already bonded");
            return true;
        }

        BlockingQueue<Boolean> q = new ArrayBlockingQueue<>(1);
        pending = q;
        try {
            boolean started = invokeCreateBrEdrBond(device);
            LogBus.log("createBond returned " + started + " -- waiting for bond state");
            Boolean ok = q.poll(BOND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (ok == null) {
                LogBus.warn("bonding timed out after " + BOND_TIMEOUT_SECONDS + "s");
                return false;
            }
            if (!ok) {
                LogBus.warn("bonding failed (BOND_NONE)");
                return false;
            }
            LogBus.log("bonded");
            return true;
        } finally {
            pending = null;
        }
    }

    /**
     * Calls the hidden createBond(int transport) with TRANSPORT_BREDR, exactly
     * as BrEdrDeviceManager.invokeCreateBrEdrBond() does.
     *
     * The public no-arg createBond() lets Android choose a transport, which is
     * ambiguous for a dual-mode device we may have just seen over BLE -- that
     * ambiguity is why earlier plain-createBond attempts paged the wrong way
     * and timed out. Falls back to the public method if reflection is blocked.
     */
    private static boolean invokeCreateBrEdrBond(BluetoothDevice device) {
        try {
            Method m = device.getClass().getMethod("createBond", int.class);
            Object result = m.invoke(device, BluetoothDevice.TRANSPORT_BREDR);
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception e) {
            LogBus.warn("createBond(TRANSPORT_BREDR) unavailable (" + e.getClass().getSimpleName()
                    + ") -- falling back to public createBond()");
            return device.createBond();
        }
    }

    public static String describeBondState(int state) {
        switch (state) {
            case BluetoothDevice.BOND_NONE: return "BOND_NONE";
            case BluetoothDevice.BOND_BONDING: return "BOND_BONDING";
            case BluetoothDevice.BOND_BONDED: return "BOND_BONDED";
            default: return "unknown(" + state + ")";
        }
    }
}

package com.myvu.client.service;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothA2dp;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

import com.myvu.client.core.LogBus;
import com.myvu.client.protocol.link.LinkCommands;

import java.lang.reflect.Method;

/**
 * Brings up the standard classic-audio profiles (HFP/HSP + A2DP) to the glasses.
 *
 * WHY THIS EXISTS: the glasses light their "phone connected" indicator only when
 * a real phone is attached over the ordinary headset profiles -- the same signal
 * any Bluetooth headset uses. Our BLE bond + custom RFCOMM app-relay is enough to
 * *send* features (notifications, teleprompter and nav all render), but it is
 * invisible to the glasses' own connection state. The official app connects these
 * profiles explicitly (see the decompiled BrEdrHfpManager / BrEdrA2dpManager); the
 * Python client leaned on Windows holding HFP after the glasses were paired as an
 * audio device. Neither happens for us, so we do it here.
 *
 * PERMISSIONS: connect() / setConnectionPolicy() / setActiveDevice() on the
 * profile proxies are @SystemApi, gated by BLUETOOTH_PRIVILEGED, and hidden from
 * the SDK -- a normal app cannot link against them. So every mutating call is
 * reflective and best-effort: a SecurityException or a blocked-reflection
 * NoSuchMethodError just means this build won't let us force the link, and we
 * fall back to (a) the connection-policy nudge, which the system honours on the
 * next ACL, and (b) reporting the true status over the app layer, which we fully
 * control. The official app is written to tolerate being unprivileged the same
 * way (its checkHfpConnectionPolicy returns true when it lacks the permission).
 *
 * getConnectionState(), by contrast, is public SDK, so status reads are direct.
 */
public class AudioProfiles {

    /** Hidden BluetoothProfile.CONNECTION_POLICY_ALLOWED. */
    private static final int CONNECTION_POLICY_ALLOWED = 100;

    public interface Listener {
        /** Fired when the HFP or A2DP connection state to the glasses changes. */
        void onStatusChanged(int btStatus);
    }

    private final Context context;
    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final String targetMac;

    private BluetoothHeadset headset;
    private BluetoothA2dp a2dp;
    private boolean receiverRegistered;

    public AudioProfiles(Context context, BluetoothAdapter adapter, String targetMac,
                         Listener listener) {
        this.context = context.getApplicationContext();
        this.adapter = adapter;
        this.targetMac = targetMac;
        this.listener = listener;
        bindProxies();
        registerReceiver();
    }

    private void bindProxies() {
        adapter.getProfileProxy(context, proxyListener, BluetoothProfile.HEADSET);
        adapter.getProfileProxy(context, proxyListener, BluetoothProfile.A2DP);
    }

    private final BluetoothProfile.ServiceListener proxyListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.HEADSET) {
                        headset = (BluetoothHeadset) proxy;
                        LogBus.trace("HFP proxy bound");
                    } else if (profile == BluetoothProfile.A2DP) {
                        a2dp = (BluetoothA2dp) proxy;
                        LogBus.trace("A2DP proxy bound");
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.HEADSET) headset = null;
                    else if (profile == BluetoothProfile.A2DP) a2dp = null;
                }
            };

    /**
     * Best-effort: allow, then connect, the audio profiles to the glasses. Safe
     * to call more than once (a no-op once the profiles are already connected).
     * Call it once the classic radio is definitely awake -- i.e. after the RFCOMM
     * relay is up -- since the glasses' BR/EDR side ignores pages until BLE has
     * woken them.
     */
    public void connect(BluetoothDevice device) {
        if (device == null) return;
        LogBus.log("connecting classic audio profiles (HFP + A2DP) to show the "
                + "glasses as phone-connected");
        tryConnect("HFP", headset, device);
        tryConnect("A2DP", a2dp, device);
    }

    private void tryConnect(String tag, BluetoothProfile proxy, BluetoothDevice device) {
        if (proxy == null) {
            LogBus.trace(tag + " proxy not ready yet");
            return;
        }
        if (getState(proxy, device) == BluetoothProfile.STATE_CONNECTED) {
            LogBus.trace(tag + " already connected");
            return;
        }
        boolean policy = invoke2(proxy, "setConnectionPolicy", device,
                CONNECTION_POLICY_ALLOWED);
        boolean connect = invoke1(proxy, "connect", device);
        LogBus.log(tag + " connect requested (policy=" + policy + ", connect=" + connect
                + (connect ? "" : " -- likely BLUETOOTH_PRIVILEGED-gated on this build;"
                + " falling back to policy + app-layer status") + ")");
    }

    /**
     * The truthful btStatus to advertise to the glasses right now: the highest
     * classic-audio profile that is actually connected, else ACL (some link is up
     * whenever we call this), never a value we cannot back up.
     */
    public int currentBtStatus() {
        BluetoothDevice device = adapter.getRemoteDevice(targetMac);
        if (a2dp != null && getState(a2dp, device) == BluetoothProfile.STATE_CONNECTED) {
            return LinkCommands.BTSTATUS_CONNECTED_A2DP;
        }
        if (headset != null && getState(headset, device) == BluetoothProfile.STATE_CONNECTED) {
            return LinkCommands.BTSTATUS_CONNECTED_HFP;
        }
        return LinkCommands.BTSTATUS_CONNECTED_ACL;
    }

    private static int getState(BluetoothProfile proxy, BluetoothDevice device) {
        try {
            return proxy.getConnectionState(device);
        } catch (Throwable t) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
    }

    // ------------------------------------------------- connection-state events

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        ContextCompat.registerReceiver(context, stateReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null || !d.getAddress().equalsIgnoreCase(targetMac)) return;
            int st = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            String which = BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED
                    .equals(intent.getAction()) ? "A2DP" : "HFP";
            LogBus.trace(which + " state -> " + st);
            if (st == BluetoothProfile.STATE_CONNECTED
                    || st == BluetoothProfile.STATE_DISCONNECTED) {
                if (listener != null) listener.onStatusChanged(currentBtStatus());
            }
        }
    };

    public void close() {
        if (receiverRegistered) {
            try { context.unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        if (headset != null) {
            adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset);
            headset = null;
        }
        if (a2dp != null) {
            adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
            a2dp = null;
        }
    }

    // ------------------------------------------------------------- reflection

    private static boolean invoke1(Object proxy, String name, BluetoothDevice device) {
        try {
            Method m = proxy.getClass().getMethod(name, BluetoothDevice.class);
            Object r = m.invoke(proxy, device);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable t) {
            LogBus.trace(name + " reflection unavailable: " + t.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean invoke2(Object proxy, String name, BluetoothDevice device, int arg) {
        try {
            Method m = proxy.getClass().getMethod(name, BluetoothDevice.class, int.class);
            Object r = m.invoke(proxy, device, arg);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable t) {
            LogBus.trace(name + " reflection unavailable: " + t.getClass().getSimpleName());
            return false;
        }
    }
}

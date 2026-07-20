package com.myvu.client.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import com.myvu.client.core.LogBus;
import com.myvu.client.ui.ConnectActivity;

/**
 * Holds the glasses connection for as long as the user wants it up.
 *
 * A foreground service is not optional here: the link must survive the app
 * being backgrounded and the screen locking, and the glasses drop the app relay
 * (then re-request it) whenever the phone side goes quiet.
 */
public class MyvuService extends Service implements ConnectionManager.Listener {

    public static final String ACTION_START = "com.myvu.client.START";
    public static final String ACTION_STOP = "com.myvu.client.STOP";
    public static final String EXTRA_MAC = "mac";

    private static final String CHANNEL_ID = "myvu_connection";
    private static final int NOTIFICATION_ID = 1;

    private ConnectionManager connection;

    /**
     * The live connection, for components that run in their OWN service process
     * slot and so cannot bind to us -- notably MirrorNotificationListener, which
     * Android instantiates independently.
     *
     * Null whenever the service is not running, which callers must treat as
     * "not connected" rather than an error.
     */
    private static volatile ConnectionManager active;

    public static ConnectionManager activeConnection() {
        return active;
    }

    public class LocalBinder extends Binder {
        public MyvuService getService() {
            return MyvuService.this;
        }
    }

    private final IBinder binder = new LocalBinder();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        connection = new ConnectionManager(this, this);
        active = connection;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            connection.stop();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        // startForeground must happen promptly after startForegroundService,
        // and on API 34+ the type is mandatory and must match the manifest.
        startInForeground("Connecting...");

        if (ACTION_START.equals(action)) {
            String mac = intent.getStringExtra(EXTRA_MAC);
            if (mac != null && !mac.isEmpty()) {
                connection.start(mac);
            } else {
                // No MAC supplied -> discover the glasses over BLE (auto search).
                connection.startAutoSearch();
            }
        }
        // REDELIVER rather than STICKY: a sticky restart hands us a null intent,
        // so we would come back as a foreground service with no MAC and no way
        // to reconnect. Redelivering the original START keeps restarts useful,
        // and ConnectionManager.start() ignores a duplicate.
        return START_REDELIVER_INTENT;
    }

    private void startInForeground(String status) {
        Notification n = buildNotification(status);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Glasses connection", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the link to the MYVU glasses alive");
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String status) {
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, ConnectActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MYVU glasses")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onStateChanged(ConnectionState state) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(describe(state)));
    }

    private static String describe(ConnectionState state) {
        switch (state) {
            case BONDING: return "Bonding...";
            case CONNECTING: return "Connecting over BLE...";
            case PAIRING: return "Exchanging keys...";
            case SESSION: return "Starting session...";
            case READY: return "Connected";
            case FAILED: return "Disconnected";
            default: return "Idle";
        }
    }

    /** The bound API the UI drives. */
    public ConnectionManager connection() {
        return connection;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        LogBus.log("service stopping");
        active = null;
        connection.shutdown();
        super.onDestroy();
    }
}

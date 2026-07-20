package com.myvu.client.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.myvu.client.R;
import com.myvu.client.service.ConnectionState;
import com.myvu.client.service.MyvuService;

/**
 * A full-screen remote trackpad for the glasses. Binds the running service and
 * turns finger gestures on {@link TrackpadView} into launcher navigation events.
 * Sends a "start" when it opens and a "stop" when it closes, mirroring the
 * official app's phone-pad mode.
 */
public class TrackpadActivity extends AppCompatActivity implements TrackpadView.Listener {

    private MyvuService service;
    private boolean bound;
    private boolean padStarted;

    private TrackpadView trackpad;
    private TextView status;
    private Vibrator vibrator;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((MyvuService.LocalBinder) binder).getService();
            bound = true;
            updateStatus();
            startPad();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            updateStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trackpad);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        trackpad = findViewById(R.id.trackpad);
        status = findViewById(R.id.txtTrackpadStatus);
        trackpad.setListener(this);
        findViewById(R.id.btnTrackpadBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, MyvuService.class), serviceConnection, 0);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPad();
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
    }

    private boolean connected() {
        return bound && service != null
                && service.connection().state() == ConnectionState.READY;
    }

    private void startPad() {
        if (padStarted || !connected()) return;
        service.connection().trackpadStart();
        padStarted = true;
    }

    private void stopPad() {
        if (!padStarted || !connected()) {
            padStarted = false;
            return;
        }
        service.connection().trackpadStop();
        padStarted = false;
    }

    private void updateStatus() {
        if (status == null) return;
        status.setText(connected()
                ? "Connected · tap, swipe, long-press to control the glasses"
                : "Glasses not connected");
    }

    private boolean guard() {
        if (connected()) return true;
        updateStatus();
        return false;
    }

    private void tick() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        // Haptic is cosmetic: never let a missing permission or an OEM vibrator
        // quirk take down the trackpad.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(18);
            }
        } catch (Exception ignored) {
        }
    }

    // -------------------------------------------------- TrackpadView.Listener

    @Override
    public void onTap() {
        if (!guard()) return;
        tick();
        service.connection().trackpadClick();
    }

    @Override
    public void onDoubleTap() {
        if (!guard()) return;
        tick();
        service.connection().trackpadDoubleClick();
    }

    @Override
    public void onLongPress() {
        if (!guard()) return;
        tick();
        service.connection().trackpadLongPress();
    }

    @Override
    public void onSwipe(int direction, float startX, float startY,
                        float endX, float endY, float speedX, float speedY) {
        if (!guard()) return;
        service.connection().trackpadSwipe(direction, startX, startY, endX, endY, speedX, speedY);
    }
}

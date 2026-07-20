package com.myvu.client.ui;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.myvu.client.R;
import com.myvu.client.app.feature.NavCommands;
import com.myvu.client.core.LogBus;
import com.myvu.client.core.Prefs;
import com.myvu.client.service.ConnectionManager;
import com.myvu.client.service.ConnectionState;
import com.myvu.client.service.MirrorNotificationListener;
import com.myvu.client.service.MyvuService;

import java.util.ArrayList;
import java.util.List;

/**
 * The single-screen client UI: a card per feature over a live connection status
 * header, driven by the bound {@link MyvuService}. Every control maps to a REPL
 * command in the Python reference client.
 */
public class ConnectActivity extends AppCompatActivity implements LogBus.Listener {

    private static final int REQ_PERMISSIONS = 1;

    private TextInputEditText txtMac, txtNotifyTitle, txtNotifyBody, txtAsk,
            txtTici, txtDest;
    private TextView txtStatus, txtGlasses;
    private View statusDot;
    private View progress;
    private RecyclerView rvLog;
    private LogAdapter logAdapter;

    // Pairing overlay (mirrors the official app's "found your glasses" flow).
    private View pairingOverlay, ring1, ring2, ring3, pairButtons;
    private android.widget.ImageView imgGlasses, imgCheck;
    private TextView pairTitle, pairSubtitle;
    private com.google.android.material.button.MaterialButton btnPairDone;
    private final List<android.animation.Animator> ringAnimators = new ArrayList<>();
    private boolean pairing;

    private MyvuService service;
    private boolean bound;
    private int lastDotColor;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((MyvuService.LocalBinder) binder).getService();
            bound = true;
            render(service.connection().state());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connect);

        txtMac = findViewById(R.id.txtMac);
        txtNotifyTitle = findViewById(R.id.txtNotifyTitle);
        txtNotifyBody = findViewById(R.id.txtNotifyBody);
        txtAsk = findViewById(R.id.txtAsk);
        txtTici = findViewById(R.id.txtTici);
        txtDest = findViewById(R.id.txtDest);
        txtStatus = findViewById(R.id.txtStatus);
        txtGlasses = findViewById(R.id.txtGlasses);
        statusDot = findViewById(R.id.statusDot);
        progress = findViewById(R.id.progress);
        rvLog = findViewById(R.id.rvLog);
        logAdapter = new LogAdapter(this);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        // New lines are appended at the end; keeping the anchor at the bottom is
        // handled explicitly in onLine so the view stays put when scrolled up.
        rvLog.setLayoutManager(lm);
        rvLog.setAdapter(logAdapter);
        lastDotColor = ContextCompat.getColor(this, R.color.state_idle);

        pairingOverlay = findViewById(R.id.pairingOverlay);
        ring1 = findViewById(R.id.ring1);
        ring2 = findViewById(R.id.ring2);
        ring3 = findViewById(R.id.ring3);
        imgGlasses = findViewById(R.id.imgGlasses);
        imgCheck = findViewById(R.id.imgCheck);
        pairTitle = findViewById(R.id.pairTitle);
        pairSubtitle = findViewById(R.id.pairSubtitle);
        pairButtons = findViewById(R.id.pairButtons);
        btnPairDone = findViewById(R.id.btnPairDone);
        wirePairing();

        txtMac.setText(Prefs.targetMac(this));
        // API keys and the assistant's system prompt live in SettingsActivity.

        wireTabs();
        wireConnection();
        wireFeatures();
        wireSettings();
        animateEntrance();
        requestNeededPermissions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        logAdapter.setAll(LogBus.history());
        scrollToBottom();
        LogBus.addListener(this);
        bindService(new Intent(this, MyvuService.class), serviceConnection, 0);

        ((MaterialSwitch) findViewById(R.id.swMirror))
                .setChecked(MirrorNotificationListener.isEnabled(this) && Prefs.mirrorEnabled(this));
    }

    @Override
    protected void onStop() {
        super.onStop();
        LogBus.removeListener(this);
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
    }

    // ------------------------------------------------------------- wiring

    /** Swaps between the Controls and Log pages, with a quick cross-fade. */
    private void wireTabs() {
        final View pageControls = findViewById(R.id.pageControls);
        final View pageLog = findViewById(R.id.pageLog);
        com.google.android.material.tabs.TabLayout tabs = findViewById(R.id.tabs);
        tabs.addOnTabSelectedListener(
                new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
                    @Override
                    public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                        boolean log = tab.getPosition() == 1;
                        crossFade(log ? pageLog : pageControls, log ? pageControls : pageLog);
                        // Opening the Log tab jumps to the latest line and resumes tailing.
                        if (log) scrollToBottom();
                    }
                    @Override
                    public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) { }
                    @Override
                    public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) { }
                });
    }

    private void crossFade(final View show, final View hide) {
        if (show.getVisibility() == View.VISIBLE) return;
        show.setAlpha(0f);
        show.setVisibility(View.VISIBLE);
        show.animate().alpha(1f).setDuration(180).start();
        hide.animate().alpha(0f).setDuration(120).withEndAction(new Runnable() {
            @Override public void run() { hide.setVisibility(View.GONE); hide.setAlpha(1f); }
        }).start();
    }

    private void wireConnection() {
        findViewById(R.id.btnConnect).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startConnection(); }
        });
        findViewById(R.id.btnDisconnect).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopConnection(); }
        });
        findViewById(R.id.btnTrackpad).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(ConnectActivity.this, TrackpadActivity.class));
            }
        });
        View.OnClickListener openSettings = new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(ConnectActivity.this, SettingsActivity.class));
            }
        };
        findViewById(R.id.btnSettings).setOnClickListener(openSettings);
        findViewById(R.id.btnAiSettings).setOnClickListener(openSettings);
    }

    private void wireFeatures() {
        findViewById(R.id.btnNotify).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!need()) return;
                String title = text(txtNotifyTitle);
                String body = text(txtNotifyBody);
                if (body.isEmpty() && title.isEmpty()) {
                    body = "Hello from the MYVU client";
                }
                service.connection().sendTestNotification(
                        title.isEmpty() ? "Notification" : title, body);
            }
        });

        findViewById(R.id.btnAsk).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!need()) return;
                String q = text(txtAsk);
                if (!q.isEmpty()) service.connection().askAi(q);
            }
        });

        findViewById(R.id.btnTici).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!need()) return;
                String t = text(txtTici);
                service.connection().openTeleprompter(
                        t.isEmpty() ? "Hello from the MYVU client." : t, "Prompter");
            }
        });

        MaterialSwitch swMirror = findViewById(R.id.swMirror);
        swMirror.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleMirroring((MaterialSwitch) v); }
        });

        findViewById(R.id.btnNavStart).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!need()) return;
                String dest = text(txtDest);
                if (dest.isEmpty()) {
                    // No destination -> just open the HUD with a demo frame.
                    try {
                        service.connection().sendAction(
                                NavCommands.buildStart(1, 1000, 1000, 120, "Demo Road",
                                        300, "0", 0, 1, 0, 0, 0, false, false),
                                NavCommands.LAUNCH_TARGET_PKG, NavCommands.SOURCE_PKG);
                    } catch (Exception e) {
                        LogBus.error("nav HUD failed", e);
                    }
                } else {
                    service.connection().nav().start(dest);
                }
            }
        });
        findViewById(R.id.btnNavStop).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (need()) service.connection().nav().stop();
            }
        });
        findViewById(R.id.btnIcNext).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!need()) return;
                calibrationIc = calibrationIc >= 16 ? 1 : calibrationIc + 1;
                service.connection().nav().sendCalibrationFrame(calibrationIc, "ic=" + calibrationIc);
            }
        });

        findViewById(R.id.btnShareLog).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareLog(); }
        });
        findViewById(R.id.btnClearLog).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { LogBus.clear(); logAdapter.clear(); }
        });
    }

    private void wireSettings() {
        // Sliders fire on release only, so a drag is one command, not dozens.
        ((Slider) findViewById(R.id.sldBrightness)).addOnSliderTouchListener(
                new Slider.OnSliderTouchListener() {
                    @Override public void onStartTrackingTouch(@NonNull Slider s) { }
                    @Override public void onStopTrackingTouch(@NonNull Slider s) {
                        if (need()) service.connection().setBrightness((int) s.getValue());
                    }
                });
        ((Slider) findViewById(R.id.sldVolume)).addOnSliderTouchListener(
                new Slider.OnSliderTouchListener() {
                    @Override public void onStartTrackingTouch(@NonNull Slider s) { }
                    @Override public void onStopTrackingTouch(@NonNull Slider s) {
                        if (need()) service.connection().setVolume((int) s.getValue());
                    }
                });

        MaterialButtonToggleGroup fov = findViewById(R.id.fovGroup);
        fov.check(R.id.fov0);
        fov.addOnButtonCheckedListener(new MaterialButtonToggleGroup.OnButtonCheckedListener() {
            @Override public void onButtonChecked(MaterialButtonToggleGroup g, int id, boolean checked) {
                if (!checked || !need()) return;
                int pos = id == R.id.fov1 ? 1 : id == R.id.fov2 ? 2 : id == R.id.fov3 ? 3 : 0;
                service.connection().setStandbyPosition(pos);
            }
        });

        toggle(R.id.swWifi, new Toggle() { public void set(boolean on) { service.connection().toggleWifi(on); } });
        toggle(R.id.swZen, new Toggle() { public void set(boolean on) { service.connection().setZenMode(on); } });
        toggle(R.id.swWear, new Toggle() { public void set(boolean on) { service.connection().setWearDetection(on); } });
        toggle(R.id.swMusicTp, new Toggle() { public void set(boolean on) { service.connection().setMusicTpControl(on); } });
        toggle(R.id.swAir, new Toggle() { public void set(boolean on) { service.connection().setAirMode(on); } });

        findViewById(R.id.btnSyncTime).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (need()) service.connection().syncTime(); }
        });
        findViewById(R.id.btnDeviceInfo).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (need()) service.connection().query("get_device_info"); }
        });
    }

    // ------------------------------------------------------- connect / status

    private void startConnection() {
        String mac = text(txtMac).toUpperCase();
        // Blank MAC = auto-search: scan for the glasses instead of requiring the
        // address. A non-blank but malformed address is still rejected.
        boolean auto = mac.isEmpty();
        if (!auto && !mac.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) {
            LogBus.warn("not a valid MAC address: " + mac);
            return;
        }
        Intent start = new Intent(this, MyvuService.class).setAction(MyvuService.ACTION_START);
        if (auto) {
            LogBus.log("no MAC entered -- auto-searching for glasses");
        } else {
            Prefs.setTargetMac(this, mac);
            start.putExtra(MyvuService.EXTRA_MAC, mac);
        }
        ContextCompat.startForegroundService(this, start);
        requestDozeExemption();
        if (!bound) bindService(new Intent(this, MyvuService.class), serviceConnection, 0);
        showPairing();
    }

    // ---------------------------------------------------- pairing overlay

    private void wirePairing() {
        findViewById(R.id.btnPairCancel).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopConnection(); dismissPairing(); }
        });
        findViewById(R.id.btnPairRetry).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startConnection(); }
        });
        btnPairDone.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissPairing(); }
        });
    }

    /** Full-screen "finding your glasses" flow, styled after the official app. */
    private void showPairing() {
        pairing = true;
        pairingOverlay.setAlpha(0f);
        pairingOverlay.setVisibility(View.VISIBLE);
        pairingOverlay.animate().alpha(1f).setDuration(220).start();

        imgCheck.setVisibility(View.GONE);
        pairButtons.setVisibility(View.GONE);
        btnPairDone.setVisibility(View.GONE);
        imgGlasses.setAlpha(0.5f);
        pairTitle.setText("Searching for your glasses");
        pairSubtitle.setText("Make sure they are powered on and nearby");
        startRings();
    }

    private void dismissPairing() {
        pairing = false;
        stopRings();
        pairingOverlay.animate().alpha(0f).setDuration(180).withEndAction(new Runnable() {
            @Override public void run() { pairingOverlay.setVisibility(View.GONE); }
        }).start();
    }

    /** Three rings expanding and fading outward, staggered, like a radar ping. */
    private void startRings() {
        stopRings();
        View[] rings = { ring1, ring2, ring3 };
        for (int i = 0; i < rings.length; i++) {
            final View r = rings[i];
            android.animation.PropertyValuesHolder sx =
                    android.animation.PropertyValuesHolder.ofFloat("scaleX", 0.35f, 1f);
            android.animation.PropertyValuesHolder sy =
                    android.animation.PropertyValuesHolder.ofFloat("scaleY", 0.35f, 1f);
            android.animation.PropertyValuesHolder al =
                    android.animation.PropertyValuesHolder.ofFloat("alpha", 0.7f, 0f);
            ObjectAnimator ping = ObjectAnimator.ofPropertyValuesHolder(r, sx, sy, al);
            ping.setDuration(2000);
            ping.setStartDelay(i * 650L);
            ping.setRepeatCount(ValueAnimator.INFINITE);
            ping.setInterpolator(new DecelerateInterpolator());
            ping.start();
            ringAnimators.add(ping);
        }
        // A gentle breathing on the glasses while searching.
        ObjectAnimator breathe = ObjectAnimator.ofFloat(imgGlasses, "alpha", 0.5f, 1f, 0.5f);
        breathe.setDuration(1800);
        breathe.setRepeatCount(ValueAnimator.INFINITE);
        breathe.start();
        ringAnimators.add(breathe);
    }

    private void stopRings() {
        for (android.animation.Animator a : ringAnimators) a.cancel();
        ringAnimators.clear();
        for (View r : new View[]{ ring1, ring2, ring3 }) { r.setScaleX(0.35f); r.setScaleY(0.35f); r.setAlpha(0f); }
    }

    /** Drives the overlay's title/subtitle/state from the connection state. */
    private void updatePairing(ConnectionState state) {
        if (!pairing) return;
        switch (state) {
            case CONNECTING:
            case BONDING:
                pairTitle.setText("Searching for your glasses");
                pairSubtitle.setText("Make sure they are powered on and nearby");
                break;
            case PAIRING:
                pairTitle.setText("Found your glasses");
                pairSubtitle.setText(deviceLabel());
                imgGlasses.animate().alpha(1f).scaleX(1.06f).scaleY(1.06f).setDuration(260)
                        .withEndAction(new Runnable() {
                            @Override public void run() {
                                imgGlasses.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
                            }
                        }).start();
                break;
            case SESSION:
                pairTitle.setText("Almost ready");
                pairSubtitle.setText(deviceLabel());
                break;
            case READY:
                pairingSuccess();
                break;
            case FAILED:
                pairingFailed();
                break;
            default:
                break;
        }
    }

    private void pairingSuccess() {
        stopRings();
        imgGlasses.setAlpha(1f);
        pairTitle.setText("Connected");
        pairSubtitle.setText("Explore your AR world");
        imgCheck.setVisibility(View.VISIBLE);
        imgCheck.setScaleX(0f); imgCheck.setScaleY(0f);
        imgCheck.animate().scaleX(1f).scaleY(1f).setDuration(360)
                .setInterpolator(new android.view.animation.OvershootInterpolator()).start();
        btnPairDone.setVisibility(View.VISIBLE);
        // Auto-dismiss shortly; the user can also tap Done.
        pairingOverlay.postDelayed(new Runnable() {
            @Override public void run() { if (pairing) dismissPairing(); }
        }, 1600);
    }

    private void pairingFailed() {
        stopRings();
        imgGlasses.setAlpha(0.5f);
        pairTitle.setText("Couldn't connect");
        pairSubtitle.setText("The glasses didn't respond. Check they are on, "
                + "and that no other phone is connected to them.");
        pairButtons.setVisibility(View.VISIBLE);
    }

    private String deviceLabel() {
        if (bound && service != null && service.connection().glassesInfo() != null) {
            return service.connection().glassesInfo().name;
        }
        return text(txtMac);
    }

    private void stopConnection() {
        startService(new Intent(this, MyvuService.class).setAction(MyvuService.ACTION_STOP));
        render(ConnectionState.IDLE);
    }

    /** Called on the main thread whenever the connection state changes. */
    private void render(ConnectionState state) {
        boolean busy = state == ConnectionState.BONDING || state == ConnectionState.CONNECTING
                || state == ConnectionState.PAIRING || state == ConnectionState.SESSION;
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        txtStatus.setText(describe(state));

        if (pairing) updatePairing(state);

        animateDot(dotColor(state));

        if (state == ConnectionState.READY && bound && service.connection().glassesInfo() != null) {
            txtGlasses.setText(service.connection().glassesInfo().toString());
            txtGlasses.setVisibility(View.VISIBLE);
        } else if (state == ConnectionState.IDLE) {
            txtGlasses.setVisibility(View.GONE);
        }
    }

    private int dotColor(ConnectionState state) {
        switch (state) {
            case READY: return ContextCompat.getColor(this, R.color.state_ready);
            case FAILED: return ContextCompat.getColor(this, R.color.state_failed);
            case IDLE: return ContextCompat.getColor(this, R.color.state_idle);
            default: return ContextCompat.getColor(this, R.color.state_connecting);
        }
    }

    /** Cross-fades the status dot colour and gives a ready link a soft pulse. */
    private void animateDot(final int target) {
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), lastDotColor, target);
        anim.setDuration(350);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                int c = (int) a.getAnimatedValue();
                if (statusDot.getBackground() instanceof GradientDrawable) {
                    ((GradientDrawable) statusDot.getBackground().mutate()).setColor(c);
                } else {
                    statusDot.getBackground().mutate().setTint(c);
                }
            }
        });
        anim.start();
        lastDotColor = target;

        statusDot.clearAnimation();
        if (target == ContextCompat.getColor(this, R.color.state_ready)) {
            ObjectAnimator pulse = ObjectAnimator.ofFloat(statusDot, "alpha", 1f, 0.4f, 1f);
            pulse.setDuration(1600);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.start();
        } else {
            statusDot.setAlpha(1f);
        }
    }

    private static String describe(ConnectionState state) {
        switch (state) {
            case BONDING: return "Bonding…";
            case CONNECTING: return "Connecting…";
            case PAIRING: return "Exchanging keys…";
            case SESSION: return "Starting session…";
            case READY: return "Connected";
            case FAILED: return "Disconnected";
            default: return "Disconnected";
        }
    }

    // ---------------------------------------------------------- animations

    /** Slides each card up into place, staggered, for a first-run entrance. */
    private void animateEntrance() {
        final View content = findViewById(R.id.content);
        content.post(new Runnable() {
            @Override public void run() {
                if (!(content instanceof android.view.ViewGroup)) return;
                android.view.ViewGroup g = (android.view.ViewGroup) content;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View child = g.getChildAt(i);
                    child.setAlpha(0f);
                    child.setTranslationY(40f);
                    child.animate().alpha(1f).translationY(0f)
                            .setStartDelay(i * 45L).setDuration(320)
                            .setInterpolator(new DecelerateInterpolator()).start();
                }
            }
        });
    }

    // ------------------------------------------------------------- helpers

    private interface Toggle { void set(boolean on); }
    private interface Saver { void save(String value); }

    private void toggle(int id, final Toggle t) {
        ((MaterialSwitch) findViewById(id)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (need()) t.set(((MaterialSwitch) v).isChecked());
                else ((MaterialSwitch) v).setChecked(!((MaterialSwitch) v).isChecked());
            }
        });
    }

    private void persist(final TextInputEditText field, final Saver saver) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { saver.save(e.toString().trim()); }
        });
    }

    /** Local mirror of the FOV/brightness icon under calibration (provisional). */
    private int calibrationIc = 0;

    private boolean need() {
        if (bound && service != null && service.connection().state() == ConnectionState.READY) {
            return true;
        }
        LogBus.warn("not connected yet");
        return false;
    }

    private static String text(TextInputEditText field) {
        CharSequence c = field.getText();
        return c == null ? "" : c.toString().trim();
    }

    private void toggleMirroring(MaterialSwitch sw) {
        if (!MirrorNotificationListener.isEnabled(this)) {
            sw.setChecked(false);
            LogBus.log("grant notification access, then enable mirroring again");
            startActivity(MirrorNotificationListener.settingsIntent());
            return;
        }
        Prefs.setMirrorEnabled(this, sw.isChecked());
        LogBus.log("notification mirroring " + (sw.isChecked() ? "ON" : "OFF"));
    }

    private void requestDozeExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            startActivity(new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            LogBus.trace("battery-optimisation prompt unavailable: " + e);
        }
    }

    private void shareLog() {
        StringBuilder sb = new StringBuilder();
        for (String line : LogBus.history()) sb.append(line).append('\n');
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "MYVU client log")
                .putExtra(Intent.EXTRA_TEXT, sb.toString()), "Share log"));
    }

    @Override
    public void onLine(String line) {
        // Decide BEFORE inserting, from the live scroll position: tail only if the
        // user is already at the bottom. If they've scrolled up to read history,
        // leave them there -- RecyclerView keeps existing rows put when a row is
        // added below, so no jump. Recomputed every line, no sticky state.
        boolean atBottom = logAtBottom();
        int last = logAdapter.add(line);
        if (atBottom) rvLog.scrollToPosition(last);
        // Reflect connection-state changes the service pushes through the log.
        if (bound && service != null) render(service.connection().state());
    }

    /** True when the log can't scroll down any further (i.e. at the bottom). */
    private boolean logAtBottom() {
        return !rvLog.canScrollVertically(1);
    }

    private void scrollToBottom() {
        rvLog.post(new Runnable() {
            @Override public void run() {
                int n = logAdapter.size();
                if (n > 0) rvLog.scrollToPosition(n - 1);
            }
        });
    }

    // ---------------------------------------------------------- permissions

    private void requestNeededPermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addIfMissing(needed, Manifest.permission.BLUETOOTH_CONNECT);
            addIfMissing(needed, Manifest.permission.BLUETOOTH_SCAN);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(needed, Manifest.permission.POST_NOTIFICATIONS);
        }
        addIfMissing(needed, Manifest.permission.ACCESS_FINE_LOCATION);
        addIfMissing(needed, Manifest.permission.RECORD_AUDIO);
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    private void addIfMissing(List<String> out, String permission) {
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            out.add(permission);
        }
    }
}

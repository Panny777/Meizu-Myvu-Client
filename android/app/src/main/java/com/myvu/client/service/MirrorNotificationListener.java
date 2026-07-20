package com.myvu.client.service;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import com.myvu.client.app.feature.Notifications;
import com.myvu.client.core.LogBus;
import com.myvu.client.core.Prefs;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Mirrors the phone's real notifications onto the lens.
 *
 * This is something the Python client could never do -- it could only push
 * hand-written test notifications. Here we forward actual incoming SMS, chat
 * messages and so on, the way the official app does.
 *
 * Requires the user to grant notification access in system settings; there is
 * no runtime-permission dialog for it (see {@link #isEnabled}).
 */
public class MirrorNotificationListener extends NotificationListenerService {

    /**
     * A busy phone can emit notifications far faster than the relay drains, and
     * flooding it starves the ACK path that everything else depends on. These
     * bounds keep mirroring from degrading the connection.
     */
    private static final int MAX_PER_WINDOW = 10;
    private static final long WINDOW_MS = 10_000;
    /** Ignore a repeat of the same notification within this interval. */
    private static final long DEDUPE_MS = 2_000;

    private final Deque<Long> recentSends = new ArrayDeque<>();
    private String lastKey;
    private long lastKeyAt;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (!Prefs.mirrorEnabled(this)) return;

        Notification n = sbn.getNotification();
        if (n == null) return;

        // Ongoing notifications are persistent UI (media players, downloads,
        // foreground services), not events worth showing on a lens.
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return; // duplicates its children

        // Opt-in only: notifications carry OTPs, 2FA codes and private messages,
        // so nothing is forwarded unless the user picked that app in Settings.
        // isPackageAllowed() also applies the hard block list (system noise, us).
        String pkg = sbn.getPackageName();
        if (!Prefs.isPackageAllowed(this, pkg)) {
            // trace(), not log(): this fires for every notification from every
            // app the user did not opt in, and would drown the on-screen log.
            // It is still in logcat when you need to ask "why not this app?".
            LogBus.trace("not mirroring " + pkg + ": not in the chosen apps");
            return;
        }

        Bundle extras = n.extras;
        if (extras == null) return;
        String title = charSequence(extras, Notification.EXTRA_TITLE);
        String text = charSequence(extras, Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) return;

        if (isDuplicate(sbn.getKey())) return;
        if (!allowedByRateLimit()) {
            LogBus.warn("notification mirroring rate-limited (" + MAX_PER_WINDOW
                    + " per " + (WINDOW_MS / 1000) + "s) -- dropping one from " + pkg);
            return;
        }

        ConnectionManager connection = MyvuService.activeConnection();
        if (connection == null) {
            // This used to return silently, which made "mirroring does nothing"
            // impossible to diagnose: the notification passed every filter and
            // the only missing piece was the glasses. Say so.
            LogBus.warn("not mirroring " + appLabel(pkg)
                    + ": not connected to the glasses");
            return;
        }

        try {
            // The id is derived from package + numeric id, NOT sbn.getKey().
            // See Notifications.notificationId -- passing the platform key here
            // made the glasses reboot on every mirrored notification.
            JSONObject entry = Notifications.entry(
                    pkg,
                    sbn.getId(),
                    TextUtils.isEmpty(title) ? appLabel(pkg) : title,
                    text == null ? "" : text,
                    appLabel(pkg),
                    sbn.getPostTime(),
                    false);
            connection.sendAction(Notifications.buildShow(entry));
            LogBus.log("mirrored notification from " + appLabel(pkg) + ": " + title);
        } catch (Exception e) {
            LogBus.error("could not mirror a notification", e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !Prefs.mirrorEnabled(this)) return;
        // Same gate as the show path -- never dismiss what we never mirrored.
        if (!Prefs.isPackageAllowed(this, sbn.getPackageName())) return;
        ConnectionManager connection = MyvuService.activeConnection();
        if (connection == null) return;
        try {
            // Must match the id used when showing it, or the dismiss is a no-op.
            connection.sendAction(Notifications.buildDismiss(
                    Notifications.notificationId(sbn.getPackageName(), sbn.getId())));
        } catch (Exception e) {
            LogBus.error("could not dismiss a mirrored notification", e);
        }
    }

    // ------------------------------------------------------------ helpers

    private boolean isDuplicate(String key) {
        long now = System.currentTimeMillis();
        if (key != null && key.equals(lastKey) && now - lastKeyAt < DEDUPE_MS) return true;
        lastKey = key;
        lastKeyAt = now;
        return false;
    }

    /** Sliding window rather than a fixed quota, so bursts recover on their own. */
    private boolean allowedByRateLimit() {
        long now = System.currentTimeMillis();
        while (!recentSends.isEmpty() && now - recentSends.peekFirst() > WINDOW_MS) {
            recentSends.removeFirst();
        }
        if (recentSends.size() >= MAX_PER_WINDOW) return false;
        recentSends.addLast(now);
        return true;
    }

    private String charSequence(Bundle extras, String key) {
        CharSequence cs = extras.getCharSequence(key);
        return cs != null ? cs.toString() : null;
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager()
                    .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))
                    .toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // -------------------------------------------------- permission plumbing

    /**
     * Notification access is granted in system settings, not by a runtime
     * dialog, so the UI has to check the state itself and deep-link there.
     */
    /**
     * Ask the system to (re)bind this listener.
     *
     * Reinstalling or updating the app leaves the listener ENABLED but UNBOUND:
     * notification access still shows as granted in system settings, yet
     * onNotificationPosted never fires again, so mirroring silently stops until
     * the user toggles the permission off and on. Requesting a rebind on startup
     * makes that heal itself. No-op when access was never granted.
     */
    public static void requestRebindIfEnabled(Context context) {
        if (!isEnabled(context)) return;
        ComponentName cn = new ComponentName(context, MirrorNotificationListener.class);
        try {
            // requestRebind() alone is NOT enough: it is meant to pair with
            // requestUnbind(), and does nothing when the system dropped us for
            // another reason (an app update). Cycling the component's enabled
            // state forces the system to re-evaluate and bind it again.
            PackageManager pm = context.getPackageManager();
            pm.setComponentEnabledSetting(cn,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(cn,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            NotificationListenerService.requestRebind(cn);
        } catch (Exception e) {
            LogBus.trace("could not request a listener rebind: " + e);
        }
    }

    public static boolean isEnabled(Context context) {
        String flat = Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(flat)) return false;
        String self = new ComponentName(context, MirrorNotificationListener.class)
                .flattenToString();
        for (String entry : flat.split(":")) {
            if (entry.equals(self)) return true;
        }
        return false;
    }

    public static Intent settingsIntent() {
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    }
}

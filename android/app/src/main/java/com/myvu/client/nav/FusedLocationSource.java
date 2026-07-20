package com.myvu.client.nav;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.myvu.client.core.LogBus;

/**
 * FusedLocationProvider-backed {@link LocationSource}: better battery behaviour
 * and better urban/indoor fixes than raw GPS.
 */
public class FusedLocationSource implements LocationSource {

    private static final long INTERVAL_MS = 1000;

    private final Context context;
    private final FusedLocationProviderClient client;
    private LocationCallback callback;

    public FusedLocationSource(Context context) {
        this.context = context.getApplicationContext();
        this.client = LocationServices.getFusedLocationProviderClient(this.context);
    }

    @Override
    public void start(final Listener listener) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            listener.onUnavailable("location permission not granted");
            return;
        }

        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
                .setMinUpdateIntervalMillis(INTERVAL_MS)
                .setWaitForAccurateLocation(false)
                .build();

        callback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                Location l = result.getLastLocation();
                if (l == null) return;
                listener.onFix(
                        l.getLatitude(),
                        l.getLongitude(),
                        l.hasSpeed() ? l.getSpeed() : -1f,
                        l.hasBearing() ? l.getBearing() : -1f);
            }
        };

        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper());
            LogBus.log("location updates started (fused, 1Hz)");
        } catch (SecurityException e) {
            listener.onUnavailable("location permission revoked: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        if (callback != null) {
            client.removeLocationUpdates(callback);
            callback = null;
            LogBus.log("location updates stopped");
        }
    }
}

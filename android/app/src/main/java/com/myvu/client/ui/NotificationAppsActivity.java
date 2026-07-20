package com.myvu.client.ui;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.myvu.client.R;
import com.myvu.client.core.Prefs;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks which apps may mirror their notifications to the glasses.
 *
 * Opt-in by design: nothing is forwarded until an app is switched on here, so a
 * fresh install never ships someone's OTPs or private messages to the lens.
 * Hard-blocked packages (system UI, our own foreground-service notice) are left
 * out of the list entirely rather than shown and ignored.
 */
public class NotificationAppsActivity extends AppCompatActivity {

    private final Set<String> allowed = new HashSet<>();
    private RecyclerView list;
    private ProgressBar progress;
    private TextView summary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notification_apps);

        list = findViewById(R.id.rvApps);
        progress = findViewById(R.id.appsProgress);
        summary = findViewById(R.id.txtAppsSummary);
        list.setLayoutManager(new LinearLayoutManager(this));

        allowed.addAll(Prefs.allowedPackages(this));
        findViewById(R.id.btnAppsBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        loadApps();
    }

    /** Querying + sorting every launchable app is far too slow for the main thread. */
    private void loadApps() {
        progress.setVisibility(View.VISIBLE);
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppRow> rows = queryApps();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        progress.setVisibility(View.GONE);
                        list.setAdapter(new AppsAdapter(rows));
                        updateSummary();
                    }
                });
            }
        }, "myvu-appscan").start();
    }

    private List<AppRow> queryApps() {
        PackageManager pm = getPackageManager();
        Set<String> blocked = Prefs.blockedPackages(this);
        // Launchable apps only: services and system stubs have no notifications
        // a user would recognise, and listing them makes the picker unusable.
        Intent launchable = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(launchable, 0);

        Set<String> seen = new HashSet<>();
        List<AppRow> rows = new ArrayList<>();
        for (ResolveInfo ri : resolved) {
            ApplicationInfo ai = ri.activityInfo != null ? ri.activityInfo.applicationInfo : null;
            if (ai == null || !seen.add(ai.packageName)) continue;
            if (blocked.contains(ai.packageName)) continue;
            rows.add(new AppRow(ai.packageName, String.valueOf(pm.getApplicationLabel(ai)),
                    ai.loadIcon(pm)));
        }

        final Collator collator = Collator.getInstance();
        Collections.sort(rows, new Comparator<AppRow>() {
            @Override
            public int compare(AppRow a, AppRow b) {
                // Selected apps first so the current choice is visible at a glance.
                boolean sa = allowed.contains(a.pkg), sb = allowed.contains(b.pkg);
                if (sa != sb) return sa ? -1 : 1;
                return collator.compare(a.label, b.label);
            }
        });
        return rows;
    }

    private void updateSummary() {
        summary.setText(allowed.isEmpty()
                ? "No apps selected — nothing is mirrored to the glasses yet."
                : allowed.size() + " app" + (allowed.size() == 1 ? "" : "s")
                        + " will mirror notifications to the glasses.");
    }

    private static final class AppRow {
        final String pkg;
        final String label;
        final Drawable icon;

        AppRow(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private final class AppsAdapter extends RecyclerView.Adapter<AppsAdapter.Row> {
        private final List<AppRow> rows;

        AppsAdapter(List<AppRow> rows) {
            this.rows = rows;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @NonNull
        @Override
        public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Row(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Row holder, int position) {
            final AppRow row = rows.get(position);
            ((ImageView) holder.itemView.findViewById(R.id.appIcon)).setImageDrawable(row.icon);
            ((TextView) holder.itemView.findViewById(R.id.appLabel)).setText(row.label);
            final MaterialSwitch sw = holder.itemView.findViewById(R.id.appSwitch);
            sw.setChecked(allowed.contains(row.pkg));
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean on = !allowed.contains(row.pkg);
                    if (on) allowed.add(row.pkg); else allowed.remove(row.pkg);
                    sw.setChecked(on);
                    Prefs.setAllowedPackages(NotificationAppsActivity.this, allowed);
                    updateSummary();
                }
            });
        }

        final class Row extends RecyclerView.ViewHolder {
            Row(@NonNull View v) {
                super(v);
            }
        }
    }
}

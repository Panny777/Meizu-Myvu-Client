package com.myvu.client.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Backs the activity-log RecyclerView. One row per line. Deliberately trivial:
 * the row view is a monospace TextView built in code (no per-item layout file),
 * matching the old log's look. Rows are append-only plus a clear(); the buffer
 * cap lives in LogBus, so no trimming here.
 */
public class LogAdapter extends RecyclerView.Adapter<LogAdapter.Row> {

    private final List<String> lines = new ArrayList<>();
    private final int textColor;

    public LogAdapter(Context context) {
        // Resolve ?attr/colorOnSurfaceVariant once, matching the previous styling.
        TypedValue tv = new TypedValue();
        boolean ok = context.getTheme()
                .resolveAttribute(R.attr.colorOnSurfaceVariant, tv, true);
        this.textColor = ok ? ContextCompat.getColor(context, tv.resourceId)
                : 0xFFAAAAAA;
    }

    /** Replace all rows (used when (re)loading history). */
    public void setAll(List<String> newLines) {
        lines.clear();
        lines.addAll(newLines);
        notifyDataSetChanged();
    }

    /** Append one line; returns the new last index. */
    public int add(String line) {
        lines.add(line);
        notifyItemInserted(lines.size() - 1);
        return lines.size() - 1;
    }

    public void clear() {
        int n = lines.size();
        lines.clear();
        notifyItemRangeRemoved(0, n);
    }

    public int size() {
        return lines.size();
    }

    @Override
    public int getItemCount() {
        return lines.size();
    }

    @NonNull
    @Override
    public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context ctx = parent.getContext();
        TextView tv = new TextView(ctx);
        tv.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(textColor);
        tv.setPadding(0, 2, 0, 2);
        return new Row(tv);
    }

    @Override
    public void onBindViewHolder(@NonNull Row holder, int position) {
        ((TextView) holder.itemView).setText(lines.get(position));
    }

    static class Row extends RecyclerView.ViewHolder {
        Row(@NonNull TextView v) {
            super(v);
        }
    }
}

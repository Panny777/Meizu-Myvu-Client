package com.myvu.client.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.myvu.client.app.feature.Trackpad;

/**
 * A touch surface that maps finger gestures to glasses trackpad events, matching
 * the official app's TouchpadFragment:
 *   - single tap        -> click
 *   - double tap        -> doubleClick
 *   - long press        -> longPress
 *   - swipe (on lift, if it moved far enough) -> a directional gesture
 *
 * Swipes are recognised on finger-UP (not continuously): we remember where the
 * drag began and ended and, if it travelled past the threshold, emit one
 * directional swipe -- the same one-gesture-per-drag behaviour as the real app.
 */
public class TrackpadView extends View {

    public interface Listener {
        void onTap();
        void onDoubleTap();
        void onLongPress();
        void onSwipe(int direction, float startX, float startY,
                     float endX, float endY, float speedX, float speedY);
    }

    private Listener listener;
    private GestureDetector detector;

    private boolean scrolled;
    private float beginX, beginY, endX, endY;
    private long beginTime;

    /** Minimum travel to count as a swipe (official app uses 100px). */
    private float swipeThreshold;

    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float dotGap;
    private float dotRadius;

    public TrackpadView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    private void init() {
        float d = getResources().getDisplayMetrics().density;
        swipeThreshold = 64 * d;
        dotGap = 26 * d;
        dotRadius = 1.6f * d;
        dotPaint.setColor(Color.parseColor("#33FFFFFF"));

        detector = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        if (listener != null) listener.onTap();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (listener != null) listener.onDoubleTap();
                        return true;
                    }

                    @Override
                    public void onLongPress(MotionEvent e) {
                        if (listener != null) listener.onLongPress();
                    }

                    @Override
                    public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                            float distanceX, float distanceY) {
                        scrolled = true;
                        endX = e2.getX();
                        endY = e2.getY();
                        return true;
                    }
                });
        detector.setIsLongpressEnabled(true);
        setClickable(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        detector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginX = event.getX();
                beginY = event.getY();
                endX = beginX;
                endY = beginY;
                beginTime = System.currentTimeMillis();
                scrolled = false;
                return true;
            case MotionEvent.ACTION_UP:
                emitSwipeIfAny();
                return true;
            default:
                return true;
        }
    }

    private void emitSwipeIfAny() {
        if (!scrolled || listener == null) return;
        float dx = endX - beginX;
        float dy = endY - beginY;
        if (Math.abs(dx) < swipeThreshold && Math.abs(dy) < swipeThreshold) return;
        long dur = Math.max(1, System.currentTimeMillis() - beginTime);
        int direction = Math.abs(dx) > Math.abs(dy)
                ? (dx > 0 ? Trackpad.SWIPE_RIGHT : Trackpad.SWIPE_LEFT)
                : (dy > 0 ? Trackpad.SWIPE_DOWN : Trackpad.SWIPE_UP);
        listener.onSwipe(direction, beginX, beginY, endX, endY, dx / dur, dy / dur);
        scrolled = false;
    }

    // A faint dot grid, so the surface reads as a trackpad rather than a blank box.
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (float y = dotGap; y < getHeight(); y += dotGap) {
            for (float x = dotGap; x < getWidth(); x += dotGap) {
                canvas.drawCircle(x, y, dotRadius, dotPaint);
            }
        }
    }
}

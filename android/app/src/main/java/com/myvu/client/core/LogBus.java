package com.myvu.client.core;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A process-wide log ring buffer with listeners.
 *
 * The connection runs on background threads while the UI comes and goes, so
 * log lines are buffered here and replayed when a screen attaches. Listeners
 * are always invoked on the main thread.
 *
 * Every Android touchpoint is guarded: android.jar's classes are non-functional
 * stubs under JVM unit tests, and without these guards merely logging would
 * throw ExceptionInInitializerError and make every pure-logic class that logs
 * untestable off-device.
 */
public final class LogBus {
    private LogBus() {}

    public static final String TAG = "myvu";
    private static final int CAPACITY = 2000;

    public interface Listener {
        void onLine(String line);
    }

    private static final Deque<String> BUFFER = new ArrayDeque<>(CAPACITY);
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    /** Null when there is no Android runtime (i.e. under JVM unit tests). */
    private static final Handler MAIN = createMainHandler();

    private static Handler createMainHandler() {
        try {
            Looper looper = Looper.getMainLooper();
            return looper != null ? new Handler(looper) : null;
        } catch (Throwable ignored) {
            return null; // no Android runtime
        }
    }

    public static void log(String msg) {
        androidLog(Log.INFO, msg, null);
        emit(stamp() + "  " + msg);
    }

    public static void warn(String msg) {
        androidLog(Log.WARN, msg, null);
        emit(stamp() + "  !! " + msg);
    }

    public static void error(String msg, Throwable t) {
        androidLog(Log.ERROR, msg, t);
        String detail = t == null ? msg
                : msg + ": " + t.getClass().getSimpleName() + ": " + t.getMessage();
        emit(stamp() + "  !! " + detail);
    }

    /** Verbose frame-level detail: goes to logcat only, never the on-screen buffer. */
    public static void trace(String msg) {
        androidLog(Log.DEBUG, msg, null);
    }

    private static void androidLog(int level, String msg, Throwable t) {
        try {
            switch (level) {
                case Log.WARN: Log.w(TAG, msg); break;
                case Log.ERROR: Log.e(TAG, msg, t); break;
                case Log.DEBUG: Log.d(TAG, msg); break;
                default: Log.i(TAG, msg); break;
            }
        } catch (Throwable ignored) {
            // Stubbed android.util.Log under unit tests.
        }
    }

    private static String stamp() {
        synchronized (STAMP) {
            return STAMP.format(new Date());
        }
    }

    private static void emit(final String line) {
        synchronized (BUFFER) {
            if (BUFFER.size() >= CAPACITY) BUFFER.removeFirst();
            BUFFER.addLast(line);
        }
        if (LISTENERS.isEmpty()) return;

        Runnable dispatch = new Runnable() {
            @Override
            public void run() {
                for (Listener l : LISTENERS) l.onLine(line);
            }
        };
        if (MAIN != null) {
            MAIN.post(dispatch);
        } else {
            dispatch.run(); // no looper: deliver inline
        }
    }

    /** Returns the buffered history so a newly attached screen can catch up. */
    public static List<String> history() {
        synchronized (BUFFER) {
            return new ArrayList<>(BUFFER);
        }
    }

    public static void addListener(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    public static void clear() {
        synchronized (BUFFER) {
            BUFFER.clear();
        }
    }
}

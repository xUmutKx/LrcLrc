package com.umutk.lrclrc;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Very small file-backed logger toggled from Settings (tap "Developer" 5 times,
 * or the Debug logging switch). When disabled, log() calls are cheap no-ops.
 * Intended so the user can attach the log file when reporting a bug
 * (Poweramp not responding, scanning getting stuck, wrong album art, etc).
 */
public final class DebugLog {

    private static final String LOG_FILE = "lrclrc_debug.log";
    private static final long MAX_LOG_BYTES = 512 * 1024; // rotate if it grows past 512KB
    private static volatile boolean enabled = false;

    private DebugLog() {}

    public static void setEnabled(boolean v) { enabled = v; }
    public static boolean isEnabled() { return enabled; }

    public static void d(Context ctx, String tag, String msg) {
        write(ctx, tag, msg, null);
    }

    public static void e(Context ctx, String tag, String msg, Throwable t) {
        write(ctx, tag, msg, t);
    }

    private static synchronized void write(Context ctx, String tag, String msg, Throwable t) {
        if (!enabled) return;
        try {
            File f = getLogFile(ctx);
            if (f.exists() && f.length() > MAX_LOG_BYTES) f.delete();
            try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                pw.println(ts + "  [" + tag + "]  " + msg);
                if (t != null) t.printStackTrace(pw);
            }
        } catch (IOException ignored) {
            // logging must never crash the app
        }
    }

    public static File getLogFile(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), LOG_FILE);
    }

    public static void clear(Context ctx) {
        File f = getLogFile(ctx);
        if (f.exists()) f.delete();
    }

    public static String deviceInfo() {
        return "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")  "
                + Build.MANUFACTURER + " " + Build.MODEL;
    }
}

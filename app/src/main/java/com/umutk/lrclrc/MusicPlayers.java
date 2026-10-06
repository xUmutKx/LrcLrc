package com.umutk.lrclrc;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lists installed music apps and plays a song straight in the one chosen in settings. */
public final class MusicPlayers {
    private MusicPlayers() {}

    /** package -> label of every app that can open audio files. */
    public static Map<String, String> installed(Context ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://x/a.mp3"), "audio/*");
        List<ResolveInfo> list = pm.queryIntentActivities(probe, 0);
        List<ResolveInfo> sorted = new ArrayList<>(list);
        for (ResolveInfo ri : sorted) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || out.containsKey(pkg)) continue;
            out.put(pkg, String.valueOf(ri.loadLabel(pm)));
        }
        if (PowerampHelper.isInstalled(ctx) && !out.containsKey(PowerampHelper.PACKAGE)) {
            out.put(PowerampHelper.PACKAGE, "Poweramp");
        }
        return out;
    }

    public static String label(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    /** True if the app was started (playing, or at least opened). False = caller should show the chooser. */
    public static boolean play(Context ctx, String pkg, String audioPath, int seekSeconds) {
        if (pkg == null || pkg.isEmpty()) return false;
        PackageManager pm = ctx.getPackageManager();
        try { pm.getPackageInfo(pkg, 0); } catch (Exception e) { return false; }
        if (PowerampHelper.PACKAGE.equals(pkg) && PowerampHelper.playAt(ctx, audioPath, seekSeconds)) return true;
        try {
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", new java.io.File(audioPath));
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/*").setPackage(pkg)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(view);
            return true;
        } catch (Exception e) {
            DebugLog.e(ctx, "Play", "direct play failed in " + pkg, e);
        }
        Intent launch = pm.getLaunchIntentForPackage(pkg);
        if (launch != null) {
            try {
                ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.music_opened_instead, label(ctx, pkg)),
                        android.widget.Toast.LENGTH_SHORT).show();
                return true;
            } catch (Exception ignored) { }
        }
        return false;
    }
}

package com.umutk.lrclrc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

public final class PowerampHelper {

    public static final String PACKAGE = "com.maxmpz.audioplayer";
    private static final String ACTION_API_COMMAND = "com.maxmpz.audioplayer.API_COMMAND";
    private static final String API_RECEIVER = "com.maxmpz.audioplayer.player.PowerampAPIReceiver";
    private static final String EXTRA_COMMAND = "cmd";
    // Poweramp's official API takes the seek position in MILLISECONDS, under this
    // extra name - not "pos" in seconds. This was the real reason Poweramp would
    // open and start playing the file but never actually jump to the lyric line:
    // the old code sent "pos" in seconds, which Poweramp's receiver doesn't read
    // at all, so the seek was silently ignored.
    private static final String EXTRA_TRACK_POSITION = "com.maxmpz.audioplayer.TRACK_POSITION";
    // cmd=20 = OPEN_TO_PLAY (open file and start playing)
    private static final int CMD_OPEN_TO_PLAY = 20;

    private PowerampHelper() {}

    public static boolean isInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Tell Poweramp to open and play audioFilePath, optionally seeking to seekSeconds.
     * Returns true if the broadcast was sent without throwing, false on any failure
     * (caller should fall back to a generic player chooser in that case).
     *
     * Two real bugs fixed here, found by reading Poweramp's actual API contract:
     *  1. file:// URIs handed to another app crash with FileUriExposedException on
     *     modern targetSdk - must use a FileProvider content:// URI with a read
     *     permission grant instead. This is very likely why Poweramp would open
     *     but show nothing / play nothing: it received a URI it had no permission
     *     to read in the first place.
     *  2. The seek position extra must be in milliseconds under
     *     "com.maxmpz.audioplayer.TRACK_POSITION", not "pos" in seconds - the old
     *     extra name/unit doesn't match what Poweramp's receiver reads, so the seek
     *     was a no-op even when everything else worked.
     */
    public static boolean playAt(Context ctx, String audioFilePath, int seekSeconds) {
        if (audioFilePath == null) return false;
        if (!isInstalled(ctx)) {
            Toast.makeText(ctx, R.string.poweramp_not_installed, Toast.LENGTH_LONG).show();
            DebugLog.d(ctx, "Poweramp", "playAt: Poweramp not installed");
            return false;
        }
        try {
            java.io.File file = new java.io.File(audioFilePath);
            Uri contentUri = FileProvider.getUriForFile(
                    ctx, ctx.getPackageName() + ".fileprovider", file);

            Intent intent = new Intent(ACTION_API_COMMAND);
            intent.setComponent(new ComponentName(PACKAGE, API_RECEIVER));
            intent.setDataAndType(contentUri, "audio/*");
            intent.putExtra(EXTRA_COMMAND, CMD_OPEN_TO_PLAY);
            if (seekSeconds >= 0) {
                intent.putExtra(EXTRA_TRACK_POSITION, seekSeconds * 1000L); // ms, not seconds
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            ctx.grantUriPermission(PACKAGE, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.sendBroadcast(intent);
            DebugLog.d(ctx, "Poweramp", "playAt: sent OPEN_TO_PLAY for " + audioFilePath
                    + " seekSeconds=" + seekSeconds);
            return true;
        } catch (Exception e) {
            DebugLog.e(ctx, "Poweramp", "playAt failed for " + audioFilePath, e);
            return false;
        }
    }
}

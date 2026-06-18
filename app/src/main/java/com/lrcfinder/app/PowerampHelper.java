package com.lrcfinder.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;

public final class PowerampHelper {

    public static final String PACKAGE = "com.maxmpz.audioplayer";
    private static final String ACTION_API_COMMAND = "com.maxmpz.audioplayer.API_COMMAND";
    private static final String API_RECEIVER = "com.maxmpz.audioplayer.player.PowerampAPIReceiver";
    private static final String EXTRA_COMMAND = "cmd";
    private static final String EXTRA_POSITION = "pos";
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
     * IMPORTANT: this previously crashed the app because sendBroadcast() to an explicit
     * component can throw SecurityException (e.g. if the receiver isn't exported on the
     * user's Poweramp/Android version) and nothing here caught it. Also fixed: Poweramp's
     * "pos" extra is documented as SECONDS, not milliseconds - the old code multiplied by
     * 1000, which made every seek land 1000x past the requested line.
     */
    public static boolean playAt(Context ctx, String audioFilePath, int seekSeconds) {
        if (audioFilePath == null) return false;
        if (!isInstalled(ctx)) {
            Toast.makeText(ctx, R.string.poweramp_not_installed, Toast.LENGTH_LONG).show();
            return false;
        }
        try {
            Uri fileUri = Uri.fromFile(new java.io.File(audioFilePath));

            Intent intent = new Intent(ACTION_API_COMMAND);
            intent.setComponent(new ComponentName(PACKAGE, API_RECEIVER));
            intent.setData(fileUri);
            intent.putExtra(EXTRA_COMMAND, CMD_OPEN_TO_PLAY);
            if (seekSeconds >= 0) {
                intent.putExtra(EXTRA_POSITION, seekSeconds); // seconds, not ms
            }
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            ctx.sendBroadcast(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

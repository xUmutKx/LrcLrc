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
     * Uses the official Poweramp API broadcast (cmd=20, OPEN_TO_PLAY).
     * The file URI must be a file:// URI — content:// does NOT work for arbitrary paths.
     */
    public static void playAt(Context ctx, String audioFilePath, int seekSeconds) {
        if (!isInstalled(ctx)) {
            Toast.makeText(ctx, R.string.poweramp_not_installed, Toast.LENGTH_LONG).show();
            return;
        }

        // Poweramp OPEN_TO_PLAY requires a file:// URI pointing to the audio file
        Uri fileUri = Uri.fromFile(new java.io.File(audioFilePath));

        Intent intent = new Intent(ACTION_API_COMMAND);
        intent.setComponent(new ComponentName(PACKAGE, API_RECEIVER));
        intent.setData(fileUri);
        intent.putExtra(EXTRA_COMMAND, CMD_OPEN_TO_PLAY);
        if (seekSeconds >= 0) {
            // pos is in milliseconds for Poweramp
            intent.putExtra(EXTRA_POSITION, seekSeconds * 1000);
        }
        // addFlags so it works even from background
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        ctx.sendBroadcast(intent);
    }
}

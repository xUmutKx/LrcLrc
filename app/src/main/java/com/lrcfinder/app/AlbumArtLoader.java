package com.lrcfinder.app;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads album art from MediaStore by matching audio file path.
 * Caches bitmaps in memory. Falls back to placeholder if not found.
 */
public class AlbumArtLoader {

    private static final AlbumArtLoader INSTANCE = new AlbumArtLoader();
    public static AlbumArtLoader getInstance() { return INSTANCE; }

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Map<String, Bitmap> cache = new HashMap<>();
    private final Map<String, Long> albumIdCache = new HashMap<>();

    private AlbumArtLoader() {}

    public void load(Context ctx, String audioPath, ImageView target) {
        if (audioPath == null) return;
        target.setTag(audioPath);

        if (cache.containsKey(audioPath)) {
            Bitmap bmp = cache.get(audioPath);
            if (bmp != null) target.setImageBitmap(bmp);
            else target.setImageResource(R.drawable.ic_music_note);
            return;
        }

        executor.execute(() -> {
            Bitmap bmp = loadFromMediaStore(ctx, audioPath);
            cache.put(audioPath, bmp);
            if (target.getTag() != null && target.getTag().equals(audioPath)) {
                target.post(() -> {
                    if (target.getTag() != null && target.getTag().equals(audioPath)) {
                        if (bmp != null) target.setImageBitmap(bmp);
                        else target.setImageResource(R.drawable.ic_music_note);
                    }
                });
            }
        });
    }

    private Bitmap loadFromMediaStore(Context ctx, String audioPath) {
        try {
            // 1. Get album ID from MediaStore by file path
            Long albumId = albumIdCache.get(audioPath);
            if (albumId == null) {
                Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                String[] proj = {MediaStore.Audio.Media.ALBUM_ID};
                String sel = MediaStore.Audio.Media.DATA + "=?";
                try (Cursor c = ctx.getContentResolver().query(uri, proj, sel,
                        new String[]{audioPath}, null)) {
                    if (c != null && c.moveToFirst()) {
                        albumId = c.getLong(0);
                        albumIdCache.put(audioPath, albumId);
                    }
                }
            }
            if (albumId == null) return null;

            // 2. Load album art from MediaStore
            Uri artUri = Uri.parse("content://media/external/audio/albumart/" + albumId);
            try (InputStream in = ctx.getContentResolver().openInputStream(artUri)) {
                if (in == null) return null;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2; // 1/4 size — enough for a 52dp thumbnail
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception e) {
            return null;
        }
    }

    public void clearCache() {
        cache.clear();
        albumIdCache.clear();
    }
}

package com.umutk.lrclrc;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads album art from MediaStore by matching audio file path.
 * Caches bitmaps in memory. Falls back to placeholder if not found.
 *
 * Thread-safety note: this is hit from multiple RecyclerView bind calls on the UI
 * thread while background loader threads write into the same cache. The previous
 * version used a plain HashMap for both, which is not thread-safe under concurrent
 * read/write and was the real cause of "wrong album art on some songs" - fast
 * scrolling could recycle an ImageView onto a different song while its old art
 * request was still in flight, and a HashMap read/write race could also hand back
 * a stale or corrupted cache entry. Fixed by using ConcurrentHashMap and by
 * re-checking the view's tag immediately before both the cache write and the
 * actual bitmap assignment.
 */
public class AlbumArtLoader {

    private static final AlbumArtLoader INSTANCE = new AlbumArtLoader();
    public static AlbumArtLoader getInstance() { return INSTANCE; }

    private static final Object NO_ART = new Object(); // sentinel for "looked up, nothing found"

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> albumIdCache = new ConcurrentHashMap<>();

    private AlbumArtLoader() {}

    public void load(Context ctx, String audioPath, ImageView target) {
        if (audioPath == null) {
            target.setTag(null);
            target.setImageResource(R.drawable.ic_music_note);
            return;
        }
        target.setTag(audioPath);

        Object cached = cache.get(audioPath);
        if (cached != null) {
            if (cached instanceof Bitmap) target.setImageBitmap((Bitmap) cached);
            else target.setImageResource(R.drawable.ic_music_note);
            return;
        }

        // Not cached yet: show a placeholder immediately so a recycled view never
        // keeps showing a previous song's art while the real lookup runs.
        target.setImageResource(R.drawable.ic_music_note);

        executor.execute(() -> {
            Bitmap bmp = loadFromMediaStore(ctx, audioPath);
            cache.put(audioPath, bmp != null ? bmp : NO_ART);
            target.post(() -> {
                // Re-check the tag on the UI thread right before assigning - if this
                // ImageView has since been recycled onto a different song, audioPath
                // here will no longer match its current tag, so we just skip the update.
                Object currentTag = target.getTag();
                if (audioPath.equals(currentTag)) {
                    if (bmp != null) target.setImageBitmap(bmp);
                    else target.setImageResource(R.drawable.ic_music_note);
                }
            });
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

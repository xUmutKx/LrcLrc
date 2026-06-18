package com.umutk.lrclrc;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Singleton repository.
 *
 * Key behaviour:
 *  - reindex() runs in a background thread AND immediately enables streaming
 *    search against the partial index that is already built.
 *  - search() never blocks the caller; it runs on a dedicated search executor
 *    and calls back on whatever thread the caller provides (caller must post
 *    to UI thread if needed).
 *  - Both operations are safe to call concurrently.
 */
public class LyricsRepository {

    private static final LyricsRepository INSTANCE = new LyricsRepository();
    public static LyricsRepository getInstance() { return INSTANCE; }

    private static final String[] AUDIO_EXT = {
            ".mp3", ".flac", ".m4a", ".ogg", ".opus", ".wav", ".aac", ".wma"
    };
    private static final Pattern RE_TAG       = Pattern.compile("^\\s*\\[[a-zA-Z]+:[^\\]]*]\\s*$");
    private static final Pattern RE_ARTIST_TAG = Pattern.compile("^\\s*\\[ar:([^\\]]*)]\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})[.:](\\d{2})]");

    // ── Index state ──────────────────────────────────────────────────────────

    /** Guarded by 'this'. Grows as indexing proceeds. */
    private final List<Song> liveIndex = new ArrayList<>();
    private final AtomicBoolean indexing = new AtomicBoolean(false);
    private String indexedDir = null;
    private int totalFilesEstimate = 0;
    private int filesScanned = 0;

    // ── Executors ────────────────────────────────────────────────────────────

    /** Single-thread for indexing to avoid disk thrashing. */
    private final ExecutorService indexExecutor  = Executors.newSingleThreadExecutor();
    /** Separate pool for search so queries run while indexing continues. */
    private final ExecutorService searchExecutor = Executors.newFixedThreadPool(2);

    // ── Public interfaces ────────────────────────────────────────────────────

    public interface IndexCallback {
        /** Fired immediately if a saved index was found on disk, before any rescan happens. */
        void onCacheLoaded(int songCount);
        /** Called on the index thread, periodically during a full/incremental scan. */
        void onProgress(int scanned, int total, int songsFound);
        /** Called when indexing is fully complete. */
        void onIndexed(int songCount, String dir);
        void onError(String message);
    }

    public interface SearchCallback {
        void onResults(List<Song> results, int totalMatches, long elapsedMs);
    }

    // ── Data classes ─────────────────────────────────────────────────────────

    public static class LrcLine implements Serializable {
        public final int lineNumber;
        public final String text;
        public final int seekSeconds;
        LrcLine(int n, String t, int s) { lineNumber = n; text = t; seekSeconds = s; }
    }

    public static class Song implements Serializable {
        public final String lrcPath, audioPath, title, folder, artist;
        public final List<LrcLine> lines;
        /** lrc file's lastModified() at the time it was parsed - used to detect changes on disk. */
        public final long lrcModified;
        public transient int hits = 0;
        public transient List<DisplayLine> displayLines = new ArrayList<>();
        Song(String lp, String ap, String t, String f, String artist, List<LrcLine> l, long mtime) {
            lrcPath = lp; audioPath = ap; title = t; folder = f; this.artist = artist; lines = l; lrcModified = mtime;
        }
    }

    public static class DisplayLine {
        public final LrcLine line;
        public final boolean isMatch;
        public final int matchStart, matchLen;
        DisplayLine(LrcLine l, boolean m, int ms, int ml) {
            line = l; isMatch = m; matchStart = ms; matchLen = ml;
        }
    }

    /** On-disk cache payload: the full song index for one directory. */
    private static class CacheData implements Serializable {
        private static final long serialVersionUID = 3L; // bumped: Song gained an 'artist' field
        String dir;
        ArrayList<Song> songs;
    }

    private LyricsRepository() {}

    // ── Accessors ────────────────────────────────────────────────────────────

    public boolean isIndexing()   { return indexing.get(); }
    public String getIndexedDir() { return indexedDir; }

    public int getIndexedCount() {
        synchronized (this) { return liveIndex.size(); }
    }

    public List<Song> getAllSongs() {
        List<Song> copy;
        synchronized (this) { copy = new ArrayList<>(liveIndex); }
        Collections.sort(copy, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return copy;
    }

    // ── Indexing ─────────────────────────────────────────────────────────────

    private static final String CACHE_FILE = "lrcfinder_index_cache.ser";

    public void reindex(Context ctx, String rootDir, IndexCallback cb) {
        if (indexing.getAndSet(true)) return; // already running
        indexExecutor.execute(() -> {
            DebugLog.d(ctx, "Index", "reindex() started for dir=" + rootDir);
            boolean cacheHit = false;
            CacheData cached = loadCache(ctx, rootDir);
            if (cached != null && cached.songs != null) {
                synchronized (this) {
                    liveIndex.clear();
                    liveIndex.addAll(cached.songs);
                }
                indexedDir = rootDir;
                cacheHit = true;
                DebugLog.d(ctx, "Index", "cache hit: " + cached.songs.size() + " songs loaded from disk cache");
                cb.onCacheLoaded(cached.songs.size());
            }

            try {
                File root = new File(rootDir);
                if (!root.isDirectory()) throw new IOException("Not a directory: " + rootDir);

                if (!cacheHit) {
                    // First time we've ever indexed this folder: full slow parse of every file.
                    synchronized (this) { liveIndex.clear(); }
                    filesScanned = 0;
                    totalFilesEstimate = Math.max(1, estimateLrcCount(root));
                    DebugLog.d(ctx, "Index", "no cache - full scan, estimated .lrc files=" + totalFilesEstimate);
                    scanDir(root, cb);
                } else {
                    // We already have a cached index: only re-parse files that are new or
                    // whose mtime changed, and drop ones that were deleted. Much faster.
                    incrementalScan(root, cb);
                }

                indexedDir = rootDir;
                indexing.set(false);
                int count;
                synchronized (this) { count = liveIndex.size(); }
                saveCache(ctx, rootDir);
                DebugLog.d(ctx, "Index", "reindex() finished: " + count + " songs indexed");
                cb.onIndexed(count, rootDir);
            } catch (Exception e) {
                indexing.set(false);
                DebugLog.e(ctx, "Index", "reindex() failed", e);
                cb.onError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
    }

    private void incrementalScan(File root, IndexCallback cb) {
        Map<String, Song> existingByPath = new HashMap<>();
        synchronized (this) {
            for (Song s : liveIndex) existingByPath.put(s.lrcPath, s);
        }
        List<Song> rebuilt = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        filesScanned = 0;
        totalFilesEstimate = Math.max(1, existingByPath.size());
        incrementalScanDir(root, existingByPath, rebuilt, seen, cb);
        synchronized (this) {
            liveIndex.clear();
            liveIndex.addAll(rebuilt);
        }
    }

    private void incrementalScanDir(File dir, Map<String, Song> existingByPath,
                                     List<Song> rebuilt, Set<String> seen, IndexCallback cb) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) incrementalScanDir(f, existingByPath, rebuilt, seen, cb);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".lrc")) {
                String path = f.getAbsolutePath();
                seen.add(path);
                Song old = existingByPath.get(path);
                Song s = (old != null && old.lrcModified == f.lastModified())
                        ? old              // unchanged - reuse cached entry, skip re-parsing
                        : parseLrcFile(f); // new or modified - parse fresh
                if (s != null) rebuilt.add(s);
                filesScanned++;
                if (filesScanned % 50 == 0) {
                    cb.onProgress(filesScanned, totalFilesEstimate, rebuilt.size());
                }
            }
        }
    }

    private CacheData loadCache(Context ctx, String dir) {
        File f = new File(ctx.getFilesDir(), CACHE_FILE);
        if (!f.exists()) return null;
        try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            Object obj = in.readObject();
            if (obj instanceof CacheData) {
                CacheData data = (CacheData) obj;
                if (dir.equals(data.dir)) return data;
            }
        } catch (Exception ignored) {
            // Corrupt or incompatible cache (e.g. after an app update) - just fall back to a full scan.
        }
        return null;
    }

    private void saveCache(Context ctx, String dir) {
        List<Song> snap;
        synchronized (this) { snap = new ArrayList<>(liveIndex); }
        CacheData data = new CacheData();
        data.dir = dir;
        data.songs = new ArrayList<>(snap);
        File f = new File(ctx.getFilesDir(), CACHE_FILE);
        try (ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
            out.writeObject(data);
        } catch (Exception ignored) {
            // Non-fatal: worst case, next launch just does a full rescan again.
        }
    }

    public void clearCache(Context ctx) {
        new File(ctx.getFilesDir(), CACHE_FILE).delete();
    }

    private int estimateLrcCount(File dir) {
        int count = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File f : children) {
            if (f.isDirectory() && !f.getName().startsWith(".")) count += estimateLrcCount(f);
            else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".lrc")) count++;
        }
        return count;
    }

    private void scanDir(File dir, IndexCallback cb) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) scanDir(f, cb);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".lrc")) {
                Song s = parseLrcFile(f);
                int scanned, total, found;
                synchronized (this) {
                    filesScanned++;
                    if (s != null) liveIndex.add(s);
                    scanned = filesScanned;
                    total   = totalFilesEstimate;
                    found   = liveIndex.size();
                }
                // Report progress every 20 files
                if (scanned % 20 == 0 || scanned == total) {
                    cb.onProgress(scanned, total, found);
                }
            }
        }
    }

    private Song parseLrcFile(File f) {
        List<LrcLine> lines = new ArrayList<>();
        String artistTag = null;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String raw; int no = 0;
            while ((raw = br.readLine()) != null) {
                no++;
                Matcher am = RE_ARTIST_TAG.matcher(raw);
                if (am.matches()) {
                    String val = am.group(1).trim();
                    if (!val.isEmpty() && artistTag == null) artistTag = val;
                    continue;
                }
                if (RE_TAG.matcher(raw).matches()) continue;
                int seek = -1;
                Matcher tm = RE_TIMESTAMP.matcher(raw);
                if (tm.find()) {
                    seek = Integer.parseInt(tm.group(1)) * 60 + Integer.parseInt(tm.group(2));
                }
                String stripped = RE_TIMESTAMP.matcher(raw).replaceAll("").trim();
                if (!stripped.isEmpty()) lines.add(new LrcLine(no, stripped, seek));
            }
        } catch (IOException e) { return null; }
        if (lines.isEmpty()) return null;

        String base  = f.getName();
        int dot      = base.lastIndexOf('.');
        String noExt = dot >= 0 ? base.substring(0, dot) : base;
        String audio  = findAudio(f.getParentFile(), noExt);
        String folder = f.getParentFile() != null ? f.getParentFile().getName() : "";
        String artist = (artistTag != null && !artistTag.isEmpty()) ? artistTag : folder;
        return new Song(f.getAbsolutePath(), audio, noExt, folder, artist, lines, f.lastModified());
    }

    private String findAudio(File dir, String base) {
        if (dir == null) return null;
        for (String ext : AUDIO_EXT) {
            File c = new File(dir, base + ext);
            if (c.isFile()) return c.getAbsolutePath();
        }
        File[] ch = dir.listFiles();
        if (ch == null) return null;
        for (File c : ch) {
            String name = c.getName();
            int dot = name.lastIndexOf('.');
            if (dot < 0) continue;
            String nb  = name.substring(0, dot);
            String ext = name.substring(dot).toLowerCase(Locale.ROOT);
            if (nb.equalsIgnoreCase(base)) {
                for (String a : AUDIO_EXT) if (a.equals(ext)) return c.getAbsolutePath();
            }
        }
        return null;
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /** Turkish-aware lowercase: I→ı  İ→i */
    public static String trLower(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if      (c == 'I') sb.append('ı');
            else if (c == 'İ') sb.append('i');
            else sb.append(c);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Splits a query into search terms. A quoted segment ("like this") is kept as a
     * single multi-word term (old "exact phrase" behaviour); anything outside quotes
     * is split on whitespace into separate terms. Each term is then matched
     * independently anywhere in the song (see search() below) so e.g. typing
     * "love rain" finds a song that has "love" in one line and "rain" in a totally
     * different, far-away line - the terms don't need to be adjacent or close.
     */
    private static List<String> splitTerms(String phrase) {
        List<String> terms = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]+)\"|(\\S+)").matcher(phrase);
        while (m.find()) {
            String quoted = m.group(1);
            String bare   = m.group(2);
            String term = quoted != null ? quoted : bare;
            if (term != null) {
                term = term.trim();
                if (!term.isEmpty()) terms.add(term);
            }
        }
        return terms;
    }

    /**
     * Searches the current (possibly partial) live index without blocking.
     * Callback fires on the search thread — post to UI thread yourself.
     *
     * Multi-word queries use AND-across-the-whole-song matching: every term in the
     * query must appear *somewhere* in the song's lyrics, but the terms don't have to
     * be on the same line or even nearby - e.g. "love rain" matches a song where
     * "love" appears in the first verse and "rain" only shows up in the bridge.
     * Each matched term's own occurrences (with their own context lines) are shown.
     */
    public void search(String phrase, Prefs prefs, SearchCallback cb) {
        searchExecutor.execute(() -> {
            long t0 = System.currentTimeMillis();

            // Snapshot the live index atomically
            List<Song> snap;
            synchronized (this) { snap = new ArrayList<>(liveIndex); }

            List<String> rawTerms = splitTerms(phrase);
            List<Song> results    = new ArrayList<>();
            int total = 0, lines  = 0;
            int maxR  = prefs.getMaxResults();
            int ctx   = prefs.getContextLines();
            boolean ci = prefs.isCaseInsensitive();
            boolean ww = prefs.isWholeWord();

            List<String> needles = new ArrayList<>();
            for (String t : rawTerms) needles.add(ci ? trLower(t) : t);

            if (needles.isEmpty()) {
                cb.onResults(results, 0, System.currentTimeMillis() - t0);
                return;
            }

            outer:
            for (Song song : snap) {
                // For each term, collect the line indices where it appears in this song.
                List<List<Integer>> perTermHits = new ArrayList<>();
                boolean allTermsFound = true;
                for (String needle : needles) {
                    List<Integer> idxs = new ArrayList<>();
                    for (int i = 0; i < song.lines.size(); i++) {
                        String hay = ci ? trLower(song.lines.get(i).text) : song.lines.get(i).text;
                        if (containsMatch(hay, needle, ww)) idxs.add(i);
                    }
                    if (idxs.isEmpty()) { allTermsFound = false; break; }
                    perTermHits.add(idxs);
                }
                // AND semantics: skip the song unless every term was found somewhere in it.
                if (!allTermsFound) continue;

                // Map each hit line back to which needle matched it (for highlighting),
                // and total hit count across all terms.
                Map<Integer, String> lineToNeedle = new HashMap<>();
                int hitCount = 0;
                for (int t = 0; t < needles.size(); t++) {
                    for (int idx : perTermHits.get(t)) {
                        lineToNeedle.putIfAbsent(idx, needles.get(t));
                        hitCount++;
                    }
                }

                Song r = new Song(song.lrcPath, song.audioPath, song.title, song.folder, song.artist, song.lines, song.lrcModified);
                r.hits = hitCount;
                total += hitCount;

                Set<Integer> hitIdxs = lineToNeedle.keySet();
                Set<Integer> incl = new TreeSet<>();
                for (int idx : hitIdxs)
                    for (int k = Math.max(0, idx - ctx); k <= Math.min(song.lines.size()-1, idx + ctx); k++)
                        incl.add(k);

                for (int idx : incl) {
                    LrcLine line = song.lines.get(idx);
                    boolean isMat = hitIdxs.contains(idx);
                    int ms = -1, ml = 0;
                    if (isMat) {
                        String needle = lineToNeedle.get(idx);
                        String hay = ci ? trLower(line.text) : line.text;
                        ms = hay.indexOf(needle);
                        ml = needle.length();
                    }
                    r.displayLines.add(new DisplayLine(line, isMat, ms, ml));
                    lines++;
                }
                results.add(r);
                if (lines >= maxR) break outer;
            }

            if (prefs.isSortByHits()) results.sort((a, b) -> Integer.compare(b.hits, a.hits));
            else results.sort(Comparator.comparing(s -> s.title.toLowerCase(Locale.ROOT)));

            long elapsed = System.currentTimeMillis() - t0;
            cb.onResults(results, total, elapsed);
        });
    }

    private boolean containsMatch(String hay, String needle, boolean ww) {
        if (needle.isEmpty()) return false;
        if (!ww) return hay.contains(needle);
        int from = 0;
        while (true) {
            int idx = hay.indexOf(needle, from);
            if (idx < 0) return false;
            boolean l = idx == 0 || !Character.isLetterOrDigit(hay.charAt(idx - 1));
            int e = idx + needle.length();
            boolean r = e >= hay.length() || !Character.isLetterOrDigit(hay.charAt(e));
            if (l && r) return true;
            from = idx + 1;
        }
    }
}

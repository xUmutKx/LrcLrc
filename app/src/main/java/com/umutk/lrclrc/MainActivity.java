package com.umutk.lrclrc;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends BaseActivity {

    private static final int    REQ_LEGACY_STORAGE = 1001;
    private static final long   SEARCH_DEBOUNCE_MS = 250;
    private static final String CURRENT_VERSION    = "2.4";

    private DrawerLayout drawerLayout;
    private TextInputEditText searchEditText;
    private TextView statusText, emptyStateText, indexingLabel;
    private RecyclerView resultsRecyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private View indexingOverlay;
    private CircularProgressIndicator indexingProgress;

    private ResultsAdapter resultsAdapter;
    private BrowseAdapter  browseAdapter;

    private final Handler  handler      = new Handler(Looper.getMainLooper());
    private Runnable       pendingSearch;
    private boolean        isShowingBrowse = false;
    /** Periodically updates the browse list while indexing in the background */
    private final Runnable browseRefresher = new Runnable() {
        @Override public void run() {
            if (isShowingBrowse) refreshBrowseList();
            if (LyricsRepository.getInstance().isIndexing())
                handler.postDelayed(this, 1000);
        }
    };

    private int     appliedThemeMode;
    private boolean appliedAmoled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        appliedThemeMode = prefs.getThemeMode();
        appliedAmoled    = prefs.isAmoled();

        bindViews();
        setupDrawer();
        setupSearch();
        setupAdapters();

        maybeShowWhatsNew();
        ensureIndexed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedThemeMode != prefs.getThemeMode() || appliedAmoled != prefs.isAmoled()) {
            recreate(); return;
        }
        LyricsRepository repo = LyricsRepository.getInstance();
        if (hasStorageAccess()
                && !repo.isIndexing()
                && (repo.getIndexedDir() == null
                    || !prefs.getSearchDir().equals(repo.getIndexedDir()))) {
            reindex();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(browseRefresher);
        handler.removeCallbacks(pendingSearch != null ? pendingSearch : () -> {});
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private void bindViews() {
        drawerLayout        = findViewById(R.id.drawerLayout);
        searchEditText      = findViewById(R.id.searchEditText);
        statusText          = findViewById(R.id.statusText);
        emptyStateText      = findViewById(R.id.emptyStateText);
        resultsRecyclerView = findViewById(R.id.resultsRecyclerView);
        swipeRefresh        = findViewById(R.id.swipeRefresh);
        indexingOverlay     = findViewById(R.id.indexingOverlay);
        indexingProgress    = findViewById(R.id.indexingProgress);
        indexingLabel       = findViewById(R.id.indexingLabel);
    }

    private void setupDrawer() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        applyTopInset(toolbar);
        toolbar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        NavigationView navView = findViewById(R.id.navigationView);
        navView.setNavigationItemSelectedListener(item -> {
            drawerLayout.closeDrawers();
            int id = item.getItemId();
            if (id == R.id.nav_search) {
                searchEditText.requestFocus();
                return true;
            }
            if (id == R.id.nav_help)     { showSearchHelp(); return true; }
            if (id == R.id.nav_history)  { showHistory();  return true; }
            if (id == R.id.nav_reindex)  { reindex();      return true; }
            if (id == R.id.nav_settings) {
                startActivity(new Intent(this, SettingsActivity.class)); return true; }
            return false;
        });

        swipeRefresh.setOnRefreshListener(this::reindex);
        emptyStateText.setOnClickListener(v -> requestStorageAccessIfNeeded());
    }

    private void setupAdapters() {
        resultsAdapter = new ResultsAdapter(this, (song, seekSeconds) -> playSong(song, seekSeconds));
        browseAdapter = new BrowseAdapter(song -> playSong(song, -1), this::showLyrics);
        resultsRecyclerView.setLayoutManager(new GridLayoutManager(this, browseColumnCount()));
        resultsRecyclerView.setLayoutAnimation(android.view.animation.AnimationUtils.loadLayoutAnimation(this, R.anim.layout_in));
        resultsRecyclerView.setAdapter(browseAdapter);
        isShowingBrowse = true;
    }

    /**
     * Album art tiles were the same fixed width in portrait and landscape, which made
     * them look oversized once rotated (more screen width but the same 2 columns).
     * Use more columns in landscape so each cover art tile shrinks back down.
     */
    private int browseColumnCount() {
        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        return landscape ? prefs.getGridColsLandscape() : prefs.getGridColsPortrait();
    }

    /** Shared by the search-results play button, the clickable lyric line, and the browse grid tile. */
    private void playSong(LyricsRepository.Song song, int seekSeconds) {
        if (song.audioPath == null) {
            Toast.makeText(this, R.string.audio_file_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        DebugLog.d(this, "Play", "playSong: " + song.title + " seekSeconds=" + seekSeconds);
        if (MusicPlayers.play(this, prefs.getMusicPackage(), song.audioPath, seekSeconds)) return;
        openWithChooser(song.audioPath);
    }

    /** Browse list: tapping a cover shows the whole lyrics of that song. */
    private void showLyrics(LyricsRepository.Song song) {
        StringBuilder sb = new StringBuilder();
        for (LyricsRepository.LrcLine l : song.lines) sb.append(l.text).append('\n');
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(sb.toString().trim());
        tv.setTextSize(16);
        tv.setTextIsSelectable(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad / 2, pad, pad / 2);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new MaterialAlertDialogBuilder(this)
                .setTitle(song.title)
                .setView(sv)
                .setPositiveButton(R.string.lyrics_play, (d, w) -> playSong(song, -1))
                .setNegativeButton(R.string.lyrics_close, null)
                .show();
    }

    private void openWithChooser(String audioPath) {
        try {
            java.io.File file = new java.io.File(audioPath);
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, "audio/*");
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(view, getString(R.string.play_choose_app));
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
        } catch (Exception e) {
            DebugLog.e(this, "Play", "openWithChooser failed for " + audioPath, e);
            Toast.makeText(this, R.string.could_not_open_file, Toast.LENGTH_SHORT).show();
        }
    }

    private void showSearchHelp() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.help_title)
                .setMessage(R.string.help_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void setupSearch() {
        com.google.android.material.textfield.TextInputLayout sl = findViewById(R.id.searchLayout);
        sl.setHelperText(getString(R.string.hint_search_rules));
        sl.setHelperTextEnabled(true);
        statusText.setOnClickListener(v -> showSearchHelp());
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                scheduleSearch(s.toString().trim());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        searchEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String q = searchEditText.getText() != null
                        ? searchEditText.getText().toString().trim() : "";
                if (!q.isEmpty()) { prefs.addHistory(q); performSearch(q); }
                return true;
            }
            return false;
        });
    }

    // ── What's New dialog ─────────────────────────────────────────────────────

    private void maybeShowWhatsNew() {
        String lastSeen = prefs.getLastSeenVersion();
        if (CURRENT_VERSION.equals(lastSeen)) return;
        prefs.setLastSeenVersion(CURRENT_VERSION);

        String notes =
            "v2.4  —  What's new\n\n" +
            "• Tap a cover in your library to read the song's lyrics\n\n" +
            "v2.3\n" +
            "• Phrase search: plain words = consecutive words (\"seni seviyorum\"), even across line breaks\n" +
            "• Comma = AND: \"aşk, yağmur\" finds songs containing both anywhere in the lyrics\n" +
            "• Words shorter than 3 letters are ignored (live hint while typing)\n" +
            "• Matched parts highlighted in different colors\n" +
            "• Rules are in the menu: Search rules\n" +
            "• Lyrics with no matching audio file are hidden from results\n" +
            "• English by default; Turkish is a switch in Settings\n\n" +
            "v2.0\n" +
            "• Streaming index: search while library is still loading\n" +
            "• Browse list: all songs with album art shown before search\n" +
            "• Navigation drawer (hamburger menu)\n" +
            "• AMOLED pure-black theme\n" +
            "• Poweramp integration fixed (cmd=20, file:// URI, ms seek)\n" +
            "• Native folder picker in Settings\n" +
            "• Theme changes apply instantly without restart\n" +
            "• Turkish I/İ/ı/i case-insensitive search\n" +
            "• Album art from MediaStore";

        new MaterialAlertDialogBuilder(this)
                .setTitle("LrcLrc " + CURRENT_VERSION)
                .setMessage(notes)
                .setPositiveButton("Got it", null)
                .show();
    }

    private void showHistory() {
        List<String> history = new ArrayList<>(prefs.getHistory());
        Collections.reverse(history);
        if (history.isEmpty()) {
            Toast.makeText(this, R.string.history_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = history.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.history_title)
                .setItems(items, (dialog, which) -> {
                    searchEditText.setText(items[which]);
                    performSearch(items[which]);
                })
                .setNegativeButton(R.string.clear_history, (d, w) -> prefs.clearHistory())
                .show();
    }

    // ── Storage permission ────────────────────────────────────────────────────

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            return Environment.isExternalStorageManager();
        return ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccessIfNeeded() {
        if (hasStorageAccess()) { reindex(); return; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQ_LEGACY_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_LEGACY_STORAGE && hasStorageAccess()) reindex();
    }

    private void ensureIndexed() {
        if (hasStorageAccess()) { reindex(); return; }
        statusText.setText(R.string.status_need_permission);
        emptyStateText.setText(R.string.grant_access);
        emptyStateText.setVisibility(View.VISIBLE);
    }

    // ── Indexing ──────────────────────────────────────────────────────────────

    private boolean cacheHitThisRun = false;

    private void reindex() {
        cacheHitThisRun = false;
        swipeRefresh.setRefreshing(false);
        AlbumArtLoader.getInstance().clearCache();

        LyricsRepository.getInstance().reindex(this, prefs.getSearchDir(),
            new LyricsRepository.IndexCallback() {

                @Override public void onCacheLoaded(int songCount) {
                    runOnUiThread(() -> {
                        cacheHitThisRun = true;
                        indexingOverlay.setVisibility(View.GONE);
                        statusText.setText(getString(R.string.status_indexed, songCount, prefs.getSearchDir())
                                + "  ·  " + getString(R.string.status_checking_updates));
                        if (isShowingBrowse) refreshBrowseList();
                    });
                }

                @Override public void onProgress(int scanned, int total, int songsFound) {
                    runOnUiThread(() -> {
                        String q = currentQuery();
                        boolean searching = !q.isEmpty();
                        if (!cacheHitThisRun && !searching) {
                            indexingOverlay.setVisibility(View.VISIBLE);
                            int pct = total > 0 ? Math.min(100, scanned * 100 / total) : 0;
                            indexingLabel.setText(getString(R.string.status_indexing_first_time)
                                    + "  " + pct + "%  (" + songsFound + " songs)");
                        } else {
                            // Don't let the overlay hide results that already exist for what's
                            // been indexed so far - searching should never feel like it's waiting.
                            indexingOverlay.setVisibility(View.GONE);
                        }
                        if (isShowingBrowse) refreshBrowseList();
                        if (searching) scheduleSearch(q);
                    });
                }

                @Override public void onIndexed(int count, String dir) {
                    runOnUiThread(() -> {
                        indexingOverlay.setVisibility(View.GONE);
                        swipeRefresh.setRefreshing(false);
                        statusText.setText(getString(R.string.status_indexed, count, dir));
                        handler.removeCallbacks(browseRefresher);
                        String q = currentQuery();
                        if (q.isEmpty()) refreshBrowseList();
                        else performSearch(q);
                    });
                }

                @Override public void onError(String msg) {
                    runOnUiThread(() -> {
                        indexingOverlay.setVisibility(View.GONE);
                        swipeRefresh.setRefreshing(false);
                        statusText.setText(msg);
                    });
                }
            });

        // Kick off periodic browse refresh while a full first-time scan is running
        handler.postDelayed(browseRefresher, 1500);
    }

    private String currentQuery() {
        return searchEditText.getText() != null ? searchEditText.getText().toString().trim() : "";
    }

    private void refreshBrowseList() {
        List<LyricsRepository.Song> all = LyricsRepository.getInstance().getAllSongs();
        browseAdapter.submit(all);
        if (!isShowingBrowse) {
            resultsRecyclerView.setLayoutManager(new GridLayoutManager(this, browseColumnCount()));
            resultsRecyclerView.setAdapter(browseAdapter);
            resultsRecyclerView.scheduleLayoutAnimation();
            isShowingBrowse = true;
        }
        emptyStateText.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        if (all.isEmpty()) emptyStateText.setText(R.string.grant_access);
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private void scheduleSearch(String query) {
        if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
        if (query.isEmpty()) {
            refreshBrowseList();
            statusText.setText(LyricsRepository.getInstance().isIndexing()
                    ? getString(R.string.status_indexing) : getString(R.string.hint_search_rules));
            return;
        }
        SearchLogic.Query pq = SearchLogic.parse(query, prefs.isCaseInsensitive());
        if (pq.isEmpty()) {
            // Nothing searchable yet (e.g. only 1-2 letter words): show a hint instead of searching.
            statusText.setText(R.string.hint_min_letters);
            return;
        }
        pendingSearch = () -> performSearch(query);
        handler.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
    }

    private void performSearch(String query) {
        if (query.isEmpty()) { refreshBrowseList(); return; }
        final SearchLogic.Query pq = SearchLogic.parse(query, prefs.isCaseInsensitive());
        if (pq.isEmpty()) { statusText.setText(R.string.hint_min_letters); return; }
        final String ignoredNote = pq.ignored.isEmpty() ? ""
                : "  ·  " + getString(R.string.hint_ignored, android.text.TextUtils.join(", ", pq.ignored));

        if (isShowingBrowse) {
            resultsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
            resultsRecyclerView.setAdapter(resultsAdapter);
            isShowingBrowse = false;
        }

        LyricsRepository.getInstance().search(query, prefs,
            (results, totalMatches, elapsed) -> runOnUiThread(() -> {
                resultsAdapter.submit(results);
                resultsRecyclerView.scheduleLayoutAnimation();
                if (results.isEmpty()) {
                    String suffix = LyricsRepository.getInstance().isIndexing()
                            ? " (still indexing…)" : "";
                    statusText.setText(getString(R.string.status_no_results) + suffix + ignoredNote);
                    emptyStateText.setText(R.string.status_no_results);
                    emptyStateText.setVisibility(View.VISIBLE);
                } else {
                    statusText.setText(getString(R.string.status_results,
                            totalMatches, results.size(), elapsed) + ignoredNote);
                    emptyStateText.setVisibility(View.GONE);
                }
            }));
    }
}

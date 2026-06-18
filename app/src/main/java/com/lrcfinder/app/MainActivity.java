package com.lrcfinder.app;

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
import android.view.MenuItem;
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
    private static final String CURRENT_VERSION    = "2.0";

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
        toolbar.setOnMenuItemClickListener(this::onMenuItemClick);
        toolbar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        NavigationView navView = findViewById(R.id.navigationView);
        navView.setNavigationItemSelectedListener(item -> {
            drawerLayout.closeDrawers();
            int id = item.getItemId();
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
        resultsAdapter = new ResultsAdapter(this, (song, seekSeconds) -> {
            if (song.audioPath == null) {
                Toast.makeText(this, R.string.audio_file_missing, Toast.LENGTH_SHORT).show();
                return;
            }
            PowerampHelper.playAt(this, song.audioPath, seekSeconds);
        });
        browseAdapter = new BrowseAdapter(song -> {
            if (song.audioPath != null) PowerampHelper.playAt(this, song.audioPath, -1);
        });
        resultsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        resultsRecyclerView.setAdapter(browseAdapter);
        isShowingBrowse = true;
    }

    private void setupSearch() {
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
            "v2.0  —  What's new\n\n" +
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

    private boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_history)  { showHistory(); return true; }
        if (id == R.id.action_reindex)  { reindex();     return true; }
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class)); return true; }
        return false;
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

    private void reindex() {
        indexingOverlay.setVisibility(View.VISIBLE);
        swipeRefresh.setRefreshing(false);
        statusText.setText(R.string.status_indexing);
        AlbumArtLoader.getInstance().clearCache();

        LyricsRepository.getInstance().reindex(prefs.getSearchDir(),
            new LyricsRepository.IndexCallback() {

                @Override public void onProgress(int scanned, int total, int songsFound) {
                    runOnUiThread(() -> {
                        int pct = total > 0 ? (scanned * 100 / total) : 0;
                        indexingLabel.setText(getString(R.string.status_indexing)
                                + "  " + pct + "%  (" + songsFound + " songs)");
                        // Start showing browse list while still indexing
                        if (isShowingBrowse) refreshBrowseList();
                        // Start debounced search with current query if there is one
                        String q = searchEditText.getText() != null
                                ? searchEditText.getText().toString().trim() : "";
                        if (!q.isEmpty()) scheduleSearch(q);
                    });
                }

                @Override public void onIndexed(int count, String dir) {
                    runOnUiThread(() -> {
                        indexingOverlay.setVisibility(View.GONE);
                        swipeRefresh.setRefreshing(false);
                        statusText.setText(getString(R.string.status_indexed, count, dir));
                        handler.removeCallbacks(browseRefresher);
                        String q = searchEditText.getText() != null
                                ? searchEditText.getText().toString().trim() : "";
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

        // Kick off periodic browse refresh while indexing
        handler.postDelayed(browseRefresher, 1500);
    }

    private void refreshBrowseList() {
        List<LyricsRepository.Song> all = LyricsRepository.getInstance().getAllSongs();
        browseAdapter.submit(all);
        if (!isShowingBrowse) {
            resultsRecyclerView.setAdapter(browseAdapter);
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
                    ? getString(R.string.status_indexing) : "");
            return;
        }
        pendingSearch = () -> performSearch(query);
        handler.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
    }

    private void performSearch(String query) {
        if (query.isEmpty()) { refreshBrowseList(); return; }

        if (isShowingBrowse) {
            resultsRecyclerView.setAdapter(resultsAdapter);
            isShowingBrowse = false;
        }

        LyricsRepository.getInstance().search(query, prefs,
            (results, totalMatches, elapsed) -> runOnUiThread(() -> {
                resultsAdapter.submit(results);
                if (results.isEmpty()) {
                    String suffix = LyricsRepository.getInstance().isIndexing()
                            ? " (still indexing…)" : "";
                    statusText.setText(getString(R.string.status_no_results) + suffix);
                    emptyStateText.setText(R.string.status_no_results);
                    emptyStateText.setVisibility(View.VISIBLE);
                } else {
                    statusText.setText(getString(R.string.status_results,
                            totalMatches, results.size(), elapsed));
                    emptyStateText.setVisibility(View.GONE);
                }
            }));
    }
}

package com.umutk.lrclrc;

import com.umutk.lrclrc.BuildConfig;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;

public class SettingsActivity extends BaseActivity {

    // Easter egg: tap developer label 5 times to unlock debug controls
    private int  developerTapCount = 0;
    private long lastTapTime       = 0;

    private TextView currentDirText;
    private Slider   contextLinesSlider;
    private Slider   gridColsPortraitSlider, gridColsLandscapeSlider;

    private final ActivityResultLauncher<Uri> folderPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    String path = safUriToPath(uri);
                    if (path != null) {
                        prefs.setSearchDir(path);
                        updateDirDisplay();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.settingsToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        currentDirText             = findViewById(R.id.currentDirText);
        MaterialButton chooseFolderBtn = findViewById(R.id.chooseFolderButton);
        MaterialButton resetDirBtn     = findViewById(R.id.resetDirButton);

        MaterialSwitch ciSwitch        = findViewById(R.id.caseInsensitiveSwitch);
        MaterialSwitch wwSwitch        = findViewById(R.id.wholeWordSwitch);
        MaterialSwitch sortSwitch      = findViewById(R.id.sortByHitsSwitch);
        MaterialSwitch amoledSwitch    = findViewById(R.id.amoledSwitch);
        MaterialSwitch debugSwitch     = findViewById(R.id.debugLoggingSwitch);
        View           debugRow        = findViewById(R.id.debugLoggingRow);
        View           debugActionsRow = findViewById(R.id.debugLogActionsRow);

        contextLinesSlider         = findViewById(R.id.contextLinesSlider);
        gridColsPortraitSlider  = findViewById(R.id.gridColsPortraitSlider);
        gridColsLandscapeSlider = findViewById(R.id.gridColsLandscapeSlider);
        TextInputEditText maxResultsEdit = findViewById(R.id.maxResultsEditText);

        MaterialButtonToggleGroup themeGroup  = findViewById(R.id.themeToggleGroup);
        MaterialButton            themeSysBtn = findViewById(R.id.themeSystemButton);
        MaterialButton            themeLitBtn = findViewById(R.id.themeLightButton);
        MaterialButton            themeDrkBtn = findViewById(R.id.themeDarkButton);

        TextView developerLabel  = findViewById(R.id.developerLabel);
        MaterialButton githubBtn = findViewById(R.id.githubButton);

        MaterialButton shareLogBtn = findViewById(R.id.shareLogButton);
        MaterialButton clearLogBtn = findViewById(R.id.clearLogButton);

        // ── Init values ───────────────────────────────────────────────────
        updateDirDisplay();
        updateContextLabel();
        ciSwitch.setChecked(prefs.isCaseInsensitive());
        wwSwitch.setChecked(prefs.isWholeWord());
        sortSwitch.setChecked(prefs.isSortByHits());
        amoledSwitch.setChecked(prefs.isAmoled());
        maxResultsEdit.setText(String.valueOf(prefs.getMaxResults()));
        gridColsPortraitSlider.setValue(prefs.getGridColsPortrait());
        gridColsLandscapeSlider.setValue(prefs.getGridColsLandscape());
        updateGridLabels();

        // Debug logging row (switch + description) is hidden by default; only the
        // 5-tap easter egg on "Developer" reveals it. Previously only debugActionsRow
        // (the share/clear buttons) was hidden, so the switch itself always showed.
        boolean debugOn = prefs.isDebugLoggingEnabled();
        debugSwitch.setChecked(debugOn);
        debugRow.setVisibility(debugOn ? View.VISIBLE : View.GONE);
        debugActionsRow.setVisibility(debugOn ? View.VISIBLE : View.GONE);
        DebugLog.setEnabled(debugOn);

        switch (prefs.getThemeMode()) {
            case Prefs.THEME_LIGHT: themeGroup.check(themeLitBtn.getId()); break;
            case Prefs.THEME_DARK:  themeGroup.check(themeDrkBtn.getId()); break;
            default:                themeGroup.check(themeSysBtn.getId());
        }

        // ── Listeners ─────────────────────────────────────────────────────
        chooseFolderBtn.setOnClickListener(v ->
                folderPicker.launch(Uri.parse(
                        "content://com.android.externalstorage.documents/tree/primary%3A")));

        resetDirBtn.setOnClickListener(v -> {
            prefs.setSearchDir(Prefs.defaultDir());
            updateDirDisplay();
        });

        ciSwitch.setOnCheckedChangeListener((v, c) -> prefs.setCaseInsensitive(c));
        wwSwitch.setOnCheckedChangeListener((v, c) -> prefs.setWholeWord(c));
        sortSwitch.setOnCheckedChangeListener((v, c) -> prefs.setSortByHits(c));
        amoledSwitch.setOnCheckedChangeListener((v, c) -> {
            prefs.setAmoled(c);
            recreate();
        });

        contextLinesSlider.addOnChangeListener((s, val, fromUser) -> {
            prefs.setContextLines((int) val);
            updateContextLabel();
        });
        gridColsPortraitSlider.addOnChangeListener((s, val, fromUser) -> {
            prefs.setGridColsPortrait((int) val);
            updateGridLabels();
        });
        gridColsLandscapeSlider.addOnChangeListener((s, val, fromUser) -> {
            prefs.setGridColsLandscape((int) val);
            updateGridLabels();
        });

        maxResultsEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                try { prefs.setMaxResults(Integer.parseInt(s.toString().trim())); }
                catch (NumberFormatException ignored) {}
            }
        });

        themeGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            if      (id == themeLitBtn.getId()) prefs.setThemeMode(Prefs.THEME_LIGHT);
            else if (id == themeDrkBtn.getId()) prefs.setThemeMode(Prefs.THEME_DARK);
            else                                prefs.setThemeMode(Prefs.THEME_SYSTEM);
            recreate();
        });

        debugSwitch.setOnCheckedChangeListener((v, c) -> {
            prefs.setDebugLoggingEnabled(c);
            DebugLog.setEnabled(c);
            debugActionsRow.setVisibility(c ? View.VISIBLE : View.GONE);
            Toast.makeText(this, c ? "Debug logging ON" : "Debug logging OFF",
                    Toast.LENGTH_SHORT).show();
        });

        shareLogBtn.setOnClickListener(v -> shareLog());
        clearLogBtn.setOnClickListener(v -> {
            DebugLog.clear(this);
            Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show();
        });

        githubBtn.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(Prefs.GITHUB_URL))));

        // ── 5-tap easter egg on developer label ───────────────────────────
        developerLabel.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastTapTime > 2000) developerTapCount = 0;
            lastTapTime = now;
            developerTapCount++;

            int remaining = 5 - developerTapCount;
            if (remaining > 0 && remaining <= 3) {
                Toast.makeText(this, remaining + " more taps…", Toast.LENGTH_SHORT).show();
            }

            if (developerTapCount >= 5) {
                developerTapCount = 0;
                new MaterialAlertDialogBuilder(this)
                        .setTitle("LrcLrc  —  dev mode")
                        .setMessage(
                                "Version " + BuildConfig.VERSION_NAME
                                + "  (build " + BuildConfig.VERSION_CODE + ")\n\n"
                                + "Developer: xUmutKx\n"
                                + "github.com/xUmutKx/LrcLrc\n\n"
                                + "Debug logging unlocked below.\n"
                                + "Log file stays on your device only.")
                        .setPositiveButton("Got it", (d, w) -> {
                                debugRow.setVisibility(View.VISIBLE);
                                debugActionsRow.setVisibility(View.VISIBLE);
                        })
                        .setNegativeButton("Close", null)
                        .show();
            }
        });
    }

    private void updateDirDisplay() {
        currentDirText.setText(prefs.getSearchDir());
    }

    private void updateGridLabels() {
        TextView p = findViewById(R.id.gridColsPortraitLabel);
        TextView l = findViewById(R.id.gridColsLandscapeLabel);
        if (p != null) p.setText(getString(R.string.settings_grid_cols_portrait,
                (int) gridColsPortraitSlider.getValue()));
        if (l != null) l.setText(getString(R.string.settings_grid_cols_landscape,
                (int) gridColsLandscapeSlider.getValue()));
    }

    private void updateContextLabel() {
        TextView label = findViewById(R.id.contextLinesLabel);
        if (label != null) label.setText(
                getString(R.string.settings_context_lines, (int) contextLinesSlider.getValue()));
    }

    private void shareLog() {
        java.io.File logFile = DebugLog.getLogFile(this);
        if (!logFile.exists() || logFile.length() == 0) {
            Toast.makeText(this, "Log file is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", logFile);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share debug log"));
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String safUriToPath(Uri uri) {
        String seg = uri.getLastPathSegment();
        if (seg == null) return null;
        if (seg.startsWith("primary:")) {
            return Environment.getExternalStorageDirectory()
                    + "/" + seg.substring("primary:".length());
        }
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }
}

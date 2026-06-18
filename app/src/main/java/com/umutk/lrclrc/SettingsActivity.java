package com.umutk.lrclrc;

import android.net.Uri;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.TextView;

public class SettingsActivity extends BaseActivity {

    private TextView currentDirText;
    private Slider contextLinesSlider;
    private TextInputEditText maxResultsEditText;

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

        currentDirText = findViewById(R.id.currentDirText);
        MaterialButton chooseFolderBtn = findViewById(R.id.chooseFolderButton);
        MaterialButton resetDirBtn = findViewById(R.id.resetDirButton);
        com.google.android.material.button.MaterialButton githubBtn = findViewById(R.id.githubButton);
        githubBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(Prefs.GITHUB_URL));
            startActivity(i);
        });


        MaterialSwitch ciSwitch = findViewById(R.id.caseInsensitiveSwitch);
        MaterialSwitch wwSwitch = findViewById(R.id.wholeWordSwitch);
        MaterialSwitch sortSwitch = findViewById(R.id.sortByHitsSwitch);
        MaterialSwitch amoledSwitch = findViewById(R.id.amoledSwitch);

        contextLinesSlider = findViewById(R.id.contextLinesSlider);
        maxResultsEditText = findViewById(R.id.maxResultsEditText);

        MaterialButtonToggleGroup themeGroup = findViewById(R.id.themeToggleGroup);
        MaterialButton themeSys = findViewById(R.id.themeSystemButton);
        MaterialButton themeLight = findViewById(R.id.themeLightButton);
        MaterialButton themeDark = findViewById(R.id.themeDarkButton);

        // Init
        updateDirDisplay();
        ciSwitch.setChecked(prefs.isCaseInsensitive());
        wwSwitch.setChecked(prefs.isWholeWord());
        sortSwitch.setChecked(prefs.isSortByHits());
        amoledSwitch.setChecked(prefs.isAmoled());
        contextLinesSlider.setValue(prefs.getContextLines());
        maxResultsEditText.setText(String.valueOf(prefs.getMaxResults()));
        updateContextLabel();

        switch (prefs.getThemeMode()) {
            case Prefs.THEME_LIGHT: themeGroup.check(themeLight.getId()); break;
            case Prefs.THEME_DARK:  themeGroup.check(themeDark.getId());  break;
            default:                themeGroup.check(themeSys.getId());
        }

        // Listeners
        chooseFolderBtn.setOnClickListener(v ->
                folderPicker.launch(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A")));

        resetDirBtn.setOnClickListener(v -> {
            prefs.setSearchDir(Prefs.defaultDir());
            updateDirDisplay();
        });

        ciSwitch.setOnCheckedChangeListener((v, c) -> prefs.setCaseInsensitive(c));
        wwSwitch.setOnCheckedChangeListener((v, c) -> prefs.setWholeWord(c));
        sortSwitch.setOnCheckedChangeListener((v, c) -> prefs.setSortByHits(c));
        amoledSwitch.setOnCheckedChangeListener((v, c) -> {
            prefs.setAmoled(c);
            recreate(); // Apply immediately
        });

        contextLinesSlider.addOnChangeListener((s, val, fromUser) -> {
            prefs.setContextLines((int) val);
            updateContextLabel();
        });

        maxResultsEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                try { prefs.setMaxResults(Integer.parseInt(s.toString().trim())); }
                catch (NumberFormatException ignored) {}
            }
        });

        themeGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            if (id == themeLight.getId()) prefs.setThemeMode(Prefs.THEME_LIGHT);
            else if (id == themeDark.getId()) prefs.setThemeMode(Prefs.THEME_DARK);
            else prefs.setThemeMode(Prefs.THEME_SYSTEM);
            recreate(); // Apply immediately
        });
    }

    private void updateDirDisplay() {
        currentDirText.setText(prefs.getSearchDir());
    }

    private void updateContextLabel() {
        TextView label = findViewById(R.id.contextLinesLabel);
        label.setText(getString(R.string.settings_context_lines, (int) contextLinesSlider.getValue()));
    }

    private String safUriToPath(Uri uri) {
        String docId = uri.getLastPathSegment();
        if (docId == null) return null;
        if (docId.startsWith("primary:")) {
            return Environment.getExternalStorageDirectory()
                    + "/" + docId.substring("primary:".length());
        }
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }
}

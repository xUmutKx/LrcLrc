package com.umutk.lrclrc;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public abstract class BaseActivity extends AppCompatActivity {
    protected Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new Prefs(this);
        switch (prefs.getThemeMode()) {
            case Prefs.THEME_LIGHT: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO); break;
            case Prefs.THEME_DARK:  AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES); break;
            default: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
        if (prefs.isAmoled()) setTheme(R.style.Theme_LRCFinder_Amoled);
        DebugLog.setEnabled(prefs.isDebugLoggingEnabled());
        super.onCreate(savedInstanceState);
    }

    /**
     * targetSdk 35 makes edge-to-edge mandatory: window content draws behind the
     * status bar by default, which is why the toolbar/title used to render partly
     * underneath the clock/notch area. This pushes the given view down by exactly
     * the system status bar height so it sits cleanly below it, on every device.
     */
    protected void applyTopInset(View view) {
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), topInset, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
    }
}

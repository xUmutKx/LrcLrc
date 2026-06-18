package com.lrcfinder.app;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

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
        super.onCreate(savedInstanceState);
    }
}

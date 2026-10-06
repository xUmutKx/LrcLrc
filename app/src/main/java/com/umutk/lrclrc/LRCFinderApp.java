package com.umutk.lrclrc;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public class LRCFinderApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // English unless the user picked Turkish in Settings.
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(new Prefs(this).getLanguage()));
        // Applies wallpaper-derived Material You colors on Android 12+ automatically.
        // On older versions this is a no-op and the seed colors in colors.xml are used.
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}

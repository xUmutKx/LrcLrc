package com.lrcfinder.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.util.ArrayList;
import java.util.List;

public class Prefs {

    private static final String FILE = "lrc_finder_prefs";

    private static final String KEY_DIR              = "search_dir";
    private static final String KEY_CASE_INSENSITIVE = "case_insensitive";
    private static final String KEY_WHOLE_WORD       = "whole_word";
    private static final String KEY_CONTEXT_LINES    = "context_lines";
    private static final String KEY_MAX_RESULTS      = "max_results";
    private static final String KEY_SORT_BY_HITS     = "sort_by_hits";
    private static final String KEY_THEME_MODE       = "theme_mode";
    private static final String KEY_AMOLED           = "amoled";
    private static final String KEY_HISTORY          = "history";
    private static final String KEY_LAST_VERSION     = "last_seen_version";

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT  = 1;
    public static final int THEME_DARK   = 2;

    public static final String GITHUB_URL = "https://github.com/xUmutKx/LrcLrc";

    private static final String HISTORY_SEPARATOR = "\u0001";
    private static final int    HISTORY_MAX        = 30;

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String defaultDir() {
        return Environment.getExternalStorageDirectory() + "/Music";
    }

    public String getSearchDir() { return sp.getString(KEY_DIR, defaultDir()); }
    public void setSearchDir(String dir) { sp.edit().putString(KEY_DIR, dir).apply(); }

    public boolean isCaseInsensitive() { return sp.getBoolean(KEY_CASE_INSENSITIVE, true); }
    public void setCaseInsensitive(boolean v) { sp.edit().putBoolean(KEY_CASE_INSENSITIVE, v).apply(); }

    public boolean isWholeWord() { return sp.getBoolean(KEY_WHOLE_WORD, false); }
    public void setWholeWord(boolean v) { sp.edit().putBoolean(KEY_WHOLE_WORD, v).apply(); }

    public int getContextLines() { return sp.getInt(KEY_CONTEXT_LINES, 1); }
    public void setContextLines(int v) { sp.edit().putInt(KEY_CONTEXT_LINES, v).apply(); }

    public int getMaxResults() { return sp.getInt(KEY_MAX_RESULTS, 200); }
    public void setMaxResults(int v) { sp.edit().putInt(KEY_MAX_RESULTS, v).apply(); }

    public boolean isSortByHits() { return sp.getBoolean(KEY_SORT_BY_HITS, true); }
    public void setSortByHits(boolean v) { sp.edit().putBoolean(KEY_SORT_BY_HITS, v).apply(); }

    public int getThemeMode() { return sp.getInt(KEY_THEME_MODE, THEME_SYSTEM); }
    public void setThemeMode(int mode) { sp.edit().putInt(KEY_THEME_MODE, mode).apply(); }

    public boolean isAmoled() { return sp.getBoolean(KEY_AMOLED, false); }
    public void setAmoled(boolean v) { sp.edit().putBoolean(KEY_AMOLED, v).apply(); }

    public String getLastSeenVersion() { return sp.getString(KEY_LAST_VERSION, ""); }
    public void setLastSeenVersion(String v) { sp.edit().putString(KEY_LAST_VERSION, v).apply(); }

    public List<String> getHistory() {
        String raw = sp.getString(KEY_HISTORY, "");
        List<String> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        for (String s : raw.split("")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public void addHistory(String phrase) {
        List<String> h = getHistory();
        h.remove(phrase);
        h.add(phrase);
        if (h.size() > HISTORY_MAX) h.remove(0);
        sp.edit().putString(KEY_HISTORY, String.join("", h)).apply();
    }

    public void clearHistory() {
        sp.edit().remove(KEY_HISTORY).apply();
    }
}

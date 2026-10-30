package com.yash.blindslock;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/** Small typed wrapper around the app's SharedPreferences. */
public final class Prefs {
    private static final String NAME = "blinds";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_STRIP_DP = "strip_dp";
    public static final String KEY_UNLOCK_PCT = "unlock_pct";
    public static final String KEY_SHOW_CLOCK = "show_clock";
    public static final String KEY_HAPTICS = "haptics";
    public static final String KEY_SOUNDS = "sounds";
    public static final String KEY_SOUND_VOL = "sound_volume";
    public static final String KEY_CUSTOM_WALLPAPER = "custom_wallpaper";
    public static final String KEY_WALLPAPER_SOURCE = "wallpaper_source";
    public static final String KEY_BAND_SLATS = "band_slats";
    public static final String KEY_WIDGET_IDS = "widget_ids";
    public static final String KEY_WIDGET_PAGE = "widget_page";

    /** Wallpaper sources. */
    public static final int WP_DEFAULT = 0, WP_PICKED = 1, WP_CURRENT = 2;

    public static final int DEFAULT_STRIP_DP = 24;
    public static final int DEFAULT_UNLOCK_PCT = 25;
    public static final int DEFAULT_BAND_SLATS = 6;

    private final SharedPreferences sp;
    private final Context app;

    public Prefs(Context c) {
        app = c.getApplicationContext();
        sp = app.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public boolean enabled() { return sp.getBoolean(KEY_ENABLED, false); }
    public void setEnabled(boolean v) { sp.edit().putBoolean(KEY_ENABLED, v).apply(); }

    public int stripDp() { return sp.getInt(KEY_STRIP_DP, DEFAULT_STRIP_DP); }
    public void setStripDp(int v) { sp.edit().putInt(KEY_STRIP_DP, v).apply(); }

    public int unlockPct() { return sp.getInt(KEY_UNLOCK_PCT, DEFAULT_UNLOCK_PCT); }
    public void setUnlockPct(int v) { sp.edit().putInt(KEY_UNLOCK_PCT, v).apply(); }

    public boolean showClock() { return sp.getBoolean(KEY_SHOW_CLOCK, true); }
    public void setShowClock(boolean v) { sp.edit().putBoolean(KEY_SHOW_CLOCK, v).apply(); }

    public boolean sounds() { return sp.getBoolean(KEY_SOUNDS, true); }
    public void setSounds(boolean v) { sp.edit().putBoolean(KEY_SOUNDS, v).apply(); }

    /** Lock/unlock sound volume, 0..100 (relative to the phone's system-sounds volume). */
    public int soundVolume() { return sp.getInt(KEY_SOUND_VOL, 80); }
    public void setSoundVolume(int v) { sp.edit().putInt(KEY_SOUND_VOL, Math.max(0, Math.min(100, v))).apply(); }

    public boolean haptics() { return sp.getBoolean(KEY_HAPTICS, true); }
    public void setHaptics(boolean v) { sp.edit().putBoolean(KEY_HAPTICS, v).apply(); }

    public int wallpaperSource() {
        int def = sp.getBoolean(KEY_CUSTOM_WALLPAPER, false) ? WP_PICKED : WP_DEFAULT;
        return sp.getInt(KEY_WALLPAPER_SOURCE, def);
    }
    public void setWallpaperSource(int v) { sp.edit().putInt(KEY_WALLPAPER_SOURCE, v).apply(); }

    /** True when a saved image (picked or copied from the system wallpaper) should be used. */
    public boolean customWallpaper() {
        return wallpaperSource() != WP_DEFAULT && wallpaperFile().exists();
    }

    /** App-widget ids shown on the lock screen, in page order. */
    public java.util.List<Integer> widgetIds() {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        String raw = sp.getString(KEY_WIDGET_IDS, "");
        for (String part : raw.split(",")) {
            try {
                if (!part.trim().isEmpty()) out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public void setWidgetIds(java.util.List<Integer> ids) {
        StringBuilder b = new StringBuilder();
        for (int id : ids) {
            if (b.length() > 0) b.append(',');
            b.append(id);
        }
        sp.edit().putString(KEY_WIDGET_IDS, b.toString()).apply();
    }

    public int widgetPage() { return sp.getInt(KEY_WIDGET_PAGE, 0); }
    public void setWidgetPage(int v) { sp.edit().putInt(KEY_WIDGET_PAGE, v).apply(); }

    /** How many blinds move with the finger (and open on unlock). */
    public int bandSlats() { return sp.getInt(KEY_BAND_SLATS, DEFAULT_BAND_SLATS); }
    public void setBandSlats(int v) { sp.edit().putInt(KEY_BAND_SLATS, v).apply(); }

    public File wallpaperFile() { return new File(app.getFilesDir(), "wallpaper.jpg"); }
}

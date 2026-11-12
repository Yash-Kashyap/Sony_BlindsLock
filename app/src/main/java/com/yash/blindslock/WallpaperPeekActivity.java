package com.yash.blindslock;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

/**
 * An empty full-screen window that only shows the system wallpaper behind it, so a screen
 * capture sees the (live) wallpaper and nothing else. Closed by {@link WallpaperCaptureService}.
 */
public class WallpaperPeekActivity extends Activity {
    private static WallpaperPeekActivity current;

    static void closeIfOpen() {
        if (current != null) current.finish();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = this;
        View v = new View(this);
        v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setContentView(v);
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }
}

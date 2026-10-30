package com.yash.blindslock;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.PowerManager;

/**
 * Lock / unlock click sounds. Played on the "system sounds" path (like Android's own lock
 * sounds), only when the ringer is in normal mode, and only if enabled in settings.
 */
public final class Sounds {
    private static SoundPool pool;
    private static int lockId, unlockId;

    private Sounds() {}

    /** For tests. */
    static SoundPool poolForTest() { return pool; }

    /** Loads both sounds (fast, ~5 KB each). Safe to call repeatedly. */
    public static synchronized void init(Context c) {
        if (pool != null) return;
        Context app = c.getApplicationContext();
        pool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        lockId = pool.load(app, R.raw.lock, 1);
        unlockId = pool.load(app, R.raw.unlock, 1);
    }

    public static void playLock(Context c) {
        if (!allowed(c)) return;
        // Keep the CPU awake for the short click: the screen has just gone off.
        try {
            PowerManager pm = c.getSystemService(PowerManager.class);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "blindslock:lockSound");
            wl.acquire(1500);
        } catch (Exception ignored) {
        }
        play(c, true);
    }

    public static void playUnlock(Context c) {
        if (allowed(c)) play(c, false);
    }

    /** Slider 0..100 mapped on a gentle curve, so the low end is usable. */
    static float volume(Context c) {
        float x = new Prefs(c).soundVolume() / 100f;
        return x * x;
    }

    /** Preview from the settings slider: plays the unlock click at the chosen volume. */
    public static void preview(Context c) {
        play(c, false);
    }

    private static boolean allowed(Context c) {
        if (!new Prefs(c).sounds()) return false;
        AudioManager am = c.getSystemService(AudioManager.class);
        return am == null || am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
    }

    private static synchronized void play(Context c, boolean lock) {
        init(c);
        try {
            float v = volume(c);
            if (v <= 0f) return;
            pool.play(lock ? lockId : unlockId, v, v, 1, 0, 1f);
        } catch (Exception ignored) {
        }
    }
}

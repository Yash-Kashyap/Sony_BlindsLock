package com.yash.blindslock;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.SoundPool;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Lock / unlock click sounds. Played on the "system sounds" path (like Android's own lock
 * sounds), only when the ringer is in normal mode, and only if enabled in settings.
 *
 * <p>The clicks are kept ready in Android's low-latency audio path (a preloaded track at the
 * speaker's own sample rate), so a click starts within a few milliseconds of being asked for.
 * If the phone won't give us that path, the regular sound pool is used instead.
 */
public final class Sounds {
    private static final AudioAttributes ATTRS = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    private static SoundPool pool;
    private static int lockId, unlockId, warmId;
    private static AudioTrack lockTrack, unlockTrack;
    private static long lastWarm = -10_000;
    private static final List<Integer> played = new ArrayList<>();

    private Sounds() {}

    /** For tests. */
    static SoundPool poolForTest() { return pool; }

    /** For tests: resource ids of the clicks played so far. */
    static synchronized List<Integer> playedForTest() { return played; }

    /** For tests. */
    static void resetWarmForTest() { lastWarm = -10_000; }

    /** Loads the sounds (uncompressed, trimmed to start on the click). Safe to call repeatedly. */
    public static synchronized void init(Context c) {
        if (pool != null) return;
        Context app = c.getApplicationContext();
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(ATTRS).build();
        lockId = pool.load(app, R.raw.lock, 1);
        unlockId = pool.load(app, R.raw.unlock, 1);
        warmId = pool.load(app, R.raw.warm, 1);
        lockTrack = fastTrack(app, R.raw.lock);
        unlockTrack = fastTrack(app, R.raw.unlock);
    }

    public static void playLock(Context c) {
        if (!allowed(c)) return;
        // Keep the CPU awake for the short click: the screen is going off.
        try {
            PowerManager pm = c.getSystemService(PowerManager.class);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "blindslock:lockSound");
            wl.acquire(1500);
        } catch (Exception ignored) {
        }
        play(c, true);
    }

    public static void playUnlock(Context c) {
        if (allowed(c)) play(c, false);
    }

    /**
     * Wakes the phone's audio output a moment before a click is due (it sleeps when idle, and
     * waking it is what makes a click arrive late). Plays a short silent clip; cheap, and
     * rate-limited so it doesn't repeat.
     */
    public static synchronized void warm(Context c) {
        if (!allowed(c)) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastWarm < 1500) return;
        lastWarm = now;
        init(c);
        try {
            pool.play(warmId, 1f, 1f, 0, 0, 1f);
        } catch (Exception ignored) {
        }
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
        float v = volume(c);
        if (v <= 0f) return;
        played.add(lock ? R.raw.lock : R.raw.unlock);
        AudioTrack t = lock ? lockTrack : unlockTrack;
        if (t != null && restart(t, v)) return;
        try {
            pool.play(lock ? lockId : unlockId, v, v, 1, 0, 1f);
        } catch (Exception ignored) {
        }
    }

    /** Rewinds a preloaded track and starts it. */
    private static boolean restart(AudioTrack t, float v) {
        try {
            if (t.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) t.stop();
            t.reloadStaticData();
            t.setVolume(v);
            t.play();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Builds a preloaded low-latency track from one of our 16-bit mono WAV clips, resampled to
     * the speaker's native rate (a requirement for Android's fast audio path).
     */
    private static AudioTrack fastTrack(Context app, int res) {
        try {
            short[] pcm = readWav(app, res);
            int rate = 44100;
            AudioManager am = app.getSystemService(AudioManager.class);
            try {
                String r = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
                if (r != null) rate = Integer.parseInt(r);
            } catch (Exception ignored) {
            }
            if (rate != 44100) pcm = resample(pcm, 44100, rate);
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(ATTRS)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length * 2)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
            if (t.write(pcm, 0, pcm.length) != pcm.length
                    || t.getState() != AudioTrack.STATE_INITIALIZED) {
                t.release();
                return null;
            }
            return t;
        } catch (Throwable e) {
            return null;
        }
    }

    static short[] readWav(Context app, int res) throws Exception {
        byte[] b;
        try (InputStream in = app.getResources().openRawResource(res)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
            b = out.toByteArray();
        }
        int n = (b.length - 44) / 2;
        short[] s = new short[Math.max(0, n)];
        for (int i = 0; i < n; i++) {
            s[i] = (short) ((b[44 + 2 * i] & 0xff) | (b[45 + 2 * i] << 8));
        }
        return s;
    }

    static short[] resample(short[] in, int from, int to) {
        int n = (int) ((long) in.length * to / from);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double p = (double) i * from / to;
            int k = (int) p;
            double f = p - k;
            int a = in[Math.min(k, in.length - 1)], b = in[Math.min(k + 1, in.length - 1)];
            out[i] = (short) Math.round(a + (b - a) * f);
        }
        return out;
    }
}

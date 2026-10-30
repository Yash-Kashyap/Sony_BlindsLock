package com.yash.blindslock;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Haptic design for the blinds.
 *
 * On phones with rich haptics (Android 11+ with composition primitives - e.g. recent Samsung,
 * Pixel) it uses crisp primitives such as TICK, LOW_TICK, CLICK, THUD and QUICK_RISE with
 * per-event intensity. Older phones fall back to amplitude-controlled one-shots, then to the
 * stock predefined effects.
 */
public final class Haptics {

    private final Vibrator vibrator;
    private final boolean enabled;
    private final boolean amplitude;
    private final boolean prims;      // TICK, CLICK, QUICK_RISE, QUICK_FALL
    private final boolean primsRich;  // LOW_TICK, THUD
    private long lastTickMs;

    public Haptics(Context ctx) {
        Vibrator v;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = ctx.getSystemService(VibratorManager.class);
            v = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            v = ctx.getSystemService(Vibrator.class);
        }
        vibrator = v;
        enabled = new Prefs(ctx).haptics() && v != null && v.hasVibrator();
        amplitude = enabled && v.hasAmplitudeControl();
        boolean p = false, rich = false;
        if (enabled && Build.VERSION.SDK_INT >= 30) try {
            p = all(v.arePrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_TICK,
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                    VibrationEffect.Composition.PRIMITIVE_QUICK_FALL));
            if (Build.VERSION.SDK_INT >= 31) {
                rich = all(v.arePrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                        VibrationEffect.Composition.PRIMITIVE_THUD));
            }
        } catch (Exception e) {
            // Some firmwares throw here; fall back to simple vibrations.
            p = false;
            rich = false;
        }
        prims = p;
        primsRich = rich;
    }

    private static boolean all(boolean[] b) {
        if (b == null || b.length == 0) return false;
        for (boolean x : b) if (!x) return false;
        return true;
    }

    // ------------------------------------------------------------------ blinds

    /** A slat passing under the finger: a light, speed-scaled tick (rate-limited). */
    public void slatTick(float intensity) {
        if (!enabled) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastTickMs < 32) return;
        lastTickMs = now;
        float s = clamp(intensity, 0.08f, 1f);
        if (prims) {
            int id = (primsRich && s < 0.45f) ? PRIMITIVE_LOW_TICK : PRIMITIVE_TICK;
            comp(new int[]{id}, new float[]{s}, new int[]{0});
        } else if (amplitude) {
            play(VibrationEffect.createOneShot(7, Math.max(1, Math.round(200 * s))));
        } else if (Build.VERSION.SDK_INT >= 29 && s > 0.3f) {
            play(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        }
    }

    /** Finger touches down: the blinds "catch". */
    public void touchDown() {
        if (!enabled) return;
        if (prims) {
            comp(new int[]{PRIMITIVE_TICK}, new float[]{0.35f}, new int[]{0});
        } else if (amplitude) {
            play(VibrationEffect.createOneShot(8, 70));
        }
    }

    /** Swipe has passed the unlock distance: a rising "armed" click. */
    public void threshold() {
        if (!enabled) return;
        if (prims) {
            comp(new int[]{PRIMITIVE_QUICK_RISE, PRIMITIVE_CLICK}, new float[]{0.45f, 0.85f}, new int[]{0, 10});
        } else if (amplitude) {
            play(VibrationEffect.createWaveform(new long[]{0, 12, 30, 14},
                    new int[]{0, 90, 0, 255}, -1));
        } else if (Build.VERSION.SDK_INT >= 29) {
            play(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        }
    }

    /** Released to unlock: a firm click followed by the slats rattling open. */
    public void unlock() {
        if (!enabled) return;
        if (prims) {
            comp(new int[]{PRIMITIVE_CLICK, PRIMITIVE_TICK, PRIMITIVE_TICK, PRIMITIVE_TICK, PRIMITIVE_TICK}, new float[]{1f, 0.7f, 0.5f, 0.35f, 0.22f}, new int[]{0, 45, 35, 35, 40});
        } else if (amplitude) {
            play(VibrationEffect.createWaveform(
                    new long[]{0, 14, 40, 8, 30, 7, 30, 6},
                    new int[]{0, 255, 0, 170, 0, 120, 0, 80}, -1));
        } else if (Build.VERSION.SDK_INT >= 29) {
            play(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
        }
    }

    /** Released too early: the slats fall back with a soft settle. */
    public void cancel() {
        if (!enabled) return;
        if (primsRich) {
            comp(new int[]{PRIMITIVE_QUICK_FALL, PRIMITIVE_LOW_TICK}, new float[]{0.35f, 0.4f}, new int[]{0, 20});
        } else if (prims) {
            comp(new int[]{PRIMITIVE_TICK}, new float[]{0.3f}, new int[]{0});
        } else if (amplitude) {
            play(VibrationEffect.createOneShot(10, 60));
        }
    }

    // ------------------------------------------------------------------ plumbing

    // Composition primitive ids (inlined constants; only used when the device reports support).
    private static final int PRIMITIVE_CLICK = 1, PRIMITIVE_THUD = 2, PRIMITIVE_QUICK_RISE = 4,
            PRIMITIVE_QUICK_FALL = 6, PRIMITIVE_TICK = 7, PRIMITIVE_LOW_TICK = 8;

    @android.annotation.TargetApi(30)
    private void comp(int[] ids, float[] scales, int[] delays) {
        VibrationEffect.Composition c = VibrationEffect.startComposition();
        for (int i = 0; i < ids.length; i++) c.addPrimitive(ids[i], scales[i], delays[i]);
        play(c.compose());
    }

    private void play(VibrationEffect e) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
            } else {
                vibrator.vibrate(e, new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
            }
        } catch (Exception ignored) {
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}

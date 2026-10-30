package com.yash.blindslock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.format.DateFormat;

import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * Builds the bitmap that the blinds are cut from: wallpaper + clock + date + hint.
 * The default wallpaper is an original procedural "sky over a wheat field" scene in the
 * spirit of the 2013 Xperia lock screens (no copyrighted artwork is bundled).
 */
public final class SceneRenderer {

    private static Bitmap cachedWallpaper;
    private static String cachedWallpaperKey;

    private SceneRenderer() {}

    public static synchronized void invalidateWallpaper() {
        cachedWallpaper = null;
        cachedWallpaperKey = null;
    }

    /** Wallpaper scaled (center-crop) to exactly w x h, cached for the lock screen size. */
    public static Bitmap wallpaper(Context ctx, int w, int h) {
        return wallpaper(ctx, w, h, true);
    }

    public static synchronized Bitmap wallpaper(Context ctx, int w, int h, boolean cache) {
        Prefs prefs = new Prefs(ctx);
        boolean custom = prefs.customWallpaper();
        String key = (custom ? "custom:" + prefs.wallpaperFile().lastModified() : "default") + ":" + w + "x" + h;
        if (cachedWallpaper != null && key.equals(cachedWallpaperKey)) return cachedWallpaper;

        Bitmap result = null;
        if (custom) {
            Bitmap src = BitmapFactory.decodeFile(prefs.wallpaperFile().getAbsolutePath());
            if (src != null) {
                result = centerCrop(src, w, h);
                if (result != src) src.recycle();
            }
        }
        if (result == null) result = defaultWallpaper(w, h);
        if (cache) {
            cachedWallpaper = result;
            cachedWallpaperKey = key;
        }
        return result;
    }

    /**
     * Composes the lock-screen background: wallpaper, scrims and the unlock hint. The clock and
     * widgets live in the widget pager on top; they are stamped into this bitmap only while the
     * blinds are moving (see LockActivity).
     */
    public static Bitmap compose(Context ctx, int w, int h, int bottomInset) {
        Prefs prefs = new Prefs(ctx);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(wallpaper(ctx, w, h), 0, 0, null);

        // Gentle scrims so white text and the status bar stay readable on any image.
        Paint scrim = new Paint();
        scrim.setShader(new LinearGradient(0, 0, 0, h * 0.42f,
                Color.argb(110, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.42f, scrim);
        scrim.setShader(new LinearGradient(0, h * 0.78f, 0, h,
                Color.argb(0, 0, 0, 0), Color.argb(90, 0, 0, 0), Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.78f, w, h, scrim);

        drawHint(ctx, c, w, h, bottomInset);
        return out;
    }

    /** Xperia-style clock + date, centred horizontally, time baseline at {@code baseline}. */
    public static void drawClock(Context ctx, Canvas c, int w, float baseline) {
        Calendar now = Calendar.getInstance();
        boolean is24 = DateFormat.is24HourFormat(ctx);
        String time = DateFormat.format(is24 ? "HH:mm" : "hh:mm", now).toString();
        String ampm = is24 ? null : DateFormat.format("a", now).toString().toLowerCase(Locale.getDefault());
        Locale loc = Locale.getDefault();
        String date = DateFormat.format(DateFormat.getBestDateTimePattern(loc, "EEEEdMMMM"), now).toString();

        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        tp.setTextSize(w * 0.25f);
        tp.setShadowLayer(w * 0.01f, 0, w * 0.003f, Color.argb(90, 0, 0, 0));

        float timeW = tp.measureText(time);
        Paint ap = new Paint(tp);
        ap.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        ap.setTextSize(w * 0.05f);
        float ampmW = ampm == null ? 0 : ap.measureText(ampm) + w * 0.01f;

        float x = (w - timeW - ampmW) / 2f;
        c.drawText(time, x, baseline, tp);
        if (ampm != null) {
            // Small superscript am/pm, like the Xperia clock.
            c.drawText(ampm, x + timeW + w * 0.01f, baseline - tp.getTextSize() * 0.52f, ap);
        }

        Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
        dp.setColor(Color.argb(235, 255, 255, 255));
        dp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        dp.setTextSize(w * 0.046f);
        dp.setShadowLayer(w * 0.008f, 0, w * 0.002f, Color.argb(90, 0, 0, 0));
        dp.setTextAlign(Paint.Align.CENTER);
        c.drawText(date, w / 2f, baseline + w * 0.10f, dp);
    }

    /** Xperia-style hint: an up chevron, "Swipe up or down to unlock", a down chevron. */
    private static void drawHint(Context ctx, Canvas c, int w, int h, int bottomInset) {
        String hint = ctx.getString(R.string.swipe_to_unlock);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.argb(225, 255, 255, 255));
        p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        p.setTextSize(w * 0.037f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setShadowLayer(w * 0.006f, 0, 0, Color.argb(140, 0, 0, 0));
        // About two-thirds of the way down, as on the Xperia Z1 lock screen.
        float y = Math.min(h * 0.70f, h - bottomInset - w * 0.16f);
        Paint.FontMetrics fm = p.getFontMetrics();
        float textMid = y + (fm.ascent + fm.descent) / 2f;
        c.drawText(hint, w / 2f, y, p);

        Paint ch = new Paint(Paint.ANTI_ALIAS_FLAG);
        ch.setStyle(Paint.Style.STROKE);
        ch.setStrokeWidth(Math.max(2f, w * 0.0045f));
        ch.setStrokeCap(Paint.Cap.ROUND);
        ch.setStrokeJoin(Paint.Join.ROUND);
        ch.setColor(Color.argb(225, 255, 255, 255));
        ch.setShadowLayer(w * 0.005f, 0, 0, Color.argb(140, 0, 0, 0));
        float cw = w * 0.042f, chH = w * 0.016f, gap = w * 0.050f;
        // up chevron (above)
        float upY = textMid - gap;
        Path up = new Path();
        up.moveTo(w / 2f - cw / 2f, upY + chH / 2f);
        up.lineTo(w / 2f, upY - chH / 2f);
        up.lineTo(w / 2f + cw / 2f, upY + chH / 2f);
        c.drawPath(up, ch);
        // down chevron (below)
        float dnY = textMid + gap;
        Path dn = new Path();
        dn.moveTo(w / 2f - cw / 2f, dnY - chH / 2f);
        dn.lineTo(w / 2f, dnY + chH / 2f);
        dn.lineTo(w / 2f + cw / 2f, dnY - chH / 2f);
        c.drawPath(dn, ch);
    }

    // ------------------------------------------------------------------ helpers

    public static Bitmap centerCrop(Bitmap src, int w, int h) {
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        float sw = w / scale, sh = h / scale;
        float left = (src.getWidth() - sw) / 2f, top = (src.getHeight() - sh) / 2f;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        c.drawBitmap(src, new Rect(Math.round(left), Math.round(top),
                Math.round(left + sw), Math.round(top + sh)), new RectF(0, 0, w, h), p);
        return out;
    }

    /** Procedural blue-sky-over-a-green-field wallpaper. */
    public static Bitmap defaultWallpaper(int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Random rnd = new Random(20131005L);
        float horizon = h * 0.60f;

        // Sky.
        Paint sky = new Paint();
        sky.setShader(new LinearGradient(0, 0, 0, horizon,
                new int[]{0xFF0A2E7A, 0xFF1E5FC0, 0xFF5FA4E6, 0xFFCDE9FA},
                new float[]{0f, 0.35f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, horizon + 2, sky);

        // Low sun glow near the horizon.
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new RadialGradient(w * 0.68f, horizon * 0.96f, w * 0.75f,
                new int[]{0xCCFFFFFF, 0x55FFF6E0, 0x00FFFFFF}, new float[]{0f, 0.35f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, horizon, glow);

        // Clouds: clusters of blurred ellipses, flatter and smaller toward the horizon.
        Paint cloud = new Paint(Paint.ANTI_ALIAS_FLAG);
        cloud.setMaskFilter(new BlurMaskFilter(w * 0.035f, BlurMaskFilter.Blur.NORMAL));
        for (int k = 0; k < 9; k++) {
            float cy = horizon * (0.18f + 0.75f * rnd.nextFloat());
            float depth = cy / horizon; // 0 = high, 1 = near horizon
            float cx = w * rnd.nextFloat();
            float size = w * (0.38f - 0.22f * depth);
            int puffs = 4 + rnd.nextInt(4);
            for (int j = 0; j < puffs; j++) {
                float px = cx + (rnd.nextFloat() - 0.5f) * size * 1.6f;
                float py = cy + (rnd.nextFloat() - 0.5f) * size * 0.18f;
                float rw = size * (0.35f + 0.4f * rnd.nextFloat());
                float rh = rw * (0.22f + 0.12f * (1 - depth));
                cloud.setColor(Color.argb(90 + rnd.nextInt(80), 255, 255, 255));
                c.drawOval(px - rw, py - rh, px + rw, py + rh, cloud);
            }
        }
        // A couple of pink-lit wisps.
        cloud.setMaskFilter(new BlurMaskFilter(w * 0.02f, BlurMaskFilter.Blur.NORMAL));
        for (int k = 0; k < 5; k++) {
            float py = horizon * (0.55f + 0.3f * rnd.nextFloat());
            float px = w * rnd.nextFloat();
            float rw = w * (0.10f + 0.08f * rnd.nextFloat());
            cloud.setColor(Color.argb(110, 255, 190, 205));
            c.drawOval(px - rw, py - rw * 0.10f, px + rw, py + rw * 0.10f, cloud);
        }

        // Distant tree line.
        Path trees = new Path();
        trees.moveTo(0, horizon + h * 0.012f);
        float x = 0;
        while (x < w) {
            float bump = h * (0.004f + 0.010f * rnd.nextFloat());
            float step = w * (0.015f + 0.03f * rnd.nextFloat());
            trees.quadTo(x + step / 2f, horizon - bump, x + step, horizon + h * 0.002f);
            x += step;
        }
        trees.lineTo(w, horizon + h * 0.03f);
        trees.lineTo(0, horizon + h * 0.03f);
        trees.close();
        Paint treeP = new Paint(Paint.ANTI_ALIAS_FLAG);
        treeP.setColor(0xFF1C2A1A);
        c.drawPath(trees, treeP);

        // Field base.
        float fieldTop = horizon + h * 0.01f;
        Paint field = new Paint();
        field.setShader(new LinearGradient(0, fieldTop, 0, h,
                new int[]{0xFF8DB83A, 0xFF5E9A22, 0xFF2F6614, 0xFF1D4A0C},
                new float[]{0f, 0.25f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, fieldTop, w, h, field);

        // Grass blades: short and dense near the horizon, long and bold in the foreground.
        Paint blade = new Paint(Paint.ANTI_ALIAS_FLAG);
        blade.setStrokeCap(Paint.Cap.ROUND);
        int blades = (int) (w * h / 260f);
        for (int k = 0; k < blades; k++) {
            float t = (float) Math.pow(rnd.nextFloat(), 0.8);
            float by = fieldTop + (h - fieldTop) * t;
            float bx = rnd.nextFloat() * w;
            float len = h * (0.004f + 0.05f * t * t) * (0.6f + 0.8f * rnd.nextFloat());
            float lean = (rnd.nextFloat() - 0.4f) * len * 0.35f;
            float light = rnd.nextFloat();
            int g = 90 + (int) (120 * light * (1.1f - 0.5f * t));
            int r = (int) (g * (0.45f + 0.35f * rnd.nextFloat()));
            int b = (int) (g * 0.15f);
            blade.setColor(Color.argb(150 + rnd.nextInt(100), Math.min(255, r), Math.min(255, g), b));
            blade.setStrokeWidth(Math.max(1f, w * 0.0025f * (0.4f + 1.6f * t)));
            c.drawLine(bx, by, bx + lean, by - len, blade);
        }

        // Soft haze where field meets sky.
        Paint haze = new Paint();
        haze.setShader(new LinearGradient(0, horizon - h * 0.03f, 0, horizon + h * 0.06f,
                new int[]{0x00FFFFFF, 0x55E8F4FF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, horizon - h * 0.03f, w, horizon + h * 0.06f, haze);
        return out;
    }
}

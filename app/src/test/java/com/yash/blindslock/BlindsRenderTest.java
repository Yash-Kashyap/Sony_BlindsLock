package com.yash.blindslock;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;

/**
 * Renders the real BlindsView / SceneRenderer code off-device (Robolectric native graphics)
 * through scripted gestures, writing every frame to PNG so the effect can be reviewed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h780dp-xxhdpi")
public class BlindsRenderTest {

    private static final int W = 1080, H = 2340, FRAME_MS = 16;
    private final File out = new File(System.getProperty("frames.out", "build/frames"));
    private Bitmap home;

    private Bitmap homeScreen() {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, W, H, 0xFF2A0D3A, 0xFFB0206A, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);
        int[] colors = {0xFFFFC857, 0xFF4ECDC4, 0xFFFF6B6B, 0xFF8E7CFF};
        for (int r = 0; r < 5; r++) for (int k = 0; k < 4; k++) {
            p.setColor(colors[(r + k) % 4]);
            c.drawCircle(170 + k * 247, 900 + r * 260, 80, p);
        }
        return b;
    }

    private static class Recorder implements BlindsView.Listener {
        BlindsView view; boolean unlocked, finished;
        @Override public void onUnlockGesture() {
            unlocked = true;
            view.startReveal(false);
            // pretend the system keyguard reports "dismissed" ~120 ms later
            new android.os.Handler(Looper.getMainLooper()).postDelayed(view::setBackdropClear, 120);
        }
        @Override public void onRevealFinished() { finished = true; }
    }

    private BlindsView newView(Recorder rec) {
        Context ctx = RuntimeEnvironment.getApplication();
        BlindsView v = new BlindsView(ctx);
        rec.view = v;
        v.setListener(rec);
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, W, H);
        v.configure(72, 0.25f, 6, null);
        v.setScene(SceneRenderer.compose(ctx, W, H, 63));
        return v;
    }

    private int frame;
    private float fingerX = -1, fingerY = -1;
    private void render(BlindsView v, String dir) throws Exception {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS));
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawBitmap(home, 0, 0, null);
        // View alpha is applied by the parent on a device; emulate it here.
        int layer = c.saveLayerAlpha(0, 0, W, H, Math.round(v.getAlpha() * 255));
        v.draw(c);
        c.restoreToCount(layer);
        if (fingerX >= 0) { // translucent finger marker, for the demo video only
            Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
            f.setColor(Color.argb(90, 255, 255, 255));
            c.drawCircle(fingerX, fingerY, 70, f);
            f.setStyle(Paint.Style.STROKE); f.setStrokeWidth(5); f.setColor(Color.argb(170, 255, 255, 255));
            c.drawCircle(fingerX, fingerY, 70, f);
        }
        File d = new File(out, dir);
        d.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(d, String.format("f%04d.png", frame++)))) {
            Bitmap small = Bitmap.createScaledBitmap(b, W / 2, H / 2, true);
            small.compress(Bitmap.CompressFormat.PNG, 100, fo);
        }
    }

    private void touch(BlindsView v, int action, float x, float y, long downTime) {
        boolean up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        fingerX = up ? -1 : x; fingerY = up ? -1 : y;
        MotionEvent e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        v.onTouchEvent(e);
        e.recycle();
    }

    @Test
    public void unlockSwipe() throws Exception {
        home = homeScreen();
        Recorder rec = new Recorder();
        BlindsView v = newView(rec);
        frame = 0;
        String dir = "unlock";
        for (int i = 0; i < 6; i++) render(v, dir);
        long down = SystemClock.uptimeMillis();
        float x = 300, y = 1650;
        touch(v, MotionEvent.ACTION_DOWN, x, y, down);
        for (int i = 0; i < 12; i++) render(v, dir); // press and hold
        // Wander upward slowly, with a little back-and-forth like a real thumb.
        for (int i = 0; i < 70; i++) {
            y -= (i < 20 ? 6f : 13f);
            x += (float) Math.sin(i / 6.0) * 9;
            if (i > 30 && i < 42) y += 16f;
            touch(v, MotionEvent.ACTION_MOVE, x, y, down);
            render(v, dir);
        }
        for (int i = 0; i < 6; i++) render(v, dir);
        touch(v, MotionEvent.ACTION_UP, x, y, down);
        assertTrue("unlock gesture should fire", rec.unlocked);
        for (int i = 0; i < 60 && !rec.finished; i++) render(v, dir);
        render(v, dir);
        assertTrue("reveal should finish", rec.finished);
    }

    @Test
    public void shortSwipeSpringsBack() throws Exception {
        home = homeScreen();
        Recorder rec = new Recorder();
        BlindsView v = newView(rec);
        frame = 0;
        String dir = "short";
        long down = SystemClock.uptimeMillis();
        float y = 1200;
        touch(v, MotionEvent.ACTION_DOWN, 540, y, down);
        for (int i = 0; i < 25; i++) {
            y += 9;
            touch(v, MotionEvent.ACTION_MOVE, 540, y, down);
            render(v, dir);
        }
        touch(v, MotionEvent.ACTION_UP, 540, y, down);
        for (int i = 0; i < 40; i++) render(v, dir);
        assertTrue("short swipe must not unlock", !rec.unlocked);
    }


    @Test
    public void fastSwipeKeepsBandSmall() throws Exception {
        home = homeScreen();
        Recorder rec = new Recorder();
        BlindsView v = newView(rec);
        frame = 0;
        String dir = "fast";
        long down = SystemClock.uptimeMillis();
        float y = 1900;
        touch(v, MotionEvent.ACTION_DOWN, 540, y, down);
        render(v, dir);
        for (int i = 0; i < 9; i++) {
            y -= 85;
            touch(v, MotionEvent.ACTION_MOVE, 540, y, down);
            render(v, dir);
        }
        touch(v, MotionEvent.ACTION_UP, 540, y, down);
        assertTrue("fast swipe unlocks", rec.unlocked);
        for (int i = 0; i < 40 && !rec.finished; i++) render(v, dir);
        render(v, dir);
        assertTrue("reveal should finish", rec.finished);
    }

    @Test
    public void android9RectRenderer() throws Exception {
        home = homeScreen();
        Recorder rec = new Recorder();
        BlindsView v = newView(rec);
        v.setForceRectRenderer(true);
        frame = 0;
        String dir = "android9";
        long down = SystemClock.uptimeMillis();
        float x = 300, y = 1650;
        touch(v, MotionEvent.ACTION_DOWN, x, y, down);
        for (int i = 0; i < 45; i++) {
            y -= 14f;
            touch(v, MotionEvent.ACTION_MOVE, x, y, down);
            render(v, dir);
        }
        touch(v, MotionEvent.ACTION_UP, x, y, down);
        for (int i = 0; i < 40 && !rec.finished; i++) render(v, dir);
        assertTrue(rec.finished);
    }
}

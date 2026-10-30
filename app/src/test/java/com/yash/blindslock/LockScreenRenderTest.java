package com.yash.blindslock;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;

import static org.robolectric.Shadows.shadowOf;

/** Renders the whole lock screen (blinds + widget strip) through a swipe across the clock. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h780dp-xxhdpi")
public class LockScreenRenderTest {
    private final File out = new File(System.getProperty("frames.out", "build/frames"), "lockscreen");
    private int frame;

    private void snap(View root) throws Exception {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
        Bitmap b = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.BLACK);
        root.draw(c);
        out.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(out, String.format("f%04d.png", frame++)))) {
            Bitmap.createScaledBitmap(b, 540, 1170, true).compress(Bitmap.CompressFormat.PNG, 100, fo);
        }
    }

    @Test
    public void swipeAcrossClock() throws Exception {
        Intent i = new Intent(RuntimeEnvironment.getApplication(), LockActivity.class)
                .putExtra(LockActivity.EXTRA_PREVIEW, true);
        ActivityController<LockActivity> c = Robolectric.buildActivity(LockActivity.class, i).setup();
        View root = c.get().getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2340);
        snap(root);
        long t = SystemClock.uptimeMillis();
        float y = 1500;
        root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 380, y, 0));
        for (int k = 1; k <= 40; k++) {
            y -= 22;
            root.dispatchTouchEvent(MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 380, y, 0));
            snap(root);
        }
        root.dispatchTouchEvent(MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 380, y, 0));
        for (int k = 0; k < 30; k++) snap(root);
        c.pause().stop().destroy();
    }

    @Test
    public void addPage() throws Exception {
        new Prefs(RuntimeEnvironment.getApplication()).setWidgetPage(1);
        Intent i = new Intent(RuntimeEnvironment.getApplication(), LockActivity.class)
                .putExtra(LockActivity.EXTRA_PREVIEW, true);
        ActivityController<LockActivity> c = Robolectric.buildActivity(LockActivity.class, i).setup();
        View root = c.get().getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2340);
        frame = 900;
        snap(root);
        c.pause().stop().destroy();
    }
}

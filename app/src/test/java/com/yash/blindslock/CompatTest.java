package com.yash.blindslock;

import static org.junit.Assert.assertNotNull;

import android.content.Intent;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** Boots the real screens on Android 9 (28), 11 (30) and 14 (34) to catch API-level crashes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 30, 34}, qualifiers = "w360dp-h780dp-xxhdpi")
public class CompatTest {

    @Test
    public void settingsScreenStarts() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        assertNotNull(c.get());
        c.pause().stop().destroy();
    }

    @Test
    public void lockScreenStartsAndUnlocksInPreview() {
        Intent i = new Intent(RuntimeEnvironment.getApplication(), LockActivity.class)
                .putExtra(LockActivity.EXTRA_PREVIEW, true);
        ActivityController<LockActivity> c = Robolectric.buildActivity(LockActivity.class, i).setup();
        View root = c.get().getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2340);
        // find the BlindsView and swipe on it
        BlindsView bv = find(root);
        assertNotNull(bv);
        long t = android.os.SystemClock.uptimeMillis();
        bv.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 540, 1700, 0));
        for (int k = 1; k <= 12; k++) {
            bv.dispatchTouchEvent(MotionEvent.obtain(t, t + k * 16, MotionEvent.ACTION_MOVE, 540, 1700 - k * 70, 0));
        }
        bv.dispatchTouchEvent(MotionEvent.obtain(t, t + 220, MotionEvent.ACTION_UP, 540, 1700 - 12 * 70, 0));
        c.pause().stop().destroy();
    }

    @Test
    public void serviceStarts() {
        new Prefs(RuntimeEnvironment.getApplication()).setEnabled(true);
        Robolectric.buildService(LockService.class).create().startCommand(0, 1).destroy();
    }

    private static BlindsView find(View v) {
        if (v instanceof BlindsView) return (BlindsView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                BlindsView r = find(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    @Test
    public void widgetStripForwardsVerticalSwipeAndStampsPage() {
        Intent i = new Intent(RuntimeEnvironment.getApplication(), LockActivity.class)
                .putExtra(LockActivity.EXTRA_PREVIEW, true);
        ActivityController<LockActivity> c = Robolectric.buildActivity(LockActivity.class, i).setup();
        View root = c.get().getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2340);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2340);
        WidgetPager pager = findPager(root);
        assertNotNull(pager);
        org.junit.Assert.assertTrue("pager has clock + add pages", pager.pageCount() >= 2);
        org.junit.Assert.assertTrue("pager laid out", pager.getHeight() > 0);
        // Vertical swipe starting on the widget strip (y ~ 500) must reach the blinds.
        long t = android.os.SystemClock.uptimeMillis();
        root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 540, 500, 0));
        for (int k = 1; k <= 14; k++) {
            root.dispatchTouchEvent(MotionEvent.obtain(t, t + k * 16, MotionEvent.ACTION_MOVE, 540, 500 + k * 60, 0));
        }
        org.junit.Assert.assertEquals("page stamped into blinds, live strip hidden", 0f, pager.getAlpha(), 0.001f);
        root.dispatchTouchEvent(MotionEvent.obtain(t, t + 260, MotionEvent.ACTION_UP, 540, 500 + 14 * 60, 0));
        c.pause().stop().destroy();
    }

    private static WidgetPager findPager(View v) {
        if (v instanceof WidgetPager) return (WidgetPager) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                WidgetPager r = findPager(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }
}

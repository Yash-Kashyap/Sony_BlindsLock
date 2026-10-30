package com.yash.blindslock;

import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.OverScroller;

import java.util.Collections;

/**
 * The Xperia-style widget strip at the top of the lock screen: pages side by side
 * (clock, the user's widgets, then a "+" page), swiped left / right. A vertical swipe that
 * starts here is handed to the blinds, so you can unlock from anywhere on the screen.
 */
public class WidgetPager extends ViewGroup {

    public interface Host {
        /** A vertical gesture started on the pager: forward these (root coordinates) to the blinds. */
        void onForwardToBlinds(MotionEvent rootEvent);
        void onPageChanged(int page);
        void onAddWidgetTapped();
    }

    private static final int MODE_NONE = 0, MODE_PAGING = 1, MODE_BLINDS = 2;

    private final OverScroller scroller;
    private final int touchSlop, minFling;
    private final float density;
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Host host;
    private VelocityTracker vt;
    private int mode = MODE_NONE;
    private float downX, downY, lastX;
    private MotionEvent downEvent;
    private int page;
    private int lastW = -1, lastH = -1;

    public WidgetPager(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        scroller = new OverScroller(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        touchSlop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity() * 3;
        setWillNotDraw(false);
        setClipChildren(true);
    }

    public void setHost(Host h) { host = h; }

    public int getPage() { return page; }

    public int pageCount() { return getChildCount(); }

    public View currentPageView() {
        return page < getChildCount() ? getChildAt(page) : null;
    }

    public void setPageImmediate(int p) {
        page = Math.max(0, Math.min(getChildCount() - 1, p));
        scroller.forceFinished(true);
        if (getWidth() > 0) scrollTo(page * getWidth(), 0);
        invalidate();
    }

    private int bottomPad() { return Math.round(18 * density); }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        int ch = Math.max(0, h - bottomPad());
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        int ch = Math.max(0, h - bottomPad());
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).layout(i * w, 0, (i + 1) * w, ch);
        }
        if (w != lastW || h != lastH) {
            lastW = w;
            lastH = h;
            scrollTo(page * w, 0);
            reportWidgetSizes(w, ch);
        }
    }

    /** Tell each widget how much room it has, so it can pick a suitable layout. */
    @SuppressWarnings("deprecation")
    private void reportWidgetSizes(int w, int h) {
        for (int i = 0; i < getChildCount(); i++) {
            View page = getChildAt(i);
            if (!(page instanceof ViewGroup) || ((ViewGroup) page).getChildCount() == 0) continue;
            View inner = ((ViewGroup) page).getChildAt(0);
            if (!(inner instanceof AppWidgetHostView)) continue;
            int wDp = Math.round((w - page.getPaddingLeft() - page.getPaddingRight()) / density);
            int hDp = Math.round((h - page.getPaddingTop() - page.getPaddingBottom()) / density);
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    ((AppWidgetHostView) inner).updateAppWidgetSize(new Bundle(),
                            Collections.singletonList(new SizeF(wDp, hDp)));
                } else {
                    ((AppWidgetHostView) inner).updateAppWidgetSize(null, wDp, hDp, wDp, hDp);
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        int n = getChildCount();
        if (n <= 1) return;
        float r = 3.2f * density, gap = 11 * density;
        float cx0 = getScrollX() + getWidth() / 2f - (n - 1) * gap / 2f;
        float cy = getHeight() - bottomPad() / 2f;
        float pos = getWidth() > 0 ? getScrollX() / (float) getWidth() : page;
        for (int i = 0; i < n; i++) {
            float k = Math.max(0f, 1f - Math.abs(pos - i));
            dotPaint.setColor(Color.argb(Math.round(90 + 150 * k), 255, 255, 255));
            canvas.drawCircle(cx0 + i * gap, cy, r * (0.8f + 0.3f * k), dotPaint);
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            postInvalidateOnAnimation();
        }
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startGesture(ev);
                if (!scroller.isFinished()) {
                    scroller.forceFinished(true);
                    mode = MODE_PAGING;
                    return true;
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                return decide(ev);
            default:
                return mode != MODE_NONE;
        }
    }

    private void startGesture(MotionEvent ev) {
        downX = lastX = ev.getX();
        downY = ev.getY();
        mode = MODE_NONE;
        if (downEvent != null) downEvent.recycle();
        downEvent = MotionEvent.obtain(ev);
        if (vt == null) vt = VelocityTracker.obtain();
        vt.clear();
        vt.addMovement(ev);
    }

    /** Decides between paging (horizontal) and unlocking (vertical). */
    private boolean decide(MotionEvent ev) {
        if (mode != MODE_NONE) return true;
        float dx = ev.getX() - downX, dy = ev.getY() - downY;
        if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) && getChildCount() > 1) {
            mode = MODE_PAGING;
            lastX = ev.getX();
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx)) {
            mode = MODE_BLINDS;
            if (host != null && downEvent != null) forward(downEvent, MotionEvent.ACTION_DOWN);
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            startGesture(ev);
            return true;
        }
        if (vt != null) vt.addMovement(ev);
        if (mode == MODE_NONE && action == MotionEvent.ACTION_MOVE) decide(ev);

        if (mode == MODE_PAGING) {
            if (action == MotionEvent.ACTION_MOVE) {
                float dx = ev.getX() - lastX;
                lastX = ev.getX();
                int max = Math.max(0, (getChildCount() - 1) * getWidth());
                int nx = Math.round(getScrollX() - dx);
                // rubber-band at the ends
                if (nx < 0 || nx > max) nx = Math.round(getScrollX() - dx * 0.35f);
                scrollTo(nx, 0);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                float v = 0;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    v = vt.getXVelocity();
                }
                int target = Math.round(getScrollX() / (float) Math.max(1, getWidth()));
                if (Math.abs(v) > minFling) target = v < 0 ? page + 1 : page - 1;
                snapTo(target);
                mode = MODE_NONE;
            }
            return true;
        }
        if (mode == MODE_BLINDS) {
            forward(ev, action);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) mode = MODE_NONE;
            return true;
        }
        if (action == MotionEvent.ACTION_UP) mode = MODE_NONE;
        return true;
    }

    private void forward(MotionEvent ev, int action) {
        if (host == null) return;
        MotionEvent e = MotionEvent.obtain(ev);
        e.setAction(action);
        e.offsetLocation(getLeft(), getTop());
        host.onForwardToBlinds(e);
        e.recycle();
    }

    private void snapTo(int target) {
        int n = getChildCount();
        target = Math.max(0, Math.min(n - 1, target));
        boolean changed = target != page;
        page = target;
        int dx = page * getWidth() - getScrollX();
        scroller.startScroll(getScrollX(), 0, dx, 0, Math.min(420, 160 + Math.abs(dx) / 3));
        postInvalidateOnAnimation();
        if (changed && host != null) host.onPageChanged(page);
    }

    // ------------------------------------------------------------------ pages

    /** Page 0 (optional): the big Xperia-style clock and date. */
    public static class ClockPage extends View {
        public ClockPage(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            float baseline = Math.min(h * 0.62f, w * 0.40f);
            SceneRenderer.drawClock(getContext(), canvas, w, baseline);
        }
    }

    /** Hosts one app widget, centred with a little margin. */
    public static class WidgetPage extends FrameLayout {
        public WidgetPage(Context c, View widget) {
            super(c);
            int pad = Math.round(14 * c.getResources().getDisplayMetrics().density);
            setPadding(pad, pad / 2, pad, pad / 2);
            addView(widget, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
    }

    /** Last page: an outlined frame with "+", like the Xperia lock screen. */
    public static class AddPage extends View {
        private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint plus = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float d;

        public AddPage(Context c, Runnable onTap) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(1.5f * d);
            frame.setColor(Color.argb(170, 255, 255, 255));
            frame.setPathEffect(new DashPathEffect(new float[]{8 * d, 6 * d}, 0));
            plus.setColor(Color.argb(230, 255, 255, 255));
            label.setColor(Color.argb(200, 255, 255, 255));
            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(14 * c.getResources().getDisplayMetrics().scaledDensity);
            setOnClickListener(v -> onTap.run());
            setContentDescription(c.getString(R.string.add_widget));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            float m = 22 * d;
            box.set(m, m * 0.5f, w - m, h - m * 0.5f);
            canvas.drawRoundRect(box, 14 * d, 14 * d, frame);
            float cx = w / 2f, cy = h / 2f - 10 * d, s = 22 * d, t = 3.5f * d;
            canvas.drawRoundRect(cx - s, cy - t / 2, cx + s, cy + t / 2, t, t, plus);
            canvas.drawRoundRect(cx - t / 2, cy - s, cx + t / 2, cy + s, t, t, plus);
            canvas.drawText(getContext().getString(R.string.add_widget), cx, cy + s + 26 * d, label);
        }
    }
}

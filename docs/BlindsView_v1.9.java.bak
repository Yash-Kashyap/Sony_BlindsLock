package com.yash.blindslock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Renders the lock screen "face" (wallpaper + clock) as horizontal slats - venetian blinds -
 * in the style of the Xperia Z / Z1 lock screen.
 *
 * <ul>
 *   <li>Every slat is a damped spring (angle + angular velocity).</li>
 *   <li>Only the user-chosen number of slats around the finger turn (a moving window); how far
 *       they turn grows with swipe distance and finger speed. Slats left behind settle fast and
 *       without wobble, so the visible count stays at that number.</li>
 *   <li>Unlocking opens just those slats while the whole face fades out - no full-screen wave.</li>
 *   <li>Touching down gives the nearby slats a kick, so even a tap ripples.</li>
 *   <li>Slats twist along their length: they turn most at the finger's x position.</li>
 *   <li>Each slat is drawn as a turning prism (outgoing + incoming face, both carrying the same
 *       slice of the picture), as a textured triangle mesh with per-vertex lighting.</li>
 * </ul>
 */
public class BlindsView extends View {

    public interface Listener {
        void onUnlockGesture();
        void onRevealFinished();
        /** A finger went down on the blinds (before any movement). */
        default void onBlindsTouchDown() { }
        /** All slats are flat and still again (and no finger is down). */
        default void onBlindsSettled() { }
    }

    private static final int STATE_IDLE = 0;
    private static final int STATE_TOUCH = 1;
    private static final int STATE_REVEAL = 3;

    private static final float DEG = (float) (Math.PI / 180.0);
    private static final float MAX_ANGLE = 84f * DEG;
    private static final int COLS = 18; // mesh columns per slat while twisting

    private static final float SPRING_K = (float) Math.pow(2 * Math.PI * 6.5, 2);
    private static final float SPRING_C = (float) (2 * 0.42 * Math.sqrt(SPRING_K));

    private static final long REVEAL_SPREAD_MS = 60;   // within the band only
    private static final long REVEAL_FLIP_MS = 240;
    private static final long FACE_FADE_MS = 260;
    private static final float SPRING_K_OUT = SPRING_K * 2.5f;   // outside the band: stiff,
    private static final float SPRING_C_OUT = (float) (2 * Math.sqrt(SPRING_K_OUT)); // no wobble
    private static final long BACKDROP_FADE_MS = 180;

    private final Paint texPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint colorPaint = new Paint();
    private final Paint plainPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final float density;
    private final int touchSlop;

    private Listener listener;
    private Haptics haptics;
    private Bitmap scene;
    private int stripPx = 64;
    private float unlockFraction = 0.25f;
    private int bandSlats = 6;
    private float faceAlpha = 1f;
    private long faceFadeStart = -1;
    private boolean[] inReveal = new boolean[0];

    private int stripCount;
    private float[] angle = new float[0];
    private float[] velocity = new float[0];
    private float[] revealFrom = new float[0];
    private long[] revealStartMs = new long[0];

    // mesh buffers
    private float[] tVerts = new float[0], tTexs = new float[0];
    private float[] cVerts = new float[0];
    private int[] cColors = new int[0];
    private int tCount, cCount; // vertices (not floats)
    private boolean forceRectRenderer;
    private boolean wasMoving;
    private final float[] geomA = new float[5], geomB = new float[5];

    private int state = STATE_IDLE;
    private float downX, downY, touchX = -1, touchY = -1, progress;
    private long downTime, lastMoveTime;
    private float fingerSpeed; // px/s, smoothed
    private float direction = 1f;
    private boolean thresholdBuzzed;
    private int lastSlatIndex = -1;
    private long lastFrameNs;
    private float twistX = -1; // x the slats twist around (last finger x)

    private float backdrop = 1f, backdropFrom = 1f, backdropTarget = 1f;
    private long backdropAnimStart;
    private boolean revealDone;
    private boolean allowFinishWhenOpaque = true;

    public BlindsView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        colorPaint.setColor(Color.WHITE);
        setHapticFeedbackEnabled(false); // we drive the vibrator ourselves
    }

    public void setListener(Listener l) { listener = l; }

    public void configure(int stripHeightPx, float unlockFrac, int slatsMoving, Haptics h) {
        stripPx = Math.max(8, stripHeightPx);
        bandSlats = Math.max(2, Math.min(16, slatsMoving));
        unlockFraction = Math.max(0.1f, Math.min(0.6f, unlockFrac));
        haptics = h;
        allocate(true);
        invalidate();
    }

    public void setScene(Bitmap bmp) {
        scene = bmp;
        if (bmp != null) {
            texPaint.setShader(new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        }
        invalidate();
    }

    public Bitmap getScene() { return scene; }

    public void resetToLocked() {
        state = STATE_IDLE;
        backdrop = backdropFrom = backdropTarget = 1f;
        revealDone = false;
        faceAlpha = 1f;
        faceFadeStart = -1;
        applyFaceAlpha();
        progress = 0;
        fingerSpeed = 0;
        for (int i = 0; i < stripCount; i++) { angle[i] = 0; velocity[i] = 0; }
        invalidate();
    }


    public void startReveal(boolean backdropClear) {
        long now = SystemClock.uptimeMillis();
        int h = Math.max(1, getHeight());
        float pivot = touchY > 0 ? touchY : h * 0.5f;
        float halfW = bandHalfWidth();
        for (int i = 0; i < stripCount; i++) {
            float d = Math.abs((i + 0.5f) * stripPx - pivot);
            // Only the slats in the finger's band (or still moving) open; the rest stay put.
            inReveal[i] = d < halfW;
            revealStartMs[i] = now + (long) (d / halfW * REVEAL_SPREAD_MS);
            revealFrom[i] = angle[i];
            if (inReveal[i]) velocity[i] = 0;
        }
        state = STATE_REVEAL;
        revealDone = false;
        allowFinishWhenOpaque = !backdropClear;
        if (backdropClear) setBackdropClear();
        kick();
    }

    public void setBackdropClear() {
        if (backdropTarget == 0f) return;
        backdropFrom = backdrop;
        backdropTarget = 0f;
        backdropAnimStart = SystemClock.uptimeMillis();
        allowFinishWhenOpaque = true;
        if (faceFadeStart < 0) faceFadeStart = backdropAnimStart;
        kick();
    }

    public void forceFinishAllowed() {
        allowFinishWhenOpaque = true;
        if (faceFadeStart < 0) faceFadeStart = SystemClock.uptimeMillis();
        kick();
    }

    /** Half the height of the window of slats that follow the finger. */
    private float bandHalfWidth() {
        return (bandSlats * 0.5f + 0.5f) * stripPx;
    }

    /** Fades the whole lock face (a cheap render-node property, works with every pipeline). */
    private void applyFaceAlpha() {
        if (getAlpha() != faceAlpha) setAlpha(faceAlpha);
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        allocate(false);
    }

    private void allocate(boolean force) {
        int h = getHeight();
        if (h <= 0) return;
        int n = (h + stripPx - 1) / stripPx;
        if (n == stripCount && !force) return;
        stripCount = n;
        angle = new float[n];
        velocity = new float[n];
        revealFrom = new float[n];
        revealStartMs = new long[n];
        inReveal = new boolean[n];
        int quadsTex = n * COLS * 2 + n;          // two faces per column (+ flat batches)
        int quadsCol = n * COLS * 3;              // two face shades + edge highlight
        tVerts = new float[quadsTex * 12];
        tTexs = new float[quadsTex * 12];
        cVerts = new float[quadsCol * 12];
        cColors = new int[quadsCol * 6];
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (state == STATE_REVEAL) return true;
        int h = Math.max(1, getHeight());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = touchX = twistX = e.getX();
                downY = touchY = e.getY();
                downTime = lastMoveTime = e.getEventTime();
                progress = 0;
                fingerSpeed = 0;
                thresholdBuzzed = false;
                lastSlatIndex = (int) (touchY / stripPx);
                state = STATE_TOUCH;
                wasMoving = true;
                if (listener != null) listener.onBlindsTouchDown();
                // Tap ripple: the slats in the band get a little kick.
                float halfW = bandHalfWidth();
                for (int i = 0; i < stripCount; i++) {
                    float d = (i + 0.5f) * stripPx - touchY;
                    if (Math.abs(d) < halfW) velocity[i] += 11f * window(d, halfW);
                }
                if (haptics != null) haptics.touchDown();
                kick();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float prevX = touchX, prevY = touchY;
                long prevT = lastMoveTime;
                touchX = twistX = e.getX();
                touchY = e.getY();
                lastMoveTime = e.getEventTime();
                float dt = Math.max(1, lastMoveTime - prevT) / 1000f;
                float inst = (float) Math.hypot(touchX - prevX, touchY - prevY) / dt;
                fingerSpeed = 0.55f * fingerSpeed + 0.45f * Math.min(inst, 8000f);
                if (Math.abs(touchY - prevY) > 0.5f) direction = touchY < prevY ? 1f : -1f;

                // Unlock is vertical only: "Swipe up or down to unlock".
                float dist = Math.abs(touchY - downY);
                progress = Math.min(1f, dist / (unlockFraction * h));

                int idx = (int) (touchY / stripPx);
                if (idx != lastSlatIndex && haptics != null) {
                    float sp = Math.min(1f, fingerSpeed / (2600f * density));
                    haptics.slatTick(0.16f + 0.5f * sp + 0.3f * progress);
                }
                lastSlatIndex = idx;
                if (progress >= 1f && !thresholdBuzzed) {
                    thresholdBuzzed = true;
                    if (haptics != null) haptics.threshold();
                }
                kick();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean unlock = false;
                float dist = Math.abs(e.getY() - downY);
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    boolean mostlyVertical = Math.abs(e.getY() - downY) > Math.abs(e.getX() - downX);
                    boolean fling = mostlyVertical && fingerSpeed > 1500 * density
                            && e.getEventTime() - lastMoveTime < 60 && dist > 0.10f * h;
                    unlock = dist >= unlockFraction * h || fling;
                    performClick();
                }
                state = STATE_IDLE;
                if (unlock) {
                    if (listener != null) listener.onUnlockGesture();
                } else if (progress > 0.15f && haptics != null) {
                    haptics.cancel();
                }
                kick();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    // ------------------------------------------------------------------ animation

    private void kick() {
        lastFrameNs = 0;
        postInvalidateOnAnimation();
    }

    private boolean step() {
        long nowNs = SystemClock.elapsedRealtimeNanos();
        float dt = lastFrameNs == 0 ? 1f / 60f : (nowNs - lastFrameNs) / 1e9f;
        lastFrameNs = nowNs;
        dt = Math.min(dt, 1f / 30f);
        boolean moving = false;
        long nowMs = SystemClock.uptimeMillis();

        if (backdrop != backdropTarget) {
            float t = Math.min(1f, (nowMs - backdropAnimStart) / (float) BACKDROP_FADE_MS);
            backdrop = backdropFrom + (backdropTarget - backdropFrom) * t;
            moving = true;
        }

        if (state == STATE_REVEAL) {
            boolean allDone = true;
            if (faceFadeStart >= 0) {
                float ft = Math.min(1f, (nowMs - faceFadeStart) / (float) FACE_FADE_MS);
                faceAlpha = 1f - ft * ft;
                applyFaceAlpha();
                if (ft < 1f) allDone = false;
            } else {
                allDone = false;
            }
            for (int i = 0; i < stripCount; i++) {
                if (!inReveal[i]) {
                    // Slats outside the band just settle flat (stiff, no wobble).
                    float ang = angle[i], vel = velocity[i];
                    if (ang != 0f || vel != 0f) {
                        int sub = Math.max(1, (int) Math.ceil(dt / (1f / 240f)));
                        float hs = dt / sub;
                        for (int k = 0; k < sub; k++) {
                            float acc = SPRING_K_OUT * (0f - ang) - SPRING_C_OUT * vel;
                            vel += acc * hs;
                            ang += vel * hs;
                        }
                        if (Math.abs(ang) < 0.0015f && Math.abs(vel) < 0.01f) { ang = 0; vel = 0; }
                        angle[i] = ang;
                        velocity[i] = vel;
                    }
                    continue;
                }
                float sign = revealFrom[i] == 0 ? direction : Math.signum(revealFrom[i]);
                float to = sign * 90f * DEG;
                float t = (nowMs - revealStartMs[i]) / (float) REVEAL_FLIP_MS;
                if (t < 0) { allDone = false; continue; }
                if (t >= 1f) { angle[i] = to; continue; }
                allDone = false;
                float ease = t * t * (3f - 2f * t);
                angle[i] = revealFrom[i] + (to - revealFrom[i]) * ease;
            }
            if (allDone && !revealDone && backdrop == backdropTarget && allowFinishWhenOpaque) {
                revealDone = true;
                if (listener != null) mainHandler.post(listener::onRevealFinished);
                return false;
            }
            return true;
        }

        boolean touching = state == STATE_TOUCH;
        int h = Math.max(1, getHeight());
        // Finger speed fades when the finger rests.
        fingerSpeed *= (float) Math.exp(-dt * 7f);
        float speedBoost = Math.min(1f, fingerSpeed / (1800f * density)) * 32f;
        float thetaHold = Math.min(82f, 42f + 34f * progress + speedBoost) * DEG;
        float halfW = bandHalfWidth();

        int sub = Math.max(1, (int) Math.ceil(dt / (1f / 240f)));
        float hs = dt / sub;
        for (int i = 0; i < stripCount; i++) {
            float target = 0f;
            boolean inBand = false;
            if (touching) {
                float d = (i + 0.5f) * stripPx - touchY;
                inBand = Math.abs(d) < halfW;
                if (inBand) target = direction * thetaHold * window(d, halfW);
            }
            // Slats the finger has left settle quickly and cleanly, so only the chosen number
            // of blinds is ever visibly turned.
            float k = (touching && !inBand) ? SPRING_K_OUT : SPRING_K;
            float cdamp = (touching && !inBand) ? SPRING_C_OUT : SPRING_C;
            float a = angle[i], v = velocity[i];
            for (int s = 0; s < sub; s++) {
                float acc = k * (target - a) - cdamp * v;
                v += acc * hs;
                a += v * hs;
            }
            if (a > MAX_ANGLE) { a = MAX_ANGLE; v = Math.min(v, 0); }
            if (a < -MAX_ANGLE) { a = -MAX_ANGLE; v = Math.max(v, 0); }
            if (!touching && Math.abs(a) < 0.0015f && Math.abs(v) < 0.01f) {
                a = 0; v = 0;
            } else {
                moving = true;
            }
            angle[i] = a;
            velocity[i] = v;
        }
        return moving;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        boolean again = step();
        if (again) {
            wasMoving = true;
        } else if (wasMoving && state == STATE_IDLE) {
            wasMoving = false;
            if (listener != null) mainHandler.post(listener::onBlindsSettled);
        }
        int w = getWidth(), h = getHeight();
        if (backdrop > 0f) canvas.drawColor(Color.argb(Math.round(backdrop * 255), 6, 8, 12));
        if (scene == null || stripCount == 0) {
            if (again) postInvalidateOnAnimation();
            return;
        }

        boolean anyTilt = false;
        for (int i = 0; i < stripCount; i++) if (angle[i] != 0f) { anyTilt = true; break; }
        if (!anyTilt) {
            src.set(0, 0, scene.getWidth(), scene.getHeight());
            dst.set(0, 0, w, h);
            canvas.drawBitmap(scene, src, dst, plainPaint);
            if (again) postInvalidateOnAnimation();
            return;
        }

        boolean meshOk = !forceRectRenderer
                && (Build.VERSION.SDK_INT >= 29 || !canvas.isHardwareAccelerated());
        if (!meshOk) {
            // Android 9's hardware renderer silently drops Canvas.drawVertices(), so draw the
            // slats with plain bitmap/rect calls (supported everywhere).
            drawWithRects(canvas, w, h);
            if (again) postInvalidateOnAnimation();
            return;
        }
        buildMesh(w, h);
        if (tCount > 0) {
            canvas.drawVertices(Canvas.VertexMode.TRIANGLES, tCount * 2, tVerts, 0, tTexs, 0,
                    null, 0, null, 0, 0, texPaint);
        }
        if (cCount > 0) {
            canvas.drawVertices(Canvas.VertexMode.TRIANGLES, cCount * 2, cVerts, 0, null, 0,
                    cColors, 0, null, 0, 0, colorPaint);
        }
        if (again) postInvalidateOnAnimation();
    }

    /** Test hook: draw with the Android 9 renderer. */
    void setForceRectRenderer(boolean v) { forceRectRenderer = v; invalidate(); }

    private static final int RECT_COLS = 30;
    private final Paint fillPaint = new Paint();

    /** Same prism model as the mesh, drawn as per-column bitmap slices and rects. */
    private void drawWithRects(Canvas canvas, int w, int h) {
        float sx = scene.getWidth() / (float) w;
        float sy = scene.getHeight() / (float) h;
        boolean incomingEmpty = state == STATE_REVEAL;
        float[] g = geomA;
        int i = 0;
        while (i < stripCount) {
            if (angle[i] == 0f) {
                int j = i;
                while (j < stripCount && angle[j] == 0f) j++;
                int top = i * stripPx, bottom = Math.min(h, j * stripPx);
                src.set(0, Math.round(top * sy), scene.getWidth(), Math.round(bottom * sy));
                dst.set(0, top, w, bottom);
                canvas.drawBitmap(scene, src, dst, plainPaint);
                i = j;
                continue;
            }
            int top = i * stripPx, bottom = Math.min(h, (i + 1) * stripPx);
            int vT = Math.round(top * sy), vB = Math.max(vT + 1, Math.round(bottom * sy));
            int cols = state == STATE_REVEAL ? 1 : RECT_COLS;
            boolean up = angle[i] > 0;
            for (int c = 0; c < cols; c++) {
                int x0 = Math.round(w * c / (float) cols), x1 = Math.round(w * (c + 1) / (float) cols);
                // Gentler twist here: per-column slices show steps if it varies too much.
                float a = angle[i] * (0.4f + 0.6f * twist((x0 + x1) * 0.5f, w));
                geom(a, top, bottom, g);
                src.set(Math.round(x0 * sx), vT, Math.round(x1 * sx), vB);
                if (g[1] - g[0] > 0.3f) {
                    dst.set(x0, g[0], x1, g[1]);
                    canvas.drawBitmap(scene, src, dst, plainPaint);
                    fillPaint.setColor(shadeA(a, up));
                    if (Color.alpha(fillPaint.getColor()) > 0) canvas.drawRect(dst, fillPaint);
                }
                if (!incomingEmpty && g[3] - g[2] > 0.3f) {
                    dst.set(x0, g[2], x1, g[3]);
                    canvas.drawBitmap(scene, src, dst, plainPaint);
                    fillPaint.setColor(shadeB(a, up));
                    if (Color.alpha(fillPaint.getColor()) > 0) canvas.drawRect(dst, fillPaint);
                }
                float e = Math.max(1f, (bottom - top) * 0.05f);
                fillPaint.setColor(edge(a));
                canvas.drawRect(x0, g[4] - e * 0.5f, x1, g[4] + e * 0.5f, fillPaint);
            }
            i++;
        }
    }

    private float twist(float x, int w) {
        if (state == STATE_REVEAL || twistX < 0) return 1f;
        float sx = 0.40f * w;
        float d = x - twistX;
        return 0.42f + 0.58f * (float) Math.exp(-d * d / (2 * sx * sx));
    }

    private void buildMesh(int w, int h) {
        tCount = 0;
        cCount = 0;
        float sx = scene.getWidth() / (float) w;
        float sy = scene.getHeight() / (float) h;
        boolean incomingEmpty = state == STATE_REVEAL;
        float[] g0 = geomA, g1 = geomB;

        int i = 0;
        while (i < stripCount) {
            if (angle[i] == 0f) {
                int j = i;
                while (j < stripCount && angle[j] == 0f) j++;
                float top = i * stripPx, bottom = Math.min(h, j * stripPx);
                texQuad(0, top, bottom, w, top, bottom, 0, w * sx, top * sy, bottom * sy);
                i = j;
                continue;
            }
            float top = i * stripPx, bottom = Math.min(h, (i + 1) * stripPx);
            float vT = top * sy, vB = bottom * sy;
            int cols = state == STATE_REVEAL ? 1 : COLS;
            for (int c = 0; c < cols; c++) {
                float x0 = w * c / (float) cols, x1 = w * (c + 1) / (float) cols;
                float a0 = angle[i] * twist(x0, w), a1 = angle[i] * twist(x1, w);
                geom(a0, top, bottom, g0);
                geom(a1, top, bottom, g1);
                // g: [aTop, aBot, bTop, bBot, edgeY]
                texQuad(x0, g0[0], g0[1], x1, g1[0], g1[1], x0 * sx, x1 * sx, vT, vB);
                if (!incomingEmpty) {
                    texQuad(x0, g0[2], g0[3], x1, g1[2], g1[3], x0 * sx, x1 * sx, vT, vB);
                }
                boolean up = angle[i] > 0;
                colorQuad(x0, g0[0], g0[1], x1, g1[0], g1[1], shadeA(a0, up), shadeA(a1, up));
                if (!incomingEmpty) {
                    colorQuad(x0, g0[2], g0[3], x1, g1[2], g1[3], shadeB(a0, up), shadeB(a1, up));
                }
                float e = Math.max(1f, (bottom - top) * 0.05f);
                colorQuad(x0, g0[4] - e * 0.5f, g0[4] + e * 0.5f, x1, g1[4] - e * 0.5f, g1[4] + e * 0.5f,
                        edge(a0), edge(a1));
            }
            i++;
        }
    }

    /** Smooth bump: 1 at the finger, 0 at the band edge. */
    private static float window(float d, float halfW) {
        float c = (float) Math.cos(Math.PI * 0.5 * d / halfW);
        return c * c;
    }

    /** Prism geometry for one slat at signed angle a. */
    private static void geom(float a, float top, float bottom, float[] out) {
        float abs = Math.abs(a);
        float c = (float) Math.cos(abs), s = (float) Math.sin(abs);
        float slot = bottom - top;
        float hA = slot * c / (c + s);
        if (a >= 0) {
            out[0] = top; out[1] = top + hA; out[2] = out[1]; out[3] = bottom; out[4] = out[1];
        } else {
            out[2] = top; out[3] = bottom - hA; out[0] = out[3]; out[1] = bottom; out[4] = out[0];
        }
    }

    private static int shadeA(float a, boolean up) {
        float s = (float) Math.sin(Math.abs(a));
        return up ? Color.argb(clamp255(255f * (0.42f * s * s + 0.06f * s)), 240, 246, 255)
                  : Color.argb(clamp255(255f * (0.40f * s * s + 0.06f * s)), 0, 0, 0);
    }

    private static int shadeB(float a, boolean up) {
        float c = (float) Math.cos(Math.abs(a));
        return up ? Color.argb(clamp255(255f * (0.55f * c * c + 0.05f)), 0, 0, 0)
                  : Color.argb(clamp255(255f * (0.60f * c * c + 0.05f)), 240, 246, 255);
    }

    private static int edge(float a) {
        float abs = Math.abs(a);
        float m = Math.min((float) Math.cos(abs), (float) Math.sin(abs));
        return Color.argb(clamp255(255f * 0.7f * Math.min(1f, 2.2f * m)), 255, 255, 255);
    }

    private void texQuad(float x0, float t0, float b0, float x1, float t1, float b1,
                         float u0, float u1, float vT, float vB) {
        if (b0 - t0 < 0.05f && b1 - t1 < 0.05f) return;
        int k = tCount * 2;
        // tri 1: (x0,t0) (x1,t1) (x1,b1)
        tVerts[k] = x0; tVerts[k + 1] = t0; tTexs[k] = u0; tTexs[k + 1] = vT;
        tVerts[k + 2] = x1; tVerts[k + 3] = t1; tTexs[k + 2] = u1; tTexs[k + 3] = vT;
        tVerts[k + 4] = x1; tVerts[k + 5] = b1; tTexs[k + 4] = u1; tTexs[k + 5] = vB;
        // tri 2: (x0,t0) (x1,b1) (x0,b0)
        tVerts[k + 6] = x0; tVerts[k + 7] = t0; tTexs[k + 6] = u0; tTexs[k + 7] = vT;
        tVerts[k + 8] = x1; tVerts[k + 9] = b1; tTexs[k + 8] = u1; tTexs[k + 9] = vB;
        tVerts[k + 10] = x0; tVerts[k + 11] = b0; tTexs[k + 10] = u0; tTexs[k + 11] = vB;
        tCount += 6;
    }

    private void colorQuad(float x0, float t0, float b0, float x1, float t1, float b1, int col0, int col1) {
        if (Color.alpha(col0) == 0 && Color.alpha(col1) == 0) return;
        if (b0 - t0 < 0.05f && b1 - t1 < 0.05f) return;
        int k = cCount * 2, ci = cCount;
        cVerts[k] = x0; cVerts[k + 1] = t0; cColors[ci] = col0;
        cVerts[k + 2] = x1; cVerts[k + 3] = t1; cColors[ci + 1] = col1;
        cVerts[k + 4] = x1; cVerts[k + 5] = b1; cColors[ci + 2] = col1;
        cVerts[k + 6] = x0; cVerts[k + 7] = t0; cColors[ci + 3] = col0;
        cVerts[k + 8] = x1; cVerts[k + 9] = b1; cColors[ci + 4] = col1;
        cVerts[k + 10] = x0; cVerts[k + 11] = b0; cColors[ci + 5] = col0;
        cCount += 6;
    }

    private static int clamp255(float v) {
        return v < 0 ? 0 : (v > 255 ? 255 : Math.round(v));
    }
}

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
 * <p>The finger presses a <b>dimple</b> into the blinds (see docs/PLAN_v2.0_depth.md):
 * <ul>
 *   <li>One damped press spring drives everything (0 = flat, 1 = fully pressed).</li>
 *   <li>Slats near the finger are pushed <i>into</i> the screen (deepest at the finger); slats
 *       above and below tilt the opposite way, so it reads as a V-shaped dent.</li>
 *   <li>A mild perspective makes deeper edges narrower. Black shows through the gaps.</li>
 *   <li>Slats above the finger darken, slats below brighten; each folded slat gets a shadow from
 *       its top edge and a thin seam on both edges.</li>
 *   <li>Unlocking keeps the dent and fades the whole face out.</li>
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

    // ---- look (tune on device) ------------------------------------------------------------
    /** Max slat tilt, reached about half a window away from the finger. */
    private static final float THETA_MAX = 52f * DEG;
    /** Max push into the screen at the finger, in slat heights. */
    private static final float DEPTH_SLATS = 0.9f;
    /** Perspective distance in view widths. */
    private static final float EYE_WIDTHS = 3.0f;
    /** In-plane lean toward the finger's side (degrees at full press). */
    private static final float ROLL_DEG = 3f;
    private static final float SHADE_DARK = 0.80f;   // above finger: black alpha per sin(theta)
    private static final float SHADE_LIGHT = 0.42f;  // below finger: white alpha per sin(theta)
    private static final float SHADE_DEPTH = 0.30f;  // deeper = darker
    private static final float SHADOW_DP = 30f;      // top-edge shadow length
    private static final float SHADOW_ALPHA = 0.72f;
    private static final float SEAM_DP = 1.2f;
    private static final float SEAM_ALPHA = 0.75f;

    // ---- dynamics -------------------------------------------------------------------------
    private static final float SPRING_K = 650f;
    private static final float SPRING_C = (float) (2 * 0.9 * Math.sqrt(SPRING_K));
    private static final float FOLLOW_RATE = 32f;     // finger smoothing, 1/s
    private static final float TAP_KICK = 6f;
    private static final float REVEAL_TARGET = 1.25f;
    private static final long FACE_FADE_MS = 220;
    private static final long BACKDROP_FADE_MS = 180;
    private static final float EPS_POS = 0.0015f, EPS_VEL = 0.01f;

    private final Paint texPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint colorPaint = new Paint();
    private final Paint plainPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final float density;

    private Listener listener;
    private Haptics haptics;
    private Bitmap scene;
    private int stripPx = 64;
    private float unlockFraction = 0.25f;
    private int bandSlats = 6;
    private float faceAlpha = 1f;
    private long faceFadeStart = -1;

    private int stripCount;

    // mesh buffers
    private float[] tVerts = new float[0], tTexs = new float[0];
    private float[] cVerts = new float[0];
    private int[] cColors = new int[0];
    private int tCount, cCount; // vertices (not floats)
    private boolean forceRectRenderer;
    private boolean wasMoving;
    /** When this view last drew; lets the service notice a lock screen that froze after wake. */
    static volatile long lastDrawUptime;

    // press spring (0 flat .. 1 pressed) and the smoothed point it presses at
    private float press, pressVel;
    private float px = -1, py = -1;

    private int state = STATE_IDLE;
    private float downX, downY, touchX = -1, touchY = -1, progress;
    private long lastMoveTime;
    private float fingerSpeed; // px/s, smoothed
    private boolean thresholdBuzzed;
    private int lastSlatIndex = -1;
    private long lastFrameNs;

    private float backdrop = 1f, backdropFrom = 1f, backdropTarget = 1f;
    private long backdropAnimStart;
    private boolean revealDone;
    private boolean allowFinishWhenOpaque = true;

    public BlindsView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
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
        press = 0;
        pressVel = 0;
        invalidate();
    }

    public void startReveal(boolean backdropClear) {
        // The dent stays where the finger left it and the whole face fades out.
        if (touchY > 0) { px = touchX; py = touchY; }
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

    /** Half the height of the window of slats that feel the finger. */
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
        int quadsTex = n + 1;      // one quad per slat (+ flat batches never exceed n)
        int quadsCol = n * 5;      // tint, depth, shadow, two seams
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
                downX = touchX = px = e.getX();
                downY = touchY = py = e.getY();
                lastMoveTime = e.getEventTime();
                progress = 0;
                fingerSpeed = 0;
                thresholdBuzzed = false;
                lastSlatIndex = (int) (touchY / stripPx);
                state = STATE_TOUCH;
                wasMoving = true;
                if (listener != null) listener.onBlindsTouchDown();
                pressVel += TAP_KICK;
                if (haptics != null) haptics.touchDown();
                kick();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float prevX = touchX, prevY = touchY;
                long prevT = lastMoveTime;
                touchX = e.getX();
                touchY = e.getY();
                lastMoveTime = e.getEventTime();
                float dt = Math.max(1, lastMoveTime - prevT) / 1000f;
                float inst = (float) Math.hypot(touchX - prevX, touchY - prevY) / dt;
                fingerSpeed = 0.55f * fingerSpeed + 0.45f * Math.min(inst, 8000f);

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

        boolean revealing = state == STATE_REVEAL;
        boolean touching = state == STATE_TOUCH;
        fingerSpeed *= (float) Math.exp(-dt * 7f); // fades when the finger rests

        float target = 0f;
        if (revealing) {
            target = REVEAL_TARGET;
        } else if (touching) {
            float speedBoost = Math.min(1f, fingerSpeed / (1800f * density)) * 0.15f;
            target = 1f + 0.25f * progress + speedBoost;
            float a = 1f - (float) Math.exp(-dt * FOLLOW_RATE);
            px += (touchX - px) * a;
            py += (touchY - py) * a;
        }

        int sub = Math.max(1, (int) Math.ceil(dt / (1f / 240f)));
        float hs = dt / sub;
        float p = press, v = pressVel;
        for (int s = 0; s < sub; s++) {
            v += (SPRING_K * (target - p) - SPRING_C * v) * hs;
            p += v * hs;
        }
        if (p < 0f) { p = 0f; v = Math.max(v, 0f); }
        if (!touching && !revealing && p < EPS_POS && Math.abs(v) < EPS_VEL) {
            p = 0f; v = 0f;
        } else {
            moving = true;
        }
        press = p;
        pressVel = v;

        if (revealing) {
            boolean done = false;
            if (faceFadeStart < 0 && allowFinishWhenOpaque) faceFadeStart = nowMs;
            if (faceFadeStart >= 0) {
                float ft = Math.min(1f, (nowMs - faceFadeStart) / (float) FACE_FADE_MS);
                faceAlpha = 1f - ft * ft;
                applyFaceAlpha();
                done = ft >= 1f;
            }
            if (done && !revealDone && backdrop == backdropTarget && allowFinishWhenOpaque) {
                revealDone = true;
                if (listener != null) mainHandler.post(listener::onRevealFinished);
                return false;
            }
            return true;
        }
        return moving;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        lastDrawUptime = SystemClock.uptimeMillis();
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

        if (press <= EPS_POS) {
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

    /** Per-slat dent geometry, filled by {@link #slatGeom}. */
    private static final class Geom {
        float d;             // signed distance to the finger in half windows, (-1, 1) inside
        float theta;         // tilt, <0 above the finger
        float fold;          // depth envelope, 0..press
        float yTop, yBot;    // projected edge centres (before roll)
        float kTop, kBot;    // perspective scale of each edge (<1 = deeper)
        float rollTan;       // in-plane lean
        boolean active;
    }
    private final Geom g = new Geom();

    /** Pure maths: where slat i ends up for the current press / finger point. */
    private void slatGeom(int i, int w, int h, Geom out) {
        float top = i * stripPx, bottom = Math.min(h, (i + 1) * stripPx);
        float yc = (top + bottom) * 0.5f, hh = (bottom - top) * 0.5f;
        float d = (yc - py) / bandHalfWidth();
        out.d = d;
        out.active = Math.abs(d) < 1f && press > EPS_POS;
        if (!out.active) {
            out.theta = 0; out.fold = 0; out.yTop = top; out.yBot = bottom;
            out.kTop = out.kBot = 1f; out.rollTan = 0;
            return;
        }
        float pi = (float) Math.PI;
        float fold = 0.5f * (1f + (float) Math.cos(pi * d)) * press;
        float theta = THETA_MAX * press * (float) Math.sin(pi * d);
        float st = (float) Math.sin(theta), ct = (float) Math.cos(theta);
        float z = DEPTH_SLATS * stripPx * fold;               // into the screen
        float zTop = z + hh * st, zBot = z - hh * st;         // above finger: bottom edge deeper
        float f = EYE_WIDTHS * w;
        out.theta = theta;
        out.fold = fold;
        out.kTop = f / (f + zTop);
        out.kBot = f / (f + zBot);
        out.yTop = yc - hh * ct * out.kTop;
        out.yBot = yc + hh * ct * out.kBot;
        float lean = (0.5f - px / Math.max(1, w)) * ROLL_DEG * DEG * Math.min(1f, fold);
        out.rollTan = (float) Math.tan(lean);
    }

    /** First slat at or after i that is dented, or stripCount. */
    private int nextActive(int i, int w, int h) {
        int j = i;
        while (j < stripCount) {
            slatGeom(j, w, h, g);
            if (g.active) break;
            j++;
        }
        return j;
    }

    /** Same dent model, drawn with per-slat bitmap/rect calls (no mesh). */
    private void drawWithRects(Canvas canvas, int w, int h) {
        float sx = scene.getWidth() / (float) w;
        float sy = scene.getHeight() / (float) h;
        int i = 0;
        while (i < stripCount) {
            slatGeom(i, w, h, g);
            if (!g.active) {
                int j = nextActive(i, w, h);
                int top = i * stripPx, bottom = Math.min(h, j * stripPx);
                src.set(0, Math.round(top * sy), scene.getWidth(), Math.round(bottom * sy));
                dst.set(0, top, w, bottom);
                canvas.drawBitmap(scene, src, dst, plainPaint);
                i = j;
                continue;
            }
            int top = i * stripPx, bottom = Math.min(h, (i + 1) * stripPx);
            int vT = Math.round(top * sy);
            src.set(0, vT, scene.getWidth(), Math.max(vT + 1, Math.round(bottom * sy)));
            float half = w * 0.25f * (g.kTop + g.kBot);
            if (g.yBot - g.yTop > 0.3f) {
                dst.set(w * 0.5f - half, g.yTop, w * 0.5f + half, g.yBot);
                canvas.drawBitmap(scene, src, dst, plainPaint);
                fillPaint.setColor(tint(g));
                canvas.drawRect(dst, fillPaint);
                fillPaint.setColor(Color.argb(clamp255(255f * SHADE_DEPTH * g.fold), 0, 0, 0));
                canvas.drawRect(dst, fillPaint);
                float sh = Math.min((g.yBot - g.yTop) * 0.3f, SHADOW_DP * density * (1f - Math.abs(g.d)) * 0.5f);
                fillPaint.setColor(Color.argb(clamp255(255f * SHADOW_ALPHA * 0.6f * Math.min(1f, g.fold * 1.6f)), 0, 0, 0));
                canvas.drawRect(dst.left, g.yTop, dst.right, g.yTop + sh, fillPaint);
            }
            fillPaint.setColor(Color.argb(clamp255(255f * SEAM_ALPHA * Math.min(1f, g.fold * 4f)), 43, 43, 43));
            float sm = SEAM_DP * density;
            canvas.drawRect(0, g.yTop - sm * 0.5f, w, g.yTop + sm * 0.5f, fillPaint);
            canvas.drawRect(0, g.yBot - sm * 0.5f, w, g.yBot + sm * 0.5f, fillPaint);
            i++;
        }
    }

    /** Above the finger the slat faces down (dark), below it faces up (bright). */
    private static int tint(Geom q) {
        float st = (float) Math.sin(q.theta);
        if (st < 0) return Color.argb(clamp255(255f * SHADE_DARK * -st), 0, 0, 0);
        return Color.argb(clamp255(255f * SHADE_LIGHT * st), 240, 246, 255);
    }

    private void buildMesh(int w, int h) {
        tCount = 0;
        cCount = 0;
        float sx = scene.getWidth() / (float) w;
        float sy = scene.getHeight() / (float) h;
        float cx = w * 0.5f;
        float seam = SEAM_DP * density;
        int i = 0;
        while (i < stripCount) {
            slatGeom(i, w, h, g);
            if (!g.active) {
                int j = nextActive(i, w, h);
                float top = i * stripPx, bottom = Math.min(h, j * stripPx);
                texQuad(0, top, w, top, w, bottom, 0, bottom, 0, w * sx, top * sy, bottom * sy);
                i = j;
                continue;
            }
            float top = i * stripPx, bottom = Math.min(h, (i + 1) * stripPx);
            float hwT = cx * g.kTop, hwB = cx * g.kBot;
            float lt = g.rollTan;
            float tlY = g.yTop - hwT * lt, trY = g.yTop + hwT * lt;
            float blY = g.yBot - hwB * lt, brY = g.yBot + hwB * lt;
            float tlX = cx - hwT, trX = cx + hwT, blX = cx - hwB, brX = cx + hwB;
            texQuad(tlX, tlY, trX, trY, brX, brY, blX, blY, 0, w * sx, top * sy, bottom * sy);

            int tn = tint(g);
            colorQuad(tlX, tlY, trX, trY, brX, brY, blX, blY, tn, tn, tn, tn);
            int dp = Color.argb(clamp255(255f * SHADE_DEPTH * g.fold), 0, 0, 0);
            colorQuad(tlX, tlY, trX, trY, brX, brY, blX, blY, dp, dp, dp, dp);
            // shadow falling from the top edge
            float sh = Math.min(Math.max(1f, g.yBot - g.yTop) * 0.6f,
                    SHADOW_DP * density * (1f - Math.abs(g.d)));
            int s0 = Color.argb(clamp255(255f * SHADOW_ALPHA * Math.min(1f, g.fold * 1.6f)), 0, 0, 0);
            colorQuad(tlX, tlY, trX, trY, trX, trY + sh, tlX, tlY + sh, s0, s0, 0, 0);
            // seams on both edges
            int sc = Color.argb(clamp255(255f * SEAM_ALPHA * Math.min(1f, g.fold * 4f)), 43, 43, 43);
            float hs = seam * 0.5f;
            colorQuad(tlX, tlY - hs, trX, trY - hs, trX, trY + hs, tlX, tlY + hs, sc, sc, sc, sc);
            colorQuad(blX, blY - hs, brX, brY - hs, brX, brY + hs, blX, blY + hs, sc, sc, sc, sc);
            i++;
        }
    }

    private void texQuad(float tlX, float tlY, float trX, float trY,
                         float brX, float brY, float blX, float blY,
                         float u0, float u1, float vT, float vB) {
        if (Math.max(blY, brY) - Math.min(tlY, trY) < 0.05f) return;
        int k = tCount * 2;
        // tri 1: TL TR BR
        tVerts[k] = tlX; tVerts[k + 1] = tlY; tTexs[k] = u0; tTexs[k + 1] = vT;
        tVerts[k + 2] = trX; tVerts[k + 3] = trY; tTexs[k + 2] = u1; tTexs[k + 3] = vT;
        tVerts[k + 4] = brX; tVerts[k + 5] = brY; tTexs[k + 4] = u1; tTexs[k + 5] = vB;
        // tri 2: TL BR BL
        tVerts[k + 6] = tlX; tVerts[k + 7] = tlY; tTexs[k + 6] = u0; tTexs[k + 7] = vT;
        tVerts[k + 8] = brX; tVerts[k + 9] = brY; tTexs[k + 8] = u1; tTexs[k + 9] = vB;
        tVerts[k + 10] = blX; tVerts[k + 11] = blY; tTexs[k + 10] = u0; tTexs[k + 11] = vB;
        tCount += 6;
    }

    private void colorQuad(float tlX, float tlY, float trX, float trY,
                           float brX, float brY, float blX, float blY,
                           int cTL, int cTR, int cBR, int cBL) {
        if (Color.alpha(cTL) == 0 && Color.alpha(cTR) == 0
                && Color.alpha(cBR) == 0 && Color.alpha(cBL) == 0) return;
        int k = cCount * 2, ci = cCount;
        cVerts[k] = tlX; cVerts[k + 1] = tlY; cColors[ci] = cTL;
        cVerts[k + 2] = trX; cVerts[k + 3] = trY; cColors[ci + 1] = cTR;
        cVerts[k + 4] = brX; cVerts[k + 5] = brY; cColors[ci + 2] = cBR;
        cVerts[k + 6] = tlX; cVerts[k + 7] = tlY; cColors[ci + 3] = cTL;
        cVerts[k + 8] = brX; cVerts[k + 9] = brY; cColors[ci + 4] = cBR;
        cVerts[k + 10] = blX; cVerts[k + 11] = blY; cColors[ci + 5] = cBL;
        cCount += 6;
    }

    private static int clamp255(float v) {
        return v < 0 ? 0 : (v > 255 ? 255 : Math.round(v));
    }
}

package com.yash.blindslock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Grabs one still frame of a live wallpaper: the user allows screen capture once, a plain
 * window ({@link WallpaperPeekActivity}) shows only the wallpaper, and the frame becomes the
 * lock-screen image. Nothing is recorded or kept apart from that one picture.
 */
public class WallpaperCaptureService extends Service {
    private static final String TAG = "BlindsLock";
    private static final String CHANNEL = "capture";
    private static final String EX_CODE = "code", EX_DATA = "data";
    private static final long SETTLE_MS = 1500;

    /** Called on the main thread when the capture finished (true = saved). */
    static volatile Consumer<Boolean> onDone;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;

    static void start(Context c, int resultCode, Intent data) {
        Intent i = new Intent(c, WallpaperCaptureService.class)
                .putExtra(EX_CODE, resultCode).putExtra(EX_DATA, data);
        c.startForegroundService(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(R.string.capture_notif))
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(2, n);
        }
        try {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            projection = mpm.getMediaProjection(intent.getIntExtra(EX_CODE, 0), intent.getParcelableExtra(EX_DATA));
            projection.registerCallback(new MediaProjection.Callback() { }, handler); // required on 14+
            DisplayMetrics dm = new DisplayMetrics();
            ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
            reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2);
            display = projection.createVirtualDisplay("blinds-wallpaper", dm.widthPixels, dm.heightPixels,
                    dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
            handler.postDelayed(() -> grab(0), SETTLE_MS);
        } catch (Exception e) {
            Log.w(TAG, "capture start failed", e);
            done(false);
        }
        return START_NOT_STICKY;
    }

    private void grab(int attempt) {
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) {
                if (attempt < 10) { handler.postDelayed(() -> grab(attempt + 1), 300); return; }
                done(false);
                return;
            }
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int w = img.getWidth(), h = img.getHeight();
            int rowPad = p.getRowStride() - p.getPixelStride() * w;
            Bitmap padded = Bitmap.createBitmap(w + rowPad / p.getPixelStride(), h, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buf);
            Bitmap bmp = Bitmap.createBitmap(padded, 0, 0, w, h);
            File tmp = new File(getFilesDir(), "wallpaper.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 94, out);
            }
            Prefs prefs = new Prefs(this);
            boolean ok = tmp.renameTo(prefs.wallpaperFile());
            if (ok) {
                prefs.setWallpaperSource(Prefs.WP_PICKED); // not "current": that one auto-syncs
                SceneRenderer.invalidateWallpaper();
                LockService.warmUp(this);
            }
            done(ok);
        } catch (Exception e) {
            Log.w(TAG, "capture failed", e);
            done(false);
        } finally {
            if (img != null) img.close();
        }
    }

    private void done(boolean ok) {
        WallpaperPeekActivity.closeIfOpen();
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        Consumer<Boolean> cb = onDone;
        if (cb != null) cb.accept(ok);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}

package com.yash.blindslock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Keeps the blinds lock screen ready: when the screen turns off it brings
 * {@link LockActivity} to the front so it is already there - above the system keyguard -
 * the moment the screen turns back on.
 */
public class LockService extends Service {

    private static final String TAG = "BlindsLock";
    private static final String CHANNEL = "blinds_service";
    private static final int NOTIF_ID = 1;

    private View keeperView;

    /** Keeps "Use current wallpaper" in sync when the user changes their wallpaper. */
    private final android.app.WallpaperManager.OnColorsChangedListener wallpaperListener =
            (colors, which) -> {
                if (new Prefs(this).wallpaperSource() != Prefs.WP_CURRENT) return;
                new Thread(() -> {
                    if (WallpaperSource.syncNow(this)) warmUp(this);
                }, "blinds-wp-sync").start();
            };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                showLockScreen();
            }
        }
    };

    public static void start(Context c) {
        Intent i = new Intent(c, LockService.class);
        c.startForegroundService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, LockService.class));
    }

    /** Pre-renders the wallpaper at screen size on a background thread. */
    public static void warmUp(Context c) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                WindowManager wm = app.getSystemService(WindowManager.class);
                int w, h;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Rect b = wm.getMaximumWindowMetrics().getBounds();
                    w = b.width(); h = b.height();
                } else {
                    android.graphics.Point pt = new android.graphics.Point();
                    //noinspection deprecation
                    wm.getDefaultDisplay().getRealSize(pt);
                    w = pt.x; h = pt.y;
                }
                SceneRenderer.wallpaper(app, Math.min(w, h), Math.max(w, h));
            } catch (Exception ignored) {
            }
        }, "blinds-warmup").start();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            startInForeground();
        } catch (Exception e) {
            // e.g. a background start the OS refused; don't crash-loop.
            Log.w(TAG, "startForeground failed", e);
            stopSelf();
            return;
        }
        warmUp(this);
        Sounds.init(this);
        try {
            android.app.WallpaperManager.getInstance(this).addOnColorsChangedListener(wallpaperListener,
                    new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Exception ignored) {
        }
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        registerReceiver(screenReceiver, f);
        addKeeperWindow();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!new Prefs(this).enabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        addKeeperWindow();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        removeKeeperWindow();
        try {
            android.app.WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperListener);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void showLockScreen() {
        if (!new Prefs(this).enabled()) return;
        if (inCall()) return; // proximity sensor turned the screen off during a call
        Sounds.playLock(this);
        if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Display-over-other-apps not granted; Android will block the launch");
        }
        Intent i = new Intent(this, LockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "Could not show lock screen", e);
        }
    }

    private boolean inCall() {
        AudioManager am = getSystemService(AudioManager.class);
        if (am == null) return false;
        int mode = am.getMode();
        return mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == AudioManager.MODE_RINGTONE;
    }

    /**
     * A 1x1, fully transparent, non-touchable overlay. Holding a visible window keeps the
     * app eligible to open its lock screen from the background on Android 10+.
     */
    private void addKeeperWindow() {
        if (keeperView != null || !Settings.canDrawOverlays(this)) return;
        WindowManager wm = getSystemService(WindowManager.class);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setTitle("BlindsKeeper");
        View v = new View(this);
        try {
            wm.addView(v, lp);
            keeperView = v;
        } catch (Exception e) {
            Log.w(TAG, "Keeper window not added", e);
        }
    }

    private void removeKeeperWindow() {
        if (keeperView == null) return;
        try {
            getSystemService(WindowManager.class).removeView(keeperView);
        } catch (Exception ignored) {
        }
        keeperView = null;
    }
}

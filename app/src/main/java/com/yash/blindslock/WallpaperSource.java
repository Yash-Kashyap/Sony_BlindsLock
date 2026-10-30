package com.yash.blindslock;

import android.Manifest;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Copies the phone's current lock-screen wallpaper (falling back to the home wallpaper) into
 * the app, so the blinds are cut from the same picture.
 *
 * Android only lets apps read the wallpaper with storage access: "Files and media" on
 * Android 9-12, "All files access" on Android 13+. The app uses it for this and nothing else.
 */
public final class WallpaperSource {
    private static final String TAG = "BlindsLock";

    private WallpaperSource() {}

    public static boolean needsAllFilesAccess() { return Build.VERSION.SDK_INT >= 33; }

    public static boolean canRead(Context c) {
        if (Build.VERSION.SDK_INT >= 33) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true;
        return c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Reads the current wallpaper and saves it as the app's wallpaper. Call off the UI thread. */
    public static boolean syncNow(Context c) {
        if (!canRead(c)) return false;
        Bitmap bmp = read(c);
        if (bmp == null) return false;
        Prefs prefs = new Prefs(c);
        File tmp = new File(c.getFilesDir(), "wallpaper.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 94, out);
        } catch (Exception e) {
            return false;
        } finally {
            bmp.recycle();
        }
        if (!tmp.renameTo(prefs.wallpaperFile())) return false;
        SceneRenderer.invalidateWallpaper();
        return true;
    }

    @SuppressWarnings("MissingPermission")
    private static Bitmap read(Context c) {
        WallpaperManager wm = WallpaperManager.getInstance(c);
        // 1) Lock-screen wallpaper file, then home wallpaper file.
        for (int which : new int[]{WallpaperManager.FLAG_LOCK, WallpaperManager.FLAG_SYSTEM}) {
            try (ParcelFileDescriptor pfd = wm.getWallpaperFile(which)) {
                if (pfd != null) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, o);
                    int longest = Math.max(o.outWidth, o.outHeight);
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = 1;
                    while (longest / o2.inSampleSize > 3200) o2.inSampleSize *= 2;
                    try (ParcelFileDescriptor pfd2 = wm.getWallpaperFile(which)) {
                        Bitmap b = BitmapFactory.decodeFileDescriptor(pfd2.getFileDescriptor(), null, o2);
                        if (b != null) return b;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "getWallpaperFile(" + which + ") failed", e);
            }
        }
        // 2) Drawable API (also covers built-in default wallpapers).
        try {
            Drawable d = null;
            if (Build.VERSION.SDK_INT >= 34) d = wm.getDrawable(WallpaperManager.FLAG_LOCK);
            if (d == null) d = wm.getDrawable();
            if (d == null) return null;
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                Bitmap src = ((BitmapDrawable) d).getBitmap();
                return src.copy(Bitmap.Config.ARGB_8888, false);
            }
            int w = Math.max(1, d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 1080);
            int h = Math.max(1, d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 2340);
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, w, h);
            d.draw(cv);
            return b;
        } catch (Exception e) {
            Log.w(TAG, "getDrawable failed", e);
            return null;
        }
    }
}

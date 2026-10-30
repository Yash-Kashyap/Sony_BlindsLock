package com.yash.blindslock;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

/** Settings / setup screen. Built in code to keep the app dependency-free. */
public class MainActivity extends Activity {

    /** Opened from the lock screen's "+" page: start the add-widget flow right away. */
    public static final String EXTRA_ADD_WIDGET = "add_widget";
    private static final int REQ_BIND = 20;
    private static final int REQ_CONFIGURE = 21;

    private static final int REQ_PICK = 10;
    private static final int REQ_NOTIF = 11;
    private static final int REQ_READ = 12;

    private static final int BG = 0xFF0E0F14;
    private static final int CARD = 0xFF1A1C24;
    private static final int ACCENT = 0xFF8E7CFF;
    private static final int TEXT = 0xFFECEDF3;
    private static final int SUBTEXT = 0xFF9AA0B4;
    private static final int OK = 0xFF5FD38D;
    private static final int WARN = 0xFFFFB45C;

    private static final class PermRow {
        TextView status;
        Button button;
    }

    private Prefs prefs;
    private boolean binding;
    private boolean pendingCurrentWallpaper;
    private Switch enableSwitch;
    private PermRow overlayRow, batteryRow, notifRow;
    private TextView securityStatus;
    private ImageView wallpaperThumb;
    private TextView wallpaperSourceLabel;
    private LinearLayout widgetList;
    private int pendingWidgetId = -1;
    private AppWidgetProviderInfo pendingInfo;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        if (savedInstanceState != null) pendingWidgetId = savedInstanceState.getInt("pendingWidget", -1);
        handleAddWidgetIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleAddWidgetIntent(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("pendingWidget", pendingWidgetId);
    }

    private void handleAddWidgetIntent(Intent i) {
        if (i != null && i.getBooleanExtra(EXTRA_ADD_WIDGET, false)) {
            i.removeExtra(EXTRA_ADD_WIDGET);
            ui.post(this::startAddWidget);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (prefs.enabled()) LockService.start(this);
        if (pendingCurrentWallpaper && WallpaperSource.canRead(this)) {
            pendingCurrentWallpaper = false;
            useCurrentWallpaper();
        }
    }

    // ------------------------------------------------------------------ UI

    @SuppressWarnings("deprecation")
    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setClipToPadding(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(22), dp(18), dp(28));
        scroll.addView(col);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = i.top; bottom = i.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });

        TextView title = text(getString(R.string.app_name), 30, TEXT);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        col.addView(title);
        TextView sub = text(getString(R.string.tagline), 14, SUBTEXT);
        sub.setPadding(0, dp(4), 0, dp(16));
        col.addView(sub);

        // --- Enable
        LinearLayout c1 = card(col);
        enableSwitch = sw(getString(R.string.enable), prefs.enabled(), on -> { if (!binding) setEnabled(on); });
        enableSwitch.setTextSize(17);
        c1.addView(enableSwitch);
        c1.addView(small(getString(R.string.enable_hint)));
        Button tryIt = button(getString(R.string.try_it), true);
        tryIt.setOnClickListener(v -> startActivity(new Intent(this, LockActivity.class)
                .putExtra(LockActivity.EXTRA_PREVIEW, true)));
        c1.addView(tryIt, matchWrap(dp(12)));

        // --- Permissions
        LinearLayout c2 = card(col);
        c2.addView(header(getString(R.string.permissions)));
        overlayRow = permissionRow(c2, getString(R.string.perm_overlay),
                getString(R.string.perm_overlay_hint), v -> openOverlaySettings());
        batteryRow = permissionRow(c2, getString(R.string.perm_battery),
                getString(R.string.perm_battery_hint), v -> requestBatteryExemption());
        if (Build.VERSION.SDK_INT >= 33) {
            notifRow = permissionRow(c2, getString(R.string.perm_notif), getString(R.string.perm_notif_hint),
                    v -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF));
        }
        Button appInfo = button(getString(R.string.open_app_info), false);
        appInfo.setOnClickListener(v -> openAppInfo());
        c2.addView(appInfo, matchWrap(dp(10)));
        c2.addView(small(getString(R.string.app_info_hint)));

        // --- Wallpaper
        LinearLayout c3 = card(col);
        c3.addView(header(getString(R.string.wallpaper)));
        LinearLayout wrow = new LinearLayout(this);
        wrow.setOrientation(LinearLayout.HORIZONTAL);
        wrow.setPadding(0, dp(8), 0, 0);
        wallpaperThumb = new ImageView(this);
        wallpaperThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable thumbBg = new GradientDrawable();
        thumbBg.setCornerRadius(dp(10));
        thumbBg.setColor(0xFF000000);
        wallpaperThumb.setBackground(thumbBg);
        wallpaperThumb.setClipToOutline(true);
        wrow.addView(wallpaperThumb, new LinearLayout.LayoutParams(dp(84), dp(176)));
        LinearLayout wbtns = new LinearLayout(this);
        wbtns.setOrientation(LinearLayout.VERTICAL);
        wbtns.setPadding(dp(14), 0, 0, 0);
        Button current = button(getString(R.string.use_current), true);
        current.setOnClickListener(v -> useCurrentWallpaper());
        wbtns.addView(current, matchWrap(0));
        Button pick = button(getString(R.string.choose_image), false);
        pick.setOnClickListener(v -> pickImage());
        wbtns.addView(pick, matchWrap(dp(8)));
        Button def = button(getString(R.string.use_default), false);
        def.setOnClickListener(v -> {
            prefs.setWallpaperSource(Prefs.WP_DEFAULT);
            SceneRenderer.invalidateWallpaper();
            updateThumb();
        });
        wbtns.addView(def, matchWrap(dp(8)));
        wallpaperSourceLabel = text("", 13, OK);
        wallpaperSourceLabel.setPadding(0, dp(8), 0, 0);
        wbtns.addView(wallpaperSourceLabel);
        wrow.addView(wbtns, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c3.addView(wrow);
        c3.addView(small(getString(R.string.wallpaper_hint)));
        updateThumb();

        // --- Lock screen widgets
        LinearLayout cw = card(col);
        cw.addView(header(getString(R.string.widgets_header)));
        cw.addView(small(getString(R.string.widgets_body)));
        widgetList = new LinearLayout(this);
        widgetList.setOrientation(LinearLayout.VERTICAL);
        cw.addView(widgetList, matchWrap(dp(6)));
        Button addW = button(getString(R.string.add_widget), true);
        addW.setOnClickListener(v -> startAddWidget());
        cw.addView(addW, matchWrap(dp(10)));
        refreshWidgetList();

        // --- Effect tuning
        LinearLayout c4 = card(col);
        c4.addView(header(getString(R.string.effect)));
        slider(c4, getString(R.string.blind_size), 10, 40, prefs.stripDp(), " dp", prefs::setStripDp);
        slider(c4, getString(R.string.band_slats), 2, 12, prefs.bandSlats(), "", prefs::setBandSlats);
        slider(c4, getString(R.string.unlock_distance), 15, 45, prefs.unlockPct(), " %", prefs::setUnlockPct);
        c4.addView(sw(getString(R.string.show_clock), prefs.showClock(), prefs::setShowClock), matchWrap(dp(8)));
        c4.addView(sw(getString(R.string.sounds), prefs.sounds(), prefs::setSounds), matchWrap(dp(8)));
        c4.addView(sw(getString(R.string.haptics), prefs.haptics(), prefs::setHaptics), matchWrap(dp(12)));
        c4.addView(small(getString(R.string.haptics_hint)));

        // --- PIN / pattern
        LinearLayout c5 = card(col);
        c5.addView(header(getString(R.string.secure_header)));
        securityStatus = text("", 14, TEXT);
        securityStatus.setPadding(0, dp(8), 0, 0);
        c5.addView(securityStatus);
        c5.addView(small(getText(R.string.secure_body)));
        Button sec = button(getString(R.string.open_lock_settings), false);
        sec.setOnClickListener(v -> openLockSettings());
        c5.addView(sec, matchWrap(dp(12)));

        // --- Tips
        LinearLayout c6 = card(col);
        c6.addView(header(getString(R.string.tips)));
        c6.addView(small(getString(R.string.tips_body)));

        return scroll;
    }

    private interface IntSink { void set(int v); }
    private interface BoolSink { void set(boolean v); }

    private Switch sw(String label, boolean checked, BoolSink sink) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(TEXT);
        s.setTextSize(15);
        s.setChecked(checked);
        s.setOnCheckedChangeListener((b, on) -> sink.set(on));
        return s;
    }

    private void slider(LinearLayout parent, String label, int min, int max, int value, String unit, IntSink sink) {
        TextView t = text(getString(R.string.slider_value, label, value, unit), 15, TEXT);
        t.setPadding(0, dp(8), 0, 0);
        parent.addView(t);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, value - min)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                t.setText(getString(R.string.slider_value, label, p + min, unit));
                sink.set(p + min);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        parent.addView(sb, matchWrap(dp(4)));
    }

    private PermRow permissionRow(LinearLayout parent, String name, String hint, View.OnClickListener onGrant) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, 0);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(name, 15, TEXT));
        PermRow pr = new PermRow();
        pr.status = text("", 13, SUBTEXT);
        texts.addView(pr.status);
        texts.addView(small(hint));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pr.button = button(getString(R.string.grant), false);
        pr.button.setOnClickListener(onGrant);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        blp.leftMargin = dp(10);
        row.addView(pr.button, blp);
        parent.addView(row);
        return pr;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(18));
        c.setBackground(bg);
        parent.addView(c, matchWrap(dp(12)));
        return c;
    }

    private TextView header(String s) {
        TextView t = text(s, 13, ACCENT);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView small(CharSequence s) {
        TextView t = text(s, 13, SUBTEXT);
        t.setPadding(0, dp(4), 0, 0);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private TextView text(CharSequence s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : TEXT);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        if (primary) {
            bg.setColor(ACCENT);
        } else {
            bg.setColor(0x00000000);
            bg.setStroke(dp(1), 0xFF3A3E4E);
        }
        b.setBackground(bg);
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setMinHeight(dp(42));
        b.setStateListAnimator(null);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    // ------------------------------------------------------------------ logic

    private void setEnabled(boolean on) {
        prefs.setEnabled(on);
        if (on) {
            if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.need_overlay_toast, Toast.LENGTH_LONG).show();
                openOverlaySettings();
            }
            LockService.start(this);
        } else {
            LockService.stop(this);
        }
    }

    private void refreshStatus() {
        binding = true;
        enableSwitch.setChecked(prefs.enabled());
        binding = false;
        setStatus(overlayRow, Settings.canDrawOverlays(this), Build.VERSION.SDK_INT >= 29);
        PowerManager pm = getSystemService(PowerManager.class);
        setStatus(batteryRow, pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()), false);
        if (notifRow != null && Build.VERSION.SDK_INT >= 33) {
            setStatus(notifRow, checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED, false);
        }
        updateSecurityStatus();
        updateSourceLabel();
    }

    /** Granted → status turns green and the Grant button is greyed out and disabled. */
    private void setStatus(PermRow row, boolean granted, boolean required) {
        if (granted) {
            row.status.setText(R.string.granted);
            row.status.setTextColor(OK);
        } else {
            row.status.setText(required ? R.string.required : R.string.recommended);
            row.status.setTextColor(WARN);
        }
        row.button.setEnabled(!granted);
        row.button.setClickable(!granted);
        row.button.setAlpha(granted ? 0.35f : 1f);
        row.button.setText(granted ? R.string.granted_short : R.string.grant);
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void openAppInfo() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private void updateSecurityStatus() {
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        boolean secure = km != null && km.isDeviceSecure();
        securityStatus.setText(secure ? R.string.secure_yes : R.string.secure_no);
        securityStatus.setTextColor(secure ? WARN : OK);
    }

    private void openLockSettings() {
        // Samsung keeps Smart Lock / "Lock automatically" under Lock screen settings.
        Intent samsung = new Intent().setClassName("com.android.settings",
                "com.android.settings.Settings$LockScreenSettingsActivity");
        try {
            startActivity(samsung);
            return;
        } catch (Exception ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    @SuppressWarnings("BatteryLife")
    private void requestBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // ------------------------------------------------------------------ wallpaper

    private void updateSourceLabel() {
        if (wallpaperSourceLabel == null) return;
        int src = prefs.customWallpaper() ? prefs.wallpaperSource() : Prefs.WP_DEFAULT;
        wallpaperSourceLabel.setText(src == Prefs.WP_CURRENT ? R.string.src_current
                : src == Prefs.WP_PICKED ? R.string.src_picked : R.string.src_default);
    }

    private void useCurrentWallpaper() {
        if (!WallpaperSource.canRead(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.use_current)
                    .setMessage(WallpaperSource.needsAllFilesAccess()
                            ? R.string.current_wp_explain_33 : R.string.current_wp_explain)
                    .setPositiveButton(R.string.continue_, (d, w) -> requestWallpaperAccess())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        Toast.makeText(this, R.string.saving_wallpaper, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = WallpaperSource.syncNow(this);
            ui.post(() -> {
                if (ok) {
                    prefs.setWallpaperSource(Prefs.WP_CURRENT);
                    updateThumb();
                    LockService.warmUp(this);
                } else {
                    Toast.makeText(this, R.string.current_wp_failed, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void requestWallpaperAccess() {
        pendingCurrentWallpaper = true;
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_READ);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_READ) {
            pendingCurrentWallpaper = false;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                useCurrentWallpaper();
            } else {
                Toast.makeText(this, R.string.current_wp_failed, Toast.LENGTH_LONG).show();
            }
        }
        refreshStatus();
    }

    private void pickImage() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
        }
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PICK);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BIND) {
            if (resultCode == RESULT_OK) configureOrFinishWidget(); else cancelPendingWidget();
            return;
        }
        if (requestCode == REQ_CONFIGURE) {
            if (resultCode == RESULT_OK) finishAddWidget(); else cancelPendingWidget();
            return;
        }
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        Toast.makeText(this, R.string.saving_wallpaper, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = saveWallpaper(uri);
            ui.post(() -> {
                if (ok) {
                    prefs.setWallpaperSource(Prefs.WP_PICKED);
                    SceneRenderer.invalidateWallpaper();
                    updateThumb();
                    LockService.warmUp(this);
                } else {
                    Toast.makeText(this, R.string.wallpaper_failed, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private boolean saveWallpaper(Uri uri) {
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
            Bitmap bmp = ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                int longest = Math.max(w, h);
                if (longest > 2800) {
                    float k = 2800f / longest;
                    decoder.setTargetSize(Math.round(w * k), Math.round(h * k));
                }
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
            File tmp = new File(getFilesDir(), "wallpaper.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 93, out);
            }
            bmp.recycle();
            return tmp.renameTo(prefs.wallpaperFile());
        } catch (Exception e) {
            return false;
        }
    }

    private void updateThumb() {
        updateSourceLabel();
        int w = dp(84), h = dp(176);
        new Thread(() -> {
            Bitmap b = SceneRenderer.centerCrop(SceneRenderer.wallpaper(this, w * 3, h * 3, false), w, h);
            ui.post(() -> wallpaperThumb.setImageBitmap(b));
        }).start();
    }

    // ------------------------------------------------------------------ widgets

    private void refreshWidgetList() {
        if (widgetList == null) return;
        widgetList.removeAllViews();
        AppWidgetManager awm = AppWidgetManager.getInstance(this);
        PackageManager pm = getPackageManager();
        java.util.List<Integer> ids = Widgets.validIds(this);
        if (ids.isEmpty()) {
            TextView none = text(getString(R.string.widgets_none), 14, SUBTEXT);
            none.setPadding(0, dp(6), 0, 0);
            widgetList.addView(none);
            return;
        }
        for (int i = 0; i < ids.size(); i++) {
            final int id = ids.get(i);
            AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
            if (info == null) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(4));
            ImageView icon = new ImageView(this);
            try {
                icon.setImageDrawable(info.loadIcon(this, getResources().getDisplayMetrics().densityDpi));
            } catch (Exception ignored) { }
            row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(10), 0, dp(6), 0);
            texts.addView(text(Widgets.label(pm, info), 15, TEXT));
            texts.addView(text(Widgets.appName(pm, info), 12, SUBTEXT));
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (i > 0) row.addView(miniButton("↑", v -> { Widgets.move(this, id, -1); refreshWidgetList(); }));
            if (i < ids.size() - 1) row.addView(miniButton("↓", v -> { Widgets.move(this, id, 1); refreshWidgetList(); }));
            row.addView(miniButton(getString(R.string.remove), v -> {
                Widgets.remove(this, id);
                refreshWidgetList();
            }));
            widgetList.addView(row);
        }
    }

    private Button miniButton(String label, View.OnClickListener l) {
        Button b = button(label, false);
        b.setMinHeight(dp(34));
        b.setMinimumHeight(dp(34));
        b.setMinWidth(dp(40));
        b.setMinimumWidth(dp(40));
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    /** Our own picker: every widget installed apps offer, with icon, name and app. */
    private void startAddWidget() {
        java.util.List<AppWidgetProviderInfo> list = Widgets.providers(this);
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.widgets_none_installed, Toast.LENGTH_LONG).show();
            return;
        }
        PackageManager pm = getPackageManager();
        int dpi = getResources().getDisplayMetrics().densityDpi;
        android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return list.size(); }
            @Override public Object getItem(int i) { return list.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                AppWidgetProviderInfo info = list.get(i);
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(18), dp(10), dp(18), dp(10));
                ImageView icon = new ImageView(MainActivity.this);
                try { icon.setImageDrawable(info.loadIcon(MainActivity.this, dpi)); } catch (Exception ignored) { }
                row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
                LinearLayout t = new LinearLayout(MainActivity.this);
                t.setOrientation(LinearLayout.VERTICAL);
                t.setPadding(dp(14), 0, 0, 0);
                t.addView(text(Widgets.label(pm, info), 15, TEXT));
                int cw = Math.max(1, Math.round(info.minWidth / getResources().getDisplayMetrics().density / 70f));
                int ch = Math.max(1, Math.round(info.minHeight / getResources().getDisplayMetrics().density / 70f));
                t.addView(text(Widgets.appName(pm, info) + "  ·  " + cw + "×" + ch, 12, SUBTEXT));
                row.addView(t);
                return row;
            }
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_widget)
                .setAdapter(adapter, (d, which) -> addWidget(list.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void addWidget(AppWidgetProviderInfo info) {
        android.appwidget.AppWidgetHost host = Widgets.host(this);
        pendingWidgetId = host.allocateAppWidgetId();
        pendingInfo = info;
        AppWidgetManager awm = AppWidgetManager.getInstance(this);
        boolean bound;
        try {
            bound = awm.bindAppWidgetIdIfAllowed(pendingWidgetId, info.getProfile(), info.provider, null);
        } catch (Exception e) {
            bound = false;
        }
        if (bound) {
            configureOrFinishWidget();
        } else {
            // Android asks the user once per app: "Allow Blinds Lock to create widgets…"
            Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
            try {
                startActivityForResult(i, REQ_BIND);
            } catch (Exception e) {
                cancelPendingWidget();
                Toast.makeText(this, R.string.widget_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void configureOrFinishWidget() {
        if (pendingWidgetId < 0) return;
        AppWidgetProviderInfo info = pendingInfo != null ? pendingInfo
                : AppWidgetManager.getInstance(this).getAppWidgetInfo(pendingWidgetId);
        boolean optional = Build.VERSION.SDK_INT >= 31 && info != null
                && (info.widgetFeatures & AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0;
        if (info != null && info.configure != null && !optional) {
            try {
                Widgets.host(this).startAppWidgetConfigureActivityForResult(
                        this, pendingWidgetId, 0, REQ_CONFIGURE, null);
                return;
            } catch (Exception e) {
                // Some configure screens can't be opened by other apps; add without configuring.
            }
        }
        finishAddWidget();
    }

    private void finishAddWidget() {
        if (pendingWidgetId < 0) return;
        java.util.List<Integer> ids = prefs.widgetIds();
        if (!ids.contains(pendingWidgetId)) ids.add(pendingWidgetId);
        prefs.setWidgetIds(ids);
        prefs.setWidgetPage((prefs.showClock() ? 1 : 0) + ids.indexOf(pendingWidgetId));
        pendingWidgetId = -1;
        pendingInfo = null;
        refreshWidgetList();
        Toast.makeText(this, R.string.widget_added, Toast.LENGTH_SHORT).show();
    }

    private void cancelPendingWidget() {
        if (pendingWidgetId >= 0) {
            try { Widgets.host(this).deleteAppWidgetId(pendingWidgetId); } catch (Exception ignored) { }
        }
        pendingWidgetId = -1;
        pendingInfo = null;
    }
}

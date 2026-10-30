package com.yash.blindslock;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared helpers for hosting app widgets on the lock screen. */
public final class Widgets {

    /** Our AppWidgetHost id (any app-unique number). */
    public static final int HOST_ID = 0xB11D;

    private Widgets() {}

    public static AppWidgetHost host(Context c) {
        return new AppWidgetHost(c.getApplicationContext(), HOST_ID);
    }

    /** Saved widget ids whose providers still exist (stale ones are cleaned up). */
    public static List<Integer> validIds(Context c) {
        Prefs prefs = new Prefs(c);
        AppWidgetManager awm = AppWidgetManager.getInstance(c);
        List<Integer> ids = prefs.widgetIds(), ok = new ArrayList<>();
        boolean changed = false;
        for (int id : ids) {
            AppWidgetProviderInfo info = null;
            try { info = awm.getAppWidgetInfo(id); } catch (Exception ignored) { }
            if (info != null) ok.add(id); else changed = true;
        }
        if (changed) prefs.setWidgetIds(ok);
        return ok;
    }

    /** All widgets installed apps offer for the home screen or lock screen, sorted by name. */
    public static List<AppWidgetProviderInfo> providers(Context c) {
        AppWidgetManager awm = AppWidgetManager.getInstance(c);
        List<AppWidgetProviderInfo> list = new ArrayList<>();
        try {
            for (AppWidgetProviderInfo p : awm.getInstalledProvidersForProfile(Process.myUserHandle())) {
                int cat = p.widgetCategory;
                if ((cat & (AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
                        | AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD)) != 0) {
                    list.add(p);
                }
            }
        } catch (Exception ignored) {
        }
        PackageManager pm = c.getPackageManager();
        Collections.sort(list, (a, b) -> label(pm, a).compareToIgnoreCase(label(pm, b)));
        return list;
    }

    public static String label(PackageManager pm, AppWidgetProviderInfo p) {
        try {
            return p.loadLabel(pm);
        } catch (Exception e) {
            return p.provider.getShortClassName();
        }
    }

    public static String appName(PackageManager pm, AppWidgetProviderInfo p) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(p.provider.getPackageName(), 0)).toString();
        } catch (Exception e) {
            return p.provider.getPackageName();
        }
    }

    public static void remove(Context c, int id) {
        Prefs prefs = new Prefs(c);
        List<Integer> ids = prefs.widgetIds();
        ids.remove(Integer.valueOf(id));
        prefs.setWidgetIds(ids);
        try { host(c).deleteAppWidgetId(id); } catch (Exception ignored) { }
    }

    public static void move(Context c, int id, int delta) {
        Prefs prefs = new Prefs(c);
        List<Integer> ids = prefs.widgetIds();
        int i = ids.indexOf(id), j = i + delta;
        if (i < 0 || j < 0 || j >= ids.size()) return;
        Collections.swap(ids, i, j);
        prefs.setWidgetIds(ids);
    }
}

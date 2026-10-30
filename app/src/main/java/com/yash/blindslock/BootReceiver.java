package com.yash.blindslock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the service after a reboot or an app update, if the effect is enabled. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            if (new Prefs(context).enabled()) {
                try {
                    LockService.start(context);
                } catch (Exception ignored) {
                    // Some OEMs restrict background starts; the user can reopen the app.
                }
            }
        }
    }
}

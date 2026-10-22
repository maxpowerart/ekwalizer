package com.ekwalizer.nightmode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores night mode after reboot (if allowed in settings) or after an app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Prefs prefs = new Prefs(context);
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction());
        if (prefs.enabled() && (!boot || prefs.restoreOnBoot())) {
            LevelerService.apply(context);
        }
    }
}

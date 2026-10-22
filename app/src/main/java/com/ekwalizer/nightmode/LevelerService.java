package com.ekwalizer.nightmode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Foreground service that keeps the leveler effect alive. The effect only exists while this
 * process holds the AudioEffect object, so the service must stay running.
 *
 * A watchdog re-creates the effect if it loses control or dies (audio server restart,
 * HDMI re-plug recreating the output thread).
 */
public class LevelerService extends Service {
    static final String ACTION_APPLY = "com.ekwalizer.nightmode.APPLY";
    static final String ACTION_STOP = "com.ekwalizer.nightmode.STOP";

    private static final String TAG = "NightMode";
    private static final String CHANNEL_ID = "leveler";
    private static final int NOTIFICATION_ID = 1;
    private static final long WATCHDOG_MS = 10_000;

    private static volatile String status = "";
    private static volatile boolean running;
    // Diagnostics for the settings screen.
    private static volatile String effectName;
    private static volatile String lastError;
    private static volatile boolean hasControl;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Leveler leveler = new Leveler();
    private Prefs prefs;
    private AudioManager audioManager;

    private final Runnable healthCheck = this::reattachIfUnhealthy;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            reattachIfUnhealthy();
            // The service lives all night, so it also drives the 6-hourly update check.
            Updater.get(LevelerService.this).checkIfDue();
            handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    // Fires once on registration with the current devices; a health check (not a blind
    // re-create) keeps that from causing an audible gap.
    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            checkSoon();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            checkSoon();
        }
    };

    static boolean isRunning() {
        return running;
    }

    /** Name of the attached effect, or null when nothing is attached. */
    static String effectName() {
        return effectName;
    }

    /** Last attach failure, or null when the effect is working. */
    static String lastError() {
        return lastError;
    }

    /** False when another app took control of the effect away from us. */
    static boolean hasControl() {
        return hasControl;
    }

    static void apply(Context context) {
        Intent intent = new Intent(context, LevelerService.class).setAction(ACTION_APPLY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        context.startService(new Intent(context, LevelerService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        audioManager = getSystemService(AudioManager.class);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String action = intent != null ? intent.getAction() : ACTION_APPLY;
        if (ACTION_STOP.equals(action) || !prefs.enabled()) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            audioManager.registerAudioDeviceCallback(deviceCallback, handler);
            handler.postDelayed(watchdog, WATCHDOG_MS);
            attach();
        } else {
            updatePreset();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (running) audioManager.unregisterAudioDeviceCallback(deviceCallback);
        leveler.release();
        running = false;
        effectName = null;
        lastError = null;
        hasControl = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void attach() {
        try {
            String info = leveler.start(prefs.engine(), prefs.preset());
            Log.i(TAG, "Attached: " + info);
            effectName = leveler.effectName();
            hasControl = true;
            lastError = null;
            setStatus(getString(R.string.chip_on));
        } catch (Throwable t) {
            Log.e(TAG, "Attach failed", t);
            effectName = null;
            hasControl = false;
            lastError = String.valueOf(t);
            setStatus(getString(R.string.chip_error));
        }
    }

    private void updatePreset() {
        try {
            String info = leveler.applyPreset(prefs.preset());
            Log.i(TAG, "Preset applied: " + info);
            setStatus(getString(R.string.chip_on));
        } catch (Throwable t) {
            Log.e(TAG, "Preset update failed", t);
            attach();
        }
    }

    private void checkSoon() {
        // Let the audio server finish rebuilding its output thread first.
        handler.removeCallbacks(healthCheck);
        handler.postDelayed(healthCheck, 1500);
    }

    private void reattachIfUnhealthy() {
        boolean healthy = leveler.isHealthy();
        hasControl = healthy;
        if (!healthy) {
            Log.w(TAG, "Effect unhealthy, re-attaching");
            attach();
        }
    }

    private void shutdown() {
        handler.removeCallbacksAndMessages(null);
        leveler.release();
        stopForeground(true);
        stopSelf();
    }

    private void setStatus(String s) {
        status = s;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    private void goForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(status)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }
}

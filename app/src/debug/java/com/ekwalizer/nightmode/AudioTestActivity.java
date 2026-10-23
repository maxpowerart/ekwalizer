package com.ekwalizer.nightmode;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Debug-only harness for listening/measurement tests of the leveler (not in release builds).
 *
 *   adb shell am start -n com.ekwalizer.nightmode/.AudioTestActivity \
 *       --ez enabled true --es engine loudness --ei preset 2 --es path /sdcard/.../test.wav
 *
 * Sets the mode like the UI would (from the foreground, so the service may start), waits for the
 * effect to attach, plays the file as media, logs "AUDIOTEST done" and finishes.
 */
public class AudioTestActivity extends Activity {
    private static final String TAG = "AUDIOTEST";
    private static final long SETTLE_MS = 1500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs prefs = new Prefs(this);
        Leveler.debugFrameMs = getIntent().getFloatExtra("frame", 0f);
        Leveler.debugTargetGainMb = getIntent().getIntExtra("gain", 0);
        boolean enabled = getIntent().getBooleanExtra("enabled", false);
        String engine = getIntent().getStringExtra("engine");
        if (engine != null) prefs.setEngine(engine);
        prefs.setPreset(getIntent().getIntExtra("preset", prefs.preset()));
        prefs.setEnabled(enabled);
        if (enabled) {
            LevelerService.stop(this);
            handler.postDelayed(() -> LevelerService.apply(this), 300);
        } else {
            LevelerService.stop(this);
        }
        String path = getIntent().getStringExtra("path");
        handler.postDelayed(() -> play(path), SETTLE_MS);
    }

    private void play(String path) {
        try {
            // Like the TV box: media volume at max (loudness is set on the TV), so the
            // effect sees full-level audio. Volume is applied per track before the output mix.
            AudioManager am = getSystemService(AudioManager.class);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
            Log.i(TAG, "volume=" + am.getStreamVolume(AudioManager.STREAM_MUSIC) + "/" + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            Log.i(TAG, "effect=" + LevelerService.effectName() + " error=" + LevelerService.lastError());
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setDataSource(path);
            player.setOnCompletionListener(mp -> {
                Log.i(TAG, "done");
                finish();
            });
            player.prepare();
            Log.i(TAG, "playing " + path);
            player.start();
        } catch (Exception e) {
            Log.e(TAG, "play failed", e);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) player.release();
        super.onDestroy();
    }
}

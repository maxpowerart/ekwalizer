package com.ekwalizer.nightmode;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent user choices: on/off, strength preset, processing engine. */
final class Prefs {
    static final String ENGINE_DYNAMICS = "dynamics";
    static final String ENGINE_AVL = "avl";

    private static final String FILE = "nightmode";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PRESET = "preset";
    private static final String KEY_ENGINE = "engine";
    private static final String KEY_RESTORE_ON_BOOT = "restore_on_boot";
    private static final String KEY_AUTO_CHECK_UPDATES = "check_updates";
    private static final String KEY_SKIPPED_VERSION = "skipped_version";
    private static final String KEY_LAST_UPDATE_CHECK = "last_update_check";
    /** Hidden channel override for testing; set with `adb shell run-as` (no UI). */
    private static final String KEY_UPDATE_URL = "update_url";

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    boolean enabled() {
        return sp.getBoolean(KEY_ENABLED, false);
    }

    void setEnabled(boolean value) {
        sp.edit().putBoolean(KEY_ENABLED, value).apply();
    }

    int preset() {
        return sp.getInt(KEY_PRESET, Leveler.PRESET_MEDIUM);
    }

    void setPreset(int value) {
        sp.edit().putInt(KEY_PRESET, value).apply();
    }

    String engine() {
        return sp.getString(KEY_ENGINE, ENGINE_DYNAMICS);
    }

    void setEngine(String value) {
        sp.edit().putString(KEY_ENGINE, value).apply();
    }

    boolean restoreOnBoot() {
        return sp.getBoolean(KEY_RESTORE_ON_BOOT, true);
    }

    void setRestoreOnBoot(boolean value) {
        sp.edit().putBoolean(KEY_RESTORE_ON_BOOT, value).apply();
    }

    boolean autoCheckUpdates() {
        return sp.getBoolean(KEY_AUTO_CHECK_UPDATES, true);
    }

    void setAutoCheckUpdates(boolean value) {
        sp.edit().putBoolean(KEY_AUTO_CHECK_UPDATES, value).apply();
    }

    String skippedVersion() {
        return sp.getString(KEY_SKIPPED_VERSION, null);
    }

    void setSkippedVersion(String value) {
        sp.edit().putString(KEY_SKIPPED_VERSION, value).apply();
    }

    long lastUpdateCheck() {
        return sp.getLong(KEY_LAST_UPDATE_CHECK, 0);
    }

    void setLastUpdateCheck(long value) {
        sp.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply();
    }

    String updateUrl() {
        return sp.getString(KEY_UPDATE_URL, null);
    }
}

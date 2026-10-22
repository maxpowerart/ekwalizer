package com.ekwalizer.nightmode;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashSet;
import java.util.Set;

/** Settings: processing engine, restore after reboot, updates, live diagnostics. */
public class SettingsActivity extends Activity implements Updater.Listener {
    private static final long REFRESH_MS = 1000;
    private static final long ENGINE_RESTART_MS = 500;
    private static final int[] DIAG_KEYS = {
            R.string.diag_effect, R.string.diag_scope, R.string.diag_output,
            R.string.diag_latency, R.string.diag_control, R.string.diag_watchdog,
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView[] diagValues = new TextView[DIAG_KEYS.length];
    private Prefs prefs;
    private LinearLayout engineDynamics;
    private LinearLayout engineAvl;
    private LinearLayout restoreRow;
    private TextView restoreValue;
    private TextView diagStatus;
    private Updater updater;
    private LinearLayout updatesRow;
    private LinearLayout autoRow;
    private LinearLayout skippedRow;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            renderDiagnostics();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Header.setSection(this, R.string.settings);
        prefs = new Prefs(this);
        engineDynamics = findViewById(R.id.engine_dynamics);
        engineAvl = findViewById(R.id.engine_avl);
        restoreRow = findViewById(R.id.restore_row);
        restoreValue = findViewById(R.id.restore_value);
        diagStatus = findViewById(R.id.diag_status);

        Ui.focusableCard(engineDynamics, this::renderEngines);
        Ui.focusableCard(engineAvl, this::renderEngines);
        engineDynamics.setOnClickListener(v -> selectEngine(Prefs.ENGINE_DYNAMICS));
        engineAvl.setOnClickListener(v -> selectEngine(Prefs.ENGINE_AVL));

        Ui.focusableCard(restoreRow, this::renderRestore);
        restoreRow.setOnClickListener(v -> {
            prefs.setRestoreOnBoot(!prefs.restoreOnBoot());
            renderRestore();
        });

        updater = Updater.get(this);
        updatesRow = findViewById(R.id.updates_row);
        autoRow = findViewById(R.id.auto_row);
        skippedRow = findViewById(R.id.skipped_row);
        Ui.focusableCard(updatesRow, () -> Ui.styleCard(updatesRow, false, updatesRow.hasFocus()));
        Ui.focusableCard(autoRow, this::renderUpdates);
        Ui.focusableCard(skippedRow, () -> Ui.styleCard(skippedRow, false, skippedRow.hasFocus()));
        updatesRow.setOnClickListener(v -> {
            Updater.State s = updater.state();
            if (updater.available() != null || s == Updater.State.WITHDRAWN) {
                startActivity(new Intent(this, UpdateActivity.class));
            } else {
                updater.check(true);
            }
        });
        autoRow.setOnClickListener(v -> {
            prefs.setAutoCheckUpdates(!prefs.autoCheckUpdates());
            renderUpdates();
        });
        skippedRow.setOnClickListener(v -> {
            prefs.setSkippedVersion(null);
            if (updatesRow.isShown()) updatesRow.requestFocus();
            renderUpdates();
        });

        buildDiagnosticsGrid();
        ((TextView) findViewById(R.id.version)).setText(getString(R.string.version, versionName()));
        engineDynamics.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
        updater.addListener(this);
        renderUpdates();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        updater.removeListener(this);
        super.onPause();
    }

    @Override
    public void onUpdaterChanged() {
        renderUpdates();
    }

    private void renderUpdates() {
        TextView title = findViewById(R.id.updates_title);
        TextView desc = findViewById(R.id.updates_desc);
        TextView value = findViewById(R.id.updates_value);
        title.setText(getString(R.string.update_name, Updater.currentVersion()));
        Updater.State s = updater.state();
        Updater.Manifest m = updater.available();
        int descColor = getColor(R.color.muted);
        value.setText(R.string.updates_check);
        if (updater.channel() == null) {
            desc.setText(R.string.updates_dev);
        } else if (m != null) {
            desc.setText(getString(R.string.updates_available, m.version));
            descColor = getColor(R.color.icon_light);
            value.setText(R.string.updates_open);
        } else if (s == Updater.State.WITHDRAWN) {
            desc.setText(getString(R.string.withdrawn_card_title, Updater.currentVersion()));
            descColor = getColor(R.color.node);
            value.setText(R.string.updates_open);
        } else if (s == Updater.State.CHECKING) {
            desc.setText(R.string.updates_checking);
        } else if (s == Updater.State.UP_TO_DATE) {
            desc.setText(R.string.updates_latest);
        } else if (s == Updater.State.FAILED && updater.error() != null) {
            desc.setText(updater.error());
            descColor = getColor(R.color.node);
        } else {
            desc.setText(R.string.updates_source);
        }
        desc.setTextColor(descColor);

        boolean auto = prefs.autoCheckUpdates();
        Ui.styleCard(autoRow, false, autoRow.hasFocus());
        TextView autoValue = findViewById(R.id.auto_value);
        autoValue.setText(auto ? R.string.switch_on : R.string.switch_off);
        autoValue.setTextColor(getColor(auto ? R.color.icon : R.color.muted));

        String skipped = prefs.skippedVersion();
        skippedRow.setVisibility(skipped != null ? View.VISIBLE : View.GONE);
        if (skipped != null) {
            ((TextView) findViewById(R.id.skipped_title)).setText(getString(R.string.updates_skipped, skipped));
        }
    }

    private void selectEngine(String engine) {
        if (engine.equals(prefs.engine())) return;
        prefs.setEngine(engine);
        if (prefs.enabled()) {
            // Engine switch needs a fresh effect instance.
            LevelerService.stop(this);
            handler.postDelayed(() -> LevelerService.apply(this), ENGINE_RESTART_MS);
        }
        renderEngines();
    }

    private void renderEngines() {
        boolean avl = Prefs.ENGINE_AVL.equals(prefs.engine());
        renderOption(engineDynamics, R.id.engine_dynamics_mark, !avl);
        renderOption(engineAvl, R.id.engine_avl_mark, avl);
    }

    private void renderOption(LinearLayout row, int markId, boolean selected) {
        Ui.styleCard(row, selected, row.hasFocus());
        ((ImageView) row.findViewById(markId))
                .setImageResource(selected ? R.drawable.ic_check_badge : R.drawable.ic_radio_off);
        row.setSelected(selected);
    }

    private void renderRestore() {
        boolean on = prefs.restoreOnBoot();
        Ui.styleCard(restoreRow, false, restoreRow.hasFocus());
        restoreValue.setText(on ? R.string.switch_on : R.string.switch_off);
        restoreValue.setTextColor(getColor(on ? R.color.icon : R.color.muted));
    }

    private void buildDiagnosticsGrid() {
        GridLayout grid = findViewById(R.id.diag_grid);
        for (int i = 0; i < DIAG_KEYS.length; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            TextView key = new TextView(this);
            key.setText(DIAG_KEYS[i]);
            key.setTextColor(getColor(R.color.muted));
            key.setTextSize(11);
            TextView value = new TextView(this);
            value.setTextColor(getColor(R.color.text_strong));
            value.setTextSize(14);
            value.setTypeface(value.getTypeface(), android.graphics.Typeface.BOLD);
            cell.addView(key);
            cell.addView(value);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.bottomMargin = Ui.dp(this, 12);
            grid.addView(cell, lp);
            diagValues[i] = value;
        }
    }

    private void renderDiagnostics() {
        boolean on = prefs.enabled();
        String error = LevelerService.lastError();
        boolean control = LevelerService.hasControl();
        String effect = LevelerService.effectName();
        boolean dynamics = Prefs.ENGINE_DYNAMICS.equals(prefs.engine());
        String none = getString(R.string.diag_none);

        int statusText;
        int statusColor;
        if (!on) {
            statusText = R.string.diag_off;
            statusColor = getColor(R.color.muted);
        } else if (error != null) {
            statusText = R.string.diag_error;
            statusColor = getColor(R.color.node);
        } else if (!control) {
            statusText = R.string.diag_lost;
            statusColor = getColor(R.color.node);
        } else {
            statusText = R.string.diag_ok;
            statusColor = getColor(R.color.icon_light);
        }
        diagStatus.setText(statusText);
        diagStatus.setTextColor(statusColor);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setSize(Ui.dp(this, 7), Ui.dp(this, 7));
        dot.setColor(statusColor);
        diagStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);

        diagValues[0].setText(on && effect != null ? effect : on && error != null ? error : none);
        diagValues[1].setText(R.string.diag_scope_value);
        diagValues[2].setText(describeOutputs());
        diagValues[3].setText(dynamics
                ? getString(R.string.diag_latency_value, Math.round(Leveler.FRAME_MS)) : none);
        diagValues[4].setText(!on || error != null ? none
                : getString(control ? R.string.diag_yes : R.string.diag_no));
        diagValues[5].setText(on ? getString(R.string.diag_watchdog_value) : none);
    }

    /** Human names of the current external audio outputs, e.g. "HDMI". */
    private String describeOutputs() {
        Set<String> names = new LinkedHashSet<>();
        for (AudioDeviceInfo d : getSystemService(AudioManager.class)
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_HDMI: names.add("HDMI"); break;
                case AudioDeviceInfo.TYPE_HDMI_ARC: names.add("HDMI ARC"); break;
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: names.add("Bluetooth"); break;
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_USB_HEADSET: names.add("USB"); break;
                case AudioDeviceInfo.TYPE_LINE_DIGITAL: names.add("S/PDIF"); break;
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_LINE_ANALOG: names.add("Аналоговый"); break;
                default: break;
            }
        }
        return names.isEmpty() ? getString(R.string.diag_none) : String.join(" · ", names);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}

package com.ekwalizer.nightmode;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Main screen: night mode toggle card, strength presets, update offer, settings. Remote-first. */
public class MainActivity extends Activity implements Updater.Listener {
    private static final long STATUS_REFRESH_MS = 1000;
    private static final int[][] PRESETS = {
            {R.string.preset_soft, R.string.preset_soft_desc},
            {R.string.preset_medium, R.string.preset_medium_desc},
            {R.string.preset_strong, R.string.preset_strong_desc},
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final View[] presetCards = new View[PRESETS.length];
    private Prefs prefs;
    private View toggleCard;
    private ImageView frog;
    private TextView stateTitle;
    private TextView stateDesc;
    private TextView switchLabel;
    private View switchTrack;
    private View switchKnob;
    private TextView chip;
    private View updateCard;
    private Updater updater;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            Header.renderChip(chip, prefs.enabled());
            handler.postDelayed(this, STATUS_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        toggleCard = findViewById(R.id.toggle_card);
        frog = findViewById(R.id.frog);
        stateTitle = findViewById(R.id.state_title);
        stateDesc = findViewById(R.id.state_desc);
        switchLabel = findViewById(R.id.switch_label);
        switchTrack = findViewById(R.id.switch_track);
        switchKnob = findViewById(R.id.switch_knob);
        chip = findViewById(R.id.header_chip);

        Ui.focusableCard(toggleCard, () -> Ui.styleCard(toggleCard, false, toggleCard.hasFocus()));
        toggleCard.setOnClickListener(v -> {
            boolean on = !prefs.enabled();
            prefs.setEnabled(on);
            if (on) {
                LevelerService.apply(this);
            } else {
                LevelerService.stop(this);
            }
            render();
        });

        LinearLayout presets = findViewById(R.id.presets);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < PRESETS.length; i++) {
            final int preset = i;
            View card = inflater.inflate(R.layout.item_preset, presets, false);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
            if (i > 0) lp.setMarginStart(Ui.dp(this, 10));
            ((TextView) card.findViewById(R.id.label)).setText(PRESETS[i][0]);
            ((TextView) card.findViewById(R.id.desc)).setText(PRESETS[i][1]);
            card.setContentDescription(getString(PRESETS[i][0]));
            presets.addView(card, lp);
            presetCards[i] = card;
            Ui.focusableCard(card, () -> renderPreset(preset));
            card.setOnClickListener(v -> {
                prefs.setPreset(preset);
                if (prefs.enabled()) LevelerService.apply(this);
                render();
            });
        }

        View settings = findViewById(R.id.settings_card);
        Ui.focusableCard(settings, () -> Ui.styleCard(settings, false, settings.hasFocus()));
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        updater = Updater.get(this);
        updateCard = findViewById(R.id.update_card);
        Ui.focusableCard(updateCard, () -> Ui.styleCard(updateCard, false, updateCard.hasFocus()));
        updateCard.setOnClickListener(v -> startActivity(new Intent(this, UpdateActivity.class)));

        render();
        toggleCard.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.enabled() && !LevelerService.isRunning()) LevelerService.apply(this);
        render();
        handler.post(refresh);
        updater.addListener(this);
        onUpdaterChanged();
        updater.checkIfDue();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        updater.removeListener(this);
        super.onPause();
    }

    @Override
    public void onUpdaterChanged() {
        Updater.Manifest m = updater.available();
        Updater.State state = updater.state();
        TextView title = updateCard.findViewById(R.id.update_title);
        TextView desc = updateCard.findViewById(R.id.update_desc);
        ImageView icon = updateCard.findViewById(R.id.update_icon);
        if (m != null) {
            title.setText(getString(R.string.update_card_title, m.version));
            desc.setText(R.string.update_card_desc);
            icon.setImageResource(R.drawable.ic_download);
            updateCard.setVisibility(View.VISIBLE);
        } else if (state == Updater.State.WITHDRAWN) {
            title.setText(getString(R.string.withdrawn_card_title, Updater.currentVersion()));
            String reason = updater.withdrawnReason();
            desc.setText(reason == null || reason.isEmpty()
                    ? getString(R.string.withdrawn_default_reason) : reason);
            icon.setImageResource(R.drawable.ic_warning);
            updateCard.setVisibility(View.VISIBLE);
        } else {
            updateCard.setVisibility(View.GONE);
        }
    }

    private void render() {
        boolean on = prefs.enabled();
        stateTitle.setText(on ? R.string.state_on : R.string.state_off);
        stateDesc.setText(on ? R.string.desc_on : R.string.desc_off);
        switchLabel.setText(on ? R.string.switch_on : R.string.switch_off);
        toggleCard.setContentDescription(getString(R.string.night_mode_caps) + ": "
                + getString(on ? R.string.state_on : R.string.state_off));
        switchTrack.setBackground(Ui.pill(this, getColor(on ? R.color.accent : R.color.line)));
        switchKnob.setBackground(Ui.pill(this, on ? 0xFFFFFFFF : getColor(R.color.caps)));
        FrameLayout.LayoutParams knob = (FrameLayout.LayoutParams) switchKnob.getLayoutParams();
        knob.gravity = Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START);
        switchKnob.setLayoutParams(knob);
        Ui.setDimmed(frog, !on);
        for (int i = 0; i < presetCards.length; i++) renderPreset(i);
        Header.renderChip(chip, on);
    }

    private void renderPreset(int i) {
        View card = presetCards[i];
        if (card == null) return;
        boolean selected = prefs.preset() == i;
        Ui.styleCard(card, selected, card.hasFocus());
        BarsView bars = card.findViewById(R.id.bars);
        bars.setPreset(i);
        bars.setActive(prefs.enabled() && selected);
        card.findViewById(R.id.check).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        card.setSelected(selected);
    }
}

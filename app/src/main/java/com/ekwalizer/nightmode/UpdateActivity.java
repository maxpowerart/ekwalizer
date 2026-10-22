package com.ekwalizer.nightmode;

import android.app.Activity;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Update dialog as a TV screen: version, release notes, Обновить / Позже / Пропустить версию,
 * download progress. Also explains a withdrawn version.
 */
public class UpdateActivity extends Activity implements Updater.Listener {
    private Updater updater;
    private TextView caps;
    private TextView name;
    private TextView meta;
    private TextView notesCaps;
    private View notesScroll;
    private TextView notes;
    private TextView install;
    private TextView later;
    private TextView skip;
    private TextView progressText;
    private ProgressBar progress;
    private TextView message;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update);
        Header.setSection(this, R.string.update_section);
        updater = Updater.get(this);
        caps = findViewById(R.id.update_caps);
        name = findViewById(R.id.update_name);
        meta = findViewById(R.id.update_meta);
        notesCaps = findViewById(R.id.update_notes_caps);
        notesScroll = findViewById(R.id.update_notes_scroll);
        notes = findViewById(R.id.update_notes);
        install = findViewById(R.id.update_install);
        later = findViewById(R.id.update_later);
        skip = findViewById(R.id.update_skip);
        progressText = findViewById(R.id.update_progress_text);
        progress = findViewById(R.id.update_progress);
        message = findViewById(R.id.update_message);

        Ui.focusableCard(install, () -> Ui.stylePrimary(install, install.hasFocus()));
        Ui.focusableCard(later, () -> Ui.styleCard(later, false, later.hasFocus()));
        Ui.focusableCard(skip, () -> Ui.styleCard(skip, false, skip.hasFocus()));
        notesScroll.setOnFocusChangeListener((v, hasFocus) -> v.setBackground(hasFocus
                ? Ui.rounded(this, getColor(R.color.panel), getColor(R.color.focus), 3f, 10f)
                : getDrawable(R.drawable.panel)));
        install.setOnClickListener(v -> updater.install());
        later.setOnClickListener(v -> finish());
        skip.setOnClickListener(v -> {
            updater.skipAvailable();
            finish();
        });
        install.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updater.addListener(this);
        onUpdaterChanged();
    }

    @Override
    protected void onPause() {
        updater.removeListener(this);
        super.onPause();
    }

    @Override
    public void onUpdaterChanged() {
        Updater.State state = updater.state();
        Updater.Manifest m = updater.available();
        if (m == null && state != Updater.State.WITHDRAWN) {
            finish();
            return;
        }
        String current = Updater.currentVersion();
        boolean busy = state == Updater.State.DOWNLOADING || state == Updater.State.INSTALLING;

        if (m != null) {
            caps.setText(R.string.update_available_caps);
            caps.setTextColor(getColor(R.color.caps));
            name.setText(getString(R.string.update_name, m.version));
            meta.setText(m.published.isEmpty() ? getString(R.string.update_meta_nodate, current)
                    : getString(R.string.update_meta, current, m.published));
            notes.setText(m.notes.isEmpty() ? getString(R.string.update_notes_empty) : m.notes);
            notesCaps.setVisibility(View.VISIBLE);
            notesScroll.setVisibility(View.VISIBLE);
        } else if (state == Updater.State.WITHDRAWN) {
            caps.setText(R.string.update_withdrawn_caps);
            caps.setTextColor(getColor(R.color.node));
            name.setText(getString(R.string.withdrawn_card_title, current));
            String reason = updater.withdrawnReason();
            meta.setText(reason == null || reason.isEmpty()
                    ? getString(R.string.withdrawn_default_reason) : reason);
            String target = updater.withdrawnTarget();
            notes.setText(target != null ? getString(R.string.update_withdrawn_rollback, target)
                    : getString(R.string.update_withdrawn_wait));
            notesCaps.setVisibility(View.GONE);
            notesScroll.setVisibility(View.VISIBLE);
        }

        boolean canInstall = m != null && !busy;
        install.setVisibility(m != null ? View.VISIBLE : View.GONE);
        skip.setVisibility(m != null ? View.VISIBLE : View.GONE);
        install.setEnabled(canInstall);
        skip.setEnabled(canInstall);
        later.setEnabled(!busy);
        install.setAlpha(canInstall ? 1f : 0.5f);
        skip.setAlpha(canInstall ? 1f : 0.5f);
        later.setAlpha(busy ? 0.5f : 1f);
        if (m == null && !later.hasFocus()) later.requestFocus();

        long done = updater.doneBytes();
        long total = updater.totalBytes();
        if (busy) {
            progressText.setVisibility(View.VISIBLE);
            progress.setVisibility(View.VISIBLE);
            progressText.setText(total > 0
                    ? getString(R.string.update_downloading, size(done), size(total))
                    : getString(R.string.update_downloading_unknown, size(done)));
            progress.setIndeterminate(total <= 0);
            if (total > 0) progress.setProgress((int) (done * 1000 / total));
        } else {
            progressText.setVisibility(View.GONE);
            progress.setVisibility(View.GONE);
        }

        if (state == Updater.State.INSTALLING) {
            message.setText(R.string.update_verified);
            message.setTextColor(getColor(R.color.icon_light));
        } else if (state == Updater.State.FAILED && updater.error() != null) {
            message.setText(updater.error());
            message.setTextColor(getColor(R.color.node));
        } else if (m != null && !getPackageManager().canRequestPackageInstalls()) {
            // Granting "install unknown apps" makes Android kill and restart us; say so upfront.
            message.setText(R.string.update_needs_permission);
            message.setTextColor(getColor(R.color.muted));
        } else {
            message.setText(null);
        }
    }

    private String size(long bytes) {
        return Formatter.formatShortFileSize(this, bytes);
    }
}

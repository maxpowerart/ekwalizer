package com.ekwalizer.nightmode;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Preset preview, same idea as the logo: pale bars show how loud seven different videos are,
 * solid bars show their loudness after leveling with this preset.
 */
public class BarsView extends View {
    /** Loudness of seven different videos before leveling, percent of max. */
    private static final int[] RAW = {34, 92, 50, 100, 24, 72, 44};
    /** How much of the original spread each preset keeps. */
    private static final float[] SPREAD = {0.6f, 0.35f, 0.12f};
    private static final float TARGET = 62f;
    private static final float BAR_DP = 12f;
    private static final float GAP_DP = 5f;

    private final Paint ghost = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baseline = new Paint();
    private final RectF rect = new RectF();
    private int preset = Leveler.PRESET_MEDIUM;

    public BarsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        ghost.setColor(context.getColor(R.color.line));
        baseline.setColor(context.getColor(R.color.line));
        setActive(false);
    }

    void setPreset(int preset) {
        this.preset = preset;
        invalidate();
    }

    /** Active = this preset is selected while night mode is on. */
    void setActive(boolean active) {
        solid.setColor(getContext().getColor(active ? R.color.icon : R.color.idle_bar));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float bar = BAR_DP * density;
        float gap = GAP_DP * density;
        float radius = 3 * density;
        float bottom = getHeight() - density;
        float max = bottom;
        for (int i = 0; i < RAW.length; i++) {
            float left = i * (bar + gap);
            float ghostH = RAW[i] / 100f * max;
            float levelH = (TARGET + (RAW[i] - TARGET) * SPREAD[preset]) / 100f * max;
            rect.set(left, bottom - ghostH, left + bar, bottom + radius);
            canvas.drawRoundRect(rect, radius, radius, ghost);
            rect.set(left, bottom - levelH, left + bar, bottom + radius);
            canvas.drawRoundRect(rect, radius, radius, solid);
        }
        canvas.drawRect(0, bottom, getWidth(), getHeight(), baseline);
    }
}

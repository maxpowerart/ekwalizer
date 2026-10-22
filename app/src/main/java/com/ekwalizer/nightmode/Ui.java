package com.ekwalizer.nightmode;

import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;

/**
 * Kwak-style surfaces built in code, since their look depends on two independent states:
 * selection (teal tint + teal outline) and remote focus (thick aqua outline + slight zoom).
 */
final class Ui {
    private static final float CARD_RADIUS_DP = 10f;
    private static final float FOCUS_SCALE = 1.03f;
    private static final long FOCUS_ANIM_MS = 120;

    private Ui() {}

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(Context c, int fill, int stroke, float strokeDp, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) d.setStroke(dp(c, strokeDp), stroke);
        return d;
    }

    static GradientDrawable pill(Context c, int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, 999));
        return d;
    }

    /** Card background for the given selection/focus combination. */
    static void styleCard(View v, boolean selected, boolean focused) {
        Context c = v.getContext();
        int fill = c.getColor(selected ? R.color.selection : R.color.panel);
        int stroke;
        float width;
        if (focused) {
            stroke = c.getColor(R.color.focus);
            width = 3f;
        } else if (selected) {
            stroke = c.getColor(R.color.icon);
            width = 2f;
        } else {
            stroke = c.getColor(R.color.line);
            width = 1f;
        }
        v.setBackground(rounded(c, fill, stroke, width, CARD_RADIUS_DP));
    }

    /** Primary dialog button: solid teal (Kwak rule 2), aqua outline when focused. */
    static void stylePrimary(View v, boolean focused) {
        Context c = v.getContext();
        v.setBackground(rounded(c, c.getColor(R.color.accent), c.getColor(R.color.focus),
                focused ? 3f : 0f, CARD_RADIUS_DP));
    }

    /** Makes a view a remote-focusable card; {@code restyle} redraws it for the new state. */
    static void focusableCard(View v, Runnable restyle) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((view, hasFocus) -> {
            restyle.run();
            float scale = hasFocus ? FOCUS_SCALE : 1f;
            view.animate().scaleX(scale).scaleY(scale).setDuration(FOCUS_ANIM_MS).start();
        });
        restyle.run();
    }

    /** Grey, faded mascot while night mode is off. */
    static void setDimmed(ImageView image, boolean dimmed) {
        if (dimmed) {
            ColorMatrix m = new ColorMatrix();
            m.setSaturation(0f);
            image.setColorFilter(new ColorMatrixColorFilter(m));
            image.setImageAlpha(115);
        } else {
            image.clearColorFilter();
            image.setImageAlpha(255);
        }
    }
}

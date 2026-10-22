package com.ekwalizer.nightmode;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

/** The navy chrome bar shared by both screens (layout_header.xml). */
final class Header {
    private Header() {}

    /** Shows "Logo | Section" instead of the status chip, for inner screens. */
    static void setSection(Activity activity, int titleRes) {
        activity.findViewById(R.id.header_divider).setVisibility(View.VISIBLE);
        TextView section = activity.findViewById(R.id.header_section);
        section.setText(titleRes);
        section.setVisibility(View.VISIBLE);
    }

    /** Status chip: working (teal), off (muted), or error (orange = warning). */
    static void renderChip(TextView chip, boolean enabled) {
        Context c = chip.getContext();
        int text;
        int fill;
        int color;
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setSize(Ui.dp(c, 8), Ui.dp(c, 8));
        if (!enabled) {
            text = R.string.chip_off;
            fill = c.getColor(R.color.chrome_chip);
            color = c.getColor(R.color.chrome_muted);
            dot.setStroke(Ui.dp(c, 1.5f), color);
        } else if (LevelerService.lastError() != null) {
            text = R.string.chip_error;
            fill = 0x33FD9921;
            color = c.getColor(R.color.node);
            dot.setColor(color);
        } else {
            text = R.string.chip_on;
            fill = c.getColor(R.color.selection);
            color = c.getColor(R.color.icon_light);
            dot.setColor(c.getColor(R.color.icon));
        }
        chip.setText(text);
        chip.setTextColor(color);
        chip.setBackground(Ui.pill(c, fill));
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
        chip.setVisibility(View.VISIBLE);
    }
}

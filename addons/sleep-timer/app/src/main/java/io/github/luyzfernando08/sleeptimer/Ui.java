package io.github.luyzfernando08.sleeptimer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The few views every screen shares, drawn like the launcher's LG-style home (SettingsHubActivity): the Outfit
 * font, glass panels and rows that turn bright glass with a white edge when focused. Keep the colours in step.
 */
final class Ui {
    private Ui() { }

    /** Dimmed Home behind a panel, as behind the launcher's Settings panel. */
    static final int DIM = 0x99000000;

    static TextView text(Context ctx, int sp, int color) {
        TextView view = new TextView(ctx);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTypeface(font(ctx, false));
        return view;
    }

    /** A heading: the same text in Outfit's medium weight. */
    static TextView title(Context ctx, int sp) {
        TextView view = text(ctx, sp, Color.WHITE);
        view.setTypeface(font(ctx, true));
        return view;
    }

    static Typeface font(Context ctx, boolean medium) {
        return ctx.getResources().getFont(medium ? R.font.outfit_medium : R.font.outfit_regular);
    }

    static TextView row(LinearLayout parent, int widthDp) {
        Context ctx = parent.getContext();
        TextView row = text(ctx, 20, Color.WHITE);
        row.setTypeface(font(ctx, true));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 24), dp(ctx, 14), dp(ctx, 24), dp(ctx, 14));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(glassStates(ctx));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, widthDp),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 10);
        parent.addView(row, lp);
        return row;
    }

    /** A button's background: faint glass at rest, the launcher's bright focused glass when focused. */
    static StateListDrawable glassStates(Context ctx) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, glass(ctx, true));
        bg.addState(new int[]{}, glass(ctx, false));
        return bg;
    }

    static GradientDrawable glass(Context ctx, boolean focused) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, focused
                ? new int[]{0x57FFFFFF, 0x1FFFFFFF} : new int[]{0x24FFFFFF, 0x0DFFFFFF});
        g.setCornerRadius(dp(ctx, 14));
        g.setStroke(Math.max(1, dp(ctx, focused ? 2 : 1)), focused ? 0xCCFFFFFF : 0x33FFFFFF);
        return g;
    }

    /** The launcher's floating panel: a 70 % dark tint under a light sheen with a thin bright edge. */
    static Drawable sheet(Context ctx) {
        GradientDrawable tint = new GradientDrawable();
        tint.setColor(0xB310141E);
        tint.setCornerRadius(dp(ctx, 26));
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x2EFFFFFF, 0x0FFFFFFF, 0x08FFFFFF});
        sheen.setCornerRadius(dp(ctx, 26));
        sheen.setStroke(Math.max(1, dp(ctx, 1)), 0x3DFFFFFF);
        return new LayerDrawable(new Drawable[]{tint, sheen});
    }

    static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}

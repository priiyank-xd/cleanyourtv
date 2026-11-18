package io.github.maxhayim.brightness;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One settings row: a name with a line saying what it does on the left, its value on the right. OK runs onClick;
 * Left and Right run onStep with -1 or +1, held keys repeat. A row with a bar draws its value as a slider.
 */
final class Row extends LinearLayout {
    interface Step {
        void step(int direction);
    }

    private final TextView value;
    private final TextView desc;
    private final View fill;
    private final View rest;

    Row(Context context, String name, String description, boolean bar, Runnable onClick, Step onStep) {
        super(context);
        float u = unit(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(Math.round(48 * u));
        int ph = Math.round(12 * u), pv = Math.round(8 * u);
        setPadding(ph, pv, ph, pv);
        setBackgroundResource(R.drawable.row_background);
        boolean active = onClick != null || onStep != null;
        setFocusable(active);
        setClickable(onClick != null);
        if (onClick != null) setOnClickListener(v -> onClick.run());
        if (onStep != null) {
            setOnKeyListener((v, code, e) -> {
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN) onStep.step(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            });
        }

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(VERTICAL);
        TextView label = text(context, 15, true, 0xFFFFFFFF);
        label.setText(name);
        text.addView(label);
        desc = text(context, 11.5f, false, 0x8CFFFFFF);
        desc.setPadding(0, Math.round(3 * u), Math.round(12 * u), 0);
        text.addView(desc);
        setDetail(description);
        addView(text, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        if (bar) {
            // White on glass, like the launcher's bars and this app's quick bar.
            LinearLayout track = new LinearLayout(context);
            track.setOrientation(HORIZONTAL);
            track.setBackgroundColor(0x33FFFFFF);
            fill = new View(context);
            fill.setBackgroundColor(0xFFFFFFFF);
            rest = new View(context);
            track.addView(fill, new LayoutParams(0, LayoutParams.MATCH_PARENT, 0f));
            track.addView(rest, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
            FrameLayout holder = new FrameLayout(context);
            holder.addView(track, new FrameLayout.LayoutParams(Math.round(110 * u), Math.round(4 * u), Gravity.CENTER_VERTICAL));
            LayoutParams hp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            hp.rightMargin = Math.round(12 * u);
            addView(holder, hp);
        } else {
            fill = rest = null;
        }

        value = text(context, 15, false, 0xCCFFFFFF);
        value.setGravity(Gravity.END);
        value.setMinWidth(Math.round(52 * u));
        addView(value, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    /** The launcher's layout unit: 1/960 of the screen width, so sizes match its Settings panel on any TV. */
    static float unit(Context context) {
        return context.getResources().getDisplayMetrics().widthPixels / 960f;
    }

    /** Text in the launcher's font (Outfit) and sizes, given in launcher units. */
    static TextView text(Context context, float size, boolean medium, int color) {
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * unit(context));
        t.setTypeface(context.getResources().getFont(medium ? R.font.outfit_medium : R.font.outfit_regular));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    void setDetail(String d) {
        desc.setText(d == null ? "" : d);
        desc.setVisibility(d == null || d.isEmpty() ? GONE : VISIBLE);
    }

    void setValue(String v) {
        value.setText(v);
    }

    /** The slider's position, 0 to 1. */
    void setBar(float fraction) {
        if (fill == null) return;
        float f = Math.max(0f, Math.min(1f, fraction));
        ((LayoutParams) fill.getLayoutParams()).weight = f;
        ((LayoutParams) rest.getLayoutParams()).weight = 1f - f;
        fill.requestLayout();
    }
}

package io.github.maxhayim.headphones;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One settings row: a name with a line saying what it does on the left, its value on the right; OK runs it.
 * Drawn like a row of the launcher's Settings panel (SettingsHubActivity).
 */
final class Row extends LinearLayout {
    private final TextView value;
    private final TextView desc;

    Row(Context context, String name, String description, Runnable onClick) {
        super(context);
        float u = unit(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(Math.round(48 * u));
        int ph = Math.round(12 * u), pv = Math.round(8 * u);
        setPadding(ph, pv, ph, pv);
        setBackgroundResource(R.drawable.row_background);
        setFocusable(onClick != null);
        setClickable(onClick != null);
        if (onClick != null) setOnClickListener(v -> onClick.run());

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

        value = text(context, 15, false, 0xCCFFFFFF);
        value.setGravity(Gravity.END);
        value.setMinWidth(Math.round(52 * u));
        addView(value, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    void setDetail(String d) {
        desc.setText(d == null ? "" : d);
        desc.setVisibility(d == null || d.isEmpty() ? GONE : VISIBLE);
    }

    void setValue(String v) {
        value.setText(v);
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
}

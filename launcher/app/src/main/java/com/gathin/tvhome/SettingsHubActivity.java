package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * The settings menu of the LG-style home (fork only): a side panel over Home with the launcher's own choices
 * (background, how often movie art changes, clock, what the Settings button opens, Follow theme) and shortcuts
 * into Google TV's settings pages. Choices are saved in the launcher's settings and Home picks them up when the
 * panel closes. Back closes it; Back from a Google TV page returns here.
 */
public class SettingsHubActivity extends Activity {
    private static final int ROW_TEXT = 0xFFFFFFFF, ROW_SUB = 0x8CFFFFFF, FOCUS_BG = 0xF2FFFFFF, FOCUS_TEXT = 0xFF111111,
            FOCUS_SUB = 0x8C000000;
    /** Liquid glass, as on Home: a light top-to-bottom gradient with a thin bright edge. */
    private static final int GLASS_TOP = 0x33FFFFFF, GLASS_BOTTOM = 0x0FFFFFFF, GLASS_EDGE = 0x3DFFFFFF;
    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};

    private float u;
    private SharedPreferences choices;
    private final List<Row> rows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        u = getResources().getDisplayMetrics().widthPixels / 960f;

        FrameLayout root = new FrameLayout(this);
        View dim = new View(this);
        dim.setBackgroundColor(0x40000000);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        // A floating glass panel: a dark tint so text stays readable over bright movie art, the glass on top.
        GradientDrawable tint = new GradientDrawable();
        tint.setColor(0x8C10141E);
        tint.setCornerRadius(px(26));
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x2EFFFFFF, 0x0FFFFFFF, 0x08FFFFFF});
        sheen.setCornerRadius(px(26));
        sheen.setStroke(Math.max(1, px(1)), GLASS_EDGE);
        pane.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{tint, sheen}));
        pane.setClipToOutline(true);
        FrameLayout.LayoutParams paneLp = new FrameLayout.LayoutParams(px(372), -1, Gravity.END);
        paneLp.setMargins(0, px(16), px(16), px(16));
        root.addView(pane, paneLp);

        TextView title = text(getString(R.string.hub_title), 22, R.string.font_medium, ROW_TEXT);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.setMargins(px(28), px(26), px(28), 0);
        pane.addView(title, titleLp);
        TextView sub = text(getString(R.string.hub_subtitle), 12, R.string.font_regular, ROW_SUB);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.setMargins(px(28), px(6), px(28), px(8));
        pane.addView(sub, subLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(px(16), 0, px(16), px(24));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        pane.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        section(list, R.string.hub_on_home);
        choiceRow(list, R.drawable.ic_picture, R.string.lg_background_label,
                new String[]{getString(R.string.lg_background_picture), getString(R.string.lg_background_movie_art)},
                () -> Config.LG_MOVIE_ART.equals(Config.lgBackground(this)) ? 1 : 0,
                i -> save(Config.LG_BACKGROUND_KEY, i == 1 ? Config.LG_MOVIE_ART : "picture"), null);
        String[] minutes = new String[HubChoices.ART_MINUTES.length];
        for (int i = 0; i < minutes.length; i++) minutes[i] = getString(R.string.hub_minutes, HubChoices.ART_MINUTES[i]);
        choiceRow(list, R.drawable.ic_hub_timer, R.string.hub_art_every, minutes,
                () -> indexOf(HubChoices.ART_MINUTES, Config.lgArtMinutes(this)),
                i -> save(Config.LG_ART_MINUTES_KEY, String.valueOf(HubChoices.ART_MINUTES[i])),
                () -> Config.LG_MOVIE_ART.equals(Config.lgBackground(this)));
        choiceRow(list, R.drawable.ic_hub_clock, R.string.hub_clock,
                new String[]{getString(R.string.clock_format_system), getString(R.string.clock_format_12),
                        getString(R.string.clock_format_24)},
                () -> indexOf(CLOCK_FORMATS, Config.load(this).clockFormat),
                i -> save(Config.CLOCK_FORMAT_KEY, CLOCK_FORMATS[i]), null);
        choiceRow(list, R.drawable.ic_settings, R.string.hub_button_opens,
                new String[]{getString(R.string.hub_button_menu), getString(R.string.hub_button_google)},
                () -> HubChoices.BUTTON_SYSTEM.equals(Config.lgSettingsButton(this)) ? 1 : 0,
                i -> save(Config.LG_SETTINGS_BUTTON_KEY, i == 1 ? HubChoices.BUTTON_SYSTEM : HubChoices.BUTTON_HUB), null);
        actionRow(list, R.drawable.ic_hub_undo, R.string.hub_follow_theme, R.string.hub_follow_theme_sub, v -> {
            choices.edit().remove(Config.LG_BACKGROUND_KEY).remove(Config.LG_ART_MINUTES_KEY)
                    .remove(Config.CLOCK_FORMAT_KEY).apply();
            changed();
            Toast.makeText(this, R.string.hub_follow_theme_done, Toast.LENGTH_SHORT).show();
        });

        section(list, R.string.hub_projector);
        settingsRow(list, R.drawable.ic_wifi, R.string.hub_wifi, R.string.hub_wifi_sub, Settings.ACTION_WIFI_SETTINGS);
        settingsRow(list, R.drawable.ic_lg_bluetooth, R.string.hub_bluetooth, R.string.hub_bluetooth_sub, Settings.ACTION_BLUETOOTH_SETTINGS);
        settingsRow(list, R.drawable.ic_hub_display, R.string.hub_display, R.string.hub_display_sub, Settings.ACTION_DISPLAY_SETTINGS);
        actionRow(list, R.drawable.ic_input, R.string.lg_input, R.string.hub_inputs_sub,
                v -> open(LgHome.inputsIntent(this)));

        section(list, R.string.hub_more);
        actionRow(list, R.drawable.ic_lg_patterns, R.string.hub_patterns, R.string.hub_patterns_sub,
                v -> open(new Intent(this, CalibrationActivity.class)));
        actionRow(list, R.drawable.ic_hub_tune, R.string.hub_launcher, R.string.hub_launcher_sub,
                v -> open(new Intent(this, LauncherSettingsActivity.class)));
        settingsRow(list, R.drawable.ic_apps, R.string.hub_apps, R.string.hub_apps_sub, Settings.ACTION_APPLICATION_SETTINGS);
        settingsRow(list, R.drawable.ic_hub_date, R.string.hub_date, R.string.hub_date_sub, Settings.ACTION_DATE_SETTINGS);
        settingsRow(list, R.drawable.ic_hub_access, R.string.hub_accessibility, R.string.hub_accessibility_sub,
                Settings.ACTION_ACCESSIBILITY_SETTINGS);
        settingsRow(list, R.drawable.ic_hub_more, R.string.hub_all, R.string.hub_all_sub, Settings.ACTION_SETTINGS);

        setContentView(root);
        refresh();
        rows.get(0).view.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A page opened from here (full launcher settings) may have changed a choice.
        refresh();
    }

    // ---- rows ----

    private final class Row {
        LinearLayout view;
        ImageView icon;
        GradientDrawable iconBg;
        TextView title, sub;
        TextView[] seg;
        IntSupplier get;
        int current;
        java.util.function.BooleanSupplier shown;

        void paint() {
            boolean f = view.hasFocus();
            view.setBackground(f ? round(FOCUS_BG, 12) : null);
            title.setTextColor(f ? FOCUS_TEXT : ROW_TEXT);
            icon.setColorFilter(f ? FOCUS_TEXT : ROW_TEXT);
            if (f) {
                iconBg.setColors(new int[]{0x14000000, 0x14000000});
                iconBg.setStroke(0, 0);
            } else {
                iconBg.setColors(new int[]{GLASS_TOP, GLASS_BOTTOM});
                iconBg.setStroke(Math.max(1, px(1)), GLASS_EDGE);
            }
            if (sub != null) sub.setTextColor(f ? FOCUS_SUB : ROW_SUB);
            if (seg != null) {
                int on = current;
                for (int i = 0; i < seg.length; i++) {
                    boolean sel = i == on;
                    seg[i].setBackground(sel ? round(f ? 0xFF111111 : 0xE6FFFFFF, 10) : f ? round(0x12000000, 10) : glass(10));
                    seg[i].setTextColor(sel ? (f ? 0xFFFFFFFF : 0xFF111111) : f ? 0x99000000 : 0xCCFFFFFF);
                }
            }
        }
    }

    private void refresh() {
        for (Row r : rows) {
            r.view.setVisibility(r.shown == null || r.shown.getAsBoolean() ? View.VISIBLE : View.GONE);
            if (r.get != null) r.current = r.get.getAsInt();
            r.paint();
        }
    }

    private Row row(LinearLayout list, int icon, int title) {
        Row r = new Row();
        r.view = new LinearLayout(this);
        r.view.setOrientation(LinearLayout.HORIZONTAL);
        r.view.setGravity(Gravity.CENTER_VERTICAL);
        r.view.setFocusable(true);
        r.view.setClickable(true);
        r.view.setMinimumHeight(px(48));
        r.view.setPadding(px(12), px(8), px(12), px(8));
        FrameLayout iconBox = new FrameLayout(this);
        r.iconBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{GLASS_TOP, GLASS_BOTTOM});
        r.iconBg.setShape(GradientDrawable.OVAL);
        iconBox.setBackground(r.iconBg);
        r.icon = new ImageView(this);
        r.icon.setImageResource(icon);
        iconBox.addView(r.icon, new FrameLayout.LayoutParams(px(17), px(17), Gravity.CENTER));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(px(30), px(30));
        iconLp.rightMargin = px(12);
        r.view.addView(iconBox, iconLp);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        r.title = text(getString(title), 15, R.string.font_medium, ROW_TEXT);
        col.addView(r.title);
        r.view.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        r.view.setOnFocusChangeListener((v, has) -> r.paint());
        list.addView(r.view, new LinearLayout.LayoutParams(-1, -2));
        rows.add(r);
        return r;
    }

    private void section(LinearLayout list, int label) {
        TextView t = text(getString(label), 11, R.string.font_medium, 0x80FFFFFF);
        t.setAllCaps(true);
        t.setLetterSpacing(0.16f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(px(12), px(14), px(12), px(6));
        list.addView(t, lp);
    }

    private void choiceRow(LinearLayout list, int icon, int title, String[] labels, IntSupplier get, IntConsumer set,
                           java.util.function.BooleanSupplier shown) {
        Row r = row(list, icon, title);
        r.get = get;
        r.shown = shown;
        LinearLayout segs = new LinearLayout(this);
        segs.setOrientation(LinearLayout.HORIZONTAL);
        r.seg = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView s = text(labels[i], 12, R.string.font_regular, 0xB3FFFFFF);
            s.setPadding(px(10), px(4), px(10), px(4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = px(4);
            segs.addView(s, lp);
            r.seg[i] = s;
        }
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(-2, -2);
        segLp.topMargin = px(7);
        ((LinearLayout) r.title.getParent()).addView(segs, segLp);
        r.view.setOnClickListener(v -> choose(r, set, 1));
        r.view.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { choose(r, set, -1); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { choose(r, set, 1); return true; }
            return false;
        });
    }

    private void choose(Row r, IntConsumer set, int dir) {
        set.accept(HubChoices.step(r.current, r.seg.length, dir));
        changed();
    }

    private void actionRow(LinearLayout list, int icon, int title, int sub, View.OnClickListener click) {
        Row r = row(list, icon, title);
        r.sub = text(getString(sub), 11.5f, R.string.font_regular, ROW_SUB);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = px(3);
        ((LinearLayout) r.title.getParent()).addView(r.sub, subLp);
        r.view.setOnClickListener(click);
        r.view.setOnKeyListener((v, code, e) -> code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT);
    }

    private void settingsRow(LinearLayout list, int icon, int title, int sub, String action) {
        actionRow(list, icon, title, sub, v -> open(SystemSettings.intent(this, action)));
    }

    // ---- actions ----

    private void save(String key, String value) {
        choices.edit().putString(key, value).apply();
    }

    /** Bumps the settings revision so Home reloads its config, then repaints the rows. */
    private void changed() {
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
        refresh();
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.hub_not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private static int indexOf(int[] values, int v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return 0;
    }

    private static int indexOf(String[] values, String v) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return i;
        return 0;
    }

    // ---- view helpers (design units of a 960-wide screen) ----

    private int px(float units) {
        return Math.round(units * u);
    }

    private TextView text(String s, float size, int font, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
        t.setTypeface(UiFont.get(this, font));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private GradientDrawable glass(float radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{GLASS_TOP, GLASS_BOTTOM});
        g.setCornerRadius(px(radius));
        g.setStroke(Math.max(1, px(1)), GLASS_EDGE);
        return g;
    }

    private GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(radius));
        return g;
    }
}

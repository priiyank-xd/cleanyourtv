package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};
    private final TextView[] clockChoices = new TextView[CLOCK_FORMATS.length];
    private TextView wallpaperChoice;
    /** Fork: which installed screensaver Android runs (ours with the theme photos, or Aerial Views). */
    private TextView screensaverChoice;
    private static final String SCREENSAVER_COMPONENTS = "screensaver_components";
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    /** Fork: Home background (LG-style home), picture or movie art. */
    private static final String[] LG_BACKGROUNDS = {"picture", Config.LG_MOVIE_ART};
    private final TextView[] lgBackgroundChoices = new TextView[LG_BACKGROUNDS.length];
    private String lgBackground;
    private TextView openOnStartChoice;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private String clockFormat;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        addFocusGlow(findViewById(android.R.id.content));
        // Fork: the Settings panel's look. The sheet clips its scrolling rows to its rounded edge (addFocusGlow turned
        // child clipping off for the glow), text is in the panel's font and each row gets the panel's round icon.
        findViewById(R.id.settingsPanel).setClipToOutline(true);
        applyFonts(findViewById(android.R.id.content));
        rowIcon(R.id.reorderChoice, R.drawable.ic_swap_vert);
        rowIcon(R.id.wallpaperChoice, R.drawable.ic_picture);
        rowIcon(R.id.screensaverChoice, R.drawable.ic_hub_display);
        rowIcon(R.id.openOnStartChoice, R.drawable.ic_lg_play);
        rowIcon(R.id.animationsChoice, R.drawable.ic_hub_tune);
        rowIcon(R.id.advancedChoice, R.drawable.ic_hub_more);
        rowIcon(R.id.defaultHomeChoice, R.drawable.ic_home_settings);
        rowIcon(R.id.tvSettingsChoice, R.drawable.ic_settings);
        rowIcon(R.id.followThemeChoice, R.drawable.ic_hub_undo);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        clockFormat = config.clockFormat;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] clockIds = {R.id.clockSystem, R.id.clock12, R.id.clock24};
        for (int i = 0; i < clockIds.length; i++) {
            final String value = CLOCK_FORMATS[i];
            clockChoices[i] = findViewById(clockIds[i]);
            clockChoices[i].setOnClickListener(v -> {
                if (value.equals(clockFormat)) return;
                clockFormat = value;
                choices.edit().putString(Config.CLOCK_FORMAT_KEY, value).apply();
                changed();
                refresh();
            });
        }
        // Mirror text alignment without globally opting the launcher into RTL. Numeric rows
        // still need explicit ordering in Android's no-supportsRtl compatibility mode.
        if (getResources().getBoolean(R.bool.rtl_menu_bar))
            findViewById(android.R.id.content).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
            reverseRow(clockChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        screensaverChoice = findViewById(R.id.screensaverChoice);
        screensaverChoice.setOnClickListener(v -> showScreensaverPicker());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                glassDialog(getString(R.string.advanced_effects), getString(R.string.advanced_effects_warning),
                        null, -1, null,
                        new String[]{getString(android.R.string.cancel), getString(R.string.enable)},
                        new Runnable[]{null, () -> setAdvanced(true)});
            }
        });
        openOnStartChoice = findViewById(R.id.openOnStartChoice);
        if (config.openOnStartChoice) {
            openOnStartChoice.setVisibility(View.VISIBLE);
            openOnStartChoice.setOnClickListener(v -> showOpenOnStartPicker());
        }
        lgBackground = Config.lgBackground(this);
        boolean lgHome = Config.HOME_LG.equals(Config.homeLayout(this));
        findViewById(R.id.lgBackgroundGroup).setVisibility(lgHome ? View.VISIBLE : View.GONE);
        // Fork: the grid layout, dock band and advanced effects only change the classic grid home.
        findViewById(R.id.classicGridGroup).setVisibility(lgHome ? View.GONE : View.VISIBLE);
        findViewById(R.id.advancedGroup).setVisibility(lgHome ? View.GONE : View.VISIBLE);
        int[] lgBackgroundIds = {R.id.lgBgPicture, R.id.lgBgMovieArt};
        for (int i = 0; i < lgBackgroundIds.length; i++) {
            final String value = LG_BACKGROUNDS[i];
            lgBackgroundChoices[i] = findViewById(lgBackgroundIds[i]);
            lgBackgroundChoices[i].setOnClickListener(v -> {
                if (value.equals(lgBackground)) return;
                lgBackground = value;
                choices.edit().putString(Config.LG_BACKGROUND_KEY, value).apply();
                changed();
                refresh();
            });
        }
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.DOCK_OPACITY_KEY).remove(Config.CLOCK_FORMAT_KEY).remove(OpenOnStart.KEY)
                    .remove(Config.LG_BACKGROUND_KEY).remove(Config.LG_ART_MINUTES_KEY).commit();
            lgBackground = Config.lgBackground(this);
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            clockFormat = current.clockFormat;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v ->
                open(new Intent(this, HomeSettingsActivity.class)));
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else if (lgHome) lgBackgroundChoices[Config.LG_MOVIE_ART.equals(lgBackground) ? 1 : 0].requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        glassDialog(getString(R.string.wallpaper_label), null, labels, checked, which -> {
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which).getName()).apply();
                    }
                    changed();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                },
                new String[]{getString(android.R.string.cancel), getString(R.string.wallpaper_theme_default)},
                new Runnable[]{null, () -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    changed();
                    refresh();
                }});
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        glassDialog(getString(R.string.open_on_start_label), null, labels, checked, which -> {
                    if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
                    else {
                        OpenOnStart.skipThisBoot(this);
                        choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
                    }
                    refresh();
                }, new String[]{getString(android.R.string.cancel)}, new Runnable[]{null});
    }

    /** Every installed screensaver; ours is named "Photos and clock". Needs WRITE_SECURE_SETTINGS (pm grant). */
    private List<ResolveInfo> screensavers() {
        return getPackageManager().queryIntentServices(
                new Intent(android.service.dreams.DreamService.SERVICE_INTERFACE), 0);
    }

    private String screensaverName(ResolveInfo r) {
        if (r.serviceInfo.packageName.equals(getPackageName())) return getString(R.string.screensaver_photos_clock);
        return r.loadLabel(getPackageManager()).toString();
    }

    private static String flat(ResolveInfo r) {
        return new android.content.ComponentName(r.serviceInfo.packageName, r.serviceInfo.name).flattenToString();
    }

    /** The active screensaver as flattened components; Android may store a comma-separated list. */
    private String currentScreensaver() {
        String value = Settings.Secure.getString(getContentResolver(), SCREENSAVER_COMPONENTS);
        return value == null ? "" : value.split(",")[0];
    }

    private void showScreensaverPicker() {
        List<ResolveInfo> dreams = screensavers();
        if (dreams.isEmpty()) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
            return;
        }
        android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(currentScreensaver());
        String current = cn == null ? "" : cn.flattenToString();   // adb may have set the short ".ClockDream" form
        String[] labels = new String[dreams.size()];
        int checked = -1;
        for (int i = 0; i < dreams.size(); i++) {
            labels[i] = screensaverName(dreams.get(i));
            if (flat(dreams.get(i)).equals(current)) checked = i;
        }
        glassDialog(getString(R.string.screensaver_label), null, labels, checked, which -> {
                    try {
                        Settings.Secure.putString(getContentResolver(), SCREENSAVER_COMPONENTS, flat(dreams.get(which)));
                        Settings.Secure.putInt(getContentResolver(), "screensaver_enabled", 1);
                    } catch (SecurityException e) {
                        Toast.makeText(this, R.string.screensaver_needs_permission, Toast.LENGTH_LONG).show();
                    }
                    refresh();
                }, new String[]{getString(android.R.string.cancel)}, new Runnable[]{null});
    }

    /**
     * Fork: a picker or confirmation in the page's own glass look, in place of the system AlertDialog. {@code items}
     * (may be null) are rows, the {@code checked} one a white pill and focused first; picking one closes the dialog.
     * {@code buttons} sit along the bottom; a null action only closes. Back closes it too.
     */
    private void glassDialog(String title, String message, String[] items, int checked,
                             java.util.function.IntConsumer onItem, String[] buttons, Runnable[] actions) {
        float dp = getResources().getDisplayMetrics().density;
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundResource(R.drawable.settings_screen_bg);
        sheet.setClipToOutline(true);
        int pad = Math.round(28 * dp);
        sheet.setPadding(pad, Math.round(24 * dp), pad, Math.round(24 * dp));

        TextView head = new TextView(this);
        head.setTag("medium");
        head.setText(title);
        head.setTextColor(0xFFFFFFFF);
        head.setTextSize(22);
        sheet.addView(head);
        if (message != null) {
            TextView body = new TextView(this);
            body.setText(message);
            body.setTextColor(0x8CFFFFFF);
            body.setTextSize(14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Math.round(10 * dp);
            sheet.addView(body, lp);
        }

        View first = null;
        if (items != null) {
            android.widget.ScrollView scroll = new android.widget.ScrollView(this);
            scroll.setVerticalScrollBarEnabled(false);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            int side = Math.round(6 * dp);
            list.setPadding(side, side, side, side);   // room for the focused row's glow
            for (int i = 0; i < items.length; i++) {
                final int which = i;
                TextView row = new TextView(this, null, 0, R.style.SettingsRow);
                row.setText(items[i]);
                row.setSingleLine(true);
                row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.setSelected(i == checked);
                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    onItem.accept(which);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.round(52 * dp));
                lp.topMargin = Math.round(10 * dp);
                list.addView(row, lp);
                if (first == null || i == checked) first = row;
            }
            scroll.addView(list);
            // Every row fits up to about half the screen; a longer list (open on start's apps) scrolls.
            int listHeight = Math.min(Math.round((62 * items.length + 12) * dp),
                    Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, listHeight);
            lp.topMargin = Math.round(8 * dp);
            sheet.addView(scroll, lp);
        }

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.END);
        bar.setPadding(Math.round(6 * dp), 0, Math.round(6 * dp), Math.round(6 * dp));
        for (int i = 0; i < buttons.length; i++) {
            final Runnable action = actions[i];
            TextView b = new TextView(this, null, 0, R.style.SettingsRow);
            b.setText(buttons[i]);
            b.setGravity(android.view.Gravity.CENTER);
            b.setPadding(Math.round(24 * dp), 0, Math.round(24 * dp), 0);
            b.setOnClickListener(v -> {
                dialog.dismiss();
                if (action != null) action.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Math.round(48 * dp));
            lp.setMarginStart(Math.round(10 * dp));
            bar.addView(b, lp);
            if (first == null && action != null) first = b;   // a confirmation focuses its action
        }
        if (first == null && bar.getChildCount() > 0) first = bar.getChildAt(0);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
        barLp.topMargin = Math.round(20 * dp);
        sheet.addView(bar, barLp);

        addFocusGlow(sheet);
        sheet.setClipChildren(true);   // keep scrolled rows inside the rounded sheet
        applyFonts(sheet);
        dialog.setContentView(sheet);
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setDimAmount(0.5f);
            w.setLayout(Math.round(560 * dp), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        if (first != null) first.requestFocus();
        dialog.show();
    }

    private String screensaverLabel() {
        String current = currentScreensaver();
        android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(current);
        if (cn == null) return getString(R.string.off);
        for (ResolveInfo r : screensavers()) {
            if (flat(r).equals(cn.flattenToString())) return screensaverName(r);
        }
        // Not in the list (Google TV's own ambient mode isn't): its app's name, never a package name on screen.
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(cn.getPackageName(), 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return getString(R.string.screensaver_system_default);   // a firmware default that isn't installed here
        }
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    /**
     * Fork: the glass look's soft glow around the focused row. Every focusable row gets a halo foreground that shows
     * only while focused; parents stop clipping so it can spill past the row.
     */
    private void addFocusGlow(View v) {
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            g.setClipChildren(false);
            g.setClipToPadding(false);
            for (int i = 0; i < g.getChildCount(); i++) addFocusGlow(g.getChildAt(i));
        } else if (v.isFocusable()) {
            float dp = getResources().getDisplayMetrics().density;
            android.graphics.drawable.StateListDrawable glow = new android.graphics.drawable.StateListDrawable();
            glow.addState(new int[]{android.R.attr.state_focused}, new GlowDrawable(14 * dp, 12 * dp, 0, 0x4DFFFFFF));
            v.setForeground(glow);
        }
    }

    /** Fork: Outfit, as in the Settings panel; views tagged "medium" (title, section and group labels) get its medium. */
    private void applyFonts(View v) {
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyFonts(g.getChildAt(i));
        } else if (v instanceof TextView) {
            ((TextView) v).setTypeface(UiFont.get(this, "medium".equals(v.getTag())
                    ? R.string.font_medium : R.string.font_regular));
        }
    }

    /** Fork: the Settings panel's round glass icon at the start of a row. */
    private void rowIcon(int rowId, int iconRes) {
        float dp = getResources().getDisplayMetrics().density;
        int size = Math.round(30 * dp), inset = Math.round(7 * dp);
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x33FFFFFF, 0x0FFFFFFF});
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setStroke(Math.max(1, Math.round(dp)), 0x3DFFFFFF);
        android.graphics.drawable.Drawable icon = getDrawable(iconRes).mutate();
        icon.setTintList(getColorStateList(R.color.settings_choice_text));   // dark on a chosen (white) row
        android.graphics.drawable.LayerDrawable d =
                new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{circle, icon});
        d.setLayerInset(1, inset, inset, inset, inset);
        d.setBounds(0, 0, size, size);
        ((TextView) findViewById(rowId)).setCompoundDrawablesRelative(d, null, null, null);
    }

    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }
        for (int i = 0; i < clockChoices.length; i++) {
            clockChoices[i].setSelected(CLOCK_FORMATS[i].equals(clockFormat));
        }
        for (int i = 0; i < lgBackgroundChoices.length; i++) {
            lgBackgroundChoices[i].setSelected(LG_BACKGROUNDS[i].equals(lgBackground));
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        openOnStartChoice.setText(getString(R.string.open_on_start_state, openOnStartLabel()));
        screensaverChoice.setText(getString(R.string.screensaver_state, screensaverLabel()));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(Config.CLOCK_FORMAT_KEY) || choices.contains(OpenOnStart.KEY)
                || choices.contains(Config.LG_BACKGROUND_KEY) || choices.contains(Config.LG_ART_MINUTES_KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}

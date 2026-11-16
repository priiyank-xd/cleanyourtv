package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A title's page in Discover (fork only): its picture across the whole screen, the facts, the story, and Watch,
 * which is focused when the page opens. Watch opens the first of your apps that streams it, offers to get the app
 * when none of yours does, or hands the title to Google TV's search when nothing streams it. "Also on" names the
 * other ways; All options lists every one in a glass sheet. Back closes the sheet, then the page.
 */
public class TitleActivity extends Activity {
    private static final String KIND = "kind", ID = "id", NAME = "name", BACKDROP = "backdrop";

    static Intent intent(Context ctx, String kind, int id, String name, String backdrop) {
        return new Intent(ctx, TitleActivity.class).putExtra(KIND, kind).putExtra(ID, id).putExtra(NAME, name)
                .putExtra(BACKDROP, backdrop == null ? "" : backdrop);
    }

    private DiscoverUi ui;
    private DiscoverNet net;
    private String kind, name;
    private int id;
    private FrameLayout root;
    private ImageView picture;
    private LinearLayout column;
    private boolean pictureShown;
    private Discover.Details details;
    private Set<String> installed;
    /** The open sheet (Where to watch, More), and what had focus before it. */
    private View sheet, beforeSheet;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new DiscoverUi(this);
        net = DiscoverNet.get(this);
        kind = "tv".equals(getIntent().getStringExtra(KIND)) ? "tv" : "movie";
        id = getIntent().getIntExtra(ID, 0);
        name = getIntent().getStringExtra(NAME);
        if (name == null) name = "";

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF060608);
        root.setClipChildren(false);   // the focused button grows past the column's edge
        picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        View veil = new View(this);
        veil.setBackground(new LayerDrawable(new Drawable[]{
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xD904040A, 0x8004040A, 0x0004040A}),
                new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xFF060608, 0x99060608, 0x00060608})}));
        root.addView(veil, new FrameLayout.LayoutParams(-1, -1));

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ui.px(640), -2, Gravity.BOTTOM | Gravity.START);
        clp.leftMargin = ui.px(56);
        clp.bottomMargin = ui.px(52);
        root.addView(column, clp);
        setContentView(root);

        showPicture(getIntent().getStringExtra(BACKDROP));
        showLoading();
        net.details(kind, id, d -> {
            if (isDestroyed()) return;
            if (d == null || d.name.isEmpty()) showFailed();
            else show(d);
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && sheet != null) {
            if (e.getAction() == KeyEvent.ACTION_UP) closeSheet();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // An app may have been installed from the Play Store in the meantime: Get becomes Watch.
        if (details != null && !installedNow().equals(installed)) show(details);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) net.trimMemory();
    }

    // ---- the page ----

    private void showPicture(String path) {
        if (pictureShown || path == null || path.isEmpty()) return;
        pictureShown = true;
        net.picture(path, "w1280", getResources().getDisplayMetrics().widthPixels, b -> {
            if (b != null && !isDestroyed()) picture.setImageBitmap(b);
        });
    }

    private void showLoading() {
        column.removeAllViews();
        column.addView(title(name));
        column.addView(line(getString(R.string.dc_loading), 14, DiscoverUi.INK_DIM, 14));
    }

    private void showFailed() {
        column.removeAllViews();
        column.addView(title(name));
        TextView t = line(getString(R.string.dc_load_failed), 14, DiscoverUi.INK_SOFT, 14);
        column.addView(t);
        LinearLayout buttons = buttonsRow();
        View google = button(R.drawable.ic_lg_search, null, getString(R.string.dc_google), v -> google());
        buttons.addView(google, buttonLp());
        column.addView(buttons);
        google.requestFocus();
    }

    private void show(Discover.Details d) {
        details = d;
        if (!d.name.isEmpty()) name = d.name;
        showPicture(d.backdrop);
        installed = installedNow();
        Discover.Provider primary = Discover.primary(d.where, installed);

        column.removeAllViews();
        column.addView(title(name));
        column.addView(line(Discover.facts(d), 14, DiscoverUi.INK_SOFT, 14));
        if (!d.overview.isEmpty()) {
            TextView story = line(d.overview, 14, 0xDBFFFFFF, 14);
            story.setSingleLine(false);
            story.setMaxLines(4);
            story.setEllipsize(TextUtils.TruncateAt.END);
            story.setLineSpacing(0, 1.35f);
            story.setMaxWidth(ui.px(520));
            column.addView(story);
        }
        String credits = Discover.credits(d);
        if (!credits.isEmpty()) column.addView(line(credits, 12, DiscoverUi.INK_DIM, 10));

        LinearLayout buttons = buttonsRow();
        View watch;
        if (primary != null) {
            String pkg = Discover.packageFor(primary.id);
            boolean have = installed.contains(pkg);
            String label = getString(have ? R.string.dc_watch_on : R.string.dc_get, primary.name);
            watch = button(0, primary, label, v -> act(primary));
            buttons.addView(watch, buttonLp());
            View more = round(R.drawable.ic_dc_more, getString(R.string.dc_more), v -> showMore());
            buttons.addView(more, roundLp());
        } else {
            watch = button(R.drawable.ic_lg_search, null, getString(R.string.dc_google), v -> google());
            buttons.addView(watch, buttonLp());
        }
        column.addView(buttons);

        List<Discover.Provider> also = Discover.others(d.where, primary, 3);
        if (d.where.isEmpty()) {
            column.addView(line(getString(R.string.dc_not_streaming), 13, DiscoverUi.INK_DIM, 18));
        } else if (!also.isEmpty() || primary == null) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setClipChildren(false);
            if (!also.isEmpty()) {
                row.addView(ui.text(getString(R.string.dc_also), 13, R.string.font_regular, DiscoverUi.INK_DIM));
                for (Discover.Provider p : also) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(46), ui.px(26));
                    lp.leftMargin = ui.px(10);
                    row.addView(ui.providerLogo(p, 46, 26), lp);
                }
            }
            TextView all = ui.text(getString(R.string.dc_all_options) + "  ›", 13, R.string.font_medium, DiscoverUi.INK);
            all.setGravity(Gravity.CENTER);
            all.setPadding(ui.px(14), 0, ui.px(14), 0);
            ui.focusGlass(all, 16);
            all.setBackground(null);
            all.setOnFocusChangeListener((v, has) -> {
                all.setBackground(has ? ui.focusedGlass(16) : null);
                all.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(ui.ms(160)).start();
            });
            all.setOnClickListener(v -> showWhere());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, ui.px(32));
            lp.leftMargin = ui.px(also.isEmpty() ? 0 : 12);
            row.addView(all, lp);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
            rlp.topMargin = ui.px(16);
            column.addView(row, rlp);
        }
        watch.requestFocus();
    }

    // ---- actions ----

    /** Watch or a sheet row: open your app, offer to get it, or search for the title on Google TV. */
    private void act(Discover.Provider p) {
        String pkg = Discover.packageFor(p.id);
        boolean ok;
        if (pkg == null) ok = Watch.googleSearch(this, name);
        else if (installed.contains(pkg)) ok = Watch.openApp(this, pkg);
        else ok = Watch.getApp(this, pkg);
        if (!ok) Toast.makeText(this, getString(R.string.dc_cant_open, p.name), Toast.LENGTH_SHORT).show();
    }

    private void google() {
        if (!Watch.googleSearch(this, name)) Toast.makeText(this, R.string.lg_search_unavailable, Toast.LENGTH_SHORT).show();
    }

    private Set<String> installedNow() {
        List<String> pkgs = new ArrayList<>();
        if (details != null) {
            for (List<Discover.Provider> g : groups(details.where)) for (Discover.Provider p : g) pkgs.add(Discover.packageFor(p.id));
        }
        return Watch.installed(this, pkgs);
    }

    private static List<List<Discover.Provider>> groups(Discover.Availability a) {
        List<List<Discover.Provider>> out = new ArrayList<>();
        out.add(a.stream);
        out.add(a.rent);
        out.add(a.buy);
        out.add(a.free);
        return out;
    }

    // ---- sheets ----

    /** Every way to watch in India, grouped; each row opens the app, gets it, or searches Google TV. */
    private void showWhere() {
        LinearLayout list = openSheet(getString(R.string.dc_where), getString(R.string.dc_where_sub));
        int[] labels = {R.string.dc_stream, R.string.dc_rent, R.string.dc_buy, R.string.dc_free};
        List<List<Discover.Provider>> groups = groups(details.where);
        View first = null;
        for (int g = 0; g < groups.size(); g++) {
            if (groups.get(g).isEmpty()) continue;
            TextView head = ui.text(getString(labels[g]).toUpperCase(java.util.Locale.ROOT), 12, R.string.font_medium, 0x80FFFFFF);
            head.setLetterSpacing(0.12f);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
            hlp.setMargins(ui.px(16), ui.px(12), 0, ui.px(6));
            list.addView(head, hlp);
            for (Discover.Provider p : groups.get(g)) {
                String pkg = Discover.packageFor(p.id);
                String value = getString(pkg == null ? R.string.dc_search_it : installed.contains(pkg) ? R.string.dc_open : R.string.dc_get_app);
                View row = sheetRow(ui.providerLogo(p, 46, 26), p.name, value, v -> act(p));
                if (first == null) first = row;
            }
        }
        if (first != null) first.requestFocus();
    }

    /** More: the other things to do with the title. */
    private void showMore() {
        openSheet(getString(R.string.dc_more), null);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_lg_search);
        View row = sheetRow(icon, getString(R.string.dc_google), null, v -> google());
        row.requestFocus();
    }

    private LinearLayout sheetList;

    private LinearLayout openSheet(String title, String sub) {
        closeSheet();
        beforeSheet = getCurrentFocus();
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x59000000);
        overlay.setClickable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(ui.sheet(28));
        panel.setPadding(ui.px(18), ui.px(24), ui.px(18), ui.px(18));
        panel.setClipChildren(false);
        TextView t = ui.text(title, 22, R.string.font_medium, DiscoverUi.INK);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.leftMargin = ui.px(16);
        panel.addView(t, tlp);
        if (sub != null) {
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.setMargins(ui.px(16), ui.px(6), 0, ui.px(6));
            panel.addView(ui.text(sub, 12, R.string.font_regular, DiscoverUi.INK_DIM), slp);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        sheetList = new LinearLayout(this);
        sheetList.setOrientation(LinearLayout.VERTICAL);
        sheetList.setPadding(0, ui.px(6), 0, ui.px(6));
        sheetList.setClipChildren(false);
        scroll.addView(sheetList, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = ui.px(8);
        panel.addView(scroll, sp);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ui.px(388), -2, Gravity.END | Gravity.TOP);
        plp.setMargins(0, ui.px(24), ui.px(24), ui.px(24));
        overlay.addView(panel, plp);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        sheet = overlay;
        column.setDescendantFocusability(LinearLayout.FOCUS_BLOCK_DESCENDANTS);
        return sheetList;
    }

    private View sheetRow(View logo, String title, String value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.px(16), 0, ui.px(16), 0);
        row.setFocusable(true);
        row.setClickable(true);
        row.addView(logo, new LinearLayout.LayoutParams(ui.px(46), ui.px(26)));
        TextView t = ui.text(title, 15, R.string.font_regular, DiscoverUi.INK);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.leftMargin = ui.px(12);
        row.addView(t, tlp);
        TextView v = value == null ? null : ui.text(value, 14, R.string.font_regular, DiscoverUi.INK_DIM);
        if (v != null) row.addView(v);
        row.setOnFocusChangeListener((x, has) -> {
            row.setBackground(has ? ui.focusedGlass(16) : null);
            row.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f).setDuration(ui.ms(160)).start();
            if (v != null) v.setTextColor(has ? DiscoverUi.INK : DiscoverUi.INK_DIM);
        });
        row.setOnClickListener(click);
        sheetList.addView(row, new LinearLayout.LayoutParams(-1, ui.px(52)));
        return row;
    }

    private void closeSheet() {
        if (sheet == null) return;
        root.removeView(sheet);
        sheet = null;
        column.setDescendantFocusability(LinearLayout.FOCUS_AFTER_DESCENDANTS);
        if (beforeSheet != null) beforeSheet.requestFocus();
    }

    // ---- pieces ----

    private TextView title(String s) {
        TextView t = ui.text(s, s.length() > 28 ? 38 : 46, R.string.font_medium, DiscoverUi.INK);
        t.setSingleLine(false);
        t.setMaxLines(2);
        t.setLetterSpacing(-0.01f);
        return t;
    }

    private TextView line(String s, float size, int color, float top) {
        TextView t = ui.text(s, size, R.string.font_regular, color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = ui.px(top);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout buttonsRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = ui.px(22);
        row.setLayoutParams(lp);
        return row;
    }

    /** A glass capsule with an app's logo or an icon, and a label. */
    private View button(int icon, Discover.Provider logoOf, String label, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(ui.px(logoOf != null ? 12 : 20), 0, ui.px(22), 0);
        if (logoOf != null) {
            b.addView(ui.providerLogo(logoOf, 52, 28), new LinearLayout.LayoutParams(ui.px(52), ui.px(28)));
        } else if (icon != 0) {
            ImageView i = new ImageView(this);
            i.setImageResource(icon);
            b.addView(i, new LinearLayout.LayoutParams(ui.px(18), ui.px(18)));
        }
        TextView t = ui.text(label, 15, R.string.font_medium, DiscoverUi.INK);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = ui.px(10);
        b.addView(t, tlp);
        ui.focusGlass(b, 24);
        b.setOnClickListener(click);
        return b;
    }

    private View round(int icon, String description, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(this);
        ImageView i = new ImageView(this);
        i.setImageResource(icon);
        b.addView(i, new FrameLayout.LayoutParams(ui.px(20), ui.px(20), Gravity.CENTER));
        b.setContentDescription(description);
        ui.focusGlass(b, 24);
        b.setOnClickListener(click);
        return b;
    }

    private LinearLayout.LayoutParams buttonLp() {
        return new LinearLayout.LayoutParams(-2, ui.px(48));
    }

    private LinearLayout.LayoutParams roundLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(48), ui.px(48));
        lp.leftMargin = ui.px(14);
        return lp;
    }
}

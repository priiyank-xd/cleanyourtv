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
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Set;

/**
 * A title's page in Discover (fork only): its picture across the whole screen, the facts, the story, and Watch,
 * which is focused when the page opens. Watch opens the first of your apps that streams it, offers to get the app
 * when none of yours does, or hands the title to Google TV's search when nothing streams it. "Also on" names the
 * other ways; All options lists every one in a glass sheet. Down scrolls to the episodes (a series), the cast and
 * crew and More like this. Back closes a sheet, then scrolls back up to Watch, then closes the page.
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
    private ScrollView scroll;
    private LinearLayout column, sections;
    private View watchButton;
    private boolean pictureShown;
    private Discover.Details details;
    private Discover.Provider primary;
    private Set<String> installed;
    private DiscoverUi.Sheet sheet;
    /** The episodes row and its season. */
    private LinearLayout episodes;
    private int season;

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

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        scroll.getViewTreeObserver().addOnScrollChangedListener(() ->
                picture.setAlpha(Math.max(0.12f, 1f - scroll.getScrollY() / (float) ui.px(420))));

        FrameLayout hero = new FrameLayout(this);
        hero.setClipChildren(false);
        content.addView(hero, new LinearLayout.LayoutParams(-1, getResources().getDisplayMetrics().heightPixels));
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ui.px(640), -2, Gravity.BOTTOM | Gravity.START);
        clp.leftMargin = ui.px(56);
        clp.bottomMargin = ui.px(52);
        hero.addView(column, clp);

        sections = new LinearLayout(this);
        sections.setOrientation(LinearLayout.VERTICAL);
        sections.setClipChildren(false);
        sections.setClipToPadding(false);
        sections.setPadding(ui.px(56), 0, 0, ui.px(56));
        sections.setBackgroundColor(0xFF060608);
        content.addView(sections, new LinearLayout.LayoutParams(-1, -2));
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
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (sheet != null) {
                if (e.getAction() == KeyEvent.ACTION_UP) closeSheet();
                return true;
            }
            View f = getCurrentFocus();
            if (f != null && watchButton != null && isInside(f, sections)) {
                if (e.getAction() == KeyEvent.ACTION_UP) {
                    scroll.smoothScrollTo(0, 0);
                    watchButton.requestFocus();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private static boolean isInside(View v, View parent) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) if (p == parent) return true;
        return false;
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
        column.addView(line(getString(R.string.dc_load_failed), 14, DiscoverUi.INK_SOFT, 14));
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
        primary = Discover.primary(d.where, installed);

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
        if (primary != null) {
            String pkg = Discover.packageFor(primary.id);
            String label = getString(installed.contains(pkg) ? R.string.dc_watch_on : R.string.dc_get, primary.name);
            watchButton = button(0, primary, label, v -> watch());
            buttons.addView(watchButton, buttonLp());
            buttons.addView(round(R.drawable.ic_dc_more, getString(R.string.dc_more), v -> showMore()), roundLp());
        } else {
            watchButton = button(R.drawable.ic_lg_search, null, getString(R.string.dc_google), v -> google());
            buttons.addView(watchButton, buttonLp());
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
            all.setFocusable(true);
            all.setClickable(true);
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
        showSections(d);
        watchButton.requestFocus();
    }

    // ---- below the fold ----

    private void showSections(Discover.Details d) {
        sections.removeAllViews();
        if (!d.seasonList.isEmpty()) {
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setClipChildren(false);
            head.addView(ui.shelfTitle(getString(R.string.dc_episodes)));
            if (d.seasonList.size() > 1) {
                for (int i = 0; i < d.seasonList.size(); i++) {
                    int number = d.seasonList.get(i)[0];
                    TextView s = ui.capsule(d.seasonNames.get(i));
                    s.setOnClickListener(v -> loadSeason(number));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, ui.px(36));
                    lp.leftMargin = ui.px(i == 0 ? 18 : 10);
                    head.addView(s, lp);
                }
            }
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
            hlp.topMargin = ui.px(20);
            sections.addView(head, hlp);
            episodes = row();
            loadSeason(d.seasonList.get(0)[0]);
        }
        if (!d.people.isEmpty()) {
            heading(getString(R.string.dc_cast));
            LinearLayout r = row();
            for (Discover.Hit p : d.people) {
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setGravity(Gravity.CENTER_HORIZONTAL);
                item.setClipChildren(false);
                FrameLayout photo = ui.avatar(p.poster, p.name, 84);
                item.addView(photo, new LinearLayout.LayoutParams(ui.px(84), ui.px(84)));
                TextView n = ui.text(p.name, 12, R.string.font_medium, DiscoverUi.INK);
                n.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ui.px(110), -2);
                nlp.topMargin = ui.px(10);
                item.addView(n, nlp);
                TextView role = ui.text(p.sub, 11, R.string.font_regular, DiscoverUi.INK_DIM);
                role.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ui.px(110), -2);
                rlp.topMargin = ui.px(3);
                item.addView(role, rlp);
                ui.focusRound(item, photo);
                item.setOnClickListener(v -> startActivity(PersonActivity.intent(this, p.id, p.name, p.poster)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(110), -2);
                lp.rightMargin = ui.px(14);
                r.addView(item, lp);
            }
        }
        if (!d.more.isEmpty()) {
            heading(getString(R.string.dc_more_like));
            LinearLayout r = row();
            for (Discover.Hit h : d.more) {
                LinearLayout card = ui.card(h.picture(), h.name, 200,
                        v -> startActivity(intent(this, h.kind, h.id, h.name, h.backdrop)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(200), -2);
                lp.rightMargin = ui.px(16);
                r.addView(card, lp);
            }
        }
    }

    private void heading(String label) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = ui.px(20);
        sections.addView(ui.shelfTitle(label), lp);
    }

    private LinearLayout row() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        LinearLayout r = new LinearLayout(this);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        r.setPadding(0, ui.px(14), ui.px(56), ui.px(10));
        hs.addView(r);
        sections.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    private void loadSeason(int number) {
        season = number;
        net.answer(Discover.seasonUrl(id, number), 24 * DiscoverNet.HOURS, a -> {
            if (isDestroyed() || season != number || episodes == null) return;
            boolean had = episodes.hasFocus();
            episodes.removeAllViews();
            if (a == null) return;
            for (Discover.Episode e : Discover.parseSeason(a.json)) {
                LinearLayout card = ui.card(e.still, e.number + ". " + e.name, 196, v -> watch());
                TextView line = ui.text(Discover.episodeLine(e), 12, R.string.font_regular, DiscoverUi.INK_DIM);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ui.px(196), -2);
                llp.topMargin = ui.px(4);
                card.addView(line, llp);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(196), -2);
                lp.rightMargin = ui.px(18);
                episodes.addView(card, lp);
            }
            if (had && episodes.getChildCount() > 0) episodes.getChildAt(0).requestFocus();
        });
    }

    // ---- actions ----

    private void watch() {
        if (primary == null) {
            google();
            return;
        }
        if (!Watch.watch(this, primary, installed, name)) {
            Toast.makeText(this, getString(R.string.dc_cant_open, primary.name), Toast.LENGTH_SHORT).show();
        }
    }

    private void google() {
        if (!Watch.googleSearch(this, name)) Toast.makeText(this, R.string.lg_search_unavailable, Toast.LENGTH_SHORT).show();
    }

    private Set<String> installedNow() {
        return Watch.installed(this, details == null ? new java.util.ArrayList<>() : Watch.packages(details.where));
    }

    // ---- sheets ----

    private void closeSheet() {
        if (sheet != null) sheet.close();
        sheet = null;
    }

    /** Every way to watch in India, grouped; each row opens the app, gets it, or searches Google TV. */
    private void showWhere() {
        sheet = ui.new Sheet(root, scroll, getString(R.string.dc_where), getString(R.string.dc_where_sub));
        int[] labels = {R.string.dc_stream, R.string.dc_rent, R.string.dc_buy, R.string.dc_free};
        List<List<Discover.Provider>> groups = java.util.Arrays.asList(details.where.stream, details.where.rent,
                details.where.buy, details.where.free);
        View first = null;
        for (int g = 0; g < groups.size(); g++) {
            if (groups.get(g).isEmpty()) continue;
            sheet.group(getString(labels[g]));
            for (Discover.Provider p : groups.get(g)) {
                String pkg = Discover.packageFor(p.id);
                String value = getString(pkg == null ? R.string.dc_search_it : installed.contains(pkg) ? R.string.dc_open : R.string.dc_get_app);
                View row = sheet.row(ui.providerLogo(p, 46, 26), p.name, value, v -> {
                    if (!Watch.watch(this, p, installed, name)) {
                        Toast.makeText(this, getString(R.string.dc_cant_open, p.name), Toast.LENGTH_SHORT).show();
                    }
                });
                if (first == null) first = row;
            }
        }
        if (first != null) first.requestFocus();
    }

    /** More: the other things to do with the title. */
    private void showMore() {
        sheet = ui.new Sheet(root, scroll, getString(R.string.dc_more), null);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_lg_search);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        sheet.row(icon, getString(R.string.dc_google), null, v -> google()).requestFocus();
    }

    // ---- pieces ----

    private TextView title(String s) {
        TextView t = ui.text(s, 46, R.string.font_medium, DiscoverUi.INK);
        t.setSingleLine(false);
        t.setMaxLines(2);
        t.setLetterSpacing(-0.01f);
        ui.sizeTitle(t, s);
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

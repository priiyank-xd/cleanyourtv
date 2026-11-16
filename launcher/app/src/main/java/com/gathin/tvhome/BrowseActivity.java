package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * A genre or a language from Discover's Browse shelf (fork only): everything in it as a grid, most popular first,
 * narrowed by Movies or Series, a sort (Popular, Top rated, Newest), a language (on a genre) or a genre (on a
 * language), and Only my apps. Down past the last row loads more. Back returns to Discover.
 */
public class BrowseActivity extends Activity {
    private static final String GENRE = "genre", LANGUAGE = "language";
    private static final int COLUMNS = 4, MAX_PAGES = 10;
    private static final long FRESH_MS = 6 * DiscoverNet.HOURS;

    static Intent genre(Context ctx, int index) {
        return new Intent(ctx, BrowseActivity.class).putExtra(GENRE, index);
    }

    static Intent language(Context ctx, String code) {
        return new Intent(ctx, BrowseActivity.class).putExtra(LANGUAGE, code);
    }

    private DiscoverUi ui;
    private DiscoverNet net;
    private FrameLayout root;
    private LinearLayout page, controls, grid;
    private TextView count;
    /** Fixed by how the page was opened: a genre (index into GENRES) or a language code. */
    private int fixedGenre = -1;
    private String fixedLanguage;
    /** The filters. */
    private String kind = "movie", sort = Discover.SORT_POPULAR, language;
    private int genre = -1;
    private boolean onlyMine = true;
    private Set<Integer> mine;
    private int loadedPages, total, seq;
    private boolean fetching;
    private LinearLayout lastRow;
    private int inRow;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new DiscoverUi(this);
        net = DiscoverNet.get(this);
        fixedGenre = getIntent().getIntExtra(GENRE, -1);
        fixedLanguage = getIntent().getStringExtra(LANGUAGE);
        if (fixedGenre >= Discover.GENRES.size()) fixedGenre = -1;
        mine = Tmdb.providerIds(Watch.installed(this, Tmdb.PROVIDERS.keySet()));
        if (mine.isEmpty()) onlyMine = false;

        root = new FrameLayout(this);
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.85f, 0f);
        glow.setGradientRadius(ui.px(640));
        glow.setColors(new int[]{fixedLanguage != null ? 0x8C4A2E14 : 0x8C14304A, 0x0008080B});
        root.setBackground(new LayerDrawable(new Drawable[]{DiscoverUi.round(0xFF08080B, 0), glow}));
        root.setClipChildren(false);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(ui.px(24));
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setPadding(ui.px(56), ui.px(32), ui.px(56), ui.px(56));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.BOTTOM);
        String name = fixedLanguage != null ? languageName(fixedLanguage) : fixedGenre >= 0 ? Discover.GENRES.get(fixedGenre).name : "";
        head.addView(ui.text(name, 40, R.string.font_medium, DiscoverUi.INK));
        count = ui.text("", 14, R.string.font_regular, DiscoverUi.INK_DIM);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.leftMargin = ui.px(14);
        clp.bottomMargin = ui.px(6);
        head.addView(count, clp);
        page.addView(head);

        controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setClipChildren(false);
        controls.setClipToPadding(false);
        LinearLayout.LayoutParams ctl = new LinearLayout.LayoutParams(-1, -2);
        ctl.topMargin = ui.px(20);
        ctl.bottomMargin = ui.px(22);
        page.addView(controls, ctl);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        page.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        paintControls();
        reload();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && ui.open != null) {
            if (e.getAction() == KeyEvent.ACTION_UP && ui.open != null) ui.open.close();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) net.trimMemory();
    }

    private static String languageName(String code) {
        for (Discover.Language l : Discover.LANGUAGES) {
            if (l.code.equals(code)) return l.own.equals(l.english) ? l.own : l.own + " · " + l.english;
        }
        return code;
    }

    // ---- the controls ----

    private void paintControls() {
        View focused = getCurrentFocus();
        int focusIndex = focused != null && focused.getParent() == controls ? controls.indexOfChild(focused) : -1;
        controls.removeAllViews();
        boolean series = fixedGenre < 0 || Discover.GENRES.get(fixedGenre).tv > 0;
        controls.addView(choice(getString(R.string.dc_movies), "movie".equals(kind), v -> setKind("movie")), capsuleLp());
        if (series) controls.addView(choice(getString(R.string.dc_series), "tv".equals(kind), v -> setKind("tv")), capsuleLp());
        controls.addView(menu(sortLabel(sort), v -> sortSheet()), capsuleLp());
        if (fixedGenre >= 0) {
            controls.addView(menu(language == null ? getString(R.string.dc_any_language) : languageName(language), v -> languageSheet()), capsuleLp());
        } else {
            controls.addView(menu(genre < 0 ? getString(R.string.dc_any_genre) : Discover.GENRES.get(genre).name, v -> genreSheet()), capsuleLp());
        }
        if (!mine.isEmpty()) controls.addView(mineToggle(), capsuleLp());
        if (focusIndex >= 0 && focusIndex < controls.getChildCount()) controls.getChildAt(focusIndex).requestFocus();
    }

    private String sortLabel(String s) {
        return getString(Discover.SORT_TOP.equals(s) ? R.string.dc_top_rated : Discover.SORT_NEW.equals(s) ? R.string.dc_newest : R.string.dc_popular_sort);
    }

    /** A capsule that is one of a pair: solid while chosen, glass otherwise, bright glass while focused. */
    private TextView choice(String label, boolean chosen, View.OnClickListener click) {
        TextView t = ui.text(label, 14, R.string.font_medium, chosen ? 0xFF111111 : DiscoverUi.INK);
        t.setGravity(Gravity.CENTER);
        t.setPadding(ui.px(18), 0, ui.px(18), 0);
        t.setFocusable(true);
        t.setClickable(true);
        Runnable paint = () -> {
            boolean f = t.isFocused();
            t.setBackground(f ? ui.focusedGlass(20) : chosen ? DiscoverUi.round(0xE6FFFFFF, ui.px(20)) : ui.glass(20));
            t.setTextColor(f || !chosen ? DiscoverUi.INK : 0xFF111111);
            t.animate().scaleX(f ? 1.07f : 1f).scaleY(f ? 1.07f : 1f).setDuration(ui.ms(160)).start();
        };
        paint.run();
        t.setOnFocusChangeListener((v, has) -> paint.run());
        t.setOnClickListener(click);
        return t;
    }

    private TextView menu(String label, View.OnClickListener click) {
        TextView t = ui.capsule(label + "  ▾");
        t.setOnClickListener(click);
        return t;
    }

    private View mineToggle() {
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(ui.px(18), 0, ui.px(14), 0);
        t.addView(ui.text(getString(R.string.dc_only_mine), 14, R.string.font_medium, DiscoverUi.INK));
        FrameLayout track = new FrameLayout(this);
        track.setBackground(DiscoverUi.round(onlyMine ? 0xFF34C759 : 0x4DFFFFFF, ui.px(10)));
        View knob = new View(this);
        GradientDrawable k = new GradientDrawable();
        k.setShape(GradientDrawable.OVAL);
        k.setColor(0xFFFFFFFF);
        knob.setBackground(k);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(ui.px(16), ui.px(16),
                Gravity.CENTER_VERTICAL | (onlyMine ? Gravity.END : Gravity.START));
        klp.setMargins(ui.px(2), 0, ui.px(2), 0);
        track.addView(knob, klp);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ui.px(34), ui.px(20));
        tlp.leftMargin = ui.px(10);
        t.addView(track, tlp);
        ui.focusGlass(t, 20);
        t.setOnClickListener(v -> {
            onlyMine = !onlyMine;
            paintControls();
            reload();
        });
        return t;
    }

    private LinearLayout.LayoutParams capsuleLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, ui.px(40));
        lp.rightMargin = ui.px(12);
        return lp;
    }

    private void setKind(String k) {
        if (k.equals(kind)) return;
        kind = k;
        paintControls();
        reload();
    }

    private DiscoverUi.Sheet sheet;

    private void closeSheet() {
        if (sheet != null) sheet.close();
        sheet = null;
    }

    private void sortSheet() {
        sheet = ui.new Sheet(root, page, getString(R.string.dc_sort), null);
        View first = null;
        for (String s : new String[]{Discover.SORT_POPULAR, Discover.SORT_TOP, Discover.SORT_NEW}) {
            View row = sheet.row(null, sortLabel(s), s.equals(sort) ? "✓" : null, v -> {
                closeSheet();
                sort = s;
                paintControls();
                reload();
            });
            if (s.equals(sort) || first == null) first = row;
        }
        first.requestFocus();
    }

    private void languageSheet() {
        sheet = ui.new Sheet(root, page, getString(R.string.dc_language), null);
        View chosen = sheet.row(null, getString(R.string.dc_any_language), language == null ? "✓" : null, v -> pickLanguage(null));
        for (Discover.Language l : Discover.LANGUAGES) {
            View row = sheet.row(null, languageName(l.code), l.code.equals(language) ? "✓" : null, v -> pickLanguage(l.code));
            if (l.code.equals(language)) chosen = row;
        }
        chosen.requestFocus();
    }

    private void pickLanguage(String code) {
        closeSheet();
        language = code;
        paintControls();
        reload();
    }

    private void genreSheet() {
        sheet = ui.new Sheet(root, page, getString(R.string.dc_genre), null);
        View chosen = sheet.row(null, getString(R.string.dc_any_genre), genre < 0 ? "✓" : null, v -> pickGenre(-1));
        for (int i = 0; i < Discover.GENRES.size(); i++) {
            int index = i;
            if (Discover.GENRES.get(i).idFor(kind) == 0) continue;
            View row = sheet.row(null, Discover.GENRES.get(i).name, i == genre ? "✓" : null, v -> pickGenre(index));
            if (i == genre) chosen = row;
        }
        chosen.requestFocus();
    }

    private void pickGenre(int index) {
        closeSheet();
        genre = index;
        paintControls();
        reload();
    }

    // ---- the grid ----

    private void reload() {
        grid.removeAllViews();
        lastRow = null;
        inRow = 0;
        loadedPages = 0;
        total = 0;
        fetching = false;
        count.setText(R.string.dc_loading);
        seq++;
        more();
    }

    /** The next page, when there is one and none is on its way. */
    private void more() {
        if (fetching || loadedPages >= MAX_PAGES || (loadedPages > 0 && loadedPages * 20 >= total)) return;
        fetching = true;
        int mineSeq = seq;
        int g = fixedGenre >= 0 ? Discover.GENRES.get(fixedGenre).idFor(kind) : genre >= 0 ? Discover.GENRES.get(genre).idFor(kind) : 0;
        String lang = fixedLanguage != null ? fixedLanguage : language;
        String url = Discover.browseUrl(kind, g, lang, sort, onlyMine ? mine : null, loadedPages + 1, LocalDate.now());
        net.answer(url, FRESH_MS, a -> {
            if (mineSeq != seq || isDestroyed()) return;
            fetching = false;
            if (a == null) {
                if (loadedPages == 0) count.setText(R.string.dc_offline);
                return;
            }
            loadedPages++;
            total = Discover.total(a.json);
            count.setText(getString(onlyMine ? R.string.dc_count_mine : R.string.dc_count_all, total));
            List<Discover.Hit> hits = Discover.parseList(a.json, kind, 20);
            boolean first = grid.getChildCount() == 0;
            for (Discover.Hit h : hits) add(h);
            if (first && grid.getChildCount() > 0 && (getCurrentFocus() == null || !getCurrentFocus().isShown())) {
                ((LinearLayout) grid.getChildAt(0)).getChildAt(0).requestFocus();
            }
            if (hits.isEmpty() && loadedPages == 1) count.setText(R.string.dc_none_here);
        });
    }

    private void add(Discover.Hit h) {
        if (lastRow == null || inRow == COLUMNS) {
            lastRow = new LinearLayout(this);
            lastRow.setClipChildren(false);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.bottomMargin = ui.px(22);
            grid.addView(lastRow, rlp);
            inRow = 0;
        }
        LinearLayout card = ui.card(h.picture(), h.name, 200,
                v -> startActivity(TitleActivity.intent(this, h.kind, h.id, h.name, h.backdrop)));
        View.OnFocusChangeListener lift = card.getOnFocusChangeListener();
        LinearLayout row = lastRow;
        card.setOnFocusChangeListener((v, has) -> {
            lift.onFocusChange(v, has);
            // reaching the last row asks for the next page
            if (has && grid.indexOfChild(row) >= grid.getChildCount() - 1) more();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(200), -2);
        if (inRow != COLUMNS - 1) lp.rightMargin = ui.px(16);
        lastRow.addView(card, lp);
        inRow++;
    }
}

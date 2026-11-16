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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Discover (fork only), opened by Search on Home: a glass tab bar (Search, Discover, My lists) over the tab's page. Search
 * on Home lands on the Search tab with the TV's keyboard up. Moving along the tab bar shows each tab; Down enters it.
 * Back works one level at a time: from inside a tab to the tab bar, from the tab bar to Home.
 */
public class DiscoverActivity extends Activity {
    static final String TAB = "tab", TAB_SEARCH = "search", TAB_DISCOVER = "discover";

    static Intent intent(Context ctx, String tab) {
        return new Intent(ctx, DiscoverActivity.class).putExtra(TAB, tab);
    }

    /** A tab's page. */
    interface Pane {
        View view();

        /** Moves focus into the page, onto its first thing. */
        void enter();

        /** The page became the one shown. */
        void shown();

        /** Back out of the page: scroll it to the top. */
        void leave();

        void destroy();
    }

    private DiscoverUi ui;
    private TmdbFeed feed;
    private FrameLayout root, pages;
    private LinearLayout bar;
    private final View[] tabs = new View[3];
    private final Pane[] panes = new Pane[3];
    private int current = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new DiscoverUi(this);
        feed = new TmdbFeed(this, BuildConfig.TMDB_TOKEN);

        root = new FrameLayout(this);
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.5f, 0f);
        glow.setGradientRadius(ui.px(620));
        glow.setColors(new int[]{0x8C1E2C48, 0x0008080B});
        root.setBackground(new LayerDrawable(new Drawable[]{DiscoverUi.round(0xFF08080B, 0), glow}));
        root.setClipChildren(false);
        backdrop();
        pages = new FrameLayout(this);
        pages.setClipChildren(false);
        root.addView(pages, new FrameLayout.LayoutParams(-1, -1));

        panes[0] = new SearchPane(this, ui, feed);
        panes[1] = new HomePane(this, ui, feed);
        panes[2] = new ListsPane(this, ui);
        for (Pane p : panes) {
            p.view().setVisibility(View.GONE);
            pages.addView(p.view(), new FrameLayout.LayoutParams(-1, -1));
        }

        // the tab bar: one glass capsule, centred at the top
        bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.px(4), 0, ui.px(4), 0);
        bar.setBackground(ui.glass(22));
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        ImageView searchIcon = new ImageView(this);
        searchIcon.setImageResource(R.drawable.ic_lg_search);
        FrameLayout searchTab = new FrameLayout(this);
        searchTab.addView(searchIcon, new FrameLayout.LayoutParams(ui.px(18), ui.px(18), Gravity.CENTER));
        searchTab.setContentDescription(getString(R.string.dc_search_it));
        tabs[0] = searchTab;
        TextView discover = ui.text(getString(R.string.hub_search_discover), 14, R.string.font_medium, DiscoverUi.INK);
        discover.setGravity(Gravity.CENTER);
        discover.setPadding(ui.px(18), 0, ui.px(18), 0);
        tabs[1] = discover;
        TextView lists = ui.text(getString(R.string.dc_my_lists), 14, R.string.font_medium, DiscoverUi.INK);
        lists.setGravity(Gravity.CENTER);
        lists.setPadding(ui.px(18), 0, ui.px(18), 0);
        tabs[2] = lists;
        for (int i = 0; i < tabs.length; i++) {
            int index = i;
            View t = tabs[i];
            t.setFocusable(true);
            t.setClickable(true);
            t.setOnFocusChangeListener((v, has) -> {
                if (has) select(index);
                paintTabs();
                t.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f).setDuration(ui.ms(160)).start();
            });
            t.setOnClickListener(v -> panes[index].enter());
            t.setOnKeyListener((v, code, e) -> {
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.getAction() == KeyEvent.ACTION_DOWN) {
                    panes[index].enter();
                    return true;
                }
                return false;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(i == 0 ? ui.px(52) : -2, ui.px(36));
            if (i > 0) lp.leftMargin = ui.px(2);
            bar.addView(t, lp);
        }
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, ui.px(44), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        blp.topMargin = ui.px(22);
        root.addView(bar, blp);
        setContentView(root);

        int first = TAB_DISCOVER.equals(getIntent().getStringExtra(TAB)) ? 1 : 0;
        select(first);
        panes[first].enter();
    }

    /** Shows a tab's page. */
    private void select(int index) {
        if (index == current) return;
        if (current >= 0) panes[current].view().setVisibility(View.GONE);
        current = index;
        panes[index].view().setVisibility(View.VISIBLE);
        setChrome(1f);
        panes[index].shown();
        paintTabs();
    }

    private void paintTabs() {
        for (int i = 0; i < tabs.length; i++) {
            View t = tabs[i];
            if (t.hasFocus() || t.isFocused()) t.setBackground(ui.focusedGlass(18));
            else t.setBackground(i == current ? DiscoverUi.round(0x2EFFFFFF, ui.px(18)) : null);
        }
    }

    /** The tab bar fades away while a page scrolls. */
    void setChrome(float alpha) {
        bar.setAlpha(alpha);
    }

    /** Focus onto the current tab in the tab bar. */
    void focusTabs() {
        tabs[current].requestFocus();
    }

    FrameLayout root() {
        return root;
    }

    /** The pages under the tab bar, which a sheet keeps focus out of while it is open. */
    FrameLayout pages() {
        return pages;
    }

    DiscoverUi ui() {
        return ui;
    }

    /**
     * Home's background behind Search and My lists: the same picture Home shows right now (its movie art, or the
     * theme's wallpaper), dimmed under a dark wash so the glass and text on top stay easy to read. The Discover tab
     * draws its own hero over it. With neither, the plain dark glow stays.
     */
    private void backdrop() {
        int width = getResources().getDisplayMetrics().widthPixels;
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setAlpha(0.55f);
        java.util.List<Tmdb.Title> titles = feed.artTitles();
        if (Config.LG_MOVIE_ART.equals(Config.lgBackground(this)) && !titles.isEmpty()) {
            // the same pick as Home's movie art at this moment
            Tmdb.Title want = titles.get(Tmdb.artIndex(System.currentTimeMillis(),
                    Config.lgArtMinutes(this) * 60_000L, titles.size()));
            feed.decodeArt(want, width, b -> {
                if (b != null && !isDestroyed()) art.setImageBitmap(b);
            });
        } else {
            android.graphics.Bitmap wallpaper = LgHome.loadWallpaper(Config.load(this).wallpaper, width);
            if (wallpaper == null) return;
            art.setImageBitmap(wallpaper);
        }
        root.addView(art, new FrameLayout.LayoutParams(-1, -1));
        View wash = new View(this);
        wash.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB308080B, 0x9908080B, 0xE608080B}));
        root.addView(wash, new FrameLayout.LayoutParams(-1, -1));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // back from a title page or Connect TMDB: lists and Watch later may have changed
        if (current == 2) panes[2].shown();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (ui.open != null) {
                if (e.getAction() == KeyEvent.ACTION_UP && ui.open != null) ui.open.close();
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP) {
                View f = getCurrentFocus();
                boolean onBar = f != null && f.getParent() == bar;
                if (onBar || f == null) {
                    finish();
                } else {
                    panes[current].leave();
                    focusTabs();
                }
            }
            return true;
        }
        // Up into the tab bar always lands on the tab you are on, never on whichever tab is nearest
        if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP && e.getAction() == KeyEvent.ACTION_DOWN && current >= 0) {
            View f = getCurrentFocus();
            if (f != null && f.getParent() != bar && ui.open == null) {
                View next = f.focusSearch(View.FOCUS_UP);
                if (next != null && next.getParent() == bar) {
                    tabs[current].requestFocus();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onDestroy() {
        for (Pane p : panes) p.destroy();
        feed.shutdown();
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) DiscoverNet.get(this).trimMemory();
    }
}

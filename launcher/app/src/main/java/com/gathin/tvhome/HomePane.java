package com.gathin.tvhome;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
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

import java.text.DateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Discover's Discover tab (fork only): a full-screen hero of five titles popular on your apps, its Watch button
 * focused on the way in, then shelves: Top 10 on your apps, New on your apps, Browse (languages and genres) and
 * Coming soon. TMDB has no per-country charts, so "on your apps" means streaming in India on the services whose
 * apps are installed, most popular first. Answers are saved on the projector: offline, the page shows the last
 * ones with a notice and Retry. Left on Watch and Right on Details page through the hero.
 */
final class HomePane implements DiscoverActivity.Pane {
    private static final long HERO_MS = 8000, FRESH_MS = 3 * DiscoverNet.HOURS;
    private static final int HERO_COUNT = 5, TOP_COUNT = 10;

    private final DiscoverActivity act;
    private final DiscoverUi ui;
    private final DiscoverNet net;
    private final TmdbFeed feed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FrameLayout page;
    private final ImageView backdrop;
    private final ScrollView scroll;
    private final LinearLayout shelves;
    private final FrameLayout notice;
    private TextView kicker, title, facts, story;
    private LinearLayout watch, dots;
    private View details;
    private boolean loading, loaded;
    private Set<String> installed = new LinkedHashSet<>();
    private final List<Discover.Hit> heroes = new ArrayList<>();
    private int hero;
    private Discover.Details heroDetails;
    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            boolean busy = watch != null && (watch.hasFocus() || (details != null && details.hasFocus()));
            if (!busy && heroes.size() > 1 && page.getVisibility() == View.VISIBLE) showHero(hero + 1);
            handler.postDelayed(this, HERO_MS);
        }
    };

    HomePane(DiscoverActivity act, DiscoverUi ui, TmdbFeed feed) {
        this.act = act;
        this.ui = ui;
        this.feed = feed;
        net = DiscoverNet.get(act);
        page = new FrameLayout(act);
        page.setBackgroundColor(0xFF060608);
        page.setClipChildren(false);
        backdrop = new ImageView(act);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        page.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View veil = new View(act);
        veil.setBackground(new LayerDrawable(new Drawable[]{
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xC704040A, 0x5904040A, 0x0004040A}),
                new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xFF060608, 0x8C060608, 0x00060608})}));
        page.addView(veil, new FrameLayout.LayoutParams(-1, -1));

        scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        LinearLayout content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // the hero: a spacer as tall as most of the screen, its words at the bottom left
        FrameLayout heroBox = new FrameLayout(act);
        heroBox.setClipChildren(false);
        content.addView(heroBox, new LinearLayout.LayoutParams(-1, ui.px(468)));
        LinearLayout column = new LinearLayout(act);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ui.px(620), -2, Gravity.BOTTOM | Gravity.START);
        clp.leftMargin = ui.px(56);
        clp.bottomMargin = ui.px(30);
        heroBox.addView(column, clp);
        kicker = ui.text(act.getString(R.string.dc_popular_hero).toUpperCase(java.util.Locale.ROOT), 12, R.string.font_medium, 0xB8FFFFFF);
        kicker.setLetterSpacing(0.14f);
        column.addView(kicker);
        title = ui.text(act.getString(R.string.dc_loading), 46, R.string.font_medium, DiscoverUi.INK);
        title.setSingleLine(false);
        title.setMaxLines(2);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = ui.px(10);
        column.addView(title, tlp);
        facts = ui.text("", 14, R.string.font_regular, DiscoverUi.INK_SOFT);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
        flp.topMargin = ui.px(12);
        column.addView(facts, flp);
        story = ui.text("", 14, R.string.font_regular, 0xDBFFFFFF);
        story.setSingleLine(false);
        story.setMaxLines(2);
        story.setEllipsize(TextUtils.TruncateAt.END);
        story.setLineSpacing(0, 1.3f);
        story.setMaxWidth(ui.px(500));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = ui.px(10);
        column.addView(story, slp);
        LinearLayout buttons = new LinearLayout(act);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = ui.px(20);
        column.addView(buttons, blp);
        watch = new LinearLayout(act);
        watch.setGravity(Gravity.CENTER_VERTICAL);
        ui.focusGlass(watch, 24);
        watch.setOnClickListener(v -> watchHero());
        watch.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && e.getAction() == KeyEvent.ACTION_DOWN && heroes.size() > 1) {
                showHero(hero - 1);
                return true;
            }
            return false;
        });
        buttons.addView(watch, new LinearLayout.LayoutParams(-2, ui.px(48)));
        TextView more = ui.text(act.getString(R.string.dc_details), 15, R.string.font_medium, DiscoverUi.INK);
        more.setGravity(Gravity.CENTER);
        more.setPadding(ui.px(22), 0, ui.px(22), 0);
        ui.focusGlass(more, 24);
        more.setOnClickListener(v -> openHero());
        more.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && e.getAction() == KeyEvent.ACTION_DOWN && heroes.size() > 1) {
                showHero(hero + 1);
                return true;
            }
            return false;
        });
        details = more;
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, ui.px(48));
        dlp.leftMargin = ui.px(14);
        buttons.addView(more, dlp);
        dots = new LinearLayout(act);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(-2, -2);
        dotsLp.leftMargin = ui.px(22);
        buttons.addView(dots, dotsLp);
        paintWatch(null);

        // the shelves, on the page's dark under the picture
        shelves = new LinearLayout(act);
        shelves.setOrientation(LinearLayout.VERTICAL);
        shelves.setClipChildren(false);
        shelves.setClipToPadding(false);
        shelves.setPadding(ui.px(56), ui.px(18), 0, ui.px(56));
        shelves.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00060608, 0xF2060608, 0xFF060608, 0xFF060608}));
        content.addView(shelves, new LinearLayout.LayoutParams(-1, -2));

        notice = new FrameLayout(act);
        notice.setVisibility(View.GONE);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-2, ui.px(52), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        nlp.topMargin = ui.px(82);
        page.addView(notice, nlp);

        scroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            int y = scroll.getScrollY();
            act.setChrome(Math.max(0f, 1f - y / (float) ui.px(120)));
            backdrop.setAlpha(Math.max(0.15f, 1f - y / (float) ui.px(420)));
        });
        skeleton();
    }

    @Override public View view() {
        return page;
    }

    @Override public void enter() {
        if (loading) return;   // nothing to focus yet: stay on the tab bar
        scroll.scrollTo(0, 0);
        watch.requestFocus();
    }

    @Override public void shown() {
        if (!loaded && !loading) load();
        handler.removeCallbacks(rotate);
        handler.postDelayed(rotate, HERO_MS);
    }

    @Override public void leave() {
        scroll.smoothScrollTo(0, 0);
    }

    @Override public void destroy() {
        handler.removeCallbacksAndMessages(null);
    }

    // ---- loading ----

    private List<Discover.Hit> heroMovies, heroTv, topMovies, topTv, soon;
    private boolean offline;
    private long savedAt;
    private int pending;

    private void load() {
        if (!net.enabled()) {
            title.setText(R.string.dc_no_key);
            return;
        }
        loading = true;
        offline = false;
        savedAt = Long.MAX_VALUE;
        installed = Watch.installed(act, Tmdb.PROVIDERS.keySet());
        Set<Integer> providers = Tmdb.providerIds(installed);
        if (providers.isEmpty()) providers = Tmdb.providerIds(Tmdb.PROVIDERS.keySet());   // no apps yet: every service
        LocalDate today = LocalDate.now();
        pending = 5;
        get(Discover.popularUrl("movie", providers, 90, today), a -> heroMovies = Discover.parseList(a, "movie", 20));
        get(Discover.popularUrl("tv", providers, 90, today), a -> heroTv = Discover.parseList(a, "tv", 20));
        get(Discover.popularUrl("movie", providers, 0, today), a -> topMovies = Discover.parseList(a, "movie", 20));
        get(Discover.popularUrl("tv", providers, 0, today), a -> topTv = Discover.parseList(a, "tv", 20));
        get(Discover.upcomingUrl(), a -> soon = Discover.upcoming(Discover.parseList(a, "movie", 20), today, 10));
    }

    private interface Into {
        void put(org.json.JSONObject answer);
    }

    private void get(String url, Into into) {
        net.answer(url, FRESH_MS, a -> {
            if (act.isDestroyed()) return;
            if (a != null) {
                into.put(a.json);
                if (a.offline) {
                    offline = true;
                    savedAt = Math.min(savedAt, a.savedAt);
                }
            } else {
                offline = true;
            }
            if (--pending == 0) build();
        });
    }

    private void build() {
        loading = false;
        List<Discover.Hit> empty = new ArrayList<>();
        heroes.clear();
        heroes.addAll(Discover.merge(heroMovies == null ? empty : heroMovies, heroTv == null ? empty : heroTv, HERO_COUNT));
        List<Discover.Hit> top = Discover.merge(topMovies == null ? empty : topMovies, topTv == null ? empty : topTv, TOP_COUNT);
        boolean any = !heroes.isEmpty() || !top.isEmpty();
        loaded = any && !offline;
        showNotice(any);
        shelves.removeAllViews();
        kicker.setVisibility(any ? View.VISIBLE : View.GONE);
        details.setVisibility(any ? View.VISIBLE : View.GONE);
        dots.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any) {
            // nothing saved yet: say so once, with Retry where Watch would be
            title.setText(R.string.dc_offline_title);
            ui.sizeTitle(title, "");
            facts.setText("");
            story.setText(R.string.dc_offline);
            paintRetry();
            if (act.getCurrentFocus() == null || !act.getCurrentFocus().isShown()) enter();
            return;
        }
        dots.removeAllViews();
        for (int i = 0; i < heroes.size(); i++) {
            View d = new View(act);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(6), ui.px(6));
            lp.rightMargin = ui.px(6);
            dots.addView(d, lp);
        }
        hero = 0;
        if (!heroes.isEmpty()) showHero(0);

        if (!top.isEmpty()) topTen(top);
        List<Tmdb.Title> fresh = feed.newThisWeek();
        if (!fresh.isEmpty()) {
            LinearLayout row = shelf(act.getString(R.string.dc_new_on_apps));
            for (Tmdb.Title t : fresh) {
                row.addView(ui.card(t.backdrop, t.name, 200, v -> openTitle(t.kind, t.id, t.name, t.backdrop)), cardLp());
            }
        }
        browse();
        if (soon != null && !soon.isEmpty()) {
            LinearLayout row = shelf(act.getString(R.string.dc_coming_soon));
            for (Discover.Hit h : soon) {
                LinearLayout card = ui.card(h.picture(), h.name, 200, v -> openTitle(h.kind, h.id, h.name, h.backdrop));
                FrameLayout pic = (FrameLayout) card.getChildAt(0);
                TextView date = ui.text(act.getString(R.string.dc_in_cinemas, Discover.shortDate(h.date)), 11, R.string.font_medium, DiscoverUi.INK);
                date.setPadding(ui.px(8), ui.px(4), ui.px(8), ui.px(4));
                date.setBackground(DiscoverUi.round(0x99000000, ui.px(6)));
                FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
                dlp.setMargins(ui.px(8), ui.px(8), 0, 0);
                pic.addView(date, dlp);
                row.addView(card, cardLp());
            }
        }
        if (act.getCurrentFocus() == null || !act.getCurrentFocus().isShown()) enter();
    }

    private void showNotice(boolean any) {
        notice.removeAllViews();
        if (!offline || !any) {
            notice.setVisibility(View.GONE);
            return;
        }
        LinearLayout pill = new LinearLayout(act);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(ui.glass(26));
        pill.setPadding(ui.px(20), 0, ui.px(8), 0);
        String when = savedAt == Long.MAX_VALUE ? "" : DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(savedAt));
        pill.addView(ui.text(any && !when.isEmpty() ? act.getString(R.string.dc_offline_saved, when) : act.getString(R.string.dc_offline_title),
                14, R.string.font_regular, DiscoverUi.INK));
        TextView retry = ui.capsule(act.getString(R.string.dc_retry));
        retry.setTag("retry");
        retry.setOnClickListener(v -> {
            loaded = false;
            load();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, ui.px(38));
        rlp.leftMargin = ui.px(14);
        pill.addView(retry, rlp);
        notice.addView(pill, new FrameLayout.LayoutParams(-2, -1));
        notice.setVisibility(View.VISIBLE);
    }

    /** While the first answers are on their way: the shelves' shapes, nothing to focus. */
    private void skeleton() {
        for (int s = 0; s < 2; s++) {
            TextView t = ui.shelfTitle(" ");
            shelves.addView(t);
            LinearLayout row = new LinearLayout(act);
            row.setPadding(0, ui.px(12), 0, ui.px(26));
            for (int i = 0; i < 5; i++) {
                View box = new View(act);
                box.setBackground(DiscoverUi.round(0x14FFFFFF, ui.px(12)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(200), ui.px(112));
                lp.rightMargin = ui.px(16);
                row.addView(box, lp);
            }
            shelves.addView(row);
        }
    }

    // ---- the hero ----

    private void showHero(int i) {
        if (heroes.isEmpty()) return;
        hero = (i % heroes.size() + heroes.size()) % heroes.size();
        Discover.Hit h = heroes.get(hero);
        heroDetails = null;
        title.setText(h.name);
        ui.sizeTitle(title, h.name);
        String kindWord = act.getString("movie".equals(h.kind) ? R.string.dc_movie : R.string.dc_one_series);
        facts.setText(h.year.isEmpty() ? kindWord : act.getString(R.string.dc_pair, kindWord, h.year));
        story.setText("");
        paintWatch(null);
        for (int d = 0; d < dots.getChildCount(); d++) {
            View dot = dots.getChildAt(d);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dot.getLayoutParams();
            lp.width = ui.px(d == hero ? 18 : 6);
            dot.setLayoutParams(lp);
            dot.setBackground(DiscoverUi.round(d == hero ? 0xFFFFFFFF : 0x59FFFFFF, ui.px(3)));
        }
        String pic = h.picture();
        backdrop.animate().alpha(0.35f).setDuration(ui.ms(160)).withEndAction(() ->
                net.picture(pic, "w1280", act.getResources().getDisplayMetrics().widthPixels, b -> {
                    if (act.isDestroyed() || hero >= heroes.size() || heroes.get(hero) != h) return;
                    if (b != null) backdrop.setImageBitmap(b);
                    backdrop.animate().alpha(scroll.getScrollY() > 0 ? backdrop.getAlpha() : 1f).setDuration(ui.ms(320)).start();
                })).start();
        net.details(h.kind, h.id, d -> {
            if (d == null || act.isDestroyed() || hero >= heroes.size() || heroes.get(hero) != h) return;
            heroDetails = d;
            facts.setText(Discover.facts(d));
            story.setText(d.overview);
            installed.addAll(Watch.installed(act, Watch.packages(d.where)));
            paintWatch(Discover.primary(d.where, installed));
        });
    }

    /** With nothing to show, Watch's place holds Retry. */
    private void paintRetry() {
        watch.removeAllViews();
        watch.setPadding(ui.px(22), 0, ui.px(22), 0);
        watch.addView(ui.text(act.getString(R.string.dc_retry), 15, R.string.font_medium, DiscoverUi.INK));
        heroes.clear();
    }

    /** Watch on the hero: the app's logo and "Watch on …", "Get …", or plain Watch while the details load. */
    private void paintWatch(Discover.Provider p) {
        watch.removeAllViews();
        watch.setPadding(ui.px(p != null ? 12 : 20), 0, ui.px(22), 0);
        if (p != null) {
            watch.addView(ui.providerLogo(p, 52, 28), new LinearLayout.LayoutParams(ui.px(52), ui.px(28)));
        } else {
            ImageView play = new ImageView(act);
            play.setImageResource(R.drawable.ic_lg_play);
            watch.addView(play, new LinearLayout.LayoutParams(ui.px(16), ui.px(16)));
        }
        String label = p == null ? act.getString(R.string.dc_watch)
                : act.getString(installed.contains(Discover.packageFor(p.id)) ? R.string.dc_watch_on : R.string.dc_get, p.name);
        TextView t = ui.text(label, 15, R.string.font_medium, DiscoverUi.INK);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = ui.px(10);
        watch.addView(t, lp);
    }

    private void watchHero() {
        if (heroes.isEmpty()) {
            if (!loading) load();
            return;
        }
        Discover.Hit h = heroes.get(hero);
        Discover.Provider p = heroDetails == null ? null : Discover.primary(heroDetails.where, installed);
        if (p == null) {
            openHero();
            return;
        }
        if (!Watch.watch(act, p, installed, h.name)) {
            Toast.makeText(act, act.getString(R.string.dc_cant_open, p.name), Toast.LENGTH_SHORT).show();
        }
    }

    private void openHero() {
        if (heroes.isEmpty()) return;
        Discover.Hit h = heroes.get(hero);
        openTitle(h.kind, h.id, h.name, h.backdrop);
    }

    private void openTitle(String kind, int id, String name, String backdrop) {
        act.startActivity(TitleActivity.intent(act, kind, id, name, backdrop));
    }

    // ---- shelves ----

    /** A titled shelf that scrolls sideways; returns its row. */
    private LinearLayout shelf(String label) {
        shelves.addView(ui.shelfTitle(label));
        HorizontalScrollView hs = new HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        LinearLayout row = new LinearLayout(act);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(0, ui.px(14), ui.px(56), ui.px(26));
        hs.addView(row);
        shelves.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(200), -2);
        lp.rightMargin = ui.px(16);
        return lp;
    }

    /** Posters with their rank beside them; the focused one's name and kind show under the shelf. */
    private void topTen(List<Discover.Hit> top) {
        LinearLayout row = shelf(act.getString(R.string.dc_top_ten));
        TextView caption = ui.text(" ", 13, R.string.font_medium, DiscoverUi.INK_SOFT);
        for (int i = 0; i < top.size(); i++) {
            Discover.Hit h = top.get(i);
            FrameLayout item = new FrameLayout(act);
            item.setClipChildren(false);
            TextView rank = ui.text(String.valueOf(i + 1), 120, R.string.font_medium, 0x4DFFFFFF);
            rank.setIncludeFontPadding(false);
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            rlp.bottomMargin = -ui.px(14);
            item.addView(rank, rlp);
            FrameLayout poster = ui.poster(h.poster.isEmpty() ? h.backdrop : h.poster, 92, 138, 12);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ui.px(92), ui.px(138), Gravity.BOTTOM | Gravity.START);
            plp.leftMargin = ui.px(i + 1 >= 10 ? 78 : 52);
            item.addView(poster, plp);
            ui.focusArt(item, poster, 12);
            View.OnFocusChangeListener lift = item.getOnFocusChangeListener();
            item.setOnFocusChangeListener((v, has) -> {
                lift.onFocusChange(v, has);
                if (has) caption.setText(act.getString(R.string.dc_pair, h.name,
                        act.getString("movie".equals(h.kind) ? R.string.dc_movie : R.string.dc_one_series)));
            });
            item.setOnClickListener(v -> openTitle(h.kind, h.id, h.name, h.backdrop));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, ui.px(150));
            lp.rightMargin = ui.px(18);
            row.addView(item, lp);
        }
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = -ui.px(10);
        clp.bottomMargin = ui.px(20);
        shelves.addView(caption, clp);
    }

    /** Browse: languages first (most watched in India), then genres, as glass capsules. */
    private void browse() {
        LinearLayout row = shelf(act.getString(R.string.dc_browse));
        for (Discover.Language l : Discover.LANGUAGES) {
            TextView c = ui.capsule(l.own.equals(l.english) ? l.own : l.own + "  " + l.english);
            c.setOnClickListener(v -> act.startActivity(BrowseActivity.language(act, l.code)));
            row.addView(c, capsuleLp());
        }
        for (int i = 0; i < Discover.GENRES.size(); i++) {
            int index = i;
            TextView c = ui.capsule(Discover.GENRES.get(i).name);
            c.setOnClickListener(v -> act.startActivity(BrowseActivity.genre(act, index)));
            row.addView(c, capsuleLp());
        }
    }

    private LinearLayout.LayoutParams capsuleLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, ui.px(44));
        lp.rightMargin = ui.px(12);
        return lp;
    }
}

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
    /** Apps shown after "Watch on" in the hero. */
    private static final int MAX_APPS = 3;
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
    private LinearLayout scores;
    private LinearLayout watch, dots, pager;
    private View details, saveButton;
    private boolean loading, loaded;
    private Set<String> installed = new LinkedHashSet<>();
    private final List<Discover.Hit> heroes = new ArrayList<>();
    private int hero;
    private Discover.Details heroDetails;
    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            boolean busy = watch != null && (watch.hasFocus() || pager.hasFocus() || (details != null && details.hasFocus())
                || (saveButton != null && saveButton.hasFocus()) || act.ui().open != null);
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

        scroll = ui.page();
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
        kicker = ui.text(act.getString(R.string.dc_popular_hero).toUpperCase(java.util.Locale.ROOT), 13, R.string.font_medium, 0xB8FFFFFF);
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
        scores = ui.ratingsRow();
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(-2, ui.px(22));
        sclp.topMargin = ui.px(10);
        column.addView(scores, sclp);
        story = ui.text("", 14, R.string.font_regular, 0xDBFFFFFF);
        story.setSingleLine(false);
        story.setMaxLines(2);
        story.setEllipsize(TextUtils.TruncateAt.END);
        story.setLineSpacing(0, 1.3f);
        story.setMaxWidth(ui.px(500));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = ui.px(10);
        column.addView(story, slp);
        // The poster level: focused (Down from the tab bar), Left/Right change the title and OK opens it.
        // Down again goes to the buttons, where Left/Right only move between them.
        pager = new LinearLayout(act);
        pager.setGravity(Gravity.CENTER_VERTICAL);
        pager.setPadding(0, 0, ui.px(10), 0);
        pager.setFocusable(true);
        pager.setClickable(true);
        pager.setContentDescription(act.getString(R.string.dc_details));
        dots = new LinearLayout(act);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        dots.setAlpha(0.7f);
        pager.addView(dots, new LinearLayout.LayoutParams(-2, -2));
        // just the dots, nothing around them: focused, they are drawn bigger and fully bright
        // (drawn, not scaled: a scaled row grows past its own edge and gets cut)
        pager.setOnFocusChangeListener((v, has) -> {
            paintDots();
            dots.animate().alpha(has ? 1f : 0.7f).setDuration(ui.ms(180)).start();
        });
        pager.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (heroes.size() > 1) showHero(hero + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1));
                return true;   // stays on the poster
            }
            return false;
        });
        pager.setOnClickListener(v -> openHero());
        LinearLayout.LayoutParams plp0 = new LinearLayout.LayoutParams(-2, ui.px(30));
        plp0.topMargin = ui.px(14);
        column.addView(pager, plp0);
        LinearLayout buttons = ui.row();
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = ui.px(12);
        column.addView(buttons, blp);
        // "Watch on" and a button per app, holding only the app's logo
        watch = new LinearLayout(act);
        watch.setGravity(Gravity.CENTER_VERTICAL);
        watch.setClipChildren(false);
        watch.setClipToPadding(false);
        buttons.addView(watch, new LinearLayout.LayoutParams(-2, ui.px(48)));
        TextView more = ui.text(act.getString(R.string.dc_details), 15, R.string.font_medium, DiscoverUi.INK);
        more.setGravity(Gravity.CENTER);
        more.setPadding(ui.px(18), 0, ui.px(18), 0);
        ui.focusBare(more, 1.06f);   // plain at rest; Home's pill around it when focused
        more.setOnClickListener(v -> openHero());
        details = more;
        FrameLayout plus = new FrameLayout(act);
        ImageView plusIcon = new ImageView(act);
        plusIcon.setImageResource(R.drawable.ic_dc_plus);
        plus.addView(plusIcon, new FrameLayout.LayoutParams(ui.px(26), ui.px(26), Gravity.CENTER));
        plus.setContentDescription(act.getString(R.string.dc_save));
        ui.focusBare(plus, 1.06f);
        plus.setOnClickListener(v -> {
            if (heroes.isEmpty()) return;
            Discover.Hit h = heroes.get(hero);
            SaveSheet.open(act, ui, act.root(), act.pages(), h.kind, h.id, h.name);
        });
        saveButton = plus;
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ui.px(48), ui.px(48));
        plp.leftMargin = ui.px(22);
        buttons.addView(plus, plp);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, ui.px(48));
        dlp.leftMargin = ui.px(4);
        buttons.addView(more, dlp);
        paintWatch(null, false);

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
        if (!pager.requestFocus() && !watch.requestFocus()) saveButton.requestFocus();
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

    private List<Discover.Hit> heroMovies, heroTv, topMovies, topTv, chart, soon;
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
        pending = 6;
        get(Discover.popularUrl("movie", providers, 90, today), a -> heroMovies = Discover.parseList(a, "movie", 20));
        get(Discover.popularUrl("tv", providers, 90, today), a -> heroTv = Discover.parseList(a, "tv", 20));
        get(Discover.popularUrl("movie", providers, 0, today), a -> topMovies = Discover.parseList(a, "movie", 20));
        get(Discover.popularUrl("tv", providers, 0, today), a -> topTv = Discover.parseList(a, "tv", 20));
        get(Discover.upcomingUrl(), a -> soon = Discover.upcoming(Discover.parseList(a, "movie", 20), today, 10));
        // Top 10: JustWatch's India chart; TMDB's (worldwide) popularity above stands in when it fails
        chart = null;
        net.answer(Discover.JUSTWATCH, Discover.justWatchTopBody(providers, 15), FRESH_MS, a -> {
            if (act.isDestroyed()) return;
            if (a != null) chart = Discover.parseJustWatchTop(a.json, TOP_COUNT);
            if (--pending == 0) build();
        });
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

    /** Titles already on the page (hero, New, your lists), so a later shelf doesn't show them again. */
    private final java.util.Set<String> onPage = new java.util.HashSet<>();

    /** Of {@code hits}, the ones not on the page yet, now counted as on it. */
    private List<Discover.Hit> unseen(List<Discover.Hit> hits) {
        List<Discover.Hit> out = new ArrayList<>();
        for (Discover.Hit h : hits) if (onPage.add(h.kind + h.id)) out.add(h);
        return out;
    }

    private void build() {
        loading = false;
        onPage.clear();
        List<Discover.Hit> empty = new ArrayList<>();
        heroes.clear();
        heroes.addAll(Discover.merge(heroMovies == null ? empty : heroMovies, heroTv == null ? empty : heroTv, HERO_COUNT));
        List<Discover.Hit> top = chart != null && !chart.isEmpty() ? chart
                : Discover.merge(topMovies == null ? empty : topMovies, topTv == null ? empty : topTv, TOP_COUNT);
        boolean any = !heroes.isEmpty() || !top.isEmpty();
        loaded = any && !offline;
        showNotice(any);
        shelves.removeAllViews();
        kicker.setVisibility(any ? View.VISIBLE : View.GONE);
        details.setVisibility(any ? View.VISIBLE : View.GONE);
        saveButton.setVisibility(any ? View.VISIBLE : View.GONE);
        pager.setVisibility(any ? View.VISIBLE : View.GONE);
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
        unseen(heroes);

        if (!top.isEmpty()) topTen(top);
        browse();
        List<Tmdb.Title> fresh = new ArrayList<>();
        for (Tmdb.Title t : feed.newThisWeek()) if (onPage.add(t.kind + t.id)) fresh.add(t);   // not those in the hero
        if (!fresh.isEmpty()) {
            LinearLayout row = shelf(act.getString(R.string.dc_new_on_apps));
            for (Tmdb.Title t : fresh) {
                LinearLayout card = ui.card(t.backdrop, t.name, 200, v -> openTitle(t.kind, t.id, t.name, t.backdrop));
                ui.markWhere(card, t.kind, t.id);
                row.addView(card, cardLp());
            }
        }
        savedShelves();
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
        TextView retry = ui.action(act.getString(R.string.dc_retry));
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
        ui.fillRatings(scores, null);
        paintWatch(null, false);
        paintDots();
        String pic = h.picture();
        // the old picture fades out fully, so it never sits under the new title while the new one loads
        backdrop.animate().alpha(0f).setDuration(ui.ms(160)).withEndAction(() ->
                net.picture(pic, "w1280", act.getResources().getDisplayMetrics().widthPixels, b -> {
                    if (act.isDestroyed() || hero >= heroes.size() || heroes.get(hero) != h) return;
                    backdrop.setImageBitmap(b);
                    float shown = Math.max(0.15f, 1f - scroll.getScrollY() / (float) ui.px(420));
                    backdrop.animate().alpha(shown).setDuration(ui.ms(320)).start();
                })).start();
        net.details(h.kind, h.id, d -> {
            if (d == null || act.isDestroyed() || hero >= heroes.size() || heroes.get(hero) != h) return;
            heroDetails = d;
            facts.setText(Discover.facts(d));
            story.setText(d.overview);
            installed.addAll(Watch.installed(act, Watch.packages(d.where)));
            paintWatch(Discover.apps(d.where, installed, MAX_APPS), true);
            net.ratings(d.imdbId, r -> {
                if (!act.isDestroyed() && hero < heroes.size() && heroes.get(hero) == h) ui.fillRatings(scores, r);
            });
        });
    }

    /** One dot per hero, the current one a longer bar; bigger while the poster level is focused. */
    private void paintDots() {
        boolean big = pager.isFocused();
        float size = big ? 9 : 6;
        for (int d = 0; d < dots.getChildCount(); d++) {
            View dot = dots.getChildAt(d);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dot.getLayoutParams();
            lp.width = ui.px(d == hero ? size * 3 : size);
            lp.height = ui.px(size);
            lp.rightMargin = ui.px(size);
            dot.setLayoutParams(lp);
            dot.setBackground(DiscoverUi.round(d == hero ? 0xFFFFFFFF : 0x59FFFFFF, ui.px(size / 2)));
        }
    }

    /** With nothing to show, Watch's place holds Retry. */
    private void paintRetry() {
        boolean had = watch.hasFocus();
        watch.removeAllViews();
        TextView retry = ui.action(act.getString(R.string.dc_retry));
        retry.setOnClickListener(v -> {
            if (!loading) load();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, ui.px(48));
        rlp.leftMargin = -ui.px(DiscoverUi.ACTION_PAD);   // its words line up with the title above
        watch.addView(retry, rlp);
        watch.setVisibility(View.VISIBLE);
        heroes.clear();
        if (had) retry.requestFocus();
    }

    /**
     * "Watch on" and the apps' logos as buttons. While the details load ({@code known} false) a single button with
     * a play sign stands in, so focus has somewhere to be; known and not streaming anywhere, only + and Details.
     */
    private void paintWatch(List<Discover.Provider> apps, boolean known) {
        boolean had = watch.hasFocus();
        watch.removeAllViews();
        List<View> keys = new ArrayList<>();
        if (!known) {
            FrameLayout wait = new FrameLayout(act);
            ImageView play = new ImageView(act);
            play.setImageResource(R.drawable.ic_lg_play);
            wait.addView(play, new FrameLayout.LayoutParams(ui.px(16), ui.px(16), Gravity.CENTER));
            wait.setContentDescription(act.getString(R.string.dc_watch));
            ui.focusBare(wait, 1.06f);
            wait.setOnClickListener(v -> openHero());
            watch.addView(wait, new LinearLayout.LayoutParams(ui.px(48), ui.px(48)));
            keys.add(wait);
        } else if (!apps.isEmpty()) {
            watch.addView(ui.text(act.getString(R.string.dc_watch_on_apps), 15, R.string.font_medium, DiscoverUi.INK_SOFT));
            for (Discover.Provider p : apps) {
                View b = ui.appSymbol(p, installed.contains(Discover.packageFor(p.id)), v -> watchHero(p));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(48), ui.px(48));
                lp.leftMargin = ui.px(keys.isEmpty() ? 14 : 12);
                watch.addView(b, lp);
                keys.add(b);
            }
        }
        watch.setVisibility(keys.isEmpty() ? View.GONE : View.VISIBLE);
        ((LinearLayout.LayoutParams) saveButton.getLayoutParams()).leftMargin = ui.px(keys.isEmpty() ? 0 : 22);
        View first = keys.isEmpty() ? saveButton : keys.get(0);
        if (had) first.requestFocus();
    }

    /** Opens the title in app {@code p}, on the title itself when the app's link is known; or offers to get the app. */
    private void watchHero(Discover.Provider p) {
        if (heroes.isEmpty()) return;
        Discover.Hit h = heroes.get(hero);
        if (!installed.contains(Discover.packageFor(p.id))) {
            if (!Watch.watch(act, p, installed, h.name)) {
                Toast.makeText(act, act.getString(R.string.dc_cant_open, p.name), Toast.LENGTH_SHORT).show();
            }
            return;
        }
        // the app's own link to the title, asked for only when the button is pressed
        net.links(h.name, h.kind, h.id, m -> {
            if (!act.isDestroyed() && !Watch.watch(act, p, installed, h.name, m.get(p.id))) {
                Toast.makeText(act, act.getString(R.string.dc_cant_open, p.name), Toast.LENGTH_SHORT).show();
            }
        });
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
        LinearLayout row = new LinearLayout(act);
        row.setPadding(0, ui.px(14), ui.px(56), ui.px(26));
        shelves.addView(ui.sideways(row, 56, 0), ui.bleed(56, 0));
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
            ui.markWhere(poster, h.kind, h.id);
            ui.focusArt(item, poster, 12);
            View.OnFocusChangeListener lift = item.getOnFocusChangeListener();
            item.setOnFocusChangeListener((v, has) -> {
                lift.onFocusChange(v, has);
                if (has) caption.setText(act.getString(R.string.dc_pair, h.name,
                        act.getString("movie".equals(h.kind) ? R.string.dc_movie : R.string.dc_one_series)));
                // focus left the shelf: its caption goes too, so it never names a title that isn't focused
                else v.post(() -> {
                    if (!row.hasFocus()) caption.setText(" ");
                });
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

    /**
     * Signed in to TMDB: Watch later first among the shelves, then "Because you saved …" from TMDB's
     * recommendations for the title saved last. They arrive after the others and slot in at the top.
     */
    private void savedShelves() {
        TmdbAccount a = TmdbAccount.load(act);
        if (a == null) return;
        net.saved(a, Discover.WATCHLIST, list -> {
            if (act.isDestroyed()) return;
            int at = 0;
            if (list != null && !list.isEmpty()) {
                Taste.get(act).listed(list);
                for (Discover.Hit h : list) onPage.add(h.kind + h.id);   // your list shows all of its titles
                at = shelfAt(at, act.getString(R.string.dc_watch_later), list);
                Discover.Hit last = list.get(0);
                int after = at;
                net.details(last.kind, last.id, d -> {
                    if (d == null || act.isDestroyed()) return;
                    List<Discover.Hit> more = unseen(d.more);
                    if (!more.isEmpty()) shelfAt(after, act.getString(R.string.dc_because, last.name), more);
                });
            }
            int recAt = at;
            net.recommended(a, recs -> {
                if (recs == null || recs.isEmpty() || act.isDestroyed()) return;
                streamingHere(recs.subList(0, Math.min(20, recs.size())), keep -> {
                    if (act.isDestroyed()) return;
                    keep = unseen(keep);
                    if (!keep.isEmpty()) shelfAt(recAt, act.getString(R.string.dc_recommended), keep);
                });
            });
        });
    }

    /**
     * Only the titles you can stream (or watch free) in India, in their order: each one's providers asked once a
     * day, the answer coming when all are in.
     */
    private void streamingHere(List<Discover.Hit> hits, DiscoverNet.Done<List<Discover.Hit>> done) {
        boolean[] ok = new boolean[hits.size()];
        int[] left = {hits.size()};
        for (int i = 0; i < hits.size(); i++) {
            int at = i;
            Discover.Hit h = hits.get(i);
            net.answer(Discover.providersUrl(h.kind, h.id), 24 * DiscoverNet.HOURS, ans -> {
                ok[at] = ans != null && Discover.streamsHere(ans.json);
                if (--left[0] > 0) return;
                List<Discover.Hit> keep = new ArrayList<>();
                for (int k = 0; k < hits.size(); k++) if (ok[k]) keep.add(hits.get(k));
                done.on(keep);
            });
        }
    }

    /** A shelf of cards put in at position {@code index} among the shelves; returns the index after it. */
    private int shelfAt(int index, String label, List<Discover.Hit> hits) {
        int before = shelves.getChildCount();
        LinearLayout row = shelf(label);
        for (Discover.Hit h : hits) {
            LinearLayout card = ui.card(h.picture(), h.name, 200, v -> openTitle(h.kind, h.id, h.name, h.backdrop));
            ui.markWhere(card, h.kind, h.id);
            row.addView(card, cardLp());
        }
        // shelf() added a title and a row at the end: move both to index
        View title = shelves.getChildAt(before), rowView = shelves.getChildAt(before + 1);
        shelves.removeView(title);
        shelves.removeView(rowView);
        shelves.addView(title, index);
        shelves.addView(rowView, index + 1);
        return index + 2;
    }

    /** The languages Browse offers on Discover, in this order (others stay in Browse's language menu and Ask). */
    private static final List<String> SHELF_LANGUAGES = java.util.Arrays.asList("hi", "en", "gu", "mr");

    /** Browse: Hindi, English, Gujarati and Marathi, then the genres and moods, each a tile with its own lettering. */
    private void browse() {
        LinearLayout row = shelf(act.getString(R.string.dc_browse));
        for (String code : SHELF_LANGUAGES) {
            Discover.Language l = null;
            for (Discover.Language x : Discover.LANGUAGES) if (x.code.equals(code)) l = x;
            Look look = LOOKS.get(code);
            if (l == null || look == null) continue;
            Discover.Language lang = l;
            row.addView(tile(l.own, null, look, v -> act.startActivity(BrowseActivity.language(act, lang.code))), tileLp());
        }
        for (int i = 0; i < Discover.GENRES.size(); i++) {
            int index = i;
            String name = Discover.GENRES.get(i).name;
            Look look = LOOKS.containsKey(name) ? LOOKS.get(name) : PLAIN;
            row.addView(tile(name, null, look, v -> act.startActivity(BrowseActivity.genre(act, index))), tileLp());
        }
    }

    /** How a Browse tile writes its name: typeface, size, case and spacing, colours, tilt, and its glass's tint. */
    private static final class Look {
        final String family;
        final int style, from, to, tint;
        final float size, spacing, tilt;
        final boolean caps, tape, glow;

        Look(String family, int style, float size, boolean caps, float spacing, int from, int to, float tilt, int tint,
             boolean tape, boolean glow) {
            this.family = family;
            this.style = style;
            this.size = size;
            this.caps = caps;
            this.spacing = spacing;
            this.from = from;
            this.to = to;
            this.tilt = tilt;
            this.tint = tint;
            this.tape = tape;
            this.glow = glow;
        }
    }

    private static final int B = android.graphics.Typeface.BOLD, I = android.graphics.Typeface.ITALIC,
            BI = android.graphics.Typeface.BOLD_ITALIC, N = android.graphics.Typeface.NORMAL;
    private static final Look PLAIN = new Look("sans-serif", B, 18, false, 0, 0xFFFFFFFF, 0xFFFFFFFF, 0, 0x33FFFFFF, false, false);
    /** Our own lettering per language and genre: system typefaces only, so nothing extra ships in the APK. */
    private static final java.util.Map<String, Look> LOOKS = new java.util.HashMap<>();
    static {
        LOOKS.put("hi", new Look("sans-serif", B, 24, false, 0, 0xFFFFC46B, 0xFFFF7A3D, 0, 0x47FF8A3D, false, false));
        LOOKS.put("en", new Look("serif", BI, 24, false, 0, 0xFFEAF2FF, 0xFF8FB8FF, 0, 0x473D7BFF, false, false));
        LOOKS.put("gu", new Look("sans-serif", B, 24, false, 0, 0xFFFF9BDE, 0xFFC56BFF, 0, 0x47C56BFF, false, false));
        LOOKS.put("mr", new Look("sans-serif", B, 24, false, 0, 0xFFFFD86B, 0xFFFF5E5E, 0, 0x47FF5E5E, false, false));
        LOOKS.put("Action", new Look("sans-serif-condensed", BI, 23, true, 0.02f, 0xFFFFC94D, 0xFFFF4A2B, -4, 0x47FF4A2B, false, false));
        LOOKS.put("Comedy", new Look("casual", B, 22, false, 0, 0xFFFFE65C, 0xFFFFE65C, -6, 0x47FFD23F, false, false));
        LOOKS.put("Drama", new Look("serif", BI, 24, false, 0, 0xFFF6E7CF, 0xFFE0B98A, 0, 0x477A4DFF, false, false));
        LOOKS.put("Thriller", new Look("monospace", B, 15, true, 0.22f, 0xFFFFFFFF, 0xFF7FE7FF, 0, 0x471FB5C9, false, false));
        LOOKS.put("Crime", new Look("sans-serif-condensed", B, 17, true, 0.1f, 0xFF111111, 0xFF111111, -4, 0x33FFD60A, true, false));
        LOOKS.put("Sci-Fi", new Look("monospace", N, 16, true, 0.3f, 0xFF7CF9FF, 0xFF9B7BFF, 0, 0x475A6BFF, false, false));
        LOOKS.put("Romance", new Look("cursive", B, 27, false, 0, 0xFFFFA6C9, 0xFFFF5C8A, -3, 0x47FF5C8A, false, false));
        LOOKS.put("Horror", new Look("serif", B, 19, true, 0.16f, 0xFFFF4040, 0xFFFF4040, 0, 0x47B00000, false, true));
        LOOKS.put("Family", new Look("casual", B, 21, false, 0, 0xFF8CF5A0, 0xFF5CC8FF, 3, 0x474CD38A, false, false));
        LOOKS.put("Animation", new Look("casual", B, 20, false, 0, 0xFFFFD45C, 0xFFFF6BD6, 4, 0x47FF6BD6, false, false));
        LOOKS.put("Documentary", new Look("serif", N, 14, true, 0.2f, 0xFFEDEDED, 0xFFBDBDBD, 0, 0x40808080, false, false));
        LOOKS.put("Stoner", new Look("casual", B, 24, false, 0.02f, 0xFFC8FF5C, 0xFF2ECC71, -5, 0x472ECC71, false, true));
    }

    /**
     * A Browse tile: just the name in its own lettering (an optional small line under it), no box around it.
     */
    private View tile(String big, String small, Look l, View.OnClickListener click) {
        FrameLayout t = new FrameLayout(act);
        t.setClipChildren(false);
        t.setPadding(ui.px(6), 0, ui.px(6), 0);
        LinearLayout words = new LinearLayout(act);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setGravity(Gravity.CENTER);
        words.setClipChildren(false);
        TextView name = new TextView(act);
        name.setText("Stoner".equals(big) ? "stoner" : l.caps ? big.toUpperCase(java.util.Locale.getDefault()) : big);
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.px(l.size));
        name.setTypeface(android.graphics.Typeface.create(l.family, l.style));
        name.setLetterSpacing(l.spacing);
        name.setIncludeFontPadding(false);
        name.setSingleLine(true);
        name.setTextColor(l.from);
        name.setRotation(l.tilt);
        if (l.glow) name.setShadowLayer(ui.px(10), 0, 0, (l.to & 0x00FFFFFF) | 0xAA000000);
        if (l.tape) {
            // a strip of tape across the tile
            name.setBackground(DiscoverUi.round(0xFFFFD60A, ui.px(3)));
            name.setPadding(ui.px(10), ui.px(4), ui.px(10), ui.px(4));
        } else if (l.from != l.to) {
            name.addOnLayoutChangeListener((v, a, b, c, d, e, f, g, h) -> {
                name.getPaint().setShader(new android.graphics.LinearGradient(0, 0, v.getWidth(), v.getHeight(),
                        l.from, l.to, android.graphics.Shader.TileMode.CLAMP));
                name.invalidate();
            });
        }
        words.addView(name, new LinearLayout.LayoutParams(-2, -2));
        if (small != null) {
            TextView sub = ui.text(small.toUpperCase(java.util.Locale.ROOT), 9, R.string.font_medium, 0xA6FFFFFF);
            sub.setLetterSpacing(0.18f);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
            slp.topMargin = ui.px(6);
            words.addView(sub, slp);
        }
        t.addView(words, new FrameLayout.LayoutParams(-1, -1));
        t.setContentDescription(small != null ? big + " " + small : big);
        // just the lettering, no tile around it: a little dim at rest; focused, it grows and turns fully bright
        t.setFocusable(true);
        t.setClickable(true);
        t.setAlpha(0.75f);
        t.setOnFocusChangeListener((v, has) -> {
            v.animate().alpha(has ? 1f : 0.75f).scaleX(has ? 1.2f : 1f).scaleY(has ? 1.2f : 1f)
                    .setDuration(ui.ms(180)).start();
        });
        t.setOnClickListener(click);
        return t;
    }

    private LinearLayout.LayoutParams tileLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, ui.px(60));
        lp.rightMargin = ui.px(30);
        return lp;
    }

}

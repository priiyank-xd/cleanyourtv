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
import android.view.ViewGroup;
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
    /** Apps shown after "Watch on". */
    private static final int MAX_APPS = 3;
    /** The first of them (an installed one when there is one), for an episode's OK; null when none streams it. */
    private Discover.Provider firstApp;
    private Set<String> installed;
    private DiscoverUi.Sheet sheet;
    /** Each provider's own link to this title (JustWatch), when known. */
    private java.util.Map<Integer, String> links = new java.util.HashMap<>();
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

        scroll = ui.page();
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
        // Back up at Watch, the trailer or Also on, the page shows its top again: name, facts and story
        scroll.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            if (now != null && isInside(now, column) && scroll.getScrollY() > 0) scroll.smoothScrollTo(0, 0);
        });

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
            if (rateBar != null) {
                if (e.getAction() == KeyEvent.ACTION_UP) closeRate();
                return true;
            }
            if (ui.open != null) {
                if (e.getAction() == KeyEvent.ACTION_UP && ui.open != null) ui.open.close();
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
        Taste.get(this).opened(kind, id, d.genreIds, d.language);
        showPicture(d.backdrop);
        installed = installedNow();
        List<Discover.Provider> apps = Discover.apps(d.where, installed, MAX_APPS);
        firstApp = apps.isEmpty() ? null : apps.get(0);

        column.removeAllViews();
        column.addView(title(name));
        column.addView(line(Discover.facts(d), 14, DiscoverUi.INK_SOFT, 14));
        LinearLayout scores = ui.ratingsRow();
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, ui.px(22));
        slp.topMargin = ui.px(12);
        column.addView(scores, slp);
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
        TextView awards = line("", 12, DiscoverUi.INK_DIM, 4);
        awards.setVisibility(View.GONE);
        column.addView(awards);
        net.ratings(d.imdbId, r -> {
            if (isDestroyed() || details != d) return;
            ui.fillRatings(scores, r);
            if (!r.awards.isEmpty()) {
                awards.setText(r.awards);
                awards.setVisibility(View.VISIBLE);
            }
        });
        boolean anyInstalled = false;
        for (Discover.Provider p : apps) anyInstalled |= installed.contains(Discover.packageFor(p.id));
        if (anyInstalled) net.links(name, kind, id, m -> links = m);

        LinearLayout buttons = buttonsRow();
        if (!apps.isEmpty()) {
            // "Watch on" and the apps' symbols, as on Discover's hero
            buttons.addView(label(getString(R.string.dc_watch_on_apps)));
            watchButton = null;
            for (Discover.Provider p : apps) {
                View b = ui.appSymbol(p, installed.contains(Discover.packageFor(p.id)), v -> watch(p));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(48), ui.px(48));
                lp.leftMargin = ui.px(watchButton == null ? 14 : 12);
                buttons.addView(b, lp);
                if (watchButton == null) watchButton = b;
            }
            LinearLayout.LayoutParams plp = roundLp();
            plp.leftMargin = ui.px(22);
            buttons.addView(round(R.drawable.ic_dc_plus, getString(R.string.dc_save), v -> save()), plp);
            buttons.addView(rateButton(), roundLp());
            buttons.addView(round(R.drawable.ic_dc_more, getString(R.string.dc_more), v -> showMore()), roundLp());
        } else {
            watchButton = button(R.drawable.ic_lg_search, null, getString(R.string.dc_google), v -> google());
            buttons.addView(watchButton, buttonLp());
            buttons.addView(round(R.drawable.ic_dc_plus, getString(R.string.dc_save), v -> save()), roundLp());
            buttons.addView(rateButton(), roundLp());
        }
        column.addView(buttons);

        List<Discover.Provider> also = Discover.others(d.where, apps, 3);
        if (d.where.isEmpty()) {
            column.addView(line(getString(R.string.dc_not_streaming), 13, DiscoverUi.INK_DIM, 18));
        } else if (!also.isEmpty() || apps.isEmpty()) {
            LinearLayout row = ui.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setClipChildren(false);
            if (!also.isEmpty()) {
                // the same words, symbols and focus as "Watch on" above: OK opens that app (to rent or buy there)
                row.addView(label(getString(R.string.dc_also)));
                boolean firstOne = true;
                for (Discover.Provider p : also) {
                    View b = ui.appSymbol(p, installed.contains(Discover.packageFor(p.id)), v -> watch(p));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(48), ui.px(48));
                    lp.leftMargin = ui.px(firstOne ? 14 : 12);
                    row.addView(b, lp);
                    firstOne = false;
                }
            }
            // the same as "Watch trailer": plain words, Home's pill around them when focused
            TextView all = ui.action(getString(R.string.dc_all_options) + "  ›");
            all.setOnClickListener(v -> showWhere());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, ui.px(44));
            lp.leftMargin = ui.px(also.isEmpty() ? -DiscoverUi.ACTION_PAD : 6);   // alone, its words line up with "Watch on"
            row.addView(all, lp);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
            rlp.topMargin = ui.px(8);
            column.addView(row, rlp);
        }
        // "Watch trailer" comes after Also on, when there is one
        if (!d.trailer.isEmpty()) column.addView(trailerRow());
        showSections(d);
        watchButton.requestFocus();
    }

    // ---- below the fold ----

    private void showSections(Discover.Details d) {
        sections.removeAllViews();
        if (!d.seasonList.isEmpty()) {
            heading(getString(R.string.dc_episodes));
            if (d.seasonList.size() > 1) {
                // seasons scroll sideways, so a long-running series keeps every season reachable
                LinearLayout seasons = new LinearLayout(this);
                seasons.setGravity(Gravity.CENTER_VERTICAL);
                seasons.setPadding(0, ui.px(12), ui.px(56), 0);
                for (int i = 0; i < d.seasonList.size(); i++) {
                    int number = d.seasonList.get(i)[0];
                    TextView s = ui.capsule(d.seasonNames.get(i));
                    s.setOnClickListener(v -> loadSeason(number));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, ui.px(36));
                    if (i > 0) lp.leftMargin = ui.px(10);
                    seasons.addView(s, lp);
                }
                sections.addView(ui.sideways(seasons, 56, 0), ui.bleed(56, 0));
            }
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
        if (!d.more.isEmpty()) moreLikeThis(d);
    }

    /**
     * More like this: TMDB's recommendations, keeping only ones you can stream (or watch free) in India, so no card
     * leads to "Not streaming". Each title's providers are asked once a day; the shelf appears when all have answered.
     */
    private void moreLikeThis(Discover.Details d) {
        List<Discover.Hit> recs = new ArrayList<>(d.more);
        boolean[] streams = new boolean[recs.size()];
        int[] left = {recs.size()};
        for (int i = 0; i < recs.size(); i++) {
            int at = i;
            Discover.Hit h = recs.get(i);
            net.answer(Discover.providersUrl(h.kind, h.id), 24 * DiscoverNet.HOURS, a -> {
                if (isDestroyed() || details != d) return;
                streams[at] = a != null && Discover.streamsHere(a.json);
                if (--left[0] > 0) return;
                List<Discover.Hit> keep = new ArrayList<>();
                for (int k = 0; k < recs.size(); k++) if (streams[k]) keep.add(recs.get(k));
                if (keep.isEmpty()) return;
                heading(getString(R.string.dc_more_like));
                LinearLayout r = row();
                for (Discover.Hit m : keep) {
                    LinearLayout card = ui.card(m.picture(), m.name, 200,
                            v -> startActivity(intent(this, m.kind, m.id, m.name, m.backdrop)));
                    ui.markWhere(card, m.kind, m.id);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(200), -2);
                    lp.rightMargin = ui.px(16);
                    r.addView(card, lp);
                }
            });
        }
    }

    private void heading(String label) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = ui.px(20);
        sections.addView(ui.shelfTitle(label), lp);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, ui.px(14), ui.px(56), ui.px(10));
        sections.addView(ui.sideways(r, 56, 0), ui.bleed(56, 0));
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
                LinearLayout card = ui.card(e.still, e.number + ". " + e.name, 196, v -> watchFirst());
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

    private void watchFirst() {
        if (firstApp == null) google();
        else watch(firstApp);
    }

    private void watch(Discover.Provider p) {
        if (!Watch.watch(this, p, installed, name, links.get(p.id))) {
            Toast.makeText(this, getString(R.string.dc_cant_open, p.name), Toast.LENGTH_SHORT).show();
        }
    }

    private void save() {
        SaveSheet.open(this, ui, root, scroll, kind, id, name);
    }

    // ---- trailer ----

    /** A line under Watch on: just the words "Watch trailer", a button (Home's pill around it when focused). */
    private View trailerRow() {
        TextView t = ui.action(getString(R.string.dc_watch_trailer));
        t.setContentDescription(getString(R.string.dc_trailer));
        t.setOnClickListener(v -> trailer());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, ui.px(44));
        lp.topMargin = ui.px(10);
        lp.leftMargin = -ui.px(16);   // the words line up with "Watch on"; the pill's padding sits outside
        t.setLayoutParams(lp);
        return t;
    }

    // ---- thumbs ----

    private ImageView thumbIcon;
    private View rateView;
    /** The rating popover over the thumb button while it is open. */
    private FrameLayout rateBar;
    private int thumb = Discover.THUMB_NONE;

    /** The thumbs button: an outline thumb until you rate, then your thumb. Reads your rating from TMDB. */
    private View rateButton() {
        View b = round(R.drawable.ic_dc_thumb, getString(R.string.dc_rate), v -> rate());
        rateView = b;
        thumbIcon = (ImageView) ((FrameLayout) b).getChildAt(0);
        thumb = Discover.THUMB_NONE;
        TmdbAccount a = TmdbAccount.load(this);
        if (a != null) {
            Discover.Details asked = details;
            net.call("GET", Discover.statesUrl(kind, id, a.session), null, net.appToken(), j -> {
                if (j == null || isDestroyed() || details != asked) return;
                thumb = Discover.thumbOf(Discover.parseRated(j));
                paintThumb();
            });
        }
        return b;
    }

    private static int thumbIconOf(int t) {
        return t == Discover.THUMB_DOWN ? R.drawable.ic_dc_thumb_down : t == Discover.THUMB_UP ? R.drawable.ic_dc_thumb_up
                : t == Discover.THUMB_LOVE ? R.drawable.ic_dc_thumb_love : R.drawable.ic_dc_thumb;
    }

    private void paintThumb() {
        if (thumbIcon != null) thumbIcon.setImageResource(thumbIconOf(thumb));
    }

    /**
     * Rate, as on Netflix: a small pill pops up over the thumb button with Not for me, I like this and Love this!,
     * the focused one's name above it. Left/Right choose, OK rates (OK on your current rating takes it back), Back
     * closes. Saved to TMDB; signed out, a sheet offers Connect.
     */
    private void rate() {
        TmdbAccount a = TmdbAccount.load(this);
        if (a == null) {
            sheet = ui.new Sheet(root, scroll, getString(R.string.dc_rate), name);
            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.ic_lg_profile);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            sheet.row(icon, getString(R.string.dc_connect_to_rate), null, v -> {
                closeSheet();
                startActivity(new Intent(this, ConnectActivity.class));
            }).requestFocus();
            return;
        }
        if (rateBar != null || rateView == null) return;
        int[] thumbs = {Discover.THUMB_DOWN, Discover.THUMB_UP, Discover.THUMB_LOVE};
        int[] labels = {R.string.dc_not_for_me, R.string.dc_like_this, R.string.dc_love_this};
        // one glass card: the three thumbs, the focused one's name under them
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClipChildren(false);
        // solid: it sits right on top of the other buttons, which must not show through
        android.graphics.drawable.GradientDrawable card = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF2A2C33, 0xFF1C1D22});
        card.setCornerRadius(ui.px(22));
        card.setStroke(Math.max(1, ui.px(1)), 0x33FFFFFF);
        box.setBackground(card);
        box.setElevation(ui.px(12));
        box.setPadding(ui.px(10), ui.px(8), ui.px(10), ui.px(8));
        TextView label = ui.text(" ", 12, R.string.font_medium, DiscoverUi.INK);
        LinearLayout pill = ui.row();
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setClipChildren(false);
        View focus = null;
        for (int i = 0; i < thumbs.length; i++) {
            int t = thumbs[i];
            String name = getString(labels[i]);
            FrameLayout b = new FrameLayout(this);
            ImageView icon = new ImageView(this);
            icon.setImageResource(thumbIconOf(t));
            b.addView(icon, new FrameLayout.LayoutParams(ui.px(24), ui.px(24), Gravity.CENTER));
            if (t == thumb) {
                // your current rating: a small dot under it
                View dot = new View(this);
                dot.setBackground(DiscoverUi.round(0xFFFFFFFF, ui.px(2)));
                FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ui.px(4), ui.px(4), Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
                dlp.bottomMargin = ui.px(4);
                b.addView(dot, dlp);
            }
            b.setContentDescription(name);
            b.setFocusable(true);
            b.setClickable(true);
            b.setAlpha(0.5f);
            // no circle: the focused thumb just turns fully bright and grows a little
            b.setOnFocusChangeListener((v, has) -> {
                v.animate().alpha(has ? 1f : 0.5f).scaleX(has ? 1.25f : 1f).scaleY(has ? 1.25f : 1f).setDuration(ui.ms(150)).start();
                if (has) label.setText(t == thumb ? getString(R.string.dc_rate_clear) : name);
            });
            b.setOnClickListener(v -> {
                closeRate();
                setThumb(a, t == thumb ? Discover.THUMB_NONE : t);
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ui.px(46), ui.px(46));
            if (i > 0) blp.leftMargin = ui.px(4);
            pill.addView(b, blp);
            if (focus == null || t == thumb) focus = b;
        }
        box.addView(pill, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = ui.px(4);
        box.addView(label, llp);

        // in place of the thumb button: centred on it, as Netflix's opens
        rateBar = new FrameLayout(this);
        rateBar.setClickable(true);
        rateBar.setClipChildren(false);
        int[] at = new int[2], origin = new int[2];
        rateView.getLocationInWindow(at);
        root.getLocationInWindow(origin);
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = Math.max(box.getMeasuredWidth(), ui.px(176));   // room for the longest name, so it stays centred
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, -2);
        lp.leftMargin = Math.max(ui.px(16), at[0] - origin[0] + rateView.getWidth() / 2 - w / 2);
        lp.topMargin = Math.max(ui.px(16), at[1] - origin[1] + rateView.getHeight() / 2 - box.getMeasuredHeight() / 2);
        rateBar.addView(box, lp);
        root.addView(rateBar, new FrameLayout.LayoutParams(-1, -1));
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        box.setAlpha(0f);
        box.setScaleX(0.9f);
        box.setScaleY(0.9f);
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ui.ms(160)).start();
        focus.requestFocus();
    }

    private void closeRate() {
        if (rateBar == null) return;
        root.removeView(rateBar);
        rateBar = null;
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (rateView != null) rateView.requestFocus();
    }

    private void setThumb(TmdbAccount a, int t) {
        closeSheet();
        int before = thumb;
        if (t == before) return;
        thumb = t;
        paintThumb();
        Discover.Details d = details;
        boolean clear = t == Discover.THUMB_NONE;
        net.call(clear ? "DELETE" : "POST", Discover.ratingUrl(kind, id, a.session),
                clear ? null : Discover.ratingBody(Discover.thumbScore(t)), net.appToken(), j -> {
                    if (isDestroyed()) return;
                    if (j == null) {
                        thumb = before;
                        paintThumb();
                        Toast.makeText(this, R.string.dc_save_failed, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (d != null) Taste.get(this).rated(kind, id, d.genreIds, d.language, t);
                });
    }

    private void trailer() {
        if (details == null || details.trailer.isEmpty()) return;
        if (!Watch.trailer(this, details.trailer)) {
            Toast.makeText(this, getString(R.string.dc_cant_open, "YouTube"), Toast.LENGTH_SHORT).show();
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
        int[] labels = {R.string.dc_stream, R.string.dc_free, R.string.dc_rent, R.string.dc_buy};
        List<List<Discover.Provider>> groups = Discover.optionGroups(details.where);
        View first = null;
        for (int g = 0; g < groups.size(); g++) {
            if (groups.get(g).isEmpty()) continue;
            sheet.group(getString(labels[g]));
            for (Discover.Provider p : groups.get(g)) {
                String pkg = Discover.packageFor(p.id);
                String value = getString(pkg == null ? R.string.dc_search_it : installed.contains(pkg) ? R.string.dc_open : R.string.dc_get_app);
                View row = sheet.row(ui.glyph(p), p.name, value, v -> {   // Home's bare app symbol, as on the page
                    if (!Watch.watch(this, p, installed, name, links.get(p.id))) {
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

    /** "Watch on" and "Also on": one style and width, so the app symbols after them line up. */
    private TextView label(String s) {
        TextView t = ui.text(s, 15, R.string.font_medium, DiscoverUi.INK_SOFT);
        t.setMinWidth(ui.px(66));
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
        LinearLayout row = ui.row();
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
        b.setPadding(ui.px(16), 0, ui.px(20), 0);
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
        ui.focusBare(b, 1.06f);   // plain at rest; Home's pill around it when focused
        b.setOnClickListener(click);
        return b;
    }

    private View round(int icon, String description, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(this);
        ImageView i = new ImageView(this);
        i.setImageResource(icon);
        b.addView(i, new FrameLayout.LayoutParams(ui.px(26), ui.px(26), Gravity.CENTER));
        b.setContentDescription(description);
        ui.focusBare(b, 1.06f);   // plain at rest; Home's glass circle around it when focused
        b.setOnClickListener(click);
        return b;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, ui.px(48));
        lp.leftMargin = -ui.px(16);   // the icon lines up with the text above; the pill's padding sits outside
        return lp;
    }

    private LinearLayout.LayoutParams roundLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(48), ui.px(48));
        lp.leftMargin = ui.px(14);
        return lp;
    }
}

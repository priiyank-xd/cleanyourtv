package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Discover's Search tab (fork only). The field takes focus with the TV's own keyboard (Gboard on Google TV) up, so
 * typing or the keyboard's mic starts at once. Results follow the typing: titles, and well-known people (first when
 * the query names one); the first row
 * sits above the keyboard, and Search on the keyboard closes it and moves to the results. An empty field shows
 * recent searches and what's popular on your apps.
 */
final class SearchPane implements DiscoverActivity.Pane {
    private static final String PREFS = "discover", RECENT_KEY = "recent";
    private static final long TYPE_PAUSE_MS = 350;
    private static final float CARD_W = 200, GAP = 16;
    private static final int COLUMNS = 4, MAX_PEOPLE = 6;

    private final DiscoverActivity act;
    private final DiscoverUi ui;
    private final DiscoverNet net;
    private final TmdbFeed feed;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FrameLayout page;
    private final EditText field;
    private final FrameLayout fieldBox;
    private final ScrollView scroll;
    private final LinearLayout content;
    /** The first focusable result, where Down from the field and Search on the keyboard go. */
    private View firstResult;
    /** Answers to older queries are dropped. */
    private int seq;
    private final Runnable typed;

    SearchPane(DiscoverActivity act, DiscoverUi ui, TmdbFeed feed) {
        this.act = act;
        this.ui = ui;
        this.feed = feed;
        net = DiscoverNet.get(act);
        prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        page = new FrameLayout(act);
        page.setClipChildren(false);

        fieldBox = new FrameLayout(act);
        fieldBox.setBackground(ui.field(26, false));
        ImageView icon = new ImageView(act);
        icon.setImageResource(R.drawable.ic_lg_search);
        icon.setAlpha(0.75f);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(ui.px(20), ui.px(20), Gravity.CENTER_VERTICAL);
        ilp.leftMargin = ui.px(22);
        fieldBox.addView(icon, ilp);
        field = new EditText(act);
        field.setId(View.generateViewId());
        field.setBackground(null);
        field.setTextColor(DiscoverUi.INK);
        field.setHintTextColor(0x66FFFFFF);
        field.setHint(R.string.dc_search_hint);
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.px(20));
        field.setTypeface(UiFont.get(act, R.string.font_regular));
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setPadding(0, 0, 0, 0);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(-1, -1);
        flp.leftMargin = ui.px(54);
        flp.rightMargin = ui.px(22);
        fieldBox.addView(field, flp);
        field.setOnFocusChangeListener((v, has) -> fieldBox.setBackground(ui.field(26, has)));
        ui.place(page, fieldBox, 180, 82, 600, 52);

        scroll = ui.page();
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        // results scroll under a clean edge below the field, never drawn over it: the page doesn't clip its
        // children, so the scroller clips its own drawing to its box (the content's padding leaves room for a
        // focused card to grow)
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                v.setClipBounds(new android.graphics.Rect(0, 0, r - l, b - t)));
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(ui.px(24));
        content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(ui.px(56), ui.px(14), ui.px(56), ui.px(48));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -1);
        slp.topMargin = ui.px(150);
        page.addView(scroll, slp);

        typed = () -> run(field.getText().toString());
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override public void afterTextChanged(Editable s) {
                handler.removeCallbacks(typed);
                handler.postDelayed(typed, TYPE_PAUSE_MS);
            }
        });
        field.setOnEditorActionListener((v, action, e) -> {
            boolean go = action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE
                    || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN);
            if (!go) return false;
            handler.removeCallbacks(typed);
            run(field.getText().toString());
            remember(field.getText().toString());
            keyboard(false);
            if (firstResult != null) firstResult.requestFocus();
            return true;
        });
        field.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.getAction() == KeyEvent.ACTION_DOWN && firstResult != null) {
                keyboard(false);
                firstResult.requestFocus();
                return true;
            }
            return false;
        });
        run("");
    }

    @Override public View view() {
        return page;
    }

    @Override public void enter() {
        field.requestFocus();
        keyboard(true);
    }

    @Override public void shown() {
    }

    @Override public void leave() {
        keyboard(false);
        scroll.smoothScrollTo(0, 0);
    }

    @Override public void destroy() {
        handler.removeCallbacksAndMessages(null);
    }

    // ---- searching ----

    private void run(String query) {
        String q = query.trim();
        int mine = ++seq;
        if (q.isEmpty()) {
            showStart();
            return;
        }
        if (!net.enabled()) {
            showMessage(act.getString(R.string.dc_no_key));
            return;
        }
        net.search(q, answer -> {
            if (mine != seq || act.isDestroyed()) return;
            if (answer == null) {
                showMessage(act.getString(R.string.dc_offline));
                gap(26);
                popular();   // what was saved last time, so the page is never empty
            } else {
                showResults(q, answer);
            }
        });
    }

    private void remember(String query) {
        List<String> recent = Discover.addRecent(Discover.splitRecent(prefs.getString(RECENT_KEY, null)), query, Discover.MAX_RECENT);
        prefs.edit().putString(RECENT_KEY, Discover.joinRecent(recent)).apply();
    }

    private void keyboard(boolean show) {
        InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (show) field.post(() -> imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT));
        else imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    // ---- what the page shows ----

    /** An empty field: recent searches, then what's popular on your apps. */
    private void showStart() {
        clear();
        List<String> recent = Discover.splitRecent(prefs.getString(RECENT_KEY, null));
        if (!recent.isEmpty()) {
            label(act.getString(R.string.dc_recent), null);
            LinearLayout row = shelfRow();
            for (String r : recent) {
                TextView chip = ui.capsule(r);
                chip.setOnClickListener(v -> {
                    field.setText(r);
                    field.setSelection(r.length());
                    handler.removeCallbacks(typed);
                    run(r);
                });
                chip.setNextFocusUpId(field.getId());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, ui.px(40));
                lp.rightMargin = ui.px(12);
                row.addView(chip, lp);
                if (firstResult == null) firstResult = chip;
            }
            // last in the row: clear them all (plain words; Home's pill around them when focused)
            TextView clearAll = ui.text(act.getString(R.string.dc_clear_recent), 14, R.string.font_medium, DiscoverUi.INK);
            clearAll.setGravity(Gravity.CENTER);
            clearAll.setPadding(ui.px(16), 0, ui.px(16), 0);
            ui.focusBare(clearAll, 1.06f);
            clearAll.setNextFocusUpId(field.getId());
            clearAll.setOnClickListener(v -> {
                prefs.edit().remove(RECENT_KEY).apply();
                showStart();
                // focus moves down to the first popular title, not the field (that would pop the keyboard up)
                if (firstResult != null) firstResult.requestFocus();
                else field.requestFocus();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, ui.px(40));
            clp.leftMargin = ui.px(4);
            row.addView(clearAll, clp);
            gap(26);
        }
        popular();
    }

    /**
     * Results: when the query names a well-known person ("shah rukh"), them first and their best known titles at the
     * head of the titles; otherwise titles first and only well-known people after them, so "dune" shows the films,
     * not people who happen to be called Dune.
     */
    private void showResults(String q, org.json.JSONObject answer) {
        clear();
        askOffer(q);
        List<Discover.Hit> found = Discover.parseSearch(answer, Discover.MAX_RESULTS);
        List<Discover.Hit> people = Discover.shownPeople(Discover.parsePeople(answer, 20), !found.isEmpty());
        if (people.size() > MAX_PEOPLE) people = new ArrayList<>(people.subList(0, MAX_PEOPLE));
        Discover.Hit lead = Discover.leadingPerson(people, q);
        List<Discover.Hit> titles = Discover.rankTitles(
                lead == null ? new ArrayList<>() : Discover.knownFor(answer, lead.id), found, Discover.MAX_RESULTS);
        if (people.isEmpty() && titles.isEmpty()) {
            label(act.getString(R.string.dc_nothing, q), null);
            gap(14);
            popular();
            return;
        }
        if (lead != null) peopleRow(people);
        if (!titles.isEmpty()) {
            label(act.getString(R.string.dc_titles), act.getResources().getQuantityString(R.plurals.dc_count, titles.size(), titles.size()));
            List<Item> items = new ArrayList<>();
            for (Discover.Hit h : titles) items.add(item(h));
            grid(items);
        }
        if (lead == null && !people.isEmpty()) {
            gap(4);
            peopleRow(people);
        }
    }

    private void peopleRow(List<Discover.Hit> people) {
        label(act.getString(R.string.dc_people), null);
        boolean first = firstResult == null;
        LinearLayout row = shelfRow();
        for (Discover.Hit p : people) {
            View item = person(p);
            if (first) item.setNextFocusUpId(field.getId());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = ui.px(30);
            row.addView(item, lp);
            if (firstResult == null) firstResult = item;
        }
        gap(24);
    }

    // ---- Ask in plain words ----

    /** A sentence, with Gemini set up: a capsule offering to ask it in plain words, first in the results. */
    private void askOffer(String q) {
        if (!Discover.isSentence(q) || !net.canAsk()) return;
        LinearLayout pill = new LinearLayout(act);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(ui.px(16), 0, ui.px(20), 0);
        ImageView spark = new ImageView(act);
        spark.setImageResource(R.drawable.ic_dc_spark);
        pill.addView(spark, new LinearLayout.LayoutParams(ui.px(18), ui.px(18)));
        TextView t = ui.text(act.getString(R.string.dc_ask, q), 14, R.string.font_medium, DiscoverUi.INK);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = ui.px(10);
        pill.addView(t, tlp);
        t.setMaxWidth(ui.px(640));
        ui.focusGlass(pill, 22);
        pill.setOnClickListener(v -> runAsk(q));
        pill.setNextFocusUpId(field.getId());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, ui.px(44));
        lp.bottomMargin = ui.px(24);
        content.addView(pill, lp);
        firstResult = pill;
    }

    private void runAsk(String q) {
        int mine = ++seq;
        remember(q);
        clear();
        label(act.getString(R.string.dc_asking), null);
        net.ask(q, a -> {
            if (mine != seq || act.isDestroyed()) return;
            if (a == null) {
                showMessage(act.getString(R.string.dc_ask_failed));
                gap(26);
                popular();
                return;
            }
            showAsk(q, a);
        });
    }

    /** What Gemini understood, as capsules (OK on one drops it), then the titles TMDB finds for it. */
    private void showAsk(String q, Discover.Ask a) {
        int mine = ++seq;
        clear();
        List<String[]> chips = Discover.chips(a);
        if (chips.isEmpty()) {
            showMessage(act.getString(R.string.dc_ask_nothing));
            gap(26);
            popular();
            return;
        }
        label(act.getString(R.string.dc_understood), null);
        LinearLayout row = shelfRow();
        for (String[] c : chips) {
            TextView chip = ui.capsule(c[1] + "  \u00d7");
            chip.setOnClickListener(v -> showAsk(q, Discover.without(a, c[0])));
            chip.setNextFocusUpId(field.getId());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, ui.px(40));
            lp.rightMargin = ui.px(10);
            row.addView(chip, lp);
            if (firstResult == null) firstResult = chip;
        }
        gap(22);
        TextView status = ui.text(act.getString(R.string.dc_loading), 14, R.string.font_regular, DiscoverUi.INK_DIM);
        content.addView(status);
        gap(14);
        DiscoverNet.Done<List<Discover.Hit>> show = hits -> {
            if (mine != seq || act.isDestroyed()) return;
            if (hits == null) {
                status.setText(R.string.dc_offline);
                return;
            }
            status.setText(hits.isEmpty() ? act.getString(R.string.dc_ask_none) : act.getResources().getQuantityString(R.plurals.dc_count, hits.size(), hits.size()));
            List<Item> items = new ArrayList<>();
            for (Discover.Hit h : hits) items.add(item(h));
            grid(items);
        };
        if (!a.similarTo.isEmpty()) {
            alike(a, show);
            return;
        }
        Set<Integer> installed = Tmdb.providerIds(Watch.installed(act, Tmdb.PROVIDERS.keySet()));
        Set<Integer> providers = Discover.askProviders(a, installed.isEmpty() ? null : installed);
        java.time.LocalDate today = java.time.LocalDate.now();
        String movies = Discover.askUrl(a, "movie", providers, today), series = Discover.askUrl(a, "tv", providers, today);
        List<List<Discover.Hit>> got = new ArrayList<>();
        int[] left = {(movies == null ? 0 : 1) + (series == null ? 0 : 1)};
        if (left[0] == 0) {
            show.on(new ArrayList<>());
            return;
        }
        for (String[] q2 : new String[][]{{movies, "movie"}, {series, "tv"}}) {
            if (q2[0] == null) continue;
            String kind = q2[1];
            net.answer(q2[0], DiscoverNet.HOURS, ans -> {
                if (ans != null) got.add(Discover.parseList(ans.json, kind, 20));
                if (--left[0] > 0) return;
                if (got.isEmpty()) {
                    show.on(null);
                    return;
                }
                List<Discover.Hit> all = got.size() == 2 ? Discover.merge(got.get(0), got.get(1), 20) : got.get(0);
                show.on(all);
            });
        }
    }

    /** "Something like X": TMDB's recommendations for the first title that matches X, of the kind asked. */
    private void alike(Discover.Ask a, DiscoverNet.Done<List<Discover.Hit>> show) {
        net.search(a.similarTo, answer -> {
            List<Discover.Hit> found = answer == null ? null : Discover.parseSearch(answer, 1);
            if (found == null || found.isEmpty()) {
                show.on(answer == null ? null : new ArrayList<>());
                return;
            }
            Discover.Hit h = found.get(0);
            net.details(h.kind, h.id, d -> {
                if (d == null) {
                    show.on(null);
                    return;
                }
                List<Discover.Hit> out = new ArrayList<>();
                for (Discover.Hit m : d.more) if ("any".equals(a.kind) || a.kind.equals(m.kind)) out.add(m);
                show.on(out);
            });
        });
    }

    private void showMessage(String message) {
        clear();
        TextView t = ui.text(message, 15, R.string.font_regular, DiscoverUi.INK_SOFT);
        t.setSingleLine(false);
        content.addView(t);
    }

    /** Trending and new titles on your apps, from Home's TMDB cache. */
    private void popular() {
        List<Item> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Tmdb.Title> all = new ArrayList<>(feed.trending());
        all.addAll(feed.newThisWeek());
        for (Tmdb.Title t : all) {
            if (items.size() >= 8) break;
            if (seen.add(t.kind + t.id)) items.add(new Item(t.backdrop, t.name, "", t.kind, t.id, t.backdrop));
        }
        if (items.isEmpty()) return;
        label(act.getString(R.string.dc_popular), null);
        grid(items);
    }

    private void clear() {
        content.removeAllViews();
        firstResult = null;
    }

    private void gap(float units) {
        content.addView(new View(act), new LinearLayout.LayoutParams(1, ui.px(units)));
    }

    private void label(String title, String note) {
        LinearLayout line = new LinearLayout(act);
        line.setGravity(Gravity.BOTTOM);
        line.addView(ui.shelfTitle(title));
        if (note != null) {
            TextView n = ui.text(note, 13, R.string.font_regular, 0x80FFFFFF);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = ui.px(10);
            line.addView(n, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = ui.px(14);
        content.addView(line, lp);
    }

    /** A horizontally scrolling row, added to the page. */
    private LinearLayout shelfRow() {
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ui.px(6), 0, ui.px(6));
        content.addView(ui.sideways(row, 56, 56), ui.bleed(56, 56));
        return row;
    }

    /** A person: round photo, name and what they do; OK opens their page. */
    private View person(Discover.Hit p) {
        LinearLayout item = new LinearLayout(act);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setClipChildren(false);
        FrameLayout photo = ui.avatar(p.poster, p.name, 64);
        item.addView(photo, new LinearLayout.LayoutParams(ui.px(64), ui.px(64)));
        LinearLayout words = new LinearLayout(act);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = ui.text(p.name, 14, R.string.font_medium, DiscoverUi.INK);
        name.setMaxWidth(ui.px(240));
        words.addView(name, new LinearLayout.LayoutParams(-2, -2));   // not the default match_parent, which squeezed it to the role's width
        TextView role = ui.text(PersonActivity.role(p.sub), 12, R.string.font_regular, DiscoverUi.INK_DIM);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.topMargin = ui.px(4);
        words.addView(role, rlp);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -2);
        wlp.leftMargin = ui.px(12);
        item.addView(words, wlp);
        ui.focusRound(item, photo);
        item.setOnClickListener(v -> {
            remember(field.getText().toString());
            act.startActivity(PersonActivity.intent(act, p.id, p.name, p.poster));
        });
        return item;
    }

    /** A result's card, with "Series · 2020" under the name so titles that share a name can be told apart. */
    private Item item(Discover.Hit h) {
        String kind = act.getString("movie".equals(h.kind) ? R.string.dc_movie : R.string.dc_one_series);
        String sub = h.year.isEmpty() ? kind : act.getString(R.string.dc_pair, kind, h.year);
        return new Item(h.picture(), h.name, sub, h.kind, h.id, h.backdrop);
    }

    private static final class Item {
        final String picture, name, sub, kind, backdrop;
        final int id;

        Item(String picture, String name, String sub, String kind, int id, String backdrop) {
            this.picture = picture;
            this.name = name;
            this.sub = sub;
            this.kind = kind;
            this.id = id;
            this.backdrop = backdrop;
        }
    }

    /** Title cards, four to a row. The first row's Up goes to the field. */
    private void grid(List<Item> items) {
        LinearLayout row = null;
        boolean firstRow = firstResult == null;
        for (int i = 0; i < items.size(); i++) {
            if (i % COLUMNS == 0) {
                row = ui.row();
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.bottomMargin = ui.px(22);
                content.addView(row, rlp);
            }
            Item it = items.get(i);
            LinearLayout card = ui.card(it.picture, it.name, it.sub, CARD_W, v -> open(it));
            ui.markWhere(card, it.kind, it.id);
            card.setId(View.generateViewId());
            if (firstRow && i < COLUMNS) card.setNextFocusUpId(field.getId());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(CARD_W), -2);
            if (i % COLUMNS != COLUMNS - 1) lp.rightMargin = ui.px(GAP);
            row.addView(card, lp);
            if (firstResult == null) firstResult = card;
        }
    }

    private void open(Item it) {
        remember(field.getText().toString());
        act.startActivity(TitleActivity.intent(act, it.kind, it.id, it.name, it.backdrop));
    }
}

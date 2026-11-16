package com.gathin.tvhome;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Discover's My lists tab (fork only): your TMDB Watch later, Favourites and own lists as capsules along the top,
 * the chosen one as a grid below. Titles join and leave lists from Save (+) on their page. Not signed in, the tab
 * explains and offers Connect TMDB.
 */
final class ListsPane implements DiscoverActivity.Pane {
    private static final int COLUMNS = 4;

    private final DiscoverActivity act;
    private final DiscoverUi ui;
    private final DiscoverNet net;
    private final FrameLayout page;
    private final ScrollView scroll;
    private final LinearLayout content;
    private LinearLayout chips, grid;
    private TextView status;
    /** The chosen list: WATCHLIST, FAVORITE, or a list's id. */
    private String chosen = Discover.WATCHLIST;
    private View firstChip;
    private int seq;

    ListsPane(DiscoverActivity act, DiscoverUi ui) {
        this.act = act;
        this.ui = ui;
        net = DiscoverNet.get(act);
        page = new FrameLayout(act);
        page.setClipChildren(false);
        scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(ui.px(24));
        content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(ui.px(56), ui.px(92), ui.px(56), ui.px(48));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    }

    @Override public View view() {
        return page;
    }

    @Override public void enter() {
        if (firstChip != null) firstChip.requestFocus();
    }

    @Override public void shown() {
        build();
    }

    @Override public void leave() {
        scroll.smoothScrollTo(0, 0);
    }

    @Override public void destroy() {
    }

    private void build() {
        content.removeAllViews();
        firstChip = null;
        TmdbAccount a = TmdbAccount.load(act);
        if (a == null) {
            TextView t = ui.text(act.getString(R.string.dc_lists_signed_out), 16, R.string.font_regular, DiscoverUi.INK_SOFT);
            t.setSingleLine(false);
            t.setMaxWidth(ui.px(560));
            t.setLineSpacing(0, 1.35f);
            content.addView(t, new LinearLayout.LayoutParams(-2, -2));
            TextView connect = ui.capsule(act.getString(R.string.dc_connect_title));
            connect.setOnClickListener(v -> act.startActivity(new Intent(act, ConnectActivity.class)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, ui.px(44));
            lp.topMargin = ui.px(22);
            content.addView(connect, lp);
            firstChip = connect;
            return;
        }
        HorizontalScrollView hs = new HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        chips = new LinearLayout(act);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setClipChildren(false);
        chips.setPadding(0, ui.px(6), ui.px(56), ui.px(6));
        hs.addView(chips);
        content.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        status = ui.text("", 14, R.string.font_regular, DiscoverUi.INK_DIM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = ui.px(16);
        content.addView(status, slp);
        grid = new LinearLayout(act);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = ui.px(14);
        content.addView(grid, glp);

        chip(Discover.WATCHLIST, act.getString(R.string.dc_watch_later), a);
        chip(Discover.FAVORITE, act.getString(R.string.dc_favourites), a);
        net.lists(a, lists -> {
            if (lists == null || act.isDestroyed()) return;
            for (Discover.UserList l : lists) chip(String.valueOf(l.id), l.name, a);
        });
        load(a);
    }

    /** A list's capsule: solid while chosen, bright glass while focused. */
    private void chip(String key, String label, TmdbAccount a) {
        TextView t = ui.text(label, 14, R.string.font_medium, DiscoverUi.INK);
        t.setGravity(Gravity.CENTER);
        t.setPadding(ui.px(18), 0, ui.px(18), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setTag(key);
        paintChip(t);
        t.setOnFocusChangeListener((v, has) -> paintChip(t));
        t.setOnClickListener(v -> {
            chosen = key;
            for (int i = 0; i < chips.getChildCount(); i++) paintChip((TextView) chips.getChildAt(i));
            load(a);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, ui.px(40));
        lp.rightMargin = ui.px(12);
        chips.addView(t, lp);
        if (firstChip == null) firstChip = t;
    }

    private void paintChip(TextView t) {
        boolean f = t.isFocused(), on = chosen.equals(t.getTag());
        t.setBackground(f ? ui.focusedGlass(20) : on ? DiscoverUi.round(0xE6FFFFFF, ui.px(20)) : ui.glass(20));
        t.setTextColor(f || !on ? DiscoverUi.INK : 0xFF111111);
        t.animate().scaleX(f ? 1.07f : 1f).scaleY(f ? 1.07f : 1f).setDuration(ui.ms(160)).start();
    }

    private void load(TmdbAccount a) {
        int mine = ++seq;
        grid.removeAllViews();
        status.setText(R.string.dc_loading);
        DiscoverNet.Done<List<Discover.Hit>> show = items -> {
            if (mine != seq || act.isDestroyed()) return;
            if (items == null) {
                status.setText(R.string.dc_offline);
                return;
            }
            status.setText(items.isEmpty() ? act.getString(R.string.dc_list_empty) : act.getString(R.string.dc_count, items.size()));
            LinearLayout row = null;
            for (int i = 0; i < items.size(); i++) {
                if (i % COLUMNS == 0) {
                    row = new LinearLayout(act);
                    row.setClipChildren(false);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                    rlp.bottomMargin = ui.px(22);
                    grid.addView(row, rlp);
                }
                Discover.Hit h = items.get(i);
                LinearLayout card = ui.card(h.picture(), h.name, 200,
                        v -> act.startActivity(TitleActivity.intent(act, h.kind, h.id, h.name, h.backdrop)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(200), -2);
                if (i % COLUMNS != COLUMNS - 1) lp.rightMargin = ui.px(16);
                row.addView(card, lp);
            }
        };
        if (Discover.WATCHLIST.equals(chosen) || Discover.FAVORITE.equals(chosen)) net.saved(a, chosen, show);
        else net.listItems(a, Integer.parseInt(chosen), show);
    }
}

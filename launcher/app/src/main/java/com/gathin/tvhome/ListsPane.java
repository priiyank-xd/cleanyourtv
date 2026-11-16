package com.gathin.tvhome;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Discover's My lists tab (fork only), laid out like a TV library: your lists down the left (Watch later,
 * Favourites, then your own TMDB lists), the chosen one's titles as a grid on the right. Moving up and down the
 * left shows each list as you land on it; Right goes into its titles, Left from the first column comes back.
 * Titles join and leave lists from Save (+) on their page. Not signed in, the tab explains and offers Connect TMDB.
 */
final class ListsPane implements DiscoverActivity.Pane {
    private static final int COLUMNS = 4;
    private static final float SIDE_W = 220, GAP = 28, CARD_W = 142, CARD_GAP = 14;
    /** A list shows once focus has rested on its name this long, so running past names doesn't load each. */
    private static final long SETTLE_MS = 250;

    private final DiscoverActivity act;
    private final DiscoverUi ui;
    private final DiscoverNet net;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FrameLayout page;
    private ScrollView sideScroll, gridScroll;
    private LinearLayout side, grid;
    private TextView heading, status;
    /** The chosen list: WATCHLIST, FAVORITE, or a list's id; and its name on the left. */
    private String chosen = Discover.WATCHLIST;
    private TextView chosenView;
    private View firstFocus;
    private int seq;

    ListsPane(DiscoverActivity act, DiscoverUi ui) {
        this.act = act;
        this.ui = ui;
        net = DiscoverNet.get(act);
        page = new FrameLayout(act);
        page.setClipChildren(false);
    }

    @Override public View view() {
        return page;
    }

    @Override public void enter() {
        if (chosenView != null) chosenView.requestFocus();
        else if (firstFocus != null) firstFocus.requestFocus();
    }

    @Override public void shown() {
        build();
    }

    @Override public void leave() {
        if (gridScroll != null) gridScroll.smoothScrollTo(0, 0);
    }

    @Override public void destroy() {
        handler.removeCallbacksAndMessages(null);
    }

    private void build() {
        page.removeAllViews();
        handler.removeCallbacksAndMessages(null);
        firstFocus = null;
        chosenView = null;
        TmdbAccount a = TmdbAccount.load(act);
        if (a == null) {
            signedOut();
            return;
        }

        // the lists, down the left
        sideScroll = ui.page();
        sideScroll.setVerticalScrollBarEnabled(false);
        sideScroll.setClipChildren(false);
        sideScroll.setClipToPadding(false);
        // one glass card holding all the lists, like Home's panels
        LinearLayout sideWrap = new LinearLayout(act);
        sideWrap.setOrientation(LinearLayout.VERTICAL);
        sideWrap.setPadding(ui.px(56), ui.px(100), 0, ui.px(48));
        side = new LinearLayout(act);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackground(ui.glass(20));
        side.setPadding(ui.px(8), ui.px(8), ui.px(8), ui.px(8));
        sideWrap.addView(side, new LinearLayout.LayoutParams(-1, -2));
        sideScroll.addView(sideWrap, new ScrollView.LayoutParams(-1, -2));
        page.addView(sideScroll, new FrameLayout.LayoutParams(ui.px(56 + SIDE_W), -1));

        // the chosen list's titles, on the right
        gridScroll = ui.page();
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setClipChildren(false);
        gridScroll.setClipToPadding(false);
        gridScroll.setVerticalFadingEdgeEnabled(true);
        gridScroll.setFadingEdgeLength(ui.px(24));
        LinearLayout right = new LinearLayout(act);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setClipChildren(false);
        right.setClipToPadding(false);
        right.setPadding(0, ui.px(100), ui.px(40), ui.px(48));
        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.BOTTOM);
        heading = ui.text("", 22, R.string.font_medium, DiscoverUi.INK);
        head.addView(heading);
        status = ui.text("", 13, R.string.font_regular, DiscoverUi.INK_DIM);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(-2, -2);
        stlp.leftMargin = ui.px(12);
        stlp.bottomMargin = ui.px(3);
        head.addView(status, stlp);
        right.addView(head, new LinearLayout.LayoutParams(-2, -2));
        grid = new LinearLayout(act);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = ui.px(18);
        right.addView(grid, glp);
        gridScroll.addView(right, new ScrollView.LayoutParams(-1, -2));
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-1, -1);
        rlp.leftMargin = ui.px(56 + SIDE_W + GAP);
        page.addView(gridScroll, rlp);

        listName(Discover.WATCHLIST, act.getString(R.string.dc_watch_later), a);
        listName(Discover.FAVORITE, act.getString(R.string.dc_favourites), a);
        net.lists(a, lists -> {
            if (lists == null || act.isDestroyed()) return;
            for (Discover.UserList l : lists) listName(String.valueOf(l.id), l.name, a);
        });
        load(a);
    }

    private void signedOut() {
        LinearLayout content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setPadding(ui.px(56), ui.px(100), ui.px(56), 0);
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
        page.addView(content, new FrameLayout.LayoutParams(-1, -1));
        firstFocus = connect;
    }

    /**
     * A list's name in the card on the left: the chosen one bright, the others dim; focused, a soft highlight
     * behind it. Resting on a name shows that list.
     */
    private void listName(String key, String label, TmdbAccount a) {
        TextView t = ui.text(label, 17, R.string.font_medium, DiscoverUi.INK);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(ui.px(14), 0, ui.px(14), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setTag(key);
        t.setId(View.generateViewId());
        if (key.equals(chosen)) chosenView = t;
        paintName(t);
        t.setOnFocusChangeListener((v, has) -> {
            paintName(t);
            if (has && !key.equals(chosen)) {
                handler.removeCallbacksAndMessages(null);
                handler.postDelayed(() -> {
                    if (t.isFocused()) choose(t, a);
                }, SETTLE_MS);
            }
        });
        t.setOnClickListener(v -> {
            if (!key.equals(chosen)) choose(t, a);
            View first = firstCard();
            if (first != null) first.requestFocus();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, ui.px(44));
        if (side.getChildCount() > 0) lp.topMargin = ui.px(2);
        side.addView(t, lp);
        if (firstFocus == null) firstFocus = t;
        pointRight();
    }

    private void paintName(TextView t) {
        boolean f = t.isFocused(), on = chosen.equals(t.getTag());
        t.setBackground(f ? DiscoverUi.round(0x2EFFFFFF, ui.px(12)) : null);
        t.setTextColor(f || on ? DiscoverUi.INK : DiscoverUi.INK_DIM);
    }

    private void choose(TextView t, TmdbAccount a) {
        chosen = (String) t.getTag();
        chosenView = t;
        for (int i = 0; i < side.getChildCount(); i++) paintName((TextView) side.getChildAt(i));
        load(a);
    }

    /** Right from any list name goes to the first title, or nowhere when the list is empty (not up to the tabs). */
    private void pointRight() {
        View first = firstCard();
        if (first != null && first.getId() == View.NO_ID) first.setId(View.generateViewId());
        for (int i = 0; i < side.getChildCount(); i++) {
            View name = side.getChildAt(i);
            name.setNextFocusRightId(first != null ? first.getId() : name.getId());
        }
    }

    private View firstCard() {
        if (grid.getChildCount() == 0) return null;
        LinearLayout row = (LinearLayout) grid.getChildAt(0);
        return row.getChildCount() == 0 ? null : row.getChildAt(0);
    }

    private void load(TmdbAccount a) {
        int mine = ++seq;
        grid.removeAllViews();
        gridScroll.scrollTo(0, 0);
        pointRight();
        heading.setText(chosenView != null ? chosenView.getText() : "");
        status.setText(R.string.dc_loading);
        DiscoverNet.Done<List<Discover.Hit>> show = items -> {
            if (mine != seq || act.isDestroyed()) return;
            if (items == null) {
                status.setText(R.string.dc_offline);
                return;
            }
            status.setText(items.isEmpty() ? act.getString(R.string.dc_list_empty) : act.getResources().getQuantityString(R.plurals.dc_count, items.size(), items.size()));
            LinearLayout row = null;
            for (int i = 0; i < items.size(); i++) {
                if (i % COLUMNS == 0) {
                    row = ui.rowOpenLeft();
                    row.setClipChildren(false);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                    rlp.bottomMargin = ui.px(22);
                    grid.addView(row, rlp);
                }
                Discover.Hit h = items.get(i);
                LinearLayout card = ui.card(h.picture(), h.name, CARD_W,
                        v -> act.startActivity(TitleActivity.intent(act, h.kind, h.id, h.name, h.backdrop)));
                // Left from the first column goes back to the chosen list's name
                if (i % COLUMNS == 0 && chosenView != null) card.setNextFocusLeftId(chosenView.getId());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(CARD_W), -2);
                if (i % COLUMNS != COLUMNS - 1) lp.rightMargin = ui.px(CARD_GAP);
                row.addView(card, lp);
            }
            pointRight();
        };
        if (Discover.WATCHLIST.equals(chosen) || Discover.FAVORITE.equals(chosen)) net.saved(a, chosen, show);
        else net.listItems(a, Integer.parseInt(chosen), show);
    }
}

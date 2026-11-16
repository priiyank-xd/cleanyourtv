package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.InputType;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

/**
 * Save (the + button) on a title page or Discover's hero (fork only): a glass sheet ticking the title onto Watch
 * later, Favourites and your own TMDB lists, one OK each, and New list to make one with the keyboard. Not signed
 * in, it offers Connect TMDB instead. Changes go to TMDB at once; a failed one is unticked again with a note.
 */
final class SaveSheet {
    private final Activity act;
    private final DiscoverUi ui;
    private final DiscoverNet net;
    private final FrameLayout root;
    private final ViewGroup behind;
    private final String kind, name;
    private final int id;
    private DiscoverUi.Sheet sheet;

    static void open(Activity act, DiscoverUi ui, FrameLayout root, ViewGroup behind, String kind, int id, String name) {
        new SaveSheet(act, ui, root, behind, kind, id, name).show();
    }

    private SaveSheet(Activity act, DiscoverUi ui, FrameLayout root, ViewGroup behind, String kind, int id, String name) {
        this.act = act;
        this.ui = ui;
        this.root = root;
        this.behind = behind;
        this.kind = kind;
        this.id = id;
        this.name = name;
        net = DiscoverNet.get(act);
    }

    private void show() {
        TmdbAccount a = TmdbAccount.load(act);
        sheet = ui.new Sheet(root, behind, act.getString(R.string.dc_save_to), name);
        if (a == null) {
            ImageView icon = new ImageView(act);
            icon.setImageResource(R.drawable.ic_lg_profile);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            sheet.row(icon, act.getString(R.string.dc_connect_to_save), null, v -> {
                sheet.close();
                act.startActivity(new Intent(act, ConnectActivity.class));
            }).requestFocus();
            return;
        }
        FrameLayout later = ui.tick(false), fav = ui.tick(false);
        View first = sheet.rowEnd(null, act.getString(R.string.dc_watch_later), later, v -> mark(a, Discover.WATCHLIST, later));
        sheet.rowEnd(null, act.getString(R.string.dc_favourites), fav, v -> mark(a, Discover.FAVORITE, fav));
        first.requestFocus();
        net.call("GET", Discover.statesUrl(kind, id, a.session), null, net.appToken(), j -> {
            if (j == null || act.isDestroyed()) return;
            boolean[] on = Discover.parseStates(j);
            ui.setTick(later, on[0]);
            ui.setTick(fav, on[1]);
        });
        net.lists(a, lists -> {
            if (lists == null || act.isDestroyed() || ui.open != sheet) return;
            for (Discover.UserList l : lists) {
                FrameLayout t = ui.tick(false);
                sheet.rowEnd(null, l.name, t, v -> toggleList(a, l, t));
                net.listItems(a, l.id, items -> {
                    if (items == null || act.isDestroyed()) return;
                    for (Discover.Hit h : items) if (h.id == id && h.kind.equals(kind)) ui.setTick(t, true);
                });
            }
            ImageView plus = new ImageView(act);
            plus.setImageResource(R.drawable.ic_dc_plus);
            plus.setScaleType(ImageView.ScaleType.FIT_CENTER);
            sheet.rowEnd(null, act.getString(R.string.dc_new_list), plus, v -> newList(a));
        });
    }

    private void mark(TmdbAccount a, String what, FrameLayout tick) {
        boolean on = !DiscoverUi.isTicked(tick);
        ui.setTick(tick, on);
        net.call("POST", Discover.markUrl(what, a.accountId, a.session), Discover.markBody(what, kind, id, on), net.appToken(), j -> {
            if (j == null) failed(tick, !on);
            else if (on) noteSaved();
        });
    }

    private void toggleList(TmdbAccount a, Discover.UserList l, FrameLayout tick) {
        boolean on = !DiscoverUi.isTicked(tick);
        ui.setTick(tick, on);
        net.call(on ? "POST" : "DELETE", Discover.itemsUrl(l.id), Discover.itemsBody(kind, id), a.access, j -> {
            if (j == null) failed(tick, !on);
            else if (on) noteSaved();
        });
    }

    /** Saving says a lot about your taste: noted for Browse's "For you" (the details are usually cached by now). */
    private void noteSaved() {
        net.details(kind, id, d -> {
            if (d != null) Taste.get(act).saved(kind, id, d.genreIds, d.language);
        });
    }

    private void failed(FrameLayout tick, boolean back) {
        if (act.isDestroyed()) return;
        ui.setTick(tick, back);
        Toast.makeText(act, R.string.dc_save_failed, Toast.LENGTH_SHORT).show();
    }

    /** New list: a name field in the same sheet; Done makes the list with this title in it. */
    private void newList(TmdbAccount a) {
        sheet.clear();
        EditText field = new EditText(act);
        field.setBackground(ui.field(16, true));
        field.setTextColor(DiscoverUi.INK);
        field.setHintTextColor(0x66FFFFFF);
        field.setHint(R.string.dc_list_name);
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.px(16));
        field.setTypeface(UiFont.get(act, R.string.font_regular));
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setPadding(ui.px(16), 0, ui.px(16), 0);
        sheet.add(field, ui.px(52));
        field.setOnEditorActionListener((v, action, e) -> {
            boolean go = action == EditorInfo.IME_ACTION_DONE || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER);
            String title = field.getText().toString().trim();
            if (!go || title.isEmpty()) return go;
            InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
            net.call("POST", Discover.newListUrl(), Discover.newListBody(title), a.access, j -> {
                int listId = j == null ? 0 : j.optInt("id");
                if (listId == 0) {
                    Toast.makeText(act, R.string.dc_save_failed, Toast.LENGTH_SHORT).show();
                    reopen();
                    return;
                }
                net.call("POST", Discover.itemsUrl(listId), Discover.itemsBody(kind, id), a.access, k -> reopen());
            });
            return true;
        });
        field.requestFocus();
        field.post(() -> {
            InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void reopen() {
        if (act.isDestroyed()) return;
        sheet.close();
        show();
    }
}

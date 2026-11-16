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
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * A person's page in Discover (fork only), from a cast row or a search: their photo, what they do, a short bio,
 * what they are known for, then everything they're in, newest first. Back returns to where they were picked.
 */
public class PersonActivity extends Activity {
    private static final String ID = "id", NAME = "name", PHOTO = "photo";

    static Intent intent(Context ctx, int id, String name, String photo) {
        return new Intent(ctx, PersonActivity.class).putExtra(ID, id).putExtra(NAME, name).putExtra(PHOTO, photo == null ? "" : photo);
    }

    /** What a TMDB department or credit says someone does, as people say it. */
    static String role(String dept) {
        if (dept == null) return "";
        switch (dept) {
            case "Acting": return "Actor";
            case "Directing": return "Director";
            case "Writing": return "Writer";
            case "Production": return "Producer";
            case "Sound": return "Music";
            case "Camera": return "Cinematographer";
            case "Editing": return "Editor";
            default: return dept;
        }
    }

    private DiscoverUi ui;
    private LinearLayout page;
    private TextView kicker, born, bio;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new DiscoverUi(this);
        int id = getIntent().getIntExtra(ID, 0);
        String name = getIntent().getStringExtra(NAME);
        String photo = getIntent().getStringExtra(PHOTO);

        FrameLayout root = new FrameLayout(this);
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.15f, 0f);
        glow.setGradientRadius(ui.px(620));
        glow.setColors(new int[]{0x8C4A1E38, 0x0008080B});
        root.setBackground(new LayerDrawable(new Drawable[]{DiscoverUi.round(0xFF08080B, 0), glow}));
        root.setClipChildren(false);
        ScrollView scroll = ui.page();
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setPadding(ui.px(56), ui.px(52), 0, ui.px(56));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        LinearLayout head = new LinearLayout(this);
        head.setClipChildren(false);
        head.addView(ui.avatar(photo, name == null ? "" : name, 168), new LinearLayout.LayoutParams(ui.px(168), ui.px(168)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ui.px(620), -2);
        wlp.leftMargin = ui.px(32);
        wlp.topMargin = ui.px(14);
        head.addView(words, wlp);
        kicker = ui.text("", 12, R.string.font_medium, 0xB8FFFFFF);
        kicker.setLetterSpacing(0.14f);
        words.addView(kicker);
        TextView title = ui.text(name == null ? "" : name, 40, R.string.font_medium, DiscoverUi.INK);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = ui.px(8);
        words.addView(title, tlp);
        born = ui.text(getString(R.string.dc_loading), 14, R.string.font_regular, DiscoverUi.INK_SOFT);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = ui.px(8);
        words.addView(born, blp);
        bio = ui.text("", 14, R.string.font_regular, 0xDBFFFFFF);
        bio.setSingleLine(false);
        bio.setMaxLines(3);
        bio.setEllipsize(TextUtils.TruncateAt.END);
        bio.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams biolp = new LinearLayout.LayoutParams(-1, -2);
        biolp.topMargin = ui.px(12);
        words.addView(bio, biolp);
        page.addView(head);

        DiscoverNet.get(this).answer(Discover.personUrl(id), 24 * DiscoverNet.HOURS, a -> {
            if (isDestroyed()) return;
            if (a == null) {
                born.setText(R.string.dc_offline);
                return;
            }
            show(Discover.parsePerson(a.json));
        });
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) DiscoverNet.get(this).trimMemory();
    }

    private void show(Discover.PersonPage p) {
        kicker.setText(role(p.dept).toUpperCase(java.util.Locale.ROOT));
        born.setText(p.born);
        bio.setText(p.bio);
        View first = null;
        if (!p.knownFor.isEmpty()) {
            first = shelf(getString(R.string.dc_known_for), p.knownFor);
        }
        if (p.all.size() > p.knownFor.size()) {
            View f = shelf(getString(R.string.dc_all_titles), p.all);
            if (first == null) first = f;
        }
        if (first != null) first.requestFocus();
    }

    /** A titled shelf of cards; returns its first card. */
    private View shelf(String label, List<Discover.Hit> hits) {
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = ui.px(30);
        page.addView(ui.shelfTitle(label), llp);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, ui.px(14), ui.px(56), ui.px(6));
        page.addView(ui.sideways(row, 56, 0), ui.bleed(56, 0));
        View first = null;
        for (Discover.Hit h : hits) {
            LinearLayout card = ui.card(h.picture(), h.name, 200,
                    v -> startActivity(TitleActivity.intent(this, h.kind, h.id, h.name, h.backdrop)));
            ui.markWhere(card, h.kind, h.id);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(200), -2);
            lp.rightMargin = ui.px(16);
            row.addView(card, lp);
            if (first == null) first = card;
        }
        return first;
    }
}

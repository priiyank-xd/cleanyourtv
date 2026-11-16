package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Map;

/**
 * Discover's look (fork only), shared by its screens: a 960x540 design grid scaled to the screen, Home's liquid
 * glass (a dark base, a light top-to-bottom sheen and a thin bright edge), and one focus style. Glass that gets focus
 * turns brighter with a bright edge and a soft glow, as on Home; a picture lifts with a sheen and a bright rim.
 */
final class DiscoverUi {
    static final int INK = 0xFFFFFFFF, INK_SOFT = 0xC7FFFFFF, INK_DIM = 0x8CFFFFFF;
    private static final int GLASS_BASE = 0x5910141E, GLASS_TOP = 0x33FFFFFF, GLASS_BOTTOM = 0x12FFFFFF, GLASS_EDGE = 0x40FFFFFF;
    private static final int FOCUS_TOP = 0x80FFFFFF, FOCUS_BOTTOM = 0x33FFFFFF, FOCUS_EDGE = 0xE6FFFFFF;

    final Activity act;
    final Config cfg;
    final float u;
    /** The sheet open over the page, if any: Back closes it first. */
    Sheet open;

    DiscoverUi(Activity act) {
        this.act = act;
        cfg = Config.load(act);
        u = act.getResources().getDisplayMetrics().widthPixels / 960f;
    }

    /** An animation's length, honouring the animations and reduced_motion settings as Home does. */
    long ms(long full) {
        if (!cfg.animations) return 0;
        return cfg.reducedMotion ? Math.min(full, Motion.REDUCED_MS) : full;
    }

    int px(float units) {
        return Math.round(units * u);
    }

    TextView text(String s, float size, int font, int color) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
        t.setTypeface(UiFont.get(act, font));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** A view at (x, y) in design units in a FrameLayout; a negative size wraps. */
    void place(FrameLayout parent, View v, float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w < 0 ? -2 : px(w), h < 0 ? -2 : px(h));
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        parent.addView(v, lp);
    }

    // ---- surfaces ----

    Drawable glass(float radius) {
        return glassOf(radius, GLASS_TOP, GLASS_BOTTOM, GLASS_EDGE, 1f);
    }

    Drawable focusedGlass(float radius) {
        return glassOf(radius, FOCUS_TOP, FOCUS_BOTTOM, FOCUS_EDGE, 1.5f);
    }

    /** The search field: glass whose edge brightens while it has focus (no fill: the typing stays easy to read). */
    Drawable field(float radius, boolean focused) {
        return focused ? glassOf(radius, 0x40FFFFFF, 0x1AFFFFFF, 0xCCFFFFFF, 1.5f) : glass(radius);
    }

    /** A sheet over the page (Where to watch, More): darker and more opaque, so its rows stay readable. */
    Drawable sheet(float radius) {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xD9282A31, 0xE618191E});
        base.setCornerRadius(px(radius));
        GradientDrawable edge = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x1FFFFFFF, 0x05FFFFFF});
        edge.setCornerRadius(px(radius));
        edge.setStroke(Math.max(1, px(1)), 0x33FFFFFF);
        return new LayerDrawable(new Drawable[]{base, edge});
    }

    private Drawable glassOf(float radius, int top, int bottom, int edge, float stroke) {
        GradientDrawable base = new GradientDrawable();
        base.setColor(GLASS_BASE);
        base.setCornerRadius(px(radius));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        g.setCornerRadius(px(radius));
        g.setStroke(Math.max(1, px(stroke)), edge);
        return new LayerDrawable(new Drawable[]{base, g});
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    // ---- sheets ----

    /**
     * A glass sheet over a page, on the right: a title, an optional line under it, and rows. The page behind can't
     * take focus while it is open; closing it gives focus back to what had it.
     */
    final class Sheet {
        private final FrameLayout root, overlay;
        private final ViewGroup behind;
        private final View before;
        final LinearLayout list;

        Sheet(FrameLayout root, ViewGroup behind, String title, String sub) {
            this.root = root;
            this.behind = behind;
            before = act.getCurrentFocus();
            overlay = new FrameLayout(act);
            overlay.setBackgroundColor(0x59000000);
            overlay.setClickable(true);
            LinearLayout panel = new LinearLayout(act);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackground(sheet(28));
            panel.setPadding(px(18), px(24), px(18), px(18));
            panel.setClipChildren(false);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.leftMargin = px(16);
            panel.addView(text(title, 22, R.string.font_medium, INK), tlp);
            if (sub != null) {
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
                slp.setMargins(px(16), px(6), 0, px(6));
                panel.addView(text(sub, 12, R.string.font_regular, INK_DIM), slp);
            }
            android.widget.ScrollView scroll = new android.widget.ScrollView(act);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setClipToPadding(false);
            list = new LinearLayout(act);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(0, px(6), 0, px(6));
            list.setClipChildren(false);
            scroll.addView(list, new android.widget.ScrollView.LayoutParams(-1, -2));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.topMargin = px(8);
            panel.addView(scroll, sp);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(px(388), -2, Gravity.END | Gravity.TOP);
            plp.setMargins(0, px(24), px(24), px(24));
            overlay.addView(panel, plp);
            root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
            behind.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            if (open != null) open.close();
            open = this;
        }

        /** A row: an optional logo or icon, a title, an optional value on the right. */
        View row(View logo, String title, String value, View.OnClickListener click) {
            LinearLayout row = new LinearLayout(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(16), 0, px(16), 0);
            row.setFocusable(true);
            row.setClickable(true);
            if (logo != null) row.addView(logo, new LinearLayout.LayoutParams(px(46), px(26)));
            TextView t = text(title, 15, R.string.font_regular, INK);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
            tlp.leftMargin = logo != null ? px(12) : 0;
            row.addView(t, tlp);
            TextView v = value == null ? null : text(value, 14, R.string.font_regular, INK_DIM);
            if (v != null) row.addView(v);
            row.setOnFocusChangeListener((x, has) -> {
                row.setBackground(has ? focusedGlass(16) : null);
                row.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f).setDuration(ms(160)).start();
                if (v != null) v.setTextColor(has ? INK : INK_DIM);
            });
            row.setOnClickListener(click);
            list.addView(row, new LinearLayout.LayoutParams(-1, px(52)));
            return row;
        }

        /** A small group heading between rows. */
        void group(String label) {
            TextView head = text(label.toUpperCase(java.util.Locale.ROOT), 12, R.string.font_medium, 0x80FFFFFF);
            head.setLetterSpacing(0.12f);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
            hlp.setMargins(px(16), px(12), 0, px(6));
            list.addView(head, hlp);
        }

        void close() {
            if (overlay.getParent() == null) return;
            root.removeView(overlay);
            behind.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            if (open == this) open = null;
            if (before != null) before.requestFocus();
        }

        /** Empties the sheet's rows, for a new step in the same sheet. */
        void clear() {
            list.removeAllViews();
        }

        /** Adds any view as a row. */
        void add(View v, int height) {
            list.addView(v, new LinearLayout.LayoutParams(-1, height));
        }
    }

    // ---- focus ----

    /** Glass that brightens, glows and lifts a little while focused. */
    void focusGlass(View v, float radius) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(glass(radius));
        v.setOnFocusChangeListener((x, has) -> {
            v.setBackground(has ? focusedGlass(radius) : glass(radius));
            v.setForeground(has ? new GlowDrawable(px(radius), px(12), 0, 0x4DFFFFFF) : null);
            v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f).setDuration(ms(180)).start();
            v.setElevation(has ? px(10) : 0);
        });
    }

    /**
     * A picture card: focus is on {@code card} (the whole tile, title included), the lift on {@code art}.
     */
    void focusArt(View card, View art, float radius) {
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnFocusChangeListener((x, has) -> {
            if (has) {
                GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[]{0x47FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
                sheen.setCornerRadius(px(radius));
                sheen.setStroke(Math.max(1, px(1.5f)), 0xB3FFFFFF);
                art.setForeground(sheen);
            } else {
                art.setForeground(null);
            }
            art.animate().scaleX(has ? 1.09f : 1f).scaleY(has ? 1.09f : 1f).translationY(has ? -px(4) : 0)
                    .setDuration(ms(200)).start();
            art.setElevation(has ? px(18) : 0);
            card.setZ(has ? 1 : 0);
        });
    }

    // ---- pieces ----

    /** A landscape picture with rounded corners, filled when its TMDB image arrives. */
    FrameLayout art(String path, float w, float h, float radius) {
        FrameLayout box = new FrameLayout(act);
        box.setBackground(round(0x1FFFFFFF, px(radius)));
        box.setClipToOutline(true);
        ImageView img = new ImageView(act);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if (path != null && !path.isEmpty()) {
            DiscoverNet.get(act).picture(path, w > 400 ? "w1280" : "w780", px(w), b -> {
                if (b != null && !act.isDestroyed()) img.setImageBitmap(b);
            });
        }
        return box;
    }

    /**
     * A title card: picture with the name under it, focusable as one. {@code w} wide, 16:9.
     */
    LinearLayout card(String picture, String name, float w, View.OnClickListener open) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        FrameLayout pic = art(picture, w, w * 9 / 16, 12);
        card.addView(pic, new LinearLayout.LayoutParams(px(w), px(w * 9 / 16)));
        TextView t = text(name, 13, R.string.font_medium, INK_SOFT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(w), -2);
        lp.topMargin = px(9);
        card.addView(t, lp);
        focusArt(card, pic, 12);
        card.setOnClickListener(open);
        return card;
    }

    /** A portrait poster with rounded corners (TMDB w342), filled when it arrives. */
    FrameLayout poster(String path, float w, float h, float radius) {
        FrameLayout box = new FrameLayout(act);
        box.setBackground(round(0x1FFFFFFF, px(radius)));
        box.setClipToOutline(true);
        ImageView img = new ImageView(act);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if (path != null && !path.isEmpty()) {
            DiscoverNet.get(act).picture(path, "w342", px(w), b -> {
                if (b != null && !act.isDestroyed()) img.setImageBitmap(b);
            });
        }
        return box;
    }

    /** A person's round photo, or their initials on a soft colour when TMDB has none. */
    FrameLayout avatar(String photo, String name, float size) {
        FrameLayout box = new FrameLayout(act);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, tones(name));
        bg.setShape(GradientDrawable.OVAL);
        box.setBackground(bg);
        box.setClipToOutline(true);
        TextView initials = text(initials(name), size / 3.2f, R.string.font_medium, INK);
        initials.setGravity(Gravity.CENTER);
        box.addView(initials, new FrameLayout.LayoutParams(-1, -1));
        ImageView img = new ImageView(act);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if (photo != null && !photo.isEmpty()) {
            DiscoverNet.get(act).picture(photo, "w185", px(size), b -> {
                if (b != null && !act.isDestroyed()) img.setImageBitmap(b);
            });
        }
        return box;
    }

    /** Focus for a round photo: it lifts with a bright rim. */
    void focusRound(View item, View photo) {
        item.setFocusable(true);
        item.setClickable(true);
        item.setOnFocusChangeListener((x, has) -> {
            GradientDrawable rim = new GradientDrawable();
            rim.setShape(GradientDrawable.OVAL);
            rim.setStroke(Math.max(1, px(1.5f)), 0xD9FFFFFF);
            photo.setForeground(has ? rim : null);
            photo.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(ms(200)).start();
            photo.setElevation(has ? px(14) : 0);
        });
    }

    static String initials(String name) {
        StringBuilder b = new StringBuilder();
        for (String w : name.trim().split("\\s+")) {
            if (!w.isEmpty() && b.length() < 2) b.appendCodePoint(w.codePointAt(0));
        }
        return b.toString().toUpperCase(java.util.Locale.ROOT);
    }

    private static int[] tones(String name) {
        int[] hues = {200, 28, 330, 160, 260, 45};
        int h = hues[Math.abs(name.hashCode()) % hues.length];
        return new int[]{android.graphics.Color.HSVToColor(new float[]{h, 0.35f, 0.42f}),
                android.graphics.Color.HSVToColor(new float[]{(h + 60) % 360, 0.4f, 0.2f})};
    }

    /** A page's big title: a size smaller for long names, so two lines hold them. */
    void sizeTitle(TextView t, String name) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, (name != null && name.length() > 28 ? 38 : 46) * u);
    }

    /**
     * Scores as a row: IMDb's yellow badge with the rating and vote count, Rotten Tomatoes' tomato with the
     * Tomatometer. Filled in later by {@link #fillRatings}; empty (and taking no room) until then.
     */
    LinearLayout ratingsRow() {
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setVisibility(View.GONE);
        return row;
    }

    void fillRatings(LinearLayout row, Discover.Ratings r) {
        row.removeAllViews();
        if (r == null || r.isEmpty()) {
            row.setVisibility(View.GONE);
            return;
        }
        if (!r.imdb.isEmpty()) {
            TextView badge = new TextView(act);
            badge.setText(R.string.dc_imdb);
            badge.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
            badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, 11 * u);
            badge.setTextColor(0xFF000000);
            badge.setIncludeFontPadding(false);
            badge.setPadding(px(5), px(3), px(5), px(3));
            badge.setBackground(round(0xFFF5C518, px(4)));
            row.addView(badge);
            TextView value = text(r.imdb, 14, R.string.font_medium, INK);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = px(7);
            row.addView(value, lp);
            if (!r.votes.isEmpty()) {
                TextView votes = text(r.votes, 12, R.string.font_regular, INK_DIM);
                LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
                vlp.leftMargin = px(6);
                row.addView(votes, vlp);
            }
        }
        if (!r.tomatoes.isEmpty()) {
            ImageView tomato = new ImageView(act);
            tomato.setImageResource(R.drawable.ic_dc_tomato);
            tomato.setContentDescription(act.getString(R.string.dc_rotten_tomatoes));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(px(18), px(18));
            tlp.leftMargin = r.imdb.isEmpty() ? 0 : px(18);
            row.addView(tomato, tlp);
            TextView value = text(r.tomatoes, 14, R.string.font_medium, INK);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = px(6);
            row.addView(value, lp);
        }
        row.setVisibility(View.VISIBLE);
    }

    /** A round tick for a sheet row, in a logo-sized box: blue with a check when on, an empty ring when off. */
    FrameLayout tick(boolean on) {
        FrameLayout box = new FrameLayout(act);
        View circle = new View(act);
        circle.setTag("circle");
        box.addView(circle, new FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER));
        ImageView check = new ImageView(act);
        check.setTag("check");
        check.setImageResource(R.drawable.ic_lg_check);
        box.addView(check, new FrameLayout.LayoutParams(px(14), px(14), Gravity.CENTER));
        setTick(box, on);
        return box;
    }

    void setTick(FrameLayout box, boolean on) {
        GradientDrawable c = new GradientDrawable();
        c.setShape(GradientDrawable.OVAL);
        if (on) c.setColor(0xFF0A84FF);
        else c.setStroke(Math.max(1, px(1.5f)), 0x80FFFFFF);
        box.findViewWithTag("circle").setBackground(c);
        box.findViewWithTag("check").setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        box.setTag(on);
    }

    static boolean isTicked(FrameLayout box) {
        return Boolean.TRUE.equals(box.getTag());
    }

    /** A shelf's title. */
    TextView shelfTitle(String s) {
        return text(s, 16, R.string.font_medium, INK);
    }

    /** A capsule of text that is glass, brighter while focused. */
    TextView capsule(String s) {
        TextView t = text(s, 14, R.string.font_medium, INK);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(18), 0, px(18), 0);
        focusGlass(t, 20);
        return t;
    }

    /** An app's logo: the launcher's bundled wordmark when it has one, else TMDB's logo for the service. */
    View providerLogo(Discover.Provider p, float w, float h) {
        Map<String, String> banners = cfg.appBanners;
        String pkg = Discover.packageFor(p.id);
        String banner = pkg == null ? null : banners.get(pkg);
        if (banner != null && banner.startsWith("banner_")) {
            int id = act.getResources().getIdentifier(banner, "drawable", act.getPackageName());
            if (id != 0) {
                ImageView img = new ImageView(act);
                img.setImageResource(id);
                img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                img.setContentDescription(p.name);
                return img;
            }
        }
        FrameLayout box = new FrameLayout(act);
        ImageView img = new ImageView(act);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setContentDescription(p.name);
        img.setBackground(round(0x00000000, px(6)));
        img.setClipToOutline(true);
        float side = Math.min(w, h);
        box.addView(img, new FrameLayout.LayoutParams(px(side), px(side), Gravity.CENTER));
        DiscoverNet.get(act).picture(p.logo, "w92", px(side), (Bitmap b) -> {
            if (b != null && !act.isDestroyed()) img.setImageBitmap(b);
        });
        return box;
    }
}

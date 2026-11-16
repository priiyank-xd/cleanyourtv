package com.gathin.tvhome;

import android.app.Activity;
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
    /** Home's glass, the same numbers as LgHome, so Discover's buttons match Home's (like "Open Netflix"). */
    private static final int GLASS_BASE = LgHome.GLASS_TINT, GLASS_TOP = 0x24FFFFFF, GLASS_BOTTOM = 0x0DFFFFFF, GLASS_EDGE = 0x33FFFFFF;
    private static final int FOCUS_TOP = 0x75FFFFFF, FOCUS_BOTTOM = 0x2EFFFFFF, FOCUS_EDGE = 0xD9FFFFFF;

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
        return glass(radius);   // Home's panel glass
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
            // a chosen option is a checkmark on the right, as in tvOS and Google TV menus
            if ("\u2713".equals(value)) return rowEnd(logo, title, tick(true), click);
            TextView v = value == null ? null : text(value, 14, R.string.font_regular, INK_DIM);
            View row = rowEnd(logo, title, v, click);
            if (v != null) {
                View.OnFocusChangeListener base = row.getOnFocusChangeListener();
                row.setOnFocusChangeListener((x, has) -> {
                    base.onFocusChange(x, has);
                    v.setTextColor(has ? INK : INK_DIM);
                });
            }
            return row;
        }

        /**
         * A row with any view at its right end (a checkmark, a +). Focus is a glass bar inside the sheet: it brightens
         * but doesn't grow, so it never pokes past the sheet's edge.
         */
        View rowEnd(View logo, String title, View end, View.OnClickListener click) {
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
            if (end != null) {
                // an icon at the end is drawn at checkmark size
                LinearLayout.LayoutParams elp = end instanceof ImageView
                        ? new LinearLayout.LayoutParams(px(18), px(18)) : new LinearLayout.LayoutParams(-2, -2);
                elp.leftMargin = px(12);
                row.addView(end, elp);
            }
            row.setOnFocusChangeListener((x, has) -> row.setBackground(has ? focusedGlass(14) : null));
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
     * A button with nothing around it at rest, a little dim; focused, Home's glass pill appears around it, it turns
     * fully bright and grows by {@code scale}.
     */
    void focusBare(View v, float scale) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(null);
        v.setAlpha(0.7f);
        // focused, it gets Home's "Open Netflix" pill (a circle around an icon), brightens and lifts a touch
        v.setOnFocusChangeListener((x, has) -> {
            v.setBackground(has ? sheet(200) : null);
            v.animate().alpha(has ? 1f : 0.7f).scaleX(has ? scale : 1f).scaleY(has ? scale : 1f)
                    .setDuration(ms(180)).start();
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

    /** Pictures that failed, asked again as soon as another picture arrives (the network is back). */
    private final java.util.List<Runnable> failed = new java.util.ArrayList<>();

    /** Puts a TMDB picture in {@code img} when it arrives; one that fails is tried again later. */
    void fill(ImageView img, String path, String size, int width) {
        DiscoverNet.get(act).picture(path, size, width, b -> {
            if (act.isDestroyed()) return;
            if (b == null) {
                failed.add(() -> fill(img, path, size, width));
                return;
            }
            img.setImageBitmap(b);
            if (failed.isEmpty()) return;
            java.util.List<Runnable> again = new java.util.ArrayList<>(failed);
            failed.clear();
            for (Runnable r : again) r.run();
        });
    }

    /** A landscape picture with rounded corners, filled when its TMDB image arrives. */
    FrameLayout art(String path, float w, float h, float radius) {
        FrameLayout box = new FrameLayout(act);
        box.setBackground(round(0x1FFFFFFF, px(radius)));
        box.setClipToOutline(true);
        ImageView img = new ImageView(act);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if (path != null && !path.isEmpty()) {
            fill(img, path, w > 400 ? "w1280" : "w780", px(w));
        }
        return box;
    }

    /**
     * A title card: picture with the name under it, focusable as one. {@code w} wide, 16:9.
     */
    LinearLayout card(String picture, String name, float w, View.OnClickListener open) {
        return card(picture, name, null, w, open);
    }

    /** A title card with a dim second line under the name ({@code sub}, such as "Series · 2020"), when given. */
    LinearLayout card(String picture, String name, String sub, float w, View.OnClickListener open) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        FrameLayout pic = art(picture, w, w * 9 / 16, 12);
        if (picture == null || picture.isEmpty()) noPicture(pic, name, 15);
        card.addView(pic, new LinearLayout.LayoutParams(px(w), px(w * 9 / 16)));
        TextView t = text(name, 13, R.string.font_medium, INK_SOFT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(w), -2);
        lp.topMargin = px(9);
        card.addView(t, lp);
        if (sub != null && !sub.isEmpty()) {
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(px(w), -2);
            slp.topMargin = px(4);
            card.addView(text(sub, 12, R.string.font_regular, INK_DIM), slp);
        }
        focusArt(card, pic, 12);
        card.setOnClickListener(open);
        return card;
    }

    /** A title TMDB has no picture for: its name on a soft colour of its own, never an empty grey box. */
    void noPicture(FrameLayout box, String name, float size) {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, tones(name == null ? "" : name));
        bg.setCornerRadius(px(12));
        box.setBackground(bg);
        TextView t = text(name == null ? "" : name, size, R.string.font_medium, INK);
        t.setSingleLine(false);
        t.setMaxLines(3);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(14), 0, px(14), 0);
        box.addView(t, 0, new FrameLayout.LayoutParams(-1, -1));
    }

    /**
     * The app it streams on, in a picture's top-right corner: Home's bare symbol over a soft shadow (no badge
     * around it). Your installed app when one streams it, else one to get, shown dimmer; nothing when no app does.
     * TMDB's answer is kept a day, so a card asks once.
     */
    void markWhere(LinearLayout card, String kind, int id) {
        if (card.getChildCount() > 0 && card.getChildAt(0) instanceof FrameLayout) markWhere((FrameLayout) card.getChildAt(0), kind, id);
    }

    void markWhere(FrameLayout pic, String kind, int id) {
        if (id <= 0 || !("movie".equals(kind) || "tv".equals(kind))) return;
        DiscoverNet.get(act).answer(Discover.providersUrl(kind, id), 24 * DiscoverNet.HOURS, a -> {
            if (a == null || act.isDestroyed()) return;
            Discover.Availability where = Discover.parseAvailability(a.json);
            java.util.Set<String> installed = Watch.installed(act, Watch.packages(where));
            java.util.List<Discover.Provider> apps = Discover.apps(where, installed, 1);
            if (apps.isEmpty()) return;
            Discover.Provider p = apps.get(0);
            FrameLayout corner = new FrameLayout(act);
            GradientDrawable shade = new GradientDrawable();
            shade.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            shade.setColors(new int[]{0x8C000000, 0x40000000, 0x00000000});
            shade.setGradientRadius(px(22));
            corner.setBackground(shade);
            ImageView g = glyph(p);
            if (!installed.contains(Discover.packageFor(p.id))) g.setAlpha(0.6f);
            corner.addView(g, new FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER));
            pic.addView(corner, new FrameLayout.LayoutParams(px(44), px(44), Gravity.TOP | Gravity.END));
        });
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
            fill(img, path, "w342", px(w));
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
            fill(img, photo, "w185", px(size));
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
    /** A checkmark for a sheet row's right end: shown when chosen, nothing (but its space) when not. */
    FrameLayout tick(boolean on) {
        FrameLayout box = new FrameLayout(act);
        ImageView check = new ImageView(act);
        check.setTag("check");
        check.setImageResource(R.drawable.ic_lg_check);
        box.addView(check, new FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER));
        box.setMinimumWidth(px(22));
        box.setMinimumHeight(px(22));
        setTick(box, on);
        return box;
    }

    void setTick(FrameLayout box, boolean on) {
        box.findViewWithTag("check").setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        box.setTag(on);
    }

    static boolean isTicked(FrameLayout box) {
        return Boolean.TRUE.equals(box.getTag());
    }

    /** A shelf's title. */
    /** A section heading in Home's style: small capitals, spaced out, soft white (like NEW THIS WEEK). */
    TextView shelfTitle(String s) {
        TextView t = text(s.toUpperCase(java.util.Locale.getDefault()), 13, R.string.font_medium, 0xB8FFFFFF);
        t.setLetterSpacing(0.14f);
        return t;
    }

    /**
     * A sideways row that reaches out over the page's side padding ({@code left}, {@code right} design units), so a
     * focused first or last item grows without being cut at the padding's edge. Add it with {@link #bleed}.
     */
    android.widget.HorizontalScrollView sideways(LinearLayout row, float left, float right) {
        android.widget.HorizontalScrollView hs = new Rail(act, px(56));
        hs.setHorizontalScrollBarEnabled(false);
        hs.setHorizontalFadingEdgeEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(row.getPaddingLeft() + px(left), row.getPaddingTop(), row.getPaddingRight() + px(right), row.getPaddingBottom());
        hs.addView(row);
        return hs;
    }

    /**
     * A row's scroller that keeps the focused item whole on screen, {@code edge} in from either side, so a grown
     * focused card is never cut and the next one peeks in (as tvOS rows do); the last item stops at the same margin.
     */
    static final class Rail extends android.widget.HorizontalScrollView {
        private final int edge;

        Rail(android.content.Context c, int edge) {
            super(c);
            this.edge = edge;
            setFocusable(false);   // only its items take focus, never the scroller itself (an invisible focus)
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) {
            return keepInside(r.left, r.right, getScrollX(), getWidth(), getChildCount() == 0 ? 0 : getChildAt(0).getWidth(), edge);
        }

        @Override
        public View focusSearch(View focused, int direction) {
            return staySideways(this, focused, direction, super.focusSearch(focused, direction));
        }
    }

    /**
     * A row (a grid's line of cards, a row of buttons) where Left and Right never leave it: at the first or last
     * item focus stays put instead of jumping to whatever is nearest in the row above or below.
     */
    static final class Row extends LinearLayout {
        private final boolean leftOut;

        Row(android.content.Context c, boolean leftOut) {
            super(c);
            this.leftOut = leftOut;
        }

        @Override
        public View focusSearch(View focused, int direction) {
            View next = super.focusSearch(focused, direction);
            if (leftOut && direction == View.FOCUS_LEFT) return next;   // Left may leave, to a sidebar
            return staySideways(this, focused, direction, next);
        }
    }

    /** A {@link Row}. */
    LinearLayout row() {
        return new Row(act, false);
    }

    /** A {@link Row} whose Left may leave it (a grid beside a sidebar): only Right stays at the end. */
    LinearLayout rowOpenLeft() {
        return new Row(act, true);
    }

    /** Left or Right that would leave {@code group}: stay on {@code focused}. Other moves go to {@code next}. */
    static View staySideways(ViewGroup group, View focused, int direction, View next) {
        if (direction != View.FOCUS_LEFT && direction != View.FOCUS_RIGHT) return next;
        for (View v = next; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == group) return next;
        }
        return focused;
    }

    /** A page that scrolls up and down the same way: the focused row stays whole, {@code edge} in from top and bottom. */
    static final class Page extends android.widget.ScrollView {
        private final int edge;

        Page(android.content.Context c, int edge) {
            super(c);
            this.edge = edge;
            setFocusable(false);   // only its items take focus, never the scroller itself (an invisible focus)
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) {
            return keepInside(r.top, r.bottom, getScrollY(), getHeight(), getChildCount() == 0 ? 0 : getChildAt(0).getHeight(), edge);
        }
    }

    /** A vertical page scroller for Discover's screens (see {@link Page}). */
    android.widget.ScrollView page() {
        return new Page(act, px(48));
    }

    /**
     * How far to scroll so the span [{@code start}, {@code end}] sits at least {@code edge} inside a window of
     * {@code size} now at {@code at}, never past the content's ends.
     */
    static int keepInside(int start, int end, int at, int size, int content, int edge) {
        if (size <= 0) return 0;
        int lo = at + edge, hi = at + size - edge, d = 0;
        if (end > hi) d = end - hi;
        if (start - d < lo) d = start - lo;
        int max = Math.max(0, content - size);
        return Math.max(-at, Math.min(max - at, d));
    }

    /** Full-width layout for a {@link #sideways} row, pulled out over the same padding. */
    LinearLayout.LayoutParams bleed(float left, float right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = -px(left);
        lp.rightMargin = -px(right);
        return lp;
    }

    /**
     * An app to watch on, as just its symbol from Home's dock (no button around it): focused, it grows and
     * brightens.
     */
    View appSymbol(Discover.Provider p, boolean installed, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(act);
        ImageView glyph = new ImageView(act);
        glyph.setImageDrawable(Glyphs.get(act, Discover.packageFor(p.id), p.name));
        b.addView(glyph, new FrameLayout.LayoutParams(px(34), px(34), Gravity.CENTER));
        b.setContentDescription(act.getString(installed ? R.string.dc_watch_on : R.string.dc_get, p.name));
        // app symbols stay bare even when focused (no pill): they grow, brighten and a soft light glows behind them
        b.setFocusable(true);
        b.setClickable(true);
        b.setAlpha(0.7f);
        b.setOnFocusChangeListener((x, has) -> {
            x.setBackground(has ? halo(24) : null);
            x.animate().alpha(has ? 1f : 0.7f).scaleX(has ? 1.3f : 1f).scaleY(has ? 1.3f : 1f).setDuration(ms(180)).start();
        });
        b.setOnClickListener(click);
        return b;
    }

    /** A soft round light, brightest in the middle and gone at {@code radius}: the focus behind a bare symbol. */
    Drawable halo(float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{0x52FFFFFF, 0x1FFFFFFF, 0x00FFFFFF});
        g.setGradientRadius(px(radius));
        return g;
    }

    /** An app's bare symbol from Home's dock, not focusable: for "Also on" and the corner of a title card. */
    ImageView glyph(Discover.Provider p) {
        ImageView img = new ImageView(act);
        img.setImageDrawable(Glyphs.get(act, Discover.packageFor(p.id), p.name));
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setContentDescription(p.name);
        return img;
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
        fill(img, p.logo, "w92", px(side));
        return box;
    }
}

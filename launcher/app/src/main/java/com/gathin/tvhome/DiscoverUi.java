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

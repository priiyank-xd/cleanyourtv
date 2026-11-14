package com.gathin.tvhome;

import android.Manifest;
import android.app.SearchManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The LG-style home (config home_layout "lg"): a feature card, quick settings, an art clock, a square widget, the
 * apps in a glass band that runs off the right edge, and a wide widget of recently opened apps. Drawn in a 960x540
 * design grid (the concept's), scaled to the screen. MainActivity hands its lifecycle and keys here and skips the
 * classic grid entirely, so turning the switch off brings the classic home back unchanged.
 */
final class LgHome {
    // geometry from the locked concept, in 960x540 design units
    private static final float BAND_X = 306, BAND_Y = 330, BAND_W = 700, BAND_H = 108;
    private static final float SZ = 70, STEP = 90;
    /** The square every app glyph is drawn in, inside its 70-unit circle (Glyphs keeps them one optical size). */
    private static final float GLYPH = 50;
    private static final int VISIBLE = 7, QUICK = 5, MAX_CHIPS = 4;
    private static final long SLIDE_MS = 6000;
    private static final int GLASS_TOP = 0x24FFFFFF, GLASS_BOTTOM = 0x0DFFFFFF, GLASS_EDGE = 0x33FFFFFF;
    /** The dark base under every glass surface on Home: 40% opaque, so glass is 60% see-through. */
    static final int GLASS_TINT = 0x6610141E;
    /** Focused glass: brighter, with a bright edge. */
    private static final int FOCUS_GLASS_TOP = 0x75FFFFFF, FOCUS_GLASS_BOTTOM = 0x2EFFFFFF, FOCUS_GLASS_EDGE = 0xD9FFFFFF;

    private final MainActivity act;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private float u;
    private Config cfg;
    private FrameLayout root;
    /** The background (picture or movie art, its dim and credit), first child of root. */
    private FrameLayout bgLayer;
    private Bitmap wallpaper;
    private List<LgApps.App> apps = new ArrayList<>();
    /** One page of the feature card: an app (last opened or a favourite) or a trending title. */
    private static final class Slide {
        final LgApps.App app;
        final Tmdb.Title title;
        final int rank;
        final boolean cont;

        Slide(LgApps.App app, Tmdb.Title title, int rank, boolean cont) {
            this.app = app;
            this.title = title;
            this.rank = rank;
            this.cont = cont;
        }
    }

    private static final long NEW_MS = 5000;
    /** Fork: peek mode of the New this week card (OK opens it): the card magnified over a blurred, dimmed Home. */
    private boolean newOpen;
    private View newScrim;
    private final List<Slide> slides = new ArrayList<>();
    private final List<View> newViews = new ArrayList<>();
    private final List<View> newDots = new ArrayList<>();
    private final Map<String, Bitmap> art = new HashMap<>();
    private TmdbFeed feed;
    private int newIndex;
    /** lg_background "movie_art": TMDB backdrops behind Home, a new one every artMs (Settings menu, 1 min default). */
    private long artMs = 60 * 1000L;
    /** The settings revision cfg was loaded at; the Settings menu bumps it. */
    private long cfgRevision;
    private boolean movieArt, artDecoding;
    private Bitmap artBitmap;
    private String artKey, shownArtKey;
    private final Runnable artTick = new Runnable() {
        @Override public void run() {
            List<Tmdb.Title> titles = feed.artTitles();
            if (resumed && !titles.isEmpty()
                    && !titles.get(Tmdb.artIndex(System.currentTimeMillis(), artMs, titles.size())).backdrop.equals(artKey)) {
                refreshBackground();
            }
            handler.postDelayed(this, untilNextArt());
        }
    };
    private final List<View> slideViews = new ArrayList<>();
    private final List<View> slideDots = new ArrayList<>();
    private final List<LgApps.App> chips = new ArrayList<>();
    /** Fork: the last-watched chip's artwork, decoded again only when the title changes. */
    private String lastWatchedKey;
    private Bitmap lastWatchedArt;
    private final Map<String, View> focusables = new HashMap<>();
    private final Map<String, Float> radii = new HashMap<>();
    private LinearLayout strip;
    /** Fork: the app band, and the label above it that shows while reorder mode is on. */
    private View bandView, reorderChip;
    private TextView reorderChipText;
    private FrameLayout feature, chipRow;
    /** The halo behind the focused card (cards clip to their outline, so it can't be their own foreground). */
    private View glowView;
    private final View[] quick = new View[QUICK];
    private String focus = "a0";
    private int slide, first;
    private boolean resumed, okDown;
    /** Fork: reorder mode, started from the circle after the last app; moving is the app being carried, or -1. */
    private boolean reordering;
    private int moving = -1;
    /** Reload the app list on the next build: set at start and whenever an app is installed, removed or updated. */
    private boolean appsDirty = true;
    private final java.util.Set<String> decoding = new java.util.HashSet<>();
    private final BroadcastReceiver packageWatch = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            appsDirty = true;
            if (resumed) build();
        }
    };

    private final Runnable advance = new Runnable() {
        @Override public void run() {
            if (!focus.equals("feat") && slides.size() > 1) showSlide(slide + 1);
            handler.postDelayed(this, SLIDE_MS);
        }
    };

    private final Runnable rotateNew = new Runnable() {
        @Override public void run() {
            if (!focus.equals("w1") && newViews.size() > 1) showNew(newIndex + 1);
            handler.postDelayed(this, NEW_MS);
        }
    };

    LgHome(MainActivity act) {
        this.act = act;
    }

    // ---- lifecycle, called by MainActivity ----

    void onCreate() {
        cfg = Config.load(act);
        cfgRevision = revision();
        artMs = Config.lgArtMinutes(act) * 60_000L;
        int w = act.getResources().getDisplayMetrics().widthPixels;
        u = w / 960f;
        root = new FrameLayout(act);
        root.setBackgroundColor(0xFF07070A);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setClipChildren(false);
        act.setContentView(root);
        wallpaper = loadWallpaper(cfg.wallpaper, w);
        feed = new TmdbFeed(act, BuildConfig.TMDB_TOKEN);
        movieArt = Config.LG_MOVIE_ART.equals(Config.lgBackground(act));
        feed.wantArt(movieArt);
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        act.registerReceiver(packageWatch, packages);
        if (Build.VERSION.SDK_INT >= 31 && act.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 41);
        }
        // The first build happens in onResume, which always follows.
    }

    void onResume() {
        resumed = true;
        // Choices may have changed in the Settings menu or Launcher settings while Home was hidden.
        if (revision() != cfgRevision) {
            cfg = Config.load(act);
            cfgRevision = revision();
        }
        artMs = Config.lgArtMinutes(act) * 60_000L;
        boolean wantArt = Config.LG_MOVIE_ART.equals(Config.lgBackground(act));
        if (wantArt != movieArt) {
            movieArt = wantArt;
            feed.wantArt(wantArt);
            if (!wantArt) { artBitmap = null; artKey = null; shownArtKey = null; }
        }
        build();
        handler.removeCallbacks(advance);
        handler.postDelayed(advance, SLIDE_MS);
        handler.removeCallbacks(rotateNew);
        handler.postDelayed(rotateNew, NEW_MS);
        root.requestFocus();
        List<String> pkgs = new ArrayList<>();
        for (LgApps.App a : apps) pkgs.add(a.pkg);
        feed.refreshIfStale(pkgs, () -> { if (resumed) build(); });
        if (movieArt) {
            feed.ensureArt(() -> { if (resumed) refreshBackground(); });
            handler.removeCallbacks(artTick);
            handler.postDelayed(artTick, untilNextArt());
        }
    }

    /** Wakes just after the clock moves to the next backdrop, so each one shows its full turn. */
    private long untilNextArt() {
        return artMs - System.currentTimeMillis() % artMs + 250;
    }

    void onPause() {
        closeNew(false);
        resumed = false;
        handler.removeCallbacks(artTick);
        handler.removeCallbacks(advance);
        handler.removeCallbacks(rotateNew);
    }

    void onNewIntent() {
        // Home pressed (from an app, or while Home is showing): back to the start, like a fresh visit.
        if (reordering) endReorder();
        closeNew(false);
        setFocus("feat");
        showSlide(0);
        first = 0;   // and the app band back to its first apps
        if (strip != null) strip.setTranslationX(0);
    }

    void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            act.unregisterReceiver(packageWatch);
        } catch (IllegalArgumentException ignored) {
        }
        feed.shutdown();
    }

    boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        char dir = code == KeyEvent.KEYCODE_DPAD_LEFT ? LgNav.LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT ? LgNav.RIGHT
                : code == KeyEvent.KEYCODE_DPAD_UP ? LgNav.UP : code == KeyEvent.KEYCODE_DPAD_DOWN ? LgNav.DOWN : 0;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (reordering && code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) endReorder();
            return true;
        }
        if (newOpen && code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) closeNew(true);
            return true;
        }
        if (dir == 0 && !ok) return false;
        if (ok) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) okDown = true;
            else if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 1 && okDown && focus.equals("top1")) {
                okDown = false;
                openSettings(true);
            } else if (e.getAction() == KeyEvent.ACTION_UP && okDown) {
                okDown = false;
                activate();
            }
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (newOpen) {   // peek mode: Left/Right page through the titles; Up/Down do nothing
            if (dir == LgNav.LEFT || dir == LgNav.RIGHT) showNew(newIndex + (dir == LgNav.LEFT ? -1 : 1));
            return true;
        }
        if (reordering) { reorderMove(dir); return true; }
        if (focus.equals("feat") && dir == LgNav.LEFT && slide > 0) { showSlide(slide - 1); return true; }
        if (focus.equals("feat") && dir == LgNav.RIGHT && slide < slides.size() - 1) { showSlide(slide + 1); return true; }
        setFocus(LgNav.next(focus, dir, bandCount(), chips.size(), QUICK, first));
        return true;
    }

    /** Apps in the band plus the reorder circle after them (no circle when there are no apps). */
    private int bandCount() {
        return apps.isEmpty() ? 0 : apps.size() + 1;
    }

    // ---- reordering (fork) ----

    private void startReorder() {
        reordering = true;
        moving = -1;
        setFocus("a0");
        reorderLook();
    }

    private void endReorder() {
        reordering = false;
        moving = -1;
        build();   // the feature card and chips follow the new order too
    }

    /** OK on an app in reorder mode: pick it up, or put it down. */
    private void toggleMoving(int n) {
        View v = focusables.get("a" + n);
        if (moving >= 0) {
            lift(focusables.get("a" + moving), false);
            paint(focus, v, true);   // back to plain focused glass
        }
        moving = moving == n ? -1 : n;
        if (moving >= 0) lift(v, true);
        reorderLook();
    }

    /**
     * Reorder mode's look: a white label above the band, a bright band edge, every app but the focused one dimmed,
     * the circle as a white Done tick, and the carried app solid white with arrows either side. Off: all back.
     */
    private void reorderLook() {
        if (strip == null) return;
        bandView.setBackground(bandBackground(reordering));
        reorderChip.setVisibility(reordering ? View.VISIBLE : View.GONE);
        if (reordering) {
            reorderChipText.setText(moving >= 0 ? act.getString(R.string.lg_reorder_moving, apps.get(moving).label)
                    : act.getString(R.string.lg_reorder_on));
        }
        for (int i = 0; i < apps.size(); i++) {
            View v = focusables.get("a" + i);
            if (v == null) continue;
            v.setAlpha(!reordering || focus.equals("a" + i) ? 1f : 0.45f);
            if (i == moving) {
                v.setBackground(solidCircle());
                v.setForeground(new Arrows());
            }
        }
        String key = "a" + apps.size();
        View circle = focusables.get(key);
        if (circle instanceof ViewGroup && ((ViewGroup) circle).getChildAt(0) instanceof ImageView) {
            ImageView ic = (ImageView) ((ViewGroup) circle).getChildAt(0);
            ic.setImageResource(reordering ? R.drawable.ic_lg_check : R.drawable.ic_lg_reorder);
            if (reordering) {
                circle.setBackground(solidCircle());
                ic.setColorFilter(0xFF15171D);
            } else {
                ic.clearColorFilter();
            }
        }
    }

    private static GradientDrawable solidCircle() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(0xFFFFFFFF);
        return d;
    }

    /** The reorder circle at rest: no fill, a dashed white ring, so it reads as a tool rather than an app. */
    private GradientDrawable dashedCircle() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setStroke(Math.max(1, px(1.5f)), 0x8CFFFFFF, px(5), px(4));
        return d;
    }

    /** The band's glass; with a bright edge while reorder mode is on. */
    private Drawable bandBackground(boolean bright) {
        float r = px(54);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{GLASS_TOP, GLASS_BOTTOM});
        g.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        g.setStroke(bright ? focusEdge() : Math.max(1, px(1)), bright ? FOCUS_GLASS_EDGE : GLASS_EDGE);
        GradientDrawable base = new GradientDrawable();
        base.setColor(GLASS_TINT);
        base.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        return new LayerDrawable(new Drawable[]{base, g});
    }

    /** Chevrons just outside the carried app's circle, left and right: it moves with Left and Right. */
    private final class Arrows extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Arrows() {
            paint.setColor(0xFFFFFFFF);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2, px(2)));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float cy = b.exactCenterY(), h = px(5), w = px(3), gap = px(5);
            Path p = new Path();
            float l = b.left - gap - w;
            p.moveTo(l + w, cy - h);
            p.lineTo(l, cy);
            p.lineTo(l + w, cy + h);
            float r = b.right + gap;
            p.moveTo(r, cy - h);
            p.lineTo(r + w, cy);
            p.lineTo(r, cy + h);
            c.drawPath(p, paint);   // outside the bounds on purpose: the strip doesn't clip its circles
        }

        @Override public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private void lift(View v, boolean up) {
        if (v == null) return;
        v.animate().scaleX(up ? 1.26f : 1.14f).scaleY(up ? 1.26f : 1.14f).translationY(up ? -px(10) : 0)
                .setDuration(ms(180)).start();
    }

    /** Left/Right in reorder mode: move the carried app one place, or move focus along the band. */
    private void reorderMove(char dir) {
        if (dir != LgNav.LEFT && dir != LgNav.RIGHT) return;
        int n = LgNav.parse(focus);
        int m = dir == LgNav.LEFT ? n - 1 : n + 1;
        if (moving < 0) {
            if (m >= 0 && m < bandCount()) {
                setFocus("a" + m);
                reorderLook();
            }
            return;
        }
        if (m < 0 || m >= apps.size()) return;
        java.util.Collections.swap(apps, n, m);
        View carried = strip.getChildAt(n), other = strip.getChildAt(m);
        strip.removeView(carried);
        strip.addView(carried, m);
        register("a" + m, carried, -1);
        register("a" + n, other, -1);
        moving = m;
        focus = "a" + m;
        scrollBand(m);
        LgApps.saveOrder(act, apps);
        reorderLook();
    }

    // ---- building ----

    private void build() {
        if (appsDirty) {
            apps = LgApps.load(act, cfg);
            appsDirty = false;
        } else {
            for (LgApps.App a : apps) a.lastOpened = LastOpened.get(act, a.pkg);
        }
        // 8. keep the band's scroll inside the list, which may have shrunk
        first = Math.max(0, Math.min(first, bandCount() - VISIBLE));
        moving = -1;   // a rebuild drops an app being carried in reorder mode
        pruneArt();
        focusables.clear();
        radii.clear();
        root.removeAllViews();
        bgLayer = new FrameLayout(act);
        root.addView(bgLayer, new FrameLayout.LayoutParams(-1, -1));
        background();
        header();
        featureCard();
        quickSettings();
        artClock();
        squareWidget();
        appBand();
        wideWidget();
        if (!focusables.containsKey(focus)) focus = apps.isEmpty() ? "feat" : "a0";
        String keep = focus;
        focus = "";
        setFocus(keep);
        if (reordering) reorderLook();
        if (newOpen) applyNewOpen(false);   // a rebuild (artwork landing, feed refresh) keeps peek mode open
    }

    /** Swaps only the background (movie art changed), so the cards and the focused item are not rebuilt. */
    private void refreshBackground() {
        if (bgLayer == null) { build(); return; }
        bgLayer.removeAllViews();
        background();
    }

    private void background() {
        if (movieArt && movieArtBackground()) return;
        if (wallpaper != null) {
            ImageView bg = new ImageView(act);
            bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            bg.setImageBitmap(wallpaper);
            bgLayer.addView(bg, new FrameLayout.LayoutParams(-1, -1));
            View dim = new View(act);
            dim.setBackgroundColor(Color.argb(Math.round(255 * Math.min(90, Math.max(0, cfg.wallpaperDim)) / 100f), 0, 0, 0));
            bgLayer.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        } else {
            View bg = new View(act);
            bg.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF15161D, 0xFF07070A}));
            bgLayer.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    /**
     * The movie-art background: the current TMDB backdrop, darkened at least 40% so the glass cards stay readable,
     * fading in when it changes; no credit line on Home. False while there is nothing to show yet (no
     * titles, or the first picture still decoding), so the theme's picture stands in.
     */
    private boolean movieArtBackground() {
        List<Tmdb.Title> titles = feed.artTitles();
        if (titles.isEmpty()) return false;
        Tmdb.Title want = titles.get(Tmdb.artIndex(System.currentTimeMillis(), artMs, titles.size()));
        if (!want.backdrop.equals(artKey) && !artDecoding) {
            artDecoding = true;
            String key = want.backdrop;
            feed.decodeArt(want, act.getResources().getDisplayMetrics().widthPixels, b -> {
                artDecoding = false;
                if (b == null) return;
                artBitmap = b;
                artKey = key;
                if (resumed) refreshBackground();
            });
        }
        if (artBitmap == null) return false;
        ImageView bg = new ImageView(act);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setImageBitmap(artBitmap);
        bgLayer.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        if (!artKey.equals(shownArtKey)) {
            bg.setAlpha(0f);
            bg.animate().alpha(1f).setDuration(ms(1200)).start();
            shownArtKey = artKey;
        }
        View dim = new View(act);
        dim.setBackgroundColor(Color.argb(Math.round(255 * Math.min(90, Math.max(40, cfg.wallpaperDim)) / 100f), 0, 0, 0));
        bgLayer.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        return true;
    }

    private void header() {
        // The date only: the time is shown once, on the Art mode card.
        TextClock date = new TextClock(act);
        date.setFormat12Hour("EEEE  d MMMM");
        date.setFormat24Hour("EEEE  d MMMM");
        style(date, 13, R.string.font_regular, 0xCCFFFFFF);
        date.setAllCaps(true);
        date.setLetterSpacing(0.12f);
        place(root, date, 40, 36, -1, -1);
        iconButton("top0", R.drawable.ic_lg_search, 790, 22);
        iconButton("top1", R.drawable.ic_settings, 840, 22);
        iconButton("top2", R.drawable.ic_lg_profile, 890, 22);   // fork: Google account / profiles
    }

    private void iconButton(String key, int icon, float x, float y) {
        FrameLayout b = new FrameLayout(act);
        b.setBackground(glass(20, true));
        ImageView i = new ImageView(act);
        i.setImageResource(icon);
        b.addView(i, centered(21, 21));
        place(root, b, x, y, 40, 40);
        register(key, b, 20);
    }

    private void featureCard() {
        slides.clear();
        slideViews.clear();
        slideDots.clear();
        List<LgApps.App> picked = new ArrayList<>();
        List<LgApps.App> recent = LgApps.recent(apps, 1);
        if (!recent.isEmpty()) picked.add(recent.get(0));
        for (String pkg : cfg.dockApps) {
            if (picked.size() >= 3) break;
            for (LgApps.App a : apps) if (a.pkg.equals(pkg) && !picked.contains(a)) picked.add(a);
        }
        for (LgApps.App a : apps) if (picked.size() < 3 && !picked.contains(a)) picked.add(a);
        for (int i = 0; i < picked.size(); i++) slides.add(new Slide(picked.get(i), null, 0, i == 0 && !recent.isEmpty()));
        List<Tmdb.Title> trend = feed.trending();
        for (int i = 0; i < trend.size(); i++) slides.add(new Slide(null, trend.get(i), i + 1, false));
        if (slide >= slides.size()) slide = 0;
        feature = new FrameLayout(act);
        feature.setClipToOutline(true);
        feature.setBackground(round(0xFF15161D, 18));
        place(root, feature, 40, 76, 572, 236);
        register("feat", feature, 18);
        for (int i = 0; i < slides.size(); i++) {
            Slide sl = slides.get(i);
            View v = sl.title != null ? titleSlideView(sl.title, sl.rank) : slideView(sl.app, sl.cont);
            v.setAlpha(i == slide ? 1f : 0f);
            feature.addView(v, new FrameLayout.LayoutParams(-1, -1));
            slideViews.add(v);
        }
        LinearLayout dots = new LinearLayout(act);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; slides.size() > 1 && i < slides.size(); i++) {
            View d = new View(act);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(i == slide ? 20 : 6), px(6));
            lp.rightMargin = px(6);
            dots.addView(d, lp);
            slideDots.add(d);
        }
        place(feature, dots, 30, 214, -1, 6);
        paintDots();
    }

    private View slideView(LgApps.App a, boolean continueSlide) {
        FrameLayout s = new FrameLayout(act);
        if (wallpaper != null) {
            ImageView art = new ImageView(act);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setImageBitmap(wallpaper);
            s.addView(art, new FrameLayout.LayoutParams(-1, -1));
        }
        View wash = new View(act);
        wash.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xED0A080C, (a.color & 0x00FFFFFF) | 0x4D000000, 0x1A0A080C}));
        s.addView(wash, new FrameLayout.LayoutParams(-1, -1));
        TextView kind = text(act.getString(continueSlide ? R.string.lg_continue : R.string.lg_favourite), 12, R.string.font_medium, 0xB3FFFFFF);
        kind.setAllCaps(true);
        kind.setLetterSpacing(0.16f);
        place(s, kind, 30, 22, 400, -1);
        ImageView logo = new ImageView(act);
        logo.setImageDrawable(fresh(a.art));
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        place(s, logo, 28, 44, 190, 54);
        String title = continueSlide ? act.getString(R.string.lg_continue_title) : act.getString(R.string.lg_favourite_title);
        place(s, text(title, 24, R.string.font_light, 0xFFFFFFFF), 30, 112, 500, -1);
        String sub = a.lastOpened > 0 ? act.getString(R.string.lg_last_opened, LastOpened.describe(act, a.lastOpened)) : a.label;
        place(s, text(sub, 14, R.string.font_regular, 0xB8FFFFFF), 30, 146, 500, -1);
        LinearLayout btn = new LinearLayout(act);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setBackground(glass(20, false));
        btn.setPadding(px(14), 0, px(18), 0);
        ImageView play = new ImageView(act);
        play.setImageResource(R.drawable.ic_lg_play);
        btn.addView(play, new LinearLayout.LayoutParams(px(18), px(18)));
        // The button says what it does: it opens the app (it can't resume a title), on every app slide alike.
        TextView label = text(act.getString(R.string.lg_open_app, a.label), 14, R.string.font_medium, 0xFFFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = px(8);
        btn.addView(label, lp);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, px(40), Gravity.END | Gravity.BOTTOM);
        bp.rightMargin = px(24);
        bp.bottomMargin = px(20);
        s.addView(btn, bp);
        return s;
    }

    private View titleSlideView(Tmdb.Title t, int rank) {
        FrameLayout s = new FrameLayout(act);
        Bitmap b = artFor(t, px(572));
        if (b != null) {
            ImageView img = new ImageView(act);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageBitmap(b);
            s.addView(img, new FrameLayout.LayoutParams(-1, -1));
        }
        LgApps.App on = appForProvider(t.provider);
        int color = on != null ? on.color : 0xFF888888;
        View wash = new View(act);
        wash.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF20A080C, (color & 0x00FFFFFF) | 0x40000000, 0x000A080C}));
        s.addView(wash, new FrameLayout.LayoutParams(-1, -1));
        TextView kind = text(act.getString(R.string.lg_trending_rank, rank), 12, R.string.font_medium, 0xB3FFFFFF);
        kind.setAllCaps(true);
        kind.setLetterSpacing(0.16f);
        place(s, kind, 30, 22, 400, -1);
        if (on != null) {
            ImageView logo = new ImageView(act);
            logo.setImageDrawable(fresh(on.art));
            logo.setScaleType(ImageView.ScaleType.FIT_START);
            place(s, logo, 28, 48, 150, 42);
        }
        TextView title = text(t.name, 30, R.string.font_light, 0xFFFFFFFF);
        place(s, title, 30, 104, 420, -1);
        String sub = act.getString("movie".equals(t.kind) ? R.string.lg_movie : R.string.lg_series)
                + (on != null ? " \u00b7 " + act.getString(R.string.lg_on_app, on.label) : "");
        place(s, text(sub, 14, R.string.font_regular, 0xB8FFFFFF), 30, 146, 420, -1);
        LinearLayout btn = new LinearLayout(act);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setBackground(glass(20, false));
        btn.setPadding(px(14), 0, px(18), 0);
        ImageView icon = new ImageView(act);
        icon.setImageResource(R.drawable.ic_lg_search);
        btn.addView(icon, new LinearLayout.LayoutParams(px(18), px(18)));
        TextView label = text(act.getString(R.string.lg_find_it), 14, R.string.font_medium, 0xFFFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = px(8);
        btn.addView(label, lp);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, px(40), Gravity.END | Gravity.BOTTOM);
        bp.rightMargin = px(24);
        bp.bottomMargin = px(20);
        s.addView(btn, bp);
        return s;   // TMDB is credited once, in Launcher settings, not on every card
    }

    private LgApps.App appForProvider(int provider) {
        String pkg = Tmdb.packageFor(provider);
        if (pkg != null) for (LgApps.App a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    /**
     * A title's backdrop if it is decoded already; otherwise null now, and a decode on the feed's worker thread
     * that redraws Home when it lands. Decoded once at the feature card's width and shared with the square widget.
     */
    private Bitmap artFor(Tmdb.Title t, int width) {
        Bitmap b = art.get(t.backdrop);
        if (b == null && decoding.add(t.backdrop)) {
            String key = t.backdrop;
            feed.decode(t, px(572), bitmap -> {
                decoding.remove(key);
                if (bitmap == null) return;
                art.put(key, bitmap);
                if (resumed) build();
            });
        }
        return b;
    }

    /** Drops backdrops of titles that are no longer in either list. */
    private void pruneArt() {
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Tmdb.Title t : feed.newThisWeek()) keep.add(t.backdrop);
        for (Tmdb.Title t : feed.trending()) keep.add(t.backdrop);
        art.keySet().retainAll(keep);
    }

    private void showSlide(int i) {
        if (slides.isEmpty()) return;
        int next = ((i % slides.size()) + slides.size()) % slides.size();
        if (next == slide && slideViews.get(next).getAlpha() == 1f) return;
        slideViews.get(slide).animate().alpha(0f).setDuration(ms(600)).start();
        slideViews.get(next).animate().alpha(1f).setDuration(ms(600)).start();
        slide = next;
        paintDots();
    }

    private void paintDots() {
        for (int i = 0; i < slideDots.size(); i++) {
            View d = slideDots.get(i);
            d.setBackground(round(i == slide ? 0xFFFFFFFF : 0x59FFFFFF, 3));
            ViewGroup.LayoutParams lp = d.getLayoutParams();
            lp.width = px(i == slide ? 20 : 6);
            d.setLayoutParams(lp);
        }
    }

    private void quickSettings() {
        FrameLayout card = new FrameLayout(act);
        card.setClipChildren(false);   // room for a focused button's glow
        card.setBackground(glass(16, false));
        place(root, card, 624, 76, 296, 116);
        TextView title = text(act.getString(R.string.lg_quick_settings), 12, R.string.font_medium, 0x99FFFFFF);
        title.setAllCaps(true);
        title.setLetterSpacing(0.14f);
        place(card, title, 16, 12, -1, -1);
        // Calibration patterns stay in the Settings panel (Projector).
        int[] icons = {R.drawable.ic_wifi, R.drawable.ic_lg_bluetooth, R.drawable.ic_lg_brightness, R.drawable.ic_lg_cast, R.drawable.ic_input};
        int[] labels = {R.string.lg_wifi, R.string.lg_bluetooth, R.string.lg_picture, R.string.lg_cast, R.string.lg_input};
        float gap = (296 - 28 - QUICK * 48) / (float) (QUICK - 1);
        for (int i = 0; i < QUICK; i++) {
            float x = 14 + i * (48 + gap);
            FrameLayout b = new FrameLayout(act);
            b.setBackground(ring(22, 0x1AFFFFFF, 0x29FFFFFF));
            ImageView ic = new ImageView(act);
            ic.setImageResource(icons[i]);
            b.addView(ic, centered(22, 22));
            place(card, b, x + 2, 38, 44, 44);
            // Wi-Fi and Bluetooth show their state: lit when connected / on, dimmed when off.
            boolean off = (i == 0 && !networkUp()) || (i == 1 && !bluetoothOn());
            ic.setAlpha(off ? 0.4f : 1f);
            TextView l = text(act.getString(labels[i]), 11, R.string.font_regular, off ? 0x73FFFFFF : 0xBFFFFFFF);
            l.setGravity(Gravity.CENTER);
            place(card, l, x - 6, 88, 60, -1);
            quick[i] = b;
            register("q" + i, b, 22);
        }
    }

    private void artClock() {
        FrameLayout card = new FrameLayout(act);
        card.setClipToOutline(true);
        card.setBackground(round(0xFF101218, 16));
        if (wallpaper != null) {
            ImageView art = new ImageView(act);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setImageBitmap(wallpaper);
            card.addView(art, new FrameLayout.LayoutParams(-1, -1));
        }
        View wash = new View(act);
        wash.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xBF000000, 0x1A000000}));
        card.addView(wash, new FrameLayout.LayoutParams(-1, -1));
        TextClock clock = new TextClock(act);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        style(clock, 36, R.string.font_light, 0xFFFFFFFF);
        ClockFormat.apply(clock, cfg.clockFormat);   // Home's only clock, so it follows the Clock setting
        place(card, clock, 20, 14, -1, -1);
        place(card, text(act.getString(R.string.lg_art_mode), 13, R.string.font_regular, 0xBFFFFFFF), 22, 62, 260, -1);
        place(root, card, 624, 200, 296, 112);
        register("clock", card, 16);
    }

    private void squareWidget() {
        newViews.clear();
        newDots.clear();
        List<Tmdb.Title> list = feed.newThisWeek();
        if (!list.isEmpty()) {
            newWidget(list);
            return;
        }
        FrameLayout card = new FrameLayout(act);
        card.setClipToOutline(true);
        card.setBackground(glass(18, false));
        TextView kick = text(act.getString(R.string.lg_new_this_week), 11, R.string.font_medium, 0xCCFFFFFF);
        kick.setAllCaps(true);
        kick.setLetterSpacing(0.14f);
        place(card, kick, 16, 14, 220, -1);
        int msg = !feed.enabled() ? R.string.lg_new_this_week_no_key
                : networkUp() ? R.string.lg_new_this_week_loading : R.string.lg_new_this_week_offline;
        TextView body = text(act.getString(msg),
                17, R.string.font_light, 0xE6FFFFFF);
        body.setSingleLine(false);
        body.setMaxLines(3);
        place(card, body, 16, 120, 218, -1);
        place(root, card, 40, 330, 250, 196);
        register("w1", card, 18);
    }

    private void newWidget(List<Tmdb.Title> list) {
        FrameLayout card = new FrameLayout(act);
        card.setClipToOutline(true);
        card.setBackground(round(0xFF101218, 18));
        place(root, card, 40, 330, 250, 196);
        register("w1", card, 18);
        if (newIndex >= list.size()) newIndex = 0;
        for (int i = 0; i < list.size(); i++) {
            Tmdb.Title t = list.get(i);
            FrameLayout s = new FrameLayout(act);
            Bitmap b = artFor(t, px(250));
            if (b != null) {
                ImageView img = new ImageView(act);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setImageBitmap(b);
                s.addView(img, new FrameLayout.LayoutParams(-1, -1));
            }
            View fade = new View(act);
            fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x59000000, 0x00000000, 0xCC000000}));
            s.addView(fade, new FrameLayout.LayoutParams(-1, -1));
            TextView kick = text(act.getString(R.string.lg_new_this_week), 11, R.string.font_medium, 0xCCFFFFFF);
            kick.setAllCaps(true);
            kick.setLetterSpacing(0.14f);
            place(s, kick, 16, 14, 150, -1);
            LgApps.App on = appForProvider(t.provider);
            if (on != null) {
                ImageView logo = new ImageView(act);
                logo.setImageDrawable(fresh(on.art));
                logo.setScaleType(ImageView.ScaleType.FIT_END);
                place(s, logo, 176, 6, 62, 35);
            }
            TextView title = text(t.name, 19, R.string.font_medium, 0xFFFFFFFF);
            title.setGravity(Gravity.END);
            place(s, title, 16, 140, 218, -1);
            TextView sub = text(act.getString("movie".equals(t.kind) ? R.string.lg_movie : R.string.lg_series), 12, R.string.font_regular, 0xB3FFFFFF);
            sub.setGravity(Gravity.END);
            place(s, sub, 16, 166, 218, -1);
            s.setAlpha(i == newIndex ? 1f : 0f);
            card.addView(s, new FrameLayout.LayoutParams(-1, -1));
            newViews.add(s);
        }
        LinearLayout dots = new LinearLayout(act);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; list.size() > 1 && i < list.size(); i++) {
            View d = new View(act);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(5), px(5));
            lp.rightMargin = px(5);
            dots.addView(d, lp);
            newDots.add(d);
        }
        place(card, dots, 16, 178, -1, 5);
        paintNewDots();
    }

    private void showNew(int i) {
        if (newViews.isEmpty()) return;
        int next = ((i % newViews.size()) + newViews.size()) % newViews.size();
        newViews.get(newIndex).animate().alpha(0f).setDuration(ms(600)).start();
        newViews.get(next).animate().alpha(1f).setDuration(ms(600)).start();
        newIndex = next;
        paintNewDots();
    }

    // ---- New this week peek mode (fork) ----

    private void openNew() {
        newOpen = true;
        applyNewOpen(true);
    }

    /**
     * The card magnified 1.64x, to about 410x321 design units with its bottom-left corner near its own (45, 521), over
     * the rest of Home, which is lightly blurred (Android 12+) and dimmed. Also re-applied without animation after a
     * rebuild while open.
     */
    private void applyNewOpen(boolean animate) {
        View card = focusables.get("w1");
        if (card == null || newViews.isEmpty() || !focus.equals("w1")) { newOpen = false; return; }
        removeGlow();
        if (Build.VERSION.SDK_INT >= 31) {
            float r = px(6);
            for (int i = 0; i < root.getChildCount(); i++) {
                View c = root.getChildAt(i);
                if (c != card) c.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
            }
        }
        float z = px(40);
        newScrim = new View(act);
        newScrim.setBackgroundColor(0x80000000);
        newScrim.setTranslationZ(z - px(2));
        root.addView(newScrim, new FrameLayout.LayoutParams(-1, -1));
        card.setTranslationZ(z);
        long d = animate ? ms(300) : 0;
        // Card 250x196 at (40, 330), scaled about its centre (165, 428): centre moved to (250, 360.5).
        card.animate().scaleX(1.64f).scaleY(1.64f).translationX(px(85)).translationY(-px(67.5f)).setDuration(d).start();
        if (animate) {
            newScrim.setAlpha(0f);
            newScrim.animate().alpha(1f).setDuration(d).start();
        }
    }

    private void closeNew(boolean animate) {
        if (!newOpen) return;
        newOpen = false;
        if (Build.VERSION.SDK_INT >= 31) {
            for (int i = 0; i < root.getChildCount(); i++) root.getChildAt(i).setRenderEffect(null);
        }
        root.removeView(newScrim);
        newScrim = null;
        View card = focusables.get("w1");
        if (card == null) return;
        card.setTranslationZ(0);
        card.animate().translationX(0).translationY(0).setDuration(animate ? ms(220) : 0).start();
        if (focus.equals("w1")) paint("w1", card, true);   // back to the focused size, with its glow
    }

    private void paintNewDots() {
        for (int i = 0; i < newDots.size(); i++) {
            View d = newDots.get(i);
            d.setBackground(round(i == newIndex ? 0xFFFFFFFF : 0x59FFFFFF, 3));
            ViewGroup.LayoutParams lp = d.getLayoutParams();
            lp.width = px(i == newIndex ? 16 : 5);
            d.setLayoutParams(lp);
        }
    }

    private void appBand() {
        FrameLayout band = new FrameLayout(act);
        band.setBackground(bandBackground(false));
        place(root, band, BAND_X, BAND_Y, BAND_W, BAND_H);
        bandView = band;
        FrameLayout clip = new FrameLayout(act);
        clip.setClipChildren(true);
        place(band, clip, 10, 0, BAND_W - 10, BAND_H);
        // The strip fills the band's height and is as wide as all its apps, so a focused circle's scale and glow
        // are not cut to a square and apps past the band's width are still drawn when it scrolls.
        strip = new LinearLayout(act);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(px(18), 0, 0, 0);
        for (int i = 0; i < apps.size(); i++) {
            LgApps.App a = apps.get(i);
            FrameLayout c = new FrameLayout(act);
            c.setBackground(appCircle(false));
            ImageView logo = new ImageView(act);
            logo.setImageDrawable(fresh(a.glyph));
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            c.addView(logo, centered(GLYPH, GLYPH));
            c.setContentDescription(a.label);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(SZ), px(SZ));
            lp.rightMargin = px(STEP - SZ);
            strip.addView(c, lp);
            register("a" + i, c, -1);
        }
        // Fork: the reorder circle, always last, as on Google TV. OK starts reorder mode.
        if (!apps.isEmpty()) {
            FrameLayout c = new FrameLayout(act);
            c.setBackground(dashedCircle());
            ImageView ic = new ImageView(act);
            ic.setImageResource(R.drawable.ic_lg_reorder);
            c.addView(ic, centered(30, 30));
            c.setContentDescription(act.getString(R.string.lg_reorder));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(SZ), px(SZ));
            lp.rightMargin = px(STEP - SZ);
            strip.addView(c, lp);
            register("a" + apps.size(), c, -1);
        }
        clip.addView(strip, new FrameLayout.LayoutParams(px(18) + bandCount() * px(STEP), -1));
        strip.setTranslationX(-first * px(STEP));
        // Fork: the reorder-mode label, a white pill on the band's top edge; hidden until reorder mode starts.
        LinearLayout chip = new LinearLayout(act);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(round(0xFFFFFFFF, 11));
        chip.setPadding(px(10), 0, px(12), 0);
        ImageView chipIcon = new ImageView(act);
        chipIcon.setImageResource(R.drawable.ic_lg_reorder);
        chipIcon.setColorFilter(0xFF15171D);
        chip.addView(chipIcon, new LinearLayout.LayoutParams(px(13), px(13)));
        reorderChipText = text("", 11, R.string.font_medium, 0xFF15171D);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = px(6);
        chip.addView(reorderChipText, tp);
        chip.setVisibility(View.GONE);
        place(root, chip, BAND_X + 34, BAND_Y - 11, -1, 22);
        reorderChip = chip;
    }

    private void wideWidget() {
        chips.clear();
        // Fork: the first chip is the title last seen playing (MediaListener), when its app is still on Home.
        LastPlayed played = LastPlayed.load(act);
        LgApps.App playedApp = null;
        if (played != null) for (LgApps.App a : apps) if (a.pkg.equals(played.pkg)) playedApp = a;
        if (playedApp != null) chips.add(playedApp);
        for (LgApps.App a : LgApps.recent(apps, MAX_CHIPS)) if (chips.size() < MAX_CHIPS && !chips.contains(a)) chips.add(a);
        for (LgApps.App a : apps) {
            if (chips.size() < MAX_CHIPS && !chips.contains(a) && !LgApps.isSystemSettings(a.pkg)) chips.add(a);
        }
        chipRow = new FrameLayout(act);
        chipRow.setClipChildren(false);   // room for a focused chip's glow
        place(root, chipRow, 306, 452, 614, 74);
        float cw = (614 - 3 * 10) / 4f;
        for (int i = 0; i < chips.size(); i++) {
            LgApps.App a = chips.get(i);
            FrameLayout chip = new FrameLayout(act);
            chip.setClipToOutline(true);
            chip.setBackground(glass(14, false));
            if (i == 0 && playedApp != null) {
                lastWatchedChip(chip, a, played, cw);
            } else {
                View tint = new View(act);
                tint.setBackgroundColor((a.color & 0x00FFFFFF) | 0x33000000);
                chip.addView(tint, new FrameLayout.LayoutParams(-1, -1));
                ImageView logo = new ImageView(act);
                logo.setImageDrawable(fresh(a.glyph));
                logo.setScaleType(ImageView.ScaleType.FIT_START);
                place(chip, logo, 8, 4, 34, 34);
                // The app's name first (a logo alone isn't always enough), then when it was opened.
                place(chip, text(a.label, 12, R.string.font_medium, 0xFFFFFFFF), 12, a.lastOpened > 0 ? 41 : 53, cw - 22, -1);
                if (a.lastOpened > 0) {
                    place(chip, text(capitalize(LastOpened.describe(act, a.lastOpened)), 10, R.string.font_regular, 0xA6FFFFFF),
                            12, 57, cw - 22, -1);
                }
            }
            place(chipRow, chip, i * (cw + 10), 0, cw, 74);
            register("c" + i, chip, 14);
        }
    }

    /** The last-watched chip: the title's artwork under a dark fade, its app's logo and the title. OK opens the app. */
    private void lastWatchedChip(FrameLayout chip, LgApps.App app, LastPlayed played, float cw) {
        String key = played.pkg + "\n" + played.title + "\n" + LastPlayed.artStamp(act);
        if (!key.equals(lastWatchedKey)) {
            lastWatchedKey = key;
            lastWatchedArt = LastPlayed.art(act);
        }
        if (lastWatchedArt != null) {
            ImageView art = new ImageView(act);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setImageBitmap(lastWatchedArt);
            chip.addView(art, new FrameLayout.LayoutParams(-1, -1));
            View fade = new View(act);
            fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x66000000, 0x1A000000, 0xD9000000}));
            chip.addView(fade, new FrameLayout.LayoutParams(-1, -1));
        } else {
            View tint = new View(act);
            tint.setBackgroundColor((app.color & 0x00FFFFFF) | 0x33000000);
            chip.addView(tint, new FrameLayout.LayoutParams(-1, -1));
        }
        ImageView logo = new ImageView(act);
        logo.setImageDrawable(fresh(app.glyph));
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        place(chip, logo, 8, 4, 28, 28);
        TextView kick = text(act.getString(R.string.lg_last_watched), 8, R.string.font_medium, 0xB3FFFFFF);
        kick.setAllCaps(true);
        kick.setLetterSpacing(0.12f);
        kick.setGravity(Gravity.END);
        place(chip, kick, cw - 92, 9, 82, -1);
        place(chip, text(played.title, 12, R.string.font_medium, 0xFFFFFFFF), 12, 53, cw - 22, -1);
    }

    // ---- focus ----

    private void setFocus(String key) {
        if (key.equals(focus) || !focusables.containsKey(key)) return;
        View old = focusables.get(focus);
        if (old != null) paint(focus, old, false);
        focus = key;
        View now = focusables.get(key);
        paint(key, now, true);
        if (key.startsWith("a")) scrollBand(LgNav.parse(key));
    }

    private void scrollBand(int n) {
        int f = LgNav.firstShown(n, bandCount(), VISIBLE);
        if (f != first) {
            first = f;
            strip.animate().translationX(-first * px(STEP)).setDuration(ms(260)).start();
        }
    }

    private void paint(String key, View v, boolean on) {
        float scale = key.startsWith("a") ? 1.14f : key.startsWith("q") || key.startsWith("top") ? 1.08f
                : key.startsWith("c") ? 1.04f : 1.03f;
        v.animate().scaleX(on ? scale : 1f).scaleY(on ? scale : 1f).setDuration(ms(220)).start();
        v.setElevation(on ? px(8) : 0);
        Float r = radii.get(key);
        if (!on) {
            v.setForeground(null);
            removeGlow();
            if (key.startsWith("a")) v.setBackground(key.equals("a" + apps.size()) ? dashedCircle() : appCircle(false));
            if (key.startsWith("q")) {
                v.setBackground(ring(22, 0x1AFFFFFF, 0x29FFFFFF));
                tint(v, 0xFFFFFFFF);
            }
            if (key.startsWith("top")) { v.setBackground(glass(20, true)); tint(v, 0xFFFFFFFF); }
            return;
        }
        // Focus is glass too: brighter frosted glass with a bright edge, lifted by the scale and elevation above.
        if (key.startsWith("q") || key.startsWith("top")) {
            GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{FOCUS_GLASS_TOP, FOCUS_GLASS_BOTTOM});
            fill.setShape(GradientDrawable.OVAL);
            fill.setStroke(focusEdge(), FOCUS_GLASS_EDGE);
            v.setBackground(fill);
            v.setForeground(new GlowDrawable(-1, px(14), 0, 0x66FFFFFF));
            tint(v, 0xFFFFFFFF);
            return;
        }
        if (key.startsWith("a")) {
            v.setBackground(appCircle(true));
            v.setForeground(new GlowDrawable(-1, px(16), 0, 0x66FFFFFF));
            return;
        }
        // Cards: a thin bright edge and a light sheen across the top, over the card's own picture.
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x33FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        if (r == null || r < 0) sheen.setShape(GradientDrawable.OVAL); else sheen.setCornerRadius(px(r));
        sheen.setStroke(focusEdge(), FOCUS_GLASS_EDGE);
        v.setForeground(sheen);
        glowBehind(v, r == null ? -1 : r, scale);
    }

    /** A glow view just behind a card, as big as the card plus the halo, scaled with it. */
    private void glowBehind(View v, float radius, float scale) {
        removeGlow();
        if (!(v.getParent() instanceof FrameLayout) || !(v.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout parent = (FrameLayout) v.getParent();
        FrameLayout.LayoutParams vl = (FrameLayout.LayoutParams) v.getLayoutParams();
        int pad = px(24);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(vl.width + 2 * pad, vl.height + 2 * pad);
        gl.leftMargin = vl.leftMargin - pad;
        gl.topMargin = vl.topMargin - pad;
        View glow = new View(act);
        glow.setBackground(new GlowDrawable(radius < 0 ? -1 : px(radius), px(20), pad, 0x55FFFFFF));
        parent.addView(glow, parent.indexOfChild(v), gl);
        glow.setScaleX(1f);
        glow.setScaleY(1f);
        glow.animate().scaleX(scale).scaleY(scale).setDuration(ms(220)).start();
        glowView = glow;
    }

    private void removeGlow() {
        if (glowView != null && glowView.getParent() instanceof ViewGroup) ((ViewGroup) glowView.getParent()).removeView(glowView);
        glowView = null;
    }

    /** An app's circle in the band: plain glass, or the brighter focused glass. */
    private GradientDrawable appCircle(boolean focused) {
        GradientDrawable circle = focused
                ? new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x57FFFFFF, 0x1FFFFFFF})
                : new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        if (focused) {
            circle.setStroke(focusEdge(), FOCUS_GLASS_EDGE);
        } else {
            circle.setColor(0x1AFFFFFF);
            circle.setStroke(Math.max(1, px(1)), 0x29FFFFFF);
        }
        return circle;
    }

    private int focusEdge() {
        return Math.max(1, px(1.5f));
    }

    private static void tint(View v, int color) {
        if (v instanceof ViewGroup && ((ViewGroup) v).getChildAt(0) instanceof ImageView) {
            ((ImageView) ((ViewGroup) v).getChildAt(0)).setColorFilter(color);
        }
    }

    private void register(String key, View v, float radius) {
        v.setFocusable(false);
        focusables.put(key, v);
        radii.put(key, radius);
        v.setOnClickListener(x -> { setFocus(key); activate(); });
    }

    // ---- actions ----

    private void activate() {
        if (focus.equals("feat")) {
            if (slides.isEmpty()) return;
            Slide sl = slides.get(slide);
            if (sl.title != null) search(sl.title.name); else LgApps.launch(act, sl.app);
            return;
        }
        if (focus.equals("top0")) { search(null); return; }
        if (focus.equals("top1")) { openSettings(false); return; }
        if (focus.equals("top2")) { start(SystemSettings.profileIntent(act)); return; }
        if (focus.equals("clock")) { start(new Intent(act, ClockActivity.class)); return; }
        if (focus.equals("w1")) {
            // First OK opens peek mode; OK there searches for the title shown.
            List<Tmdb.Title> list = feed.newThisWeek();
            if (list.isEmpty()) return;
            if (!newOpen) { openNew(); return; }
            closeNew(false);
            search(list.get(Math.min(newIndex, list.size() - 1)).name);
            return;
        }
        int n = LgNav.parse(focus);
        if (focus.startsWith("a") && n == apps.size()) { if (reordering) endReorder(); else startReorder(); return; }
        if (reordering) { if (focus.startsWith("a")) toggleMoving(n); return; }
        if (focus.startsWith("a") && n < apps.size()) { LgApps.launch(act, apps.get(n)); return; }
        if (focus.startsWith("c") && n < chips.size()) { LgApps.launch(act, chips.get(n)); return; }
        if (focus.startsWith("q")) {
            if (n == 0) start(SystemSettings.intent(act, Settings.ACTION_WIFI_SETTINGS));
            else if (n == 1) start(SystemSettings.bluetoothIntent(act));
            else if (n == 2) start(SystemSettings.pictureIntent(act));   // picture, brightness
            else if (n == 3) start(SystemSettings.castIntent(act));
            else openInputs();
        }
    }

    /**
     * The TV's input switcher. With handle_view_inputs on, this launcher also answers VIEW_INPUTS (and forwards it
     * back to Home), so pick another app's handler; Settings if the TV has none.
     */
    void openInputs() {
        start(inputsIntent(act));
    }

    static Intent inputsIntent(Context ctx) {
        Intent view = new Intent(MainActivity.ACTION_VIEW_INPUTS);
        for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(view, 0)) {
            if (ri.activityInfo.packageName.equals(ctx.getPackageName())) continue;
            return new Intent(view).setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
        }
        return SystemSettings.intent(ctx, Settings.ACTION_SETTINGS);
    }

    /** Settings on Home: a press opens the Settings menu or Google TV's settings, as chosen; a hold opens the other. */
    private void openSettings(boolean hold) {
        String target = HubChoices.settingsTarget(Config.lgSettingsButton(act), hold);
        if (HubChoices.OPEN_SYSTEM.equals(target)) start(SystemSettings.intent(act, Settings.ACTION_SETTINGS));
        else if (HubChoices.OPEN_LAUNCHER.equals(target)) start(new Intent(act, LauncherSettingsActivity.class));
        else start(new Intent(act, SettingsHubActivity.class));
    }

    private long revision() {
        return act.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getLong(Config.SETTINGS_REVISION_KEY, 0);
    }

    /** Google TV search (Gemini on newer firmware), for a title when one is given: where to watch, ratings, watchlist. */
    private void search(String query) {
        Intent i = new Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH);
        if (query != null) i.putExtra(SearchManager.QUERY, query);
        if (!start(i) && !start(new Intent(Intent.ACTION_ASSIST))) {
            android.widget.Toast.makeText(act, R.string.lg_search_unavailable, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private boolean start(Intent i) {
        if (i == null) return false;
        try {
            act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    // ---- state ----

    private boolean networkUp() {
        ConnectivityManager cm = act.getSystemService(ConnectivityManager.class);
        Network n = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private boolean bluetoothOn() {
        if (Build.VERSION.SDK_INT >= 31 && act.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            BluetoothManager bm = act.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
            return adapter != null && adapter.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    private static Bitmap loadWallpaper(File file, int screenWidth) {
        if (file == null || !file.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= screenWidth) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getPath(), o);
    }

    /** Animation length honouring the launcher's animations and reduced_motion settings. */
    private long ms(long full) {
        if (!cfg.animations) return 0;
        return cfg.reducedMotion ? Math.min(full, Motion.REDUCED_MS) : full;
    }

    /** Frees the decoded backdrops while Home is hidden and Android asks for memory. */
    void onTrimMemory(int level) {
        if (!resumed && level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) art.clear();
    }

    // ---- small view helpers (design units to pixels) ----

    private int px(float units) {
        return Math.round(units * u);
    }

    private void place(ViewGroup parent, View v, float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w < 0 ? -2 : px(w), h < 0 ? -2 : px(h));
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        parent.addView(v, lp);
    }

    private FrameLayout.LayoutParams centered(float w, float h) {
        return new FrameLayout.LayoutParams(px(w), px(h), Gravity.CENTER);
    }

    private TextView text(String s, float size, int font, int color) {
        TextView t = new TextView(act);
        t.setText(s);
        style(t, size, font, color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private void style(TextView t, float size, int font, int color) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
        t.setTypeface(UiFont.get(act, font));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
    }

    /** A glass surface over the background: the dark base (GLASS_TINT) with the light sheen and edge on top. */
    private Drawable glass(float radius, boolean oval) {
        GradientDrawable base = new GradientDrawable();
        base.setColor(GLASS_TINT);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{GLASS_TOP, GLASS_BOTTOM});
        if (oval) {
            base.setShape(GradientDrawable.OVAL);
            g.setShape(GradientDrawable.OVAL);
        } else {
            base.setCornerRadius(px(radius));
            g.setCornerRadius(px(radius));
        }
        g.setStroke(Math.max(1, px(1)), GLASS_EDGE);
        return new LayerDrawable(new Drawable[]{base, g});
    }

    private Drawable ring(float radius, int fill, int edge) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        g.setStroke(Math.max(1, px(1)), edge);
        return g;
    }

    private Drawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(radius));
        return g;
    }

    /** Its own copy of a drawable: one shared between views of different sizes would be drawn at the last size. */
    private Drawable fresh(Drawable d) {
        Drawable.ConstantState cs = d == null ? null : d.getConstantState();
        return cs != null ? cs.newDrawable(act.getResources()) : d;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

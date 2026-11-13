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
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
    private static final int VISIBLE = 7, QUICK = 5, MAX_CHIPS = 4;
    private static final long SLIDE_MS = 6000;
    private static final int GLASS_TOP = 0x24FFFFFF, GLASS_BOTTOM = 0x0DFFFFFF, GLASS_EDGE = 0x33FFFFFF;

    private final MainActivity act;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private float u;
    private Config cfg;
    private FrameLayout root;
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
    private final List<Slide> slides = new ArrayList<>();
    private final List<View> newViews = new ArrayList<>();
    private final List<View> newDots = new ArrayList<>();
    private final Map<String, Bitmap> art = new HashMap<>();
    private TmdbFeed feed;
    private int newIndex;
    /** lg_background "movie_art": TMDB backdrops behind Home, a new one every {@link #ART_MS}. */
    private static final long ART_MS = 20 * 60 * 1000L;
    private boolean movieArt, artDecoding;
    private Bitmap artBitmap;
    private String artKey, shownArtKey;
    private final Runnable artTick = new Runnable() {
        @Override public void run() {
            List<Tmdb.Title> titles = feed.artTitles();
            if (resumed && !titles.isEmpty()
                    && !titles.get(Tmdb.artIndex(System.currentTimeMillis(), ART_MS, titles.size())).backdrop.equals(artKey)) {
                build();
            }
            handler.postDelayed(this, 60_000);
        }
    };
    private final List<View> slideViews = new ArrayList<>();
    private final List<View> slideDots = new ArrayList<>();
    private final List<LgApps.App> chips = new ArrayList<>();
    private final Map<String, View> focusables = new HashMap<>();
    private final Map<String, Float> radii = new HashMap<>();
    private LinearLayout strip;
    private FrameLayout feature, chipRow;
    private final View[] quick = new View[QUICK];
    private String focus = "a0";
    private int slide, first;
    private boolean resumed, okDown;
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
        // Home background may have changed in Launcher settings (or a pushed theme) while Home was hidden.
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
            feed.ensureArt(() -> { if (resumed) build(); });
            handler.removeCallbacks(artTick);
            handler.postDelayed(artTick, 60_000);
        }
    }

    void onPause() {
        resumed = false;
        handler.removeCallbacks(artTick);
        handler.removeCallbacks(advance);
        handler.removeCallbacks(rotateNew);
    }

    void onNewIntent() {
        // Home pressed while Home is showing: back to the start, like a fresh visit.
        setFocus("feat");
        showSlide(0);
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
        if (dir == 0 && !ok) return false;
        if (ok) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) okDown = true;
            else if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 1 && okDown && focus.equals("top1")) {
                okDown = false;
                start(new Intent(act, LauncherSettingsActivity.class));
            } else if (e.getAction() == KeyEvent.ACTION_UP && okDown) {
                okDown = false;
                activate();
            }
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (focus.equals("feat") && dir == LgNav.LEFT && slide > 0) { showSlide(slide - 1); return true; }
        if (focus.equals("feat") && dir == LgNav.RIGHT && slide < slides.size() - 1) { showSlide(slide + 1); return true; }
        setFocus(LgNav.next(focus, dir, apps.size(), chips.size(), QUICK, first));
        return true;
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
        first = Math.max(0, Math.min(first, apps.size() - VISIBLE));
        pruneArt();
        focusables.clear();
        radii.clear();
        root.removeAllViews();
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
    }

    private void background() {
        if (movieArt && movieArtBackground()) return;
        if (wallpaper != null) {
            ImageView bg = new ImageView(act);
            bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            bg.setImageBitmap(wallpaper);
            root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
            View dim = new View(act);
            dim.setBackgroundColor(Color.argb(Math.round(255 * Math.min(90, Math.max(0, cfg.wallpaperDim)) / 100f), 0, 0, 0));
            root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        } else {
            View bg = new View(act);
            bg.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF15161D, 0xFF07070A}));
            root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    /**
     * The movie-art background: the current TMDB backdrop, darkened at least 40% so the glass cards stay readable,
     * fading in when it changes, with a credit line under the date. False while there is nothing to show yet (no
     * titles, or the first picture still decoding), so the theme's picture stands in.
     */
    private boolean movieArtBackground() {
        List<Tmdb.Title> titles = feed.artTitles();
        if (titles.isEmpty()) return false;
        Tmdb.Title want = titles.get(Tmdb.artIndex(System.currentTimeMillis(), ART_MS, titles.size()));
        if (!want.backdrop.equals(artKey) && !artDecoding) {
            artDecoding = true;
            String key = want.backdrop;
            feed.decodeArt(want, act.getResources().getDisplayMetrics().widthPixels, b -> {
                artDecoding = false;
                if (b == null) return;
                artBitmap = b;
                artKey = key;
                if (resumed) build();
            });
        }
        if (artBitmap == null) return false;
        ImageView bg = new ImageView(act);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setImageBitmap(artBitmap);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        if (!artKey.equals(shownArtKey)) {
            bg.setAlpha(0f);
            bg.animate().alpha(1f).setDuration(ms(1200)).start();
            shownArtKey = artKey;
        }
        View dim = new View(act);
        dim.setBackgroundColor(Color.argb(Math.round(255 * Math.min(90, Math.max(40, cfg.wallpaperDim)) / 100f), 0, 0, 0));
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        for (Tmdb.Title t : titles) {
            if (!t.backdrop.equals(artKey)) continue;
            LgApps.App on = appForProvider(t.provider);
            String credit = on != null ? act.getString(R.string.lg_backdrop_credit, t.name, on.label)
                    : act.getString(R.string.lg_backdrop_credit_plain, t.name);
            place(root, text(credit, 11, R.string.font_regular, 0xA6FFFFFF), 40, 60, 560, -1);
            break;
        }
        return true;
    }

    private void header() {
        TextClock time = new TextClock(act);
        time.setFormat12Hour("h:mm a");
        time.setFormat24Hour("HH:mm");
        style(time, 30, R.string.font_light, 0xFFFFFFFF);
        place(root, time, 40, 22, -1, -1);
        TextClock date = new TextClock(act);
        date.setFormat12Hour("EEEE  d MMMM");
        date.setFormat24Hour("EEEE  d MMMM");
        style(date, 13, R.string.font_regular, 0x99FFFFFF);
        date.setAllCaps(true);
        date.setLetterSpacing(0.12f);
        place(root, date, 190, 36, -1, -1);
        iconButton("top0", R.drawable.ic_lg_search, 840, 22);
        iconButton("top1", R.drawable.ic_settings, 890, 22);
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
        logo.setImageDrawable(a.art);
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
        TextView label = text(continueSlide ? act.getString(R.string.lg_continue_button) : act.getString(R.string.lg_open_app, a.label), 14, R.string.font_medium, 0xFFFFFFFF);
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
            logo.setImageDrawable(on.art);
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
        TextView credit = text(act.getString(R.string.lg_tmdb_credit), 10, R.string.font_regular, 0x80FFFFFF);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        cp.rightMargin = px(18);
        cp.topMargin = px(18);
        s.addView(credit, cp);
        return s;
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
        card.setBackground(glass(16, false));
        place(root, card, 624, 76, 296, 116);
        TextView title = text(act.getString(R.string.lg_quick_settings), 12, R.string.font_medium, 0x99FFFFFF);
        title.setAllCaps(true);
        title.setLetterSpacing(0.14f);
        place(card, title, 16, 12, -1, -1);
        int[] icons = {R.drawable.ic_wifi, R.drawable.ic_lg_bluetooth, R.drawable.ic_lg_cast, R.drawable.ic_input, R.drawable.ic_lg_patterns};
        int[] labels = {R.string.lg_wifi, R.string.lg_bluetooth, R.string.lg_mirror, R.string.lg_input, R.string.lg_patterns};
        boolean[] on = {networkUp(), bluetoothOn(), false, false, false};
        float gap = (296 - 28 - QUICK * 48) / (float) (QUICK - 1);
        for (int i = 0; i < QUICK; i++) {
            float x = 14 + i * (48 + gap);
            FrameLayout b = new FrameLayout(act);
            b.setBackground(on[i] ? ring(22, 0x476EC1FF, 0xB36EC1FF) : ring(22, 0x1AFFFFFF, 0x29FFFFFF));
            ImageView ic = new ImageView(act);
            ic.setImageResource(icons[i]);
            b.addView(ic, centered(22, 22));
            place(card, b, x + 2, 38, 44, 44);
            TextView l = text(act.getString(labels[i]), 11, R.string.font_regular, 0xBFFFFFFF);
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
        place(card, clock, 20, 14, -1, -1);
        place(card, text(act.getString(R.string.lg_art_mode), 13, R.string.font_regular, 0xBFFFFFFF), 22, 62, 260, -1);
        place(card, text(act.getString(R.string.lg_ok_to_start), 12, R.string.font_regular, 0x80FFFFFF), 22, 82, 260, -1);
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
        TextView body = text(act.getString(feed.enabled() ? R.string.lg_new_this_week_loading : R.string.lg_new_this_week_no_key),
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
            place(s, text(act.getString(R.string.lg_tmdb_credit), 9, R.string.font_regular, 0x80FFFFFF), 16, 30, 150, -1);
            LgApps.App on = appForProvider(t.provider);
            if (on != null) {
                ImageView logo = new ImageView(act);
                logo.setImageDrawable(on.art);
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
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{GLASS_TOP, GLASS_BOTTOM});
        float r = px(54);
        g.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        g.setStroke(Math.max(1, px(1)), GLASS_EDGE);
        band.setBackground(g);
        place(root, band, BAND_X, BAND_Y, BAND_W, BAND_H);
        FrameLayout clip = new FrameLayout(act);
        clip.setClipChildren(true);
        place(band, clip, 22, 0, BAND_W - 22, BAND_H);
        strip = new LinearLayout(act);
        strip.setClipChildren(false);
        strip.setPadding(px(6), 0, 0, 0);
        for (int i = 0; i < apps.size(); i++) {
            LgApps.App a = apps.get(i);
            FrameLayout c = new FrameLayout(act);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(0x1AFFFFFF);
            circle.setStroke(Math.max(1, px(1)), 0x29FFFFFF);
            c.setBackground(circle);
            ImageView logo = new ImageView(act);
            logo.setImageDrawable(a.art);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            c.addView(logo, centered(SZ * 0.94f, SZ * 0.94f * 0.5625f));
            c.setContentDescription(a.label);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(SZ), px(SZ));
            lp.rightMargin = px(STEP - SZ);
            strip.addView(c, lp);
            register("a" + i, c, -1);
        }
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, px(SZ));
        sp.topMargin = px((BAND_H - SZ) / 2);
        clip.addView(strip, sp);
        strip.setTranslationX(-first * px(STEP));
    }

    private void wideWidget() {
        chips.clear();
        chips.addAll(LgApps.recent(apps, MAX_CHIPS));
        for (LgApps.App a : apps) if (chips.size() < MAX_CHIPS && !chips.contains(a)) chips.add(a);
        chipRow = new FrameLayout(act);
        place(root, chipRow, 306, 452, 614, 74);
        float cw = (614 - 3 * 10) / 4f;
        for (int i = 0; i < chips.size(); i++) {
            LgApps.App a = chips.get(i);
            FrameLayout chip = new FrameLayout(act);
            chip.setClipToOutline(true);
            chip.setBackground(glass(14, false));
            View tint = new View(act);
            tint.setBackgroundColor((a.color & 0x00FFFFFF) | 0x33000000);
            chip.addView(tint, new FrameLayout.LayoutParams(-1, -1));
            ImageView logo = new ImageView(act);
            logo.setImageDrawable(a.art);
            logo.setScaleType(ImageView.ScaleType.FIT_START);
            place(chip, logo, 10, 4, 66, 37);
            String when = a.lastOpened > 0 ? capitalize(LastOpened.describe(act, a.lastOpened)) : a.label;
            place(chip, text(when, 11, R.string.font_regular, 0xBFFFFFFF), 12, 56, cw - 20, -1);
            place(chipRow, chip, i * (cw + 10), 0, cw, 74);
            register("c" + i, chip, 14);
        }
    }

    // ---- focus ----

    private void setFocus(String key) {
        if (key.equals(focus) || !focusables.containsKey(key)) return;
        View old = focusables.get(focus);
        if (old != null) paint(focus, old, false);
        focus = key;
        View now = focusables.get(key);
        paint(key, now, true);
        if (key.startsWith("a")) {
            int n = LgNav.parse(key);
            int f = LgNav.firstShown(n, apps.size(), VISIBLE);
            if (f != first) {
                first = f;
                strip.animate().translationX(-first * px(STEP)).setDuration(ms(260)).start();
            }
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
            if (key.startsWith("q")) {
                int i = LgNav.parse(key);
                boolean active = i == 0 ? networkUp() : i == 1 && bluetoothOn();
                v.setBackground(active ? ring(22, 0x476EC1FF, 0xB36EC1FF) : ring(22, 0x1AFFFFFF, 0x29FFFFFF));
                tint(v, 0xFFFFFFFF);
            }
            if (key.startsWith("top")) { v.setBackground(glass(20, true)); tint(v, 0xFFFFFFFF); }
            return;
        }
        if (key.startsWith("q") || key.startsWith("top")) {
            GradientDrawable fill = new GradientDrawable();
            fill.setShape(GradientDrawable.OVAL);
            fill.setColor(0xF5FFFFFF);
            v.setBackground(fill);
            tint(v, 0xFF111111);
            return;
        }
        GradientDrawable ring = new GradientDrawable();
        if (r == null || r < 0) ring.setShape(GradientDrawable.OVAL); else ring.setCornerRadius(px(r));
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(px(3), 0xFFFFFFFF);
        v.setForeground(ring);
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
        if (focus.equals("top1")) { start(SystemSettings.intent(act, Settings.ACTION_SETTINGS)); return; }
        if (focus.equals("clock")) { start(new Intent(act, ClockActivity.class)); return; }
        if (focus.equals("w1")) {
            List<Tmdb.Title> list = feed.newThisWeek();
            if (!list.isEmpty()) search(list.get(Math.min(newIndex, list.size() - 1)).name);
            return;
        }
        int n = LgNav.parse(focus);
        if (focus.startsWith("a") && n < apps.size()) { LgApps.launch(act, apps.get(n)); return; }
        if (focus.startsWith("c") && n < chips.size()) { LgApps.launch(act, chips.get(n)); return; }
        if (focus.startsWith("q")) {
            if (n == 0) start(SystemSettings.intent(act, Settings.ACTION_WIFI_SETTINGS));
            else if (n == 1) start(SystemSettings.intent(act, Settings.ACTION_BLUETOOTH_SETTINGS));
            else if (n == 2) start(SystemSettings.intent(act, Settings.ACTION_CAST_SETTINGS));
            else if (n == 3) openInputs();
            else start(new Intent(act, CalibrationActivity.class));
        }
    }

    /**
     * The TV's input switcher. With handle_view_inputs on, this launcher also answers VIEW_INPUTS (and forwards it
     * back to Home), so pick another app's handler; Settings if the TV has none.
     */
    void openInputs() {
        Intent view = new Intent(MainActivity.ACTION_VIEW_INPUTS);
        for (ResolveInfo ri : act.getPackageManager().queryIntentActivities(view, 0)) {
            if (ri.activityInfo.packageName.equals(act.getPackageName())) continue;
            start(new Intent(view).setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)));
            return;
        }
        start(SystemSettings.intent(act, Settings.ACTION_SETTINGS));
    }

    /** Google TV search (Gemini on newer firmware), for a title when one is given: where to watch, ratings, watchlist. */
    private void search(String query) {
        Intent i = new Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH);
        if (query != null) i.putExtra(SearchManager.QUERY, query);
        if (!start(i)) start(new Intent(Intent.ACTION_ASSIST));
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

    private Drawable glass(float radius, boolean oval) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{GLASS_TOP, GLASS_BOTTOM});
        if (oval) g.setShape(GradientDrawable.OVAL); else g.setCornerRadius(px(radius));
        g.setStroke(Math.max(1, px(1)), GLASS_EDGE);
        return g;
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

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

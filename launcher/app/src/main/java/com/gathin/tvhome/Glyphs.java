package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Fork: the LG-style home's minimal app glyphs (res/drawable/glyph_*.xml, one 48x48 grid, same optical size), so
 * every app in the round band and the chips reads the same size with no square banner behind it. Known apps are
 * matched by package, then by a word in their label; any other app gets its initial in the launcher's font.
 */
final class Glyphs {
    private Glyphs() {
    }

    private static final Map<String, Integer> BY_PACKAGE = new HashMap<>();
    /** Label words, checked in order (more specific first). */
    private static final String[][] BY_LABEL = {
            {"netflix", "netflix"}, {"prime video", "primevideo"}, {"hotstar", "jiohotstar"},
            {"youtube music", "ytmusic"}, {"youtube kids", "ytkids"}, {"smarttube", "smarttube"},
            {"youtube", "youtube"}, {"spotify", "spotify"}, {"zee5", "zee5"}, {"sonyliv", "sonyliv"},
            {"mx player", "mxplayer"}, {"plex", "plex"}, {"apple tv", "appletv"}, {"play store", "playstore"},
            {"vlc", "vlc"}, {"kodi", "kodi"}, {"jellyfin", "jellyfin"}, {"twitch", "twitch"},
            {"crunchyroll", "crunchyroll"}, {"discovery+", "discoveryplus"}, {"downloader", "downloader"},
            {"airscreen", "cast"}, {"airplay", "cast"}, {"send files", "send"}, {"localsend", "send"},
            {"explorer", "files"}, {"x-plore", "files"}, {"file", "files"}, {"browser", "browser"},
            {"chrome", "browser"}, {"firefox", "browser"}, {"settings", "settings"},
    };

    static {
        put("netflix", "com.netflix.ninja", "com.netflix.mediaclient");
        put("primevideo", "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient");
        put("jiohotstar", "in.startv.hotstar");
        put("youtube", "com.google.android.youtube.tv", "com.google.android.youtube.googletv");
        put("ytmusic", "com.google.android.youtube.tvmusic");
        put("ytkids", "com.google.android.youtube.tvkids");
        put("smarttube", "com.liskovsoft.smarttubetv", "com.liskovsoft.smarttubetv.beta", "com.teamsmart.videomanager.tv");
        put("spotify", "com.spotify.tv.android");
        put("zee5", "com.graymatrix.did");
        put("sonyliv", "com.sonyliv");
        put("mxplayer", "com.mxtech.videoplayer.television", "com.mxtech.videoplayer.ad");
        put("plex", "com.plexapp.android");
        put("appletv", "com.apple.atve.androidtv.appletv");
        put("playstore", "com.android.vending");
        put("settings", "com.android.tv.settings", "com.android.settings");
        put("livetv", "com.android.tv", "com.google.android.tv");
        put("vlc", "org.videolan.vlc");
        put("kodi", "org.xbmc.kodi");
        put("jellyfin", "org.jellyfin.androidtv");
        put("files", "pl.solidexplorer2", "com.alphainventor.filemanager", "nextapp.fx", "com.lonelycatgames.Xplore",
                "com.mobisystems.fileman", "com.estrongs.android.pop", "dev.dworks.apps.anexplorer",
                "dev.dworks.apps.anexplorer.pro", "com.google.android.documentsui", "com.android.documentsui");
        put("browser", "com.phlox.tvwebbrowser", "com.android.chrome", "org.mozilla.firefox", "org.mozilla.tv.firefox",
                "com.cloudmosa.puffinTV");
        put("send", "com.yablio.sendfilestotv", "org.localsend.localsend_app");
        put("downloader", "com.esaba.downloader");
        put("cast", "com.ionitech.airscreen");
        put("twitch", "tv.twitch.android.app", "tv.twitch.android.viewer");
        put("crunchyroll", "com.crunchyroll.crunchyroid");
        put("discoveryplus", "com.discoveryplus.tv.android", "com.discovery.discoveryplus.androidtv");
    }

    private static void put(String glyph, String... packages) {
        for (String p : packages) BY_PACKAGE.put(p, id(glyph));
    }

    private static int id(String glyph) {
        switch (glyph) {
            case "netflix": return R.drawable.glyph_netflix;
            case "primevideo": return R.drawable.glyph_primevideo;
            case "jiohotstar": return R.drawable.glyph_jiohotstar;
            case "youtube": return R.drawable.glyph_youtube;
            case "ytmusic": return R.drawable.glyph_ytmusic;
            case "ytkids": return R.drawable.glyph_ytkids;
            case "smarttube": return R.drawable.glyph_smarttube;
            case "spotify": return R.drawable.glyph_spotify;
            case "zee5": return R.drawable.glyph_zee5;
            case "sonyliv": return R.drawable.glyph_sonyliv;
            case "mxplayer": return R.drawable.glyph_mxplayer;
            case "plex": return R.drawable.glyph_plex;
            case "appletv": return R.drawable.glyph_appletv;
            case "playstore": return R.drawable.glyph_playstore;
            case "settings": return R.drawable.glyph_settings;
            case "livetv": return R.drawable.glyph_livetv;
            case "vlc": return R.drawable.glyph_vlc;
            case "kodi": return R.drawable.glyph_kodi;
            case "jellyfin": return R.drawable.glyph_jellyfin;
            case "files": return R.drawable.glyph_files;
            case "browser": return R.drawable.glyph_browser;
            case "send": return R.drawable.glyph_send;
            case "downloader": return R.drawable.glyph_downloader;
            case "cast": return R.drawable.glyph_cast;
            case "twitch": return R.drawable.glyph_twitch;
            case "crunchyroll": return R.drawable.glyph_crunchyroll;
            case "discoveryplus": return R.drawable.glyph_discoveryplus;
            default: return 0;
        }
    }

    /** The glyph resource for an app, or 0 when it has none (then {@link #get} draws its initial). */
    static int resource(String pkg, String label) {
        Integer byPkg = BY_PACKAGE.get(pkg);
        if (byPkg != null) return byPkg;
        String l = label == null ? "" : label.toLowerCase(Locale.ROOT);
        for (String[] row : BY_LABEL) if (l.contains(row[0])) return id(row[1]);
        return 0;
    }

    static Drawable get(Context ctx, String pkg, String label) {
        int res = resource(pkg, label);
        if (res != 0) return ctx.getDrawable(res);
        return new Initial(ctx, label);
    }

    /** An app with no glyph: its first letter, white, in the launcher's font, at the glyphs' optical size. */
    static final class Initial extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String letter;
        private final Context ctx;
        private final String label;

        Initial(Context ctx, String label) {
            this.ctx = ctx;
            this.label = label;
            String l = label == null ? "" : label.trim();
            letter = l.isEmpty() ? "?" : l.substring(0, l.offsetByCodePoints(0, 1)).toUpperCase(Locale.getDefault());
            paint.setColor(0xF2FFFFFF);
            paint.setTypeface(UiFont.get(ctx, R.string.font_medium));
            paint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            // Same grid as the vector glyphs: the letter's cap height is about 28/48 of the box.
            paint.setTextSize(b.height() * 0.80f);
            Rect t = new Rect();
            paint.getTextBounds(letter, 0, letter.length(), t);
            c.drawText(letter, b.exactCenterX(), b.exactCenterY() - t.exactCenterY(), paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        /** Lets each view get its own copy (a shared one would be sized for whichever view laid out last). */
        @Override
        public ConstantState getConstantState() {
            return new ConstantState() {
                @Override
                public Drawable newDrawable() {
                    return new Initial(ctx, label);
                }

                @Override
                public int getChangingConfigurations() {
                    return 0;
                }
            };
        }
    }
}

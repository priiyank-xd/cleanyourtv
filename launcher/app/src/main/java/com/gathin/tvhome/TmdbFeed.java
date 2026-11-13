package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "New this week" and "Trending" for the LG-style home, from TMDB. Kept on disk (files/tmdb_feed.json, images in
 * cache/tmdb) so Home shows the last answer at once and never waits on the network; refreshed in the background
 * when older than {@link #MAX_AGE_MS} or when the set of streaming apps changes. Images are TMDB's w780 backdrops,
 * decoded small, and with the movie-art background on, w1280 ones for the full screen.
 */
final class TmdbFeed {
    interface Listener {
        void onFeed();
    }

    static final long MAX_AGE_MS = 6 * 60 * 60 * 1000L;
    /** After a failed refresh, wait this long before trying again (Home resumes often). */
    static final long RETRY_MS = 15 * 60 * 1000L;
    static final int MAX_NEW = 8, MAX_TRENDING = 3, TRENDING_CHECKS = 20;
    private static final String TAG = "TmdbFeed", FILE = "tmdb_feed.json", SIZE = "w780";
    /** Full-screen backdrops for the movie-art background (lg_background "movie_art"), only when it is on. */
    static final String ART_SIZE = "w1280";
    private volatile boolean wantArt;

    private final Context app;
    private final String token;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile List<Tmdb.Title> fresh = new ArrayList<>();
    private volatile List<Tmdb.Title> trending = new ArrayList<>();
    private volatile long fetchedAt;
    private volatile String providersKey = "";
    /** When the last refresh started; main thread only. */
    private long attemptAt;
    private boolean busy;

    TmdbFeed(Context ctx, String token) {
        this.app = ctx.getApplicationContext();
        this.token = token == null ? "" : token.trim();
        loadCache();
    }

    boolean enabled() {
        return !token.isEmpty();
    }

    List<Tmdb.Title> newThisWeek() {
        return fresh;
    }

    List<Tmdb.Title> trending() {
        return trending;
    }

    /** Whether to keep full-screen backdrops for the movie-art background. */
    void wantArt(boolean on) {
        wantArt = on;
    }

    /** The titles the movie-art background rotates through: trending first, then new this week, no repeats. */
    List<Tmdb.Title> artTitles() {
        List<Tmdb.Title> out = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (Tmdb.Title t : trending) if (seen.add(t.backdrop)) out.add(t);
        for (Tmdb.Title t : fresh) if (seen.add(t.backdrop)) out.add(t);
        return out;
    }

    /** Downloads any full-screen backdrop the current lists lack (movie art just switched on); then {@code done}. */
    void ensureArt(Listener done) {
        if (!wantArt || worker.isShutdown()) return;
        List<Tmdb.Title> titles = artTitles();
        worker.execute(() -> {
            boolean added = false;
            for (Tmdb.Title t : titles) added |= cacheImage(t.backdrop, ART_SIZE);
            if (added) main.post(done::onFeed);
        });
    }

    /** Decodes a title's full-screen backdrop on the worker thread; {@code done} gets null when it is not on disk. */
    void decodeArt(Tmdb.Title t, int width, Decoded done) {
        if (worker.isShutdown()) return;
        worker.execute(() -> {
            Bitmap b = image(t, width, ART_SIZE);
            main.post(() -> done.onBitmap(b));
        });
    }

    /** Refreshes in the background if stale or the apps changed; {@code listener} runs on the main thread after. */
    void refreshIfStale(Collection<String> installedPackages, Listener listener) {
        Set<Integer> mine = Tmdb.providerIds(installedPackages);
        String key = mine.toString();
        long now = System.currentTimeMillis();
        boolean appsChanged = !key.equals(providersKey);
        boolean stale = now - fetchedAt > MAX_AGE_MS || appsChanged;
        boolean waiting = !appsChanged && now - attemptAt < RETRY_MS;
        if (!enabled() || mine.isEmpty() || !stale || waiting || busy) return;
        busy = true;
        attemptAt = now;
        worker.execute(() -> {
            try {
                fetch(mine, key);
                main.post(listener::onFeed);
            } catch (Exception e) {
                Log.w(TAG, "TMDB refresh failed: " + e.getMessage());
            } finally {
                main.post(() -> busy = false);
            }
        });
    }

    interface Decoded {
        void onBitmap(Bitmap bitmap);
    }

    /** Decodes a title's cached backdrop on the worker thread; {@code done} runs on the main thread (null if none). */
    void decode(Tmdb.Title t, int width, Decoded done) {
        if (worker.isShutdown()) return;
        worker.execute(() -> {
            Bitmap b = image(t, width, SIZE);
            main.post(() -> done.onBitmap(b));
        });
    }

    /** The cached backdrop for a title, decoded no wider than {@code width} pixels, or null when not yet on disk. */
    private Bitmap image(Tmdb.Title t, int width, String size) {
        File f = imageFile(t.backdrop, size);
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    void shutdown() {
        worker.shutdownNow();
    }

    // ---- background work ----

    private void fetch(Set<Integer> mine, String key) throws Exception {
        LocalDate today = LocalDate.now();
        List<Tmdb.Title> movies = Tmdb.parseTitles(Tmdb.get(Tmdb.newMoviesUrl(mine, today), token), "movie");
        List<Tmdb.Title> series = Tmdb.parseTitles(Tmdb.get(Tmdb.newSeriesUrl(mine, today), token), "tv");
        List<Tmdb.Title> newList = new ArrayList<>();
        for (Tmdb.Title t : Tmdb.merge(movies, series, MAX_NEW * 2)) {
            if (newList.size() >= MAX_NEW) break;
            t.provider = provider(t, mine);
            if (t.provider != 0) newList.add(t);
        }
        List<Tmdb.Title> trendList = new ArrayList<>();
        List<Tmdb.Title> candidates = Tmdb.parseTitles(Tmdb.get(Tmdb.trendingUrl(), token), null);
        for (int i = 0; i < candidates.size() && i < TRENDING_CHECKS && trendList.size() < MAX_TRENDING; i++) {
            Tmdb.Title t = candidates.get(i);
            t.provider = provider(t, mine);
            if (t.provider != 0) trendList.add(t);
        }
        // Lookups that failed count as "not on your apps"; an empty answer is more likely a network blip than
        // a week with nothing new, so keep the previous list rather than blank the widget for six hours.
        if (newList.isEmpty() && trendList.isEmpty()) throw new java.io.IOException("TMDB returned no titles on your apps");
        if (newList.isEmpty()) newList = fresh;
        if (trendList.isEmpty()) trendList = trending;
        for (Tmdb.Title t : newList) cacheImage(t.backdrop, SIZE);
        for (Tmdb.Title t : trendList) cacheImage(t.backdrop, SIZE);
        if (wantArt) {
            for (Tmdb.Title t : trendList) cacheImage(t.backdrop, ART_SIZE);
            for (Tmdb.Title t : newList) cacheImage(t.backdrop, ART_SIZE);
        }
        fresh = newList;
        trending = trendList;
        fetchedAt = System.currentTimeMillis();
        providersKey = key;
        saveCache();
        pruneImages();
    }

    private int provider(Tmdb.Title t, Set<Integer> mine) {
        try {
            return Tmdb.streamingOn(Tmdb.get(Tmdb.providersUrl(t.kind, t.id), token), mine);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Downloads one backdrop at {@code size} unless it is on disk; true when it newly arrived. */
    private boolean cacheImage(String path, String size) {
        File f = imageFile(path, size);
        if (f.isFile()) return false;
        try {
            byte[] bytes = Tmdb.download(Tmdb.imageUrl(path, size));
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
            File tmp = new File(f.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            if (tmp.renameTo(f)) return true;
            tmp.delete();
        } catch (Exception e) {
            Log.w(TAG, "image " + path + ": " + e.getMessage());
        }
        return false;
    }

    /** Keeps only the images the current lists use. */
    private void pruneImages() {
        File[] files = new File(app.getCacheDir(), "tmdb").listFiles();
        if (files == null) return;
        List<String> keep = new ArrayList<>();
        for (Tmdb.Title t : artTitles()) {
            keep.add(imageFile(t.backdrop, SIZE).getName());
            if (wantArt) keep.add(imageFile(t.backdrop, ART_SIZE).getName());
        }
        for (File f : files) if (!keep.contains(f.getName())) f.delete();
    }

    private File imageFile(String path, String size) {
        String name = path.replaceAll("[^A-Za-z0-9._-]", "");
        return new File(new File(app.getCacheDir(), "tmdb"), size + "_" + name);
    }

    // ---- disk cache of the lists ----

    private void loadCache() {
        File f = new File(app.getFilesDir(), FILE);
        if (!f.isFile()) return;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            fetchedAt = o.optLong("fetchedAt");
            providersKey = o.optString("providers");
            fresh = list(o.optJSONArray("new"));
            trending = list(o.optJSONArray("trending"));
        } catch (Exception e) {
            Log.w(TAG, "cache: " + e.getMessage());
        }
    }

    private void saveCache() throws Exception {
        JSONObject o = new JSONObject().put("fetchedAt", fetchedAt).put("providers", providersKey)
                .put("new", array(fresh)).put("trending", array(trending));
        try (FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), FILE))) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static List<Tmdb.Title> list(JSONArray a) {
        List<Tmdb.Title> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(Tmdb.Title.fromJson(o));
        }
        return out;
    }

    private static JSONArray array(List<Tmdb.Title> titles) throws Exception {
        JSONArray a = new JSONArray();
        for (Tmdb.Title t : titles) a.put(t.toJson());
        return a;
    }
}

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
 * decoded small.
 */
final class TmdbFeed {
    interface Listener {
        void onFeed();
    }

    static final long MAX_AGE_MS = 6 * 60 * 60 * 1000L;
    static final int MAX_NEW = 8, MAX_TRENDING = 3, TRENDING_CHECKS = 20;
    private static final String TAG = "TmdbFeed", FILE = "tmdb_feed.json", SIZE = "w780";

    private final Context app;
    private final String token;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private List<Tmdb.Title> fresh = new ArrayList<>();
    private List<Tmdb.Title> trending = new ArrayList<>();
    private long fetchedAt;
    private String providersKey = "";
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

    /** Refreshes in the background if stale or the apps changed; {@code listener} runs on the main thread after. */
    void refreshIfStale(Collection<String> installedPackages, Listener listener) {
        Set<Integer> mine = Tmdb.providerIds(installedPackages);
        String key = mine.toString();
        boolean stale = System.currentTimeMillis() - fetchedAt > MAX_AGE_MS || !key.equals(providersKey);
        if (!enabled() || mine.isEmpty() || !stale || busy) return;
        busy = true;
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

    /** The cached backdrop for a title, decoded no wider than {@code width} pixels, or null when not yet on disk. */
    Bitmap image(Tmdb.Title t, int width) {
        File f = imageFile(t.backdrop);
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
        for (Tmdb.Title t : newList) cacheImage(t.backdrop);
        for (Tmdb.Title t : trendList) cacheImage(t.backdrop);
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

    private void cacheImage(String path) {
        File f = imageFile(path);
        if (f.isFile()) return;
        try {
            byte[] bytes = Tmdb.download(Tmdb.imageUrl(path, SIZE));
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(f.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception e) {
            Log.w(TAG, "image " + path + ": " + e.getMessage());
        }
    }

    /** Keeps only the images the current lists use. */
    private void pruneImages() {
        File[] files = new File(app.getCacheDir(), "tmdb").listFiles();
        if (files == null) return;
        List<String> keep = new ArrayList<>();
        for (Tmdb.Title t : fresh) keep.add(imageFile(t.backdrop).getName());
        for (Tmdb.Title t : trending) keep.add(imageFile(t.backdrop).getName());
        for (File f : files) if (!keep.contains(f.getName())) f.delete();
    }

    private File imageFile(String path) {
        String name = path.replaceAll("[^A-Za-z0-9._-]", "");
        return new File(new File(app.getCacheDir(), "tmdb"), SIZE + "_" + name);
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

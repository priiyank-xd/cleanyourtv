package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Discover's network side (fork only): TMDB search and title details on background threads, and TMDB pictures
 * through a small memory cache and a disk cache (cache/discover, trimmed to {@link #DISK_BYTES}). One instance for
 * the whole app, so going back to a screen shows its pictures at once. Answers arrive on the main thread; a failed
 * call answers null.
 */
final class DiscoverNet {
    interface Done<T> {
        void on(T value);
    }

    /** A TMDB answer, and whether it is the copy saved earlier because the network failed. */
    static final class Answer {
        final JSONObject json;
        final boolean offline;
        final long savedAt;

        Answer(JSONObject json, boolean offline, long savedAt) {
            this.json = json;
            this.offline = offline;
            this.savedAt = savedAt;
        }
    }

    static final long HOURS = 60 * 60 * 1000L;
    private static final int MAX_SAVED = 300;

    private static final String TAG = "DiscoverNet";
    private static final long DISK_BYTES = 40L * 1024 * 1024;
    private static DiscoverNet instance;

    private final String token;
    private final File dir, answers;
    private final ExecutorService calls = Executors.newFixedThreadPool(2);
    private final ExecutorService pictures = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memory;
    /** The last few title pages, so Back and forth between them doesn't wait on the network. */
    private final Map<String, Discover.Details> details = new LinkedHashMap<String, Discover.Details>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Discover.Details> e) {
            return size() > 24;
        }
    };

    static synchronized DiscoverNet get(Context ctx) {
        if (instance == null) instance = new DiscoverNet(ctx.getApplicationContext());
        return instance;
    }

    private DiscoverNet(Context app) {
        token = BuildConfig.TMDB_TOKEN == null ? "" : BuildConfig.TMDB_TOKEN.trim();
        dir = new File(app.getCacheDir(), "discover");
        answers = new File(app.getFilesDir(), "discover_answers");
        int max = (int) Math.min(Runtime.getRuntime().maxMemory() / 8, 48L * 1024 * 1024);
        memory = new LruCache<String, Bitmap>(max) {
            @Override protected int sizeOf(String key, Bitmap b) {
                return b.getAllocationByteCount();
            }
        };
    }

    boolean enabled() {
        return !token.isEmpty();
    }

    /** A search answer (titles and people), null when the call failed. */
    void search(String query, Done<JSONObject> done) {
        calls.execute(() -> {
            JSONObject a = null;
            try {
                a = Tmdb.get(Discover.searchUrl(query), token);
            } catch (Exception e) {
                Log.w(TAG, "search failed: " + e.getMessage());
            }
            JSONObject result = a;
            main.post(() -> done.on(result));
        });
    }

    /** A title's page; from memory when it was opened recently. */
    void details(String kind, int id, Done<Discover.Details> done) {
        String key = kind + id;
        Discover.Details known;
        synchronized (details) {
            known = details.get(key);
        }
        if (known != null) {
            done.on(known);
            return;
        }
        calls.execute(() -> {
            Discover.Details d = null;
            try {
                d = Discover.parseDetails(Tmdb.get(Discover.detailsUrl(kind, id), token), kind);
                synchronized (details) {
                    details.put(key, d);
                }
            } catch (Exception e) {
                Log.w(TAG, "details failed: " + e.getMessage());
            }
            Discover.Details result = d;
            main.post(() -> done.on(result));
        });
    }

    /**
     * A TMDB answer, kept on the projector: the saved copy while it is younger than {@code freshMs}, else a new one
     * from TMDB, else (no network) the saved copy marked offline. Null when there is neither.
     */
    void answer(String url, long freshMs, Done<Answer> done) {
        answer(url, null, freshMs, done);
    }

    /** As {@link #answer(String, long, Done)}, POSTing {@code body} as JSON when it isn't null (JustWatch). */
    void answer(String url, String body, long freshMs, Done<Answer> done) {
        calls.execute(() -> {
            File f = new File(answers, sha1(body == null ? url : url + "\n" + body) + ".json");
            JSONObject saved = null;
            long at = 0;
            try {
                if (f.isFile()) {
                    JSONObject w = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                    saved = w.optJSONObject("body");
                    at = w.optLong("at");
                }
            } catch (Exception e) {
                Log.w(TAG, "saved answer unreadable: " + e.getMessage());
            }
            Answer result = null;
            if (saved != null && System.currentTimeMillis() - at < freshMs) {
                result = new Answer(saved, false, at);
            } else {
                try {
                    JSONObject fresh = url.startsWith(Tmdb.API) ? Tmdb.get(url, token) : http(url, body);
                    long now = System.currentTimeMillis();
                    saveAnswer(f, new JSONObject().put("at", now).put("body", fresh));
                    result = new Answer(fresh, false, now);
                } catch (Exception e) {
                    Log.w(TAG, "answer failed: " + e.getMessage());
                    if (saved != null) result = new Answer(saved, true, at);
                }
            }
            Answer r = result;
            main.post(() -> done.on(r));
        });
    }

    /** A GET (or a JSON POST when {@code body} isn't null) to a service other than TMDB: no TMDB token sent. */
    private static JSONObject http(String url, String body) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (java.io.OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            try (java.io.InputStream in = c.getInputStream(); java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream()) {
                byte[] b = new byte[16 * 1024];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                return new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }

    // ---- OMDb and JustWatch ----

    private static final long WEEK_MS = 7 * 24 * HOURS;

    boolean hasScores() {
        return BuildConfig.OMDB_KEY != null && !BuildConfig.OMDB_KEY.trim().isEmpty();
    }

    /** IMDb and Rotten Tomatoes scores for an IMDb id, kept a week; empty scores when unknown or with no key. */
    void ratings(String imdbId, Done<Discover.Ratings> done) {
        if (!hasScores() || imdbId == null || imdbId.isEmpty()) {
            done.on(new Discover.Ratings());
            return;
        }
        answer(Discover.omdbUrl(imdbId, BuildConfig.OMDB_KEY.trim()), null, WEEK_MS,
                a -> done.on(a == null ? new Discover.Ratings() : Discover.parseOmdb(a.json)));
    }

    /** Each provider's own link to a title (JustWatch), kept a day; empty when JustWatch can't say. */
    void links(String name, String kind, int tmdbId, Done<Map<Integer, String>> done) {
        answer(Discover.JUSTWATCH, Discover.justWatchBody(name), 24 * HOURS,
                a -> done.on(a == null ? new java.util.HashMap<>() : Discover.parseJustWatch(a.json, kind, tmdbId)));
    }

    // ---- your TMDB account ----

    /**
     * A call to TMDB as you (sign-in, lists, marking): {@code method} GET, POST or DELETE, an optional JSON body,
     * and the bearer to send (your v4 access token, or the launcher's for v3 calls with a session). Answers null
     * when the call fails. Nothing here is saved: your lists change.
     */
    void call(String method, String url, String body, String bearer, Done<JSONObject> done) {
        calls.execute(() -> {
            JSONObject result = null;
            try {
                result = send(method, url, body, bearer);
            } catch (Exception e) {
                Log.w(TAG, "account call failed: " + e.getMessage());
            }
            JSONObject r = result;
            main.post(() -> done.on(r));
        });
    }

    String appToken() {
        return token;
    }

    private static JSONObject send(String method, String url, String body, String bearer) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        if (bearer != null && !bearer.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + bearer);
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json;charset=utf-8");
                try (java.io.OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new java.io.IOException("TMDB " + code);
            try (java.io.InputStream in = c.getInputStream(); java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream()) {
                byte[] b = new byte[16 * 1024];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                String text = new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
                return text.isEmpty() ? new JSONObject() : new JSONObject(text);
            }
        } finally {
            c.disconnect();
        }
    }

    /** Your watchlist or favourites (WATCHLIST or FAVORITE), movies and series together, newest first. */
    void saved(TmdbAccount a, String what, Done<java.util.List<Discover.Hit>> done) {
        call("GET", Discover.savedUrl(a.objectId, what, "movie", 1), null, a.access, movies ->
                call("GET", Discover.savedUrl(a.objectId, what, "tv", 1), null, a.access, series -> {
                    if (movies == null && series == null) {
                        done.on(null);
                        return;
                    }
                    done.on(Discover.interleave(Discover.parseList(movies, "movie", 20), Discover.parseList(series, "tv", 20), 40));
                }));
    }

    /** Your own lists, null when they couldn't be read. */
    void lists(TmdbAccount a, Done<java.util.List<Discover.UserList>> done) {
        call("GET", Discover.listsUrl(a.objectId, 1), null, a.access, j -> done.on(j == null ? null : Discover.parseLists(j)));
    }

    /** A list's titles (its first 60), null when it couldn't be read. */
    void listItems(TmdbAccount a, int listId, Done<java.util.List<Discover.Hit>> done) {
        java.util.List<Discover.Hit> all = new java.util.ArrayList<>();
        listPage(a, listId, 1, all, done);
    }

    private void listPage(TmdbAccount a, int listId, int page, java.util.List<Discover.Hit> all, Done<java.util.List<Discover.Hit>> done) {
        call("GET", Discover.listUrl(listId, page), null, a.access, j -> {
            if (j == null) {
                done.on(page == 1 ? null : all);
                return;
            }
            all.addAll(Discover.parseList(j, null, 20));
            if (page < 3 && page < j.optInt("total_pages", 1)) listPage(a, listId, page + 1, all, done);
            else done.on(all);
        });
    }

    private void saveAnswer(File f, JSONObject w) {
        try {
            if (!answers.isDirectory() && !answers.mkdirs()) return;
            File tmp = new File(f.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(w.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) return;
            File[] all = answers.listFiles();
            if (all != null && all.length > MAX_SAVED) {
                Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (int i = 0; i < all.length - MAX_SAVED; i++) if (!all[i].delete()) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "could not save an answer: " + e.getMessage());
        }
    }

    private static String sha1(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(java.util.Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** A TMDB picture ({@code size} as w300, w780, w1280...), decoded no wider than about {@code width} pixels. */
    void picture(String path, String size, int width, Done<Bitmap> done) {
        if (path == null || path.isEmpty()) {
            done.on(null);
            return;
        }
        String key = size + path + "@" + width;
        Bitmap hit = memory.get(key);
        if (hit != null) {
            done.on(hit);
            return;
        }
        pictures.execute(() -> {
            Bitmap b = null;
            try {
                File f = new File(dir, size + "_" + path.replace("/", ""));
                if (!f.exists()) save(f, Tmdb.download(Tmdb.imageUrl(path, size)));
                b = decode(f, width);
                if (b != null) memory.put(key, b);
            } catch (Exception e) {
                Log.w(TAG, "picture failed: " + e.getMessage());
            }
            Bitmap result = b;
            main.post(() -> done.on(result));
        });
    }

    void trimMemory() {
        memory.evictAll();
    }

    private void save(File f, byte[] bytes) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("no cache dir");
        File tmp = new File(f.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename failed");
        prune();
    }

    /** Oldest pictures first, until the folder is under its size. */
    private void prune() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK_BYTES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= DISK_BYTES * 3 / 4) break;
            total -= f.length();
            if (!f.delete()) Log.w(TAG, "could not delete " + f.getName());
        }
    }

    private static Bitmap decode(File f, int width) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) {
            if (!f.delete()) Log.w(TAG, "could not delete a broken picture");
            return null;
        }
        int sample = 1;
        while (width > 0 && o.outWidth / (sample * 2) >= width) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), d);
    }
}

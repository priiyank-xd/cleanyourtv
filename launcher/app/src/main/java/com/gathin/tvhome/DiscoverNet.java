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
        calls.execute(() -> {
            File f = new File(answers, sha1(url) + ".json");
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
                    JSONObject fresh = Tmdb.get(url, token);
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

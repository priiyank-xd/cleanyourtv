package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
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

    private static final String TAG = "DiscoverNet";
    private static final long DISK_BYTES = 40L * 1024 * 1024;
    private static DiscoverNet instance;

    private final String token;
    private final File dir;
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

    void search(String query, Done<List<Discover.Hit>> done) {
        calls.execute(() -> {
            List<Discover.Hit> hits = null;
            try {
                hits = Discover.parseSearch(Tmdb.get(Discover.searchUrl(query), token), Discover.MAX_RESULTS);
            } catch (Exception e) {
                Log.w(TAG, "search failed: " + e.getMessage());
            }
            List<Discover.Hit> result = hits;
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

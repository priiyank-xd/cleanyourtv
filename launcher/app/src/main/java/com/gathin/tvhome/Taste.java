package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What you like, learned on the projector (fork only), for Browse's "For you" order. It notes the genres and
 * language of titles you open, save or keep on your TMDB lists, and which titles Browse showed you that you passed
 * over. Nothing leaves the device; notes older than 30 days are dropped. The ranking itself ({@link #rank}) is pure.
 */
final class Taste {
    static final long DAY = 86_400_000L;
    private static final long KEEP_MS = 30 * DAY, RECENT_MS = 14 * DAY;
    private static final double HALF_LIFE_DAYS = 14;
    /** How much each kind of note says about your taste (a thumbs down counts against). */
    static final double OPENED = 1, SAVED = 3, LISTED = 1.5, LIKED = 3, LOVED = 5, DISLIKED = -3;
    private static final int MAX_NOTES = 400, MAX_SHOWN = 1500;
    private static final String PREFS = "discover_taste", NOTES = "notes", SHOWN = "shown";

    private static Taste instance;
    private final SharedPreferences prefs;
    /** "why:kind:id" to {g: genre ids, l: language, w: weight, t: when}. */
    private final JSONObject notes;
    /** "kind:id" to [times shown, last shown]. */
    private final JSONObject shown;

    static synchronized Taste get(Context ctx) {
        if (instance == null) instance = new Taste(ctx.getApplicationContext());
        return instance;
    }

    private Taste(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        notes = read(prefs.getString(NOTES, null));
        shown = read(prefs.getString(SHOWN, null));
        prune(System.currentTimeMillis());
    }

    private static JSONObject read(String s) {
        try {
            return s == null ? new JSONObject() : new JSONObject(s);
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    // ---- noting ----

    /** You opened a title's page. */
    synchronized void opened(String kind, int id, Collection<Integer> genres, String language) {
        note("open", kind, id, genres, language, OPENED);
    }

    /** You saved a title to Watch later, Favourites or a list. */
    synchronized void saved(String kind, int id, Collection<Integer> genres, String language) {
        note("save", kind, id, genres, language, SAVED);
    }

    /** You rated a title with thumbs ({@link Discover#THUMB_NONE} takes the rating back). */
    synchronized void rated(String kind, int id, Collection<Integer> genres, String language, int thumb) {
        if (thumb == Discover.THUMB_NONE) {
            notes.remove("rate:" + kind + ":" + id);
            save();
            return;
        }
        note("rate", kind, id, genres, language,
                thumb == Discover.THUMB_DOWN ? DISLIKED : thumb == Discover.THUMB_LOVE ? LOVED : LIKED);
    }

    /** Titles on your TMDB Watch later list: still there, so still your taste. */
    synchronized void listed(List<Discover.Hit> hits) {
        for (Discover.Hit h : hits) {
            List<Integer> g = new ArrayList<>();
            for (int x : h.genres) g.add(x);
            note("list", h.kind, h.id, g, h.language, LISTED);
        }
    }

    /** Browse put these titles on screen. */
    synchronized void shown(List<Discover.Hit> hits) {
        long now = System.currentTimeMillis();
        boolean changed = false;
        try {
            for (Discover.Hit h : hits) {
                String k = h.kind + ":" + h.id;
                JSONArray was = shown.optJSONArray(k);
                // once per visit: a title seen again within the hour is the same look
                if (was != null && now - was.optLong(1) < DAY / 24) continue;
                shown.put(k, new JSONArray().put(was == null ? 1 : was.optInt(0) + 1).put(now));
                changed = true;
            }
        } catch (org.json.JSONException ignored) {
            return;
        }
        // written only when something changed: this runs on every focus move in Browse
        if (changed) save();
    }

    private void note(String why, String kind, int id, Collection<Integer> genres, String language, double weight) {
        try {
            JSONArray g = new JSONArray();
            for (int x : genres) g.put(x);
            notes.put(why + ":" + kind + ":" + id, new JSONObject().put("g", g).put("l", language == null ? "" : language)
                    .put("w", weight).put("t", System.currentTimeMillis()));
        } catch (org.json.JSONException ignored) {
            return;
        }
        save();
    }

    private void save() {
        prune(System.currentTimeMillis());
        prefs.edit().putString(NOTES, notes.toString()).putString(SHOWN, shown.toString()).apply();
    }

    /** Drops notes older than 30 days, and the oldest when there are too many. */
    private void prune(long now) {
        dropOld(notes, now, MAX_NOTES, true);
        dropOld(shown, now, MAX_SHOWN, false);
    }

    private static void dropOld(JSONObject o, long now, int max, boolean isNote) {
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) keys.add(it.next());
        List<String> kept = new ArrayList<>();
        for (String k : keys) {
            if (now - when(o, k, isNote) > KEEP_MS) o.remove(k);
            else kept.add(k);
        }
        if (kept.size() <= max) return;
        kept.sort((a, b) -> Long.compare(when(o, a, isNote), when(o, b, isNote)));
        for (int i = 0; i < kept.size() - max; i++) o.remove(kept.get(i));
    }

    private static long when(JSONObject o, String k, boolean isNote) {
        if (isNote) {
            JSONObject n = o.optJSONObject(k);
            return n == null ? 0 : n.optLong("t");
        }
        JSONArray a = o.optJSONArray(k);
        return a == null ? 0 : a.optLong(1);
    }

    /** Everything {@link #rank} needs, as of now. */
    synchronized Signals signals() {
        long now = System.currentTimeMillis();
        Signals s = new Signals();
        for (Iterator<String> it = notes.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject n = notes.optJSONObject(k);
            if (n == null) continue;
            long t = n.optLong("t");
            double w = n.optDouble("w", 1) * Math.pow(0.5, (now - t) / (double) DAY / HALF_LIFE_DAYS);
            JSONArray g = n.optJSONArray("g");
            for (int i = 0; g != null && i < g.length(); i++) s.genres.merge(g.optInt(i), w, Double::sum);
            String l = n.optString("l", "");
            if (!l.isEmpty()) s.languages.merge(l, w, Double::sum);
            String title = k.substring(k.indexOf(':') + 1);
            if (k.startsWith("open:") && now - t < RECENT_MS) s.openedRecently.add(title);
            if (k.startsWith("rate:") && n.optDouble("w", 1) < 0) s.disliked.add(title);
            s.engaged.add(title);
        }
        for (Iterator<String> it = shown.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONArray a = shown.optJSONArray(k);
            if (a != null) s.shown.put(k, a.optInt(0));
        }
        return s;
    }

    // ---- ranking (pure) ----

    /** Your taste as numbers: genre and language weights, how often titles were shown, what you opened lately. */
    static final class Signals {
        final Map<Integer, Double> genres = new HashMap<>();
        final Map<String, Double> languages = new HashMap<>();
        /** "kind:id" to times Browse showed it. */
        final Map<String, Integer> shown = new HashMap<>();
        /** "kind:id" opened in the last two weeks. */
        final Set<String> openedRecently = new HashSet<>();
        /** "kind:id" you opened, saved or listed at any time (shown-but-passed-over doesn't apply to these). */
        final Set<String> engaged = new HashSet<>();
        /** "kind:id" you gave a thumbs down: For you leaves them out. */
        final Set<String> disliked = new HashSet<>();
    }

    /**
     * "For you": {@code pool} (most popular first) reordered by popularity, your taste in genres and language, how new
     * it is, a small shuffle that changes each day, titles passed over many times sinking, titles opened lately
     * sinking a little, and no more than two in a row from one series.
     */
    static List<Discover.Hit> rank(List<Discover.Hit> pool, Signals s, LocalDate today) {
        List<Discover.Hit> kept = new ArrayList<>();
        for (Discover.Hit h : pool) if (!s.disliked.contains(h.kind + ":" + h.id)) kept.add(h);
        pool = kept;
        int n = pool.size();
        double topGenre = 0, topLanguage = 0;
        for (double v : s.genres.values()) topGenre = Math.max(topGenre, Math.abs(v));
        for (double v : s.languages.values()) topLanguage = Math.max(topLanguage, Math.abs(v));
        List<double[]> scored = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Discover.Hit h = pool.get(i);
            String key = h.kind + ":" + h.id;
            double score = 1 - i / (double) Math.max(1, n);
            if (topGenre > 0) {
                // the genre you like most among its genres; below zero when you've only thumbed its genres down
                double best = 0;
                boolean any = false;
                for (int g : h.genres) {
                    Double w = s.genres.get(g);
                    if (w == null) continue;
                    best = any ? Math.max(best, w) : w;
                    any = true;
                }
                score += 0.6 * best / topGenre;
            }
            if (topLanguage > 0) score += 0.4 * s.languages.getOrDefault(h.language, 0.0) / topLanguage;
            score += 0.3 * fresh(h.date, today);
            score += 0.25 * jitter(key, today.toEpochDay());
            if (!s.engaged.contains(key)) score -= 0.06 * Math.min(6, s.shown.getOrDefault(key, 0));
            if (s.openedRecently.contains(key)) score -= 0.5;
            scored.add(new double[]{score, i});
        }
        scored.sort((a, b) -> Double.compare(b[0], a[0]));
        List<Discover.Hit> order = new ArrayList<>();
        for (double[] x : scored) order.add(pool.get((int) x[1]));
        return spread(order);
    }

    /** 1 for a title out today, falling to 0 at 60 days old (and for older or undated ones). */
    static double fresh(String date, LocalDate today) {
        if (date == null || date.length() < 10) return 0;
        try {
            long days = today.toEpochDay() - LocalDate.parse(date.substring(0, 10)).toEpochDay();
            return days < 0 || days > 60 ? 0 : 1 - days / 60.0;
        } catch (java.time.format.DateTimeParseException e) {
            return 0;
        }
    }

    /** A number in [0, 1) fixed for a title on a day, different the next day. */
    static double jitter(String key, long epochDay) {
        long h = (key.hashCode() * 0x9E3779B97F4A7C15L) ^ (epochDay * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) / (double) (1L << 53);
    }

    /** No more than two in a row from one series (titles whose names start with the same word). */
    static List<Discover.Hit> spread(List<Discover.Hit> order) {
        List<Discover.Hit> left = new ArrayList<>(order), out = new ArrayList<>();
        while (!left.isEmpty()) {
            int pick = 0;
            if (out.size() >= 2) {
                String a = series(out.get(out.size() - 1).name), b = series(out.get(out.size() - 2).name);
                if (!a.isEmpty() && a.equals(b)) {
                    for (int i = 0; i < left.size(); i++) {
                        if (!a.equals(series(left.get(i).name))) {
                            pick = i;
                            break;
                        }
                    }
                }
            }
            out.add(left.remove(pick));
        }
        return out;
    }

    /** A name's series key: its first real word ("golmaal" for Golmaal Returns), or "" when too short to tell. */
    static String series(String name) {
        for (String w : name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.isEmpty() || w.equals("the") || w.equals("a") || w.equals("an")) continue;
            return w.length() >= 4 ? w : "";
        }
        return "";
    }
}

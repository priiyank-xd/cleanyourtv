package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * TMDB for the LG-style home (fork only): which streaming services in India your installed apps are, the URLs
 * for "New this week" and "Trending", and parsing their answers. Everything but {@link #get} is pure, so it is
 * unit tested. Data comes from TMDB; this product uses the TMDB API but is not endorsed or certified by TMDB.
 */
final class Tmdb {
    static final String API = "https://api.themoviedb.org/3/";
    static final String IMAGES = "https://image.tmdb.org/t/p/";
    static final String REGION = "IN", LANGUAGE = "en-IN";
    /** TMDB genre ids for talk, news and reality: long-running daily shows, never "new this week". */
    static final String SKIP_TV_GENRES = "10767|10763|10764";

    /** TMDB's India provider ids (watch/providers?watch_region=IN) for the apps' packages. */
    static final Map<String, int[]> PROVIDERS = new LinkedHashMap<>();
    static {
        PROVIDERS.put("com.netflix.ninja", new int[]{8});
        PROVIDERS.put("com.amazon.amazonvideo.livingroom", new int[]{119});
        PROVIDERS.put("in.startv.hotstar", new int[]{2336});
        PROVIDERS.put("com.graymatrix.did", new int[]{232});
        PROVIDERS.put("com.sonyliv", new int[]{237});
        PROVIDERS.put("com.apple.atve.androidtv.appletv", new int[]{350});
        PROVIDERS.put("com.mxtech.videoplayer.television", new int[]{515, 1898});
        PROVIDERS.put("com.plexapp.android", new int[]{538});
    }

    /** One movie or series. */
    static final class Title {
        final int id;
        final String kind;   // "movie" or "tv"
        final String name;
        final String date;   // release or first air date, yyyy-mm-dd, may be empty
        final String backdrop;
        final double popularity;
        int provider;        // TMDB provider id it streams on in India for you, 0 until known

        Title(int id, String kind, String name, String date, String backdrop, double popularity) {
            this.id = id;
            this.kind = kind;
            this.name = name;
            this.date = date;
            this.backdrop = backdrop;
            this.popularity = popularity;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("kind", kind).put("name", name).put("date", date)
                    .put("backdrop", backdrop).put("popularity", popularity).put("provider", provider);
        }

        static Title fromJson(JSONObject o) {
            Title t = new Title(o.optInt("id"), o.optString("kind"), o.optString("name"), o.optString("date"),
                    o.optString("backdrop"), o.optDouble("popularity", 0));
            t.provider = o.optInt("provider");
            return t;
        }
    }

    private Tmdb() {
    }

    // ---- which services are yours ----

    /** Provider ids of the streaming apps installed, in a stable order. */
    static Set<Integer> providerIds(Collection<String> installedPackages) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Map.Entry<String, int[]> e : PROVIDERS.entrySet()) {
            if (!installedPackages.contains(e.getKey())) continue;
            for (int id : e.getValue()) ids.add(id);
        }
        return ids;
    }

    /** The package of the app for a provider id, or null. */
    static String packageFor(int providerId) {
        for (Map.Entry<String, int[]> e : PROVIDERS.entrySet()) {
            for (int id : e.getValue()) if (id == providerId) return e.getKey();
        }
        return null;
    }

    // ---- URLs ----

    static String newMoviesUrl(Set<Integer> providers, LocalDate today) {
        return discover("movie", providers) + "&primary_release_date.gte=" + today.minusDays(30)
                + "&primary_release_date.lte=" + today;
    }

    static String newSeriesUrl(Set<Integer> providers, LocalDate today) {
        return discover("tv", providers) + "&first_air_date.gte=" + today.minusDays(45)
                + "&first_air_date.lte=" + today + "&without_genres=" + enc(SKIP_TV_GENRES);
    }

    static String trendingUrl() {
        return API + "trending/all/day?language=" + LANGUAGE;
    }

    static String providersUrl(String kind, int id) {
        return API + kind + "/" + id + "/watch/providers";
    }

    static String imageUrl(String path, String size) {
        return IMAGES + size + path;
    }

    private static String discover(String kind, Set<Integer> providers) {
        StringJoiner ids = new StringJoiner("|");
        for (int id : providers) ids.add(String.valueOf(id));
        return API + "discover/" + kind + "?watch_region=" + REGION + "&language=" + LANGUAGE
                + "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&include_adult=false"
                + "&with_watch_providers=" + enc(ids.toString());
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    // ---- parsing ----

    /** Results of a discover or trending answer. {@code kind} is null for trending, which names each one's type. */
    static List<Title> parseTitles(JSONObject answer, String kind) {
        List<Title> out = new ArrayList<>();
        JSONArray results = answer == null ? null : answer.optJSONArray("results");
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String k = kind != null ? kind : r.optString("media_type");
            if (!"movie".equals(k) && !"tv".equals(k)) continue;
            String name = r.optString("movie".equals(k) ? "title" : "name", "").trim();
            String backdrop = r.optString("backdrop_path", "");
            if (name.isEmpty() || backdrop.isEmpty() || "null".equals(backdrop)) continue;
            String date = r.optString("movie".equals(k) ? "release_date" : "first_air_date", "");
            out.add(new Title(r.optInt("id"), k, name, date, backdrop, r.optDouble("popularity", 0)));
        }
        return out;
    }

    /**
     * The first of your providers this title streams on in India (flatrate, in TMDB's display order), or 0.
     * {@code answer} is a watch/providers response.
     */
    static int streamingOn(JSONObject answer, Set<Integer> mine) {
        JSONObject results = answer == null ? null : answer.optJSONObject("results");
        JSONObject region = results == null ? null : results.optJSONObject(REGION);
        JSONArray flat = region == null ? null : region.optJSONArray("flatrate");
        if (flat == null) return 0;
        for (int i = 0; i < flat.length(); i++) {
            JSONObject p = flat.optJSONObject(i);
            if (p != null && mine.contains(p.optInt("provider_id"))) return p.optInt("provider_id");
        }
        return 0;
    }

    /**
     * Which of {@code count} backdrops the movie-art background shows at {@code nowMs}: the next one every
     * {@code rotateMs}, from the clock alone, so a restart of Home keeps the picture until its turn ends.
     */
    static int artIndex(long nowMs, long rotateMs, int count) {
        if (count <= 0 || rotateMs <= 0) return 0;
        return (int) ((nowMs / rotateMs) % count);
    }

    /** Movies and series together, most popular first, without repeats, at most {@code max}. */
    static List<Title> merge(List<Title> a, List<Title> b, int max) {
        List<Title> all = new ArrayList<>(a);
        all.addAll(b);
        Collections.sort(all, (x, y) -> Double.compare(y.popularity, x.popularity));
        List<Title> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Title t : all) {
            if (out.size() >= max) break;
            if (seen.add(t.kind + t.id)) out.add(t);
        }
        return out;
    }

    // ---- network (background thread only) ----

    static JSONObject get(String url, String token) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("TMDB " + code);
            return new JSONObject(new String(read(c.getInputStream()), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    static byte[] download(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("image " + c.getResponseCode());
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}

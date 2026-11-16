package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discover, the launcher's own search (fork only): TMDB search and title details, where a title streams in India,
 * and the words the screens show. Everything here is pure, so it is unit tested; SearchActivity and TitleActivity
 * do the drawing and DiscoverNet the fetching. Data comes from TMDB, availability from JustWatch through TMDB.
 */
final class Discover {
    /**
     * Providers that are apps on Google TV but are not in {@link Tmdb#PROVIDERS}, which also picks Home's rows:
     * rent and buy stores, so their rows on a title can open the app.
     */
    static final Map<Integer, String> EXTRA_PACKAGES = new HashMap<>();
    static {
        EXTRA_PACKAGES.put(192, "com.google.android.youtube.tv");
    }

    /** Results the search screen keeps. */
    static final int MAX_RESULTS = 20;
    /** Recent searches kept on the projector. */
    static final int MAX_RECENT = 8;

    private Discover() {
    }

    /** The app package for a TMDB provider id, or null when no app is known for it. */
    static String packageFor(int provider) {
        String p = Tmdb.packageFor(provider);
        return p != null ? p : EXTRA_PACKAGES.get(provider);
    }

    // ---- search ----

    /** A movie or series in the results. */
    static final class Hit {
        final String kind;   // "movie" or "tv"
        final int id;
        final String name;
        final String year;   // may be empty
        final String backdrop, poster;   // TMDB paths, may be empty

        Hit(String kind, int id, String name, String year, String backdrop, String poster) {
            this.kind = kind;
            this.id = id;
            this.name = name;
            this.year = year;
            this.backdrop = backdrop;
            this.poster = poster;
        }

        /** The landscape picture when there is one, else the poster. */
        String picture() {
            return !backdrop.isEmpty() ? backdrop : poster;
        }
    }

    static String searchUrl(String query) {
        return Tmdb.API + "search/multi?query=" + enc(query.trim()) + "&language=" + Tmdb.LANGUAGE
                + "&region=" + Tmdb.REGION + "&include_adult=false&page=1";
    }

    /** Movies and series of a search answer, in TMDB's order (most relevant first), at most {@code max}. */
    static List<Hit> parseSearch(JSONObject answer, int max) {
        List<Hit> out = new ArrayList<>();
        JSONArray results = answer == null ? null : answer.optJSONArray("results");
        if (results == null) return out;
        for (int i = 0; i < results.length() && out.size() < max; i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null || r.optBoolean("adult")) continue;
            String kind = r.optString("media_type");
            if (!"movie".equals(kind) && !"tv".equals(kind)) continue;   // people come with their own page later
            boolean movie = "movie".equals(kind);
            String name = r.optString(movie ? "title" : "name", "").trim();
            if (name.isEmpty()) continue;
            out.add(new Hit(kind, r.optInt("id"), name, year(r.optString(movie ? "release_date" : "first_air_date", "")),
                    path(r, "backdrop_path"), path(r, "poster_path")));
        }
        return out;
    }

    // ---- recent searches ----

    /** {@code q} first, without an earlier copy of it (any case), at most {@code max}. Blank queries change nothing. */
    static List<String> addRecent(List<String> recent, String q, int max) {
        List<String> out = new ArrayList<>();
        String t = q == null ? "" : q.trim();
        if (!t.isEmpty()) out.add(t);
        for (String r : recent) {
            if (out.size() >= max) break;
            if (!r.trim().isEmpty() && !r.trim().equalsIgnoreCase(t)) out.add(r.trim());
        }
        return out;
    }

    static List<String> splitRecent(String saved) {
        List<String> out = new ArrayList<>();
        if (saved == null) return out;
        for (String s : saved.split("\n")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    static String joinRecent(List<String> recent) {
        return String.join("\n", recent);
    }

    // ---- title details ----

    static final class Provider {
        final int id;
        final String name;
        final String logo;   // TMDB path, may be empty

        Provider(int id, String name, String logo) {
            this.id = id;
            this.name = name;
            this.logo = logo;
        }
    }

    /** Ways to watch a title in India, each group in TMDB's display order. Free includes free-with-ads. */
    static final class Availability {
        final List<Provider> stream = new ArrayList<>(), rent = new ArrayList<>(), buy = new ArrayList<>(),
                free = new ArrayList<>();

        boolean isEmpty() {
            return stream.isEmpty() && rent.isEmpty() && buy.isEmpty() && free.isEmpty();
        }
    }

    static final class Details {
        String kind = "movie", name = "", year = "", overview = "", backdrop = "", cert = "", director = "";
        int id, minutes, seasons;
        final List<String> genres = new ArrayList<>();
        final List<String> cast = new ArrayList<>();
        Availability where = new Availability();
    }

    static String detailsUrl(String kind, int id) {
        boolean movie = "movie".equals(kind);
        return Tmdb.API + (movie ? "movie/" : "tv/") + id + "?language=" + Tmdb.LANGUAGE
                + "&append_to_response=credits,watch/providers," + (movie ? "release_dates" : "content_ratings");
    }

    static Details parseDetails(JSONObject o, String kind) {
        Details d = new Details();
        if (o == null) return d;
        boolean movie = "movie".equals(kind);
        d.kind = movie ? "movie" : "tv";
        d.id = o.optInt("id");
        d.name = o.optString(movie ? "title" : "name", "").trim();
        d.year = year(o.optString(movie ? "release_date" : "first_air_date", ""));
        d.overview = o.optString("overview", "").trim();
        d.backdrop = path(o, "backdrop_path");
        if (d.backdrop.isEmpty()) d.backdrop = path(o, "poster_path");
        JSONArray genres = o.optJSONArray("genres");
        if (genres != null) {
            for (int i = 0; i < genres.length(); i++) {
                JSONObject g = genres.optJSONObject(i);
                if (g != null && !g.optString("name").isEmpty()) d.genres.add(g.optString("name"));
            }
        }
        if (movie) d.minutes = o.optInt("runtime");
        else d.seasons = o.optInt("number_of_seasons");
        JSONObject credits = o.optJSONObject("credits");
        JSONArray cast = credits == null ? null : credits.optJSONArray("cast");
        if (cast != null) {
            for (int i = 0; i < cast.length() && d.cast.size() < 2; i++) {
                JSONObject c = cast.optJSONObject(i);
                if (c != null && !c.optString("name").isEmpty()) d.cast.add(c.optString("name"));
            }
        }
        if (movie) {
            JSONArray crew = credits == null ? null : credits.optJSONArray("crew");
            if (crew != null) {
                for (int i = 0; i < crew.length(); i++) {
                    JSONObject c = crew.optJSONObject(i);
                    if (c != null && "Director".equals(c.optString("job"))) { d.director = c.optString("name"); break; }
                }
            }
        } else {
            JSONArray by = o.optJSONArray("created_by");
            JSONObject first = by == null ? null : by.optJSONObject(0);
            if (first != null) d.director = first.optString("name", "");
        }
        d.cert = movie ? movieCert(o.optJSONObject("release_dates")) : tvCert(o.optJSONObject("content_ratings"));
        d.where = parseAvailability(o.optJSONObject("watch/providers"));
        return d;
    }

    /** India's groups of a watch/providers object (or a whole watch/providers answer). */
    static Availability parseAvailability(JSONObject providers) {
        Availability a = new Availability();
        JSONObject results = providers == null ? null : providers.optJSONObject("results");
        JSONObject in = results == null ? null : results.optJSONObject(Tmdb.REGION);
        if (in == null) return a;
        add(a.stream, in.optJSONArray("flatrate"));
        add(a.rent, in.optJSONArray("rent"));
        add(a.buy, in.optJSONArray("buy"));
        add(a.free, in.optJSONArray("free"));
        add(a.free, in.optJSONArray("ads"));
        return a;
    }

    /**
     * The provider the Watch button uses: the first streaming one whose app is installed; else the first streaming
     * one with a known app (the button then offers to get it); else null (the button opens Google TV's search).
     */
    static Provider primary(Availability a, Collection<String> installed) {
        for (Provider p : a.stream) {
            String pkg = packageFor(p.id);
            if (pkg != null && installed.contains(pkg)) return p;
        }
        for (Provider p : a.stream) if (packageFor(p.id) != null) return p;
        return null;
    }

    /** Up to {@code max} other providers for the "Also on" line: streaming first, then rent, buy and free. */
    static List<Provider> others(Availability a, Provider primary, int max) {
        List<Provider> out = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        if (primary != null) seen.add(primary.id);
        for (List<Provider> group : java.util.Arrays.asList(a.stream, a.rent, a.buy, a.free)) {
            for (Provider p : group) {
                if (out.size() >= max) return out;
                if (seen.add(p.id)) out.add(p);
            }
        }
        return out;
    }

    // ---- words on screen ----

    /** "Movie · 2025 · Science Fiction, Adventure · 2h 18m · U/A 13+": what's known, at most two genres. */
    static String facts(Details d) {
        List<String> parts = new ArrayList<>();
        parts.add("movie".equals(d.kind) ? "Movie" : "Series");
        if (!d.year.isEmpty()) parts.add(d.year);
        if (!d.genres.isEmpty()) parts.add(String.join(", ", d.genres.subList(0, Math.min(2, d.genres.size()))));
        if ("movie".equals(d.kind) && d.minutes > 0) parts.add(runtime(d.minutes));
        if (!"movie".equals(d.kind) && d.seasons > 0) parts.add(d.seasons == 1 ? "1 season" : d.seasons + " seasons");
        if (!d.cert.isEmpty()) parts.add(d.cert);
        return String.join(" · ", parts);
    }

    /** "Ritu Saran · Tara Fernandes, Arjun Sethi", or what's known of it. */
    static String credits(Details d) {
        List<String> parts = new ArrayList<>();
        if (!d.director.isEmpty()) parts.add(d.director);
        if (!d.cast.isEmpty()) parts.add(String.join(", ", d.cast));
        return String.join(" · ", parts);
    }

    static String runtime(int minutes) {
        if (minutes <= 0) return "";
        int h = minutes / 60, m = minutes % 60;
        if (h == 0) return m + "m";
        return m == 0 ? h + "h" : h + "h " + m + "m";
    }

    /** India's age rating as people write it: "UA 13+" becomes "U/A 13+". */
    static String cert(String raw) {
        String c = raw == null ? "" : raw.trim();
        if (c.startsWith("UA")) c = "U/A" + c.substring(2);
        return c;
    }

    // ---- helpers ----

    private static String movieCert(JSONObject releaseDates) {
        JSONArray results = releaseDates == null ? null : releaseDates.optJSONArray("results");
        if (results == null) return "";
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null || !Tmdb.REGION.equals(r.optString("iso_3166_1"))) continue;
            JSONArray dates = r.optJSONArray("release_dates");
            if (dates == null) return "";
            for (int j = 0; j < dates.length(); j++) {
                JSONObject rd = dates.optJSONObject(j);
                if (rd != null && !rd.optString("certification").trim().isEmpty()) return cert(rd.optString("certification"));
            }
        }
        return "";
    }

    private static String tvCert(JSONObject ratings) {
        JSONArray results = ratings == null ? null : ratings.optJSONArray("results");
        if (results == null) return "";
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r != null && Tmdb.REGION.equals(r.optString("iso_3166_1"))) return cert(r.optString("rating"));
        }
        return "";
    }

    private static void add(List<Provider> into, JSONArray list) {
        if (list == null) return;
        List<JSONObject> sorted = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) if (list.optJSONObject(i) != null) sorted.add(list.optJSONObject(i));
        Collections.sort(sorted, (x, y) -> Integer.compare(x.optInt("display_priority", 999), y.optInt("display_priority", 999)));
        for (JSONObject p : sorted) {
            int id = p.optInt("provider_id");
            boolean dup = false;
            for (Provider q : into) dup |= q.id == id;
            if (!dup && id != 0) into.add(new Provider(id, p.optString("provider_name", "").trim(), path(p, "logo_path")));
        }
    }

    private static String year(String date) {
        return date != null && date.length() >= 4 ? date.substring(0, 4) : "";
    }

    private static String path(JSONObject o, String key) {
        String v = o.optString(key, "");
        return v == null || "null".equals(v) ? "" : v;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }
}

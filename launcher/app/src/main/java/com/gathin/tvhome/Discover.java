package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

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

    /** A movie, a series or a person in a list or a search. */
    static final class Hit {
        final String kind;   // "movie", "tv" or "person"
        final int id;
        final String name;
        final String date;   // release, first air or nothing (people): yyyy-mm-dd, may be empty
        final String year;   // may be empty
        final String backdrop, poster;   // TMDB paths, may be empty; a person's photo is the poster
        final double popularity;
        /** A person's department ("Acting", "Directing"); a credit's role ("Anjali"); else empty. */
        final String sub;

        Hit(String kind, int id, String name, String date, String backdrop, String poster, double popularity, String sub) {
            this.kind = kind;
            this.id = id;
            this.name = name;
            this.date = date;
            this.year = year(date);
            this.backdrop = backdrop;
            this.poster = poster;
            this.popularity = popularity;
            this.sub = sub;
        }

        /** The landscape picture when there is one, else the poster. */
        String picture() {
            return !backdrop.isEmpty() ? backdrop : poster;
        }

        boolean isPerson() {
            return "person".equals(kind);
        }
    }

    /** One result of a TMDB list or search; {@code kind} when the list has one kind, else its media_type. Null to skip. */
    static Hit hit(JSONObject r, String kind) {
        if (r == null || r.optBoolean("adult")) return null;
        String k = kind != null ? kind : r.optString("media_type");
        if ("person".equals(k)) {
            String name = r.optString("name", "").trim();
            if (name.isEmpty()) return null;
            return new Hit(k, r.optInt("id"), name, "", "", path(r, "profile_path"), r.optDouble("popularity", 0),
                    r.optString("known_for_department", ""));
        }
        if (!"movie".equals(k) && !"tv".equals(k)) return null;
        boolean movie = "movie".equals(k);
        String name = r.optString(movie ? "title" : "name", "").trim();
        if (name.isEmpty()) return null;
        return new Hit(k, r.optInt("id"), name, r.optString(movie ? "release_date" : "first_air_date", ""),
                path(r, "backdrop_path"), path(r, "poster_path"), r.optDouble("popularity", 0), r.optString("character", ""));
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
            Hit h = hit(results.optJSONObject(i), null);
            if (h != null && !h.isPerson()) out.add(h);
        }
        return out;
    }

    /** The people of a search answer, in TMDB's order, at most {@code max}. */
    static List<Hit> parsePeople(JSONObject answer, int max) {
        List<Hit> out = new ArrayList<>();
        JSONArray results = answer == null ? null : answer.optJSONArray("results");
        if (results == null) return out;
        for (int i = 0; i < results.length() && out.size() < max; i++) {
            Hit h = hit(results.optJSONObject(i), null);
            if (h != null && h.isPerson()) out.add(h);
        }
        return out;
    }

    /** The titles of a discover, upcoming or recommendations answer; {@code kind} null when each names its own. */
    static List<Hit> parseList(JSONObject answer, String kind, int max) {
        List<Hit> out = new ArrayList<>();
        JSONArray results = answer == null ? null : answer.optJSONArray("results");
        if (results == null) return out;
        for (int i = 0; i < results.length() && out.size() < max; i++) {
            Hit h = hit(results.optJSONObject(i), kind);
            if (h != null && !h.isPerson()) out.add(h);
        }
        return out;
    }

    /** Two lists together, most popular first, without repeats, at most {@code max}. */
    static List<Hit> merge(List<Hit> a, List<Hit> b, int max) {
        List<Hit> all = new ArrayList<>(a);
        all.addAll(b);
        Collections.sort(all, (x, y) -> Double.compare(y.popularity, x.popularity));
        List<Hit> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Hit h : all) {
            if (out.size() >= max) break;
            if (seen.add(h.kind + h.id)) out.add(h);
        }
        return out;
    }

    // ---- Discover's shelves ----

    /**
     * Titles that stream in India on your apps, most popular first; only those first out within {@code withinDays}
     * of {@code today} when that is above zero. TMDB has no per-country chart, so "popular on your apps" is this.
     */
    static String popularUrl(String kind, Set<Integer> providers, int withinDays, LocalDate today) {
        boolean movie = "movie".equals(kind);
        StringBuilder u = new StringBuilder(Tmdb.API + "discover/" + (movie ? "movie" : "tv") + "?watch_region=" + Tmdb.REGION
                + "&language=" + Tmdb.LANGUAGE + "&include_adult=false&sort_by=popularity.desc"
                + "&with_watch_monetization_types=flatrate&with_watch_providers=" + enc(ids(providers)));
        if (!movie) u.append("&without_genres=").append(enc(Tmdb.SKIP_TV_GENRES));
        if (withinDays > 0) {
            String field = movie ? "primary_release_date" : "first_air_date";
            u.append("&").append(field).append(".gte=").append(today.minusDays(withinDays))
                    .append("&").append(field).append(".lte=").append(today);
        }
        return u.toString();
    }

    /** Films coming to cinemas in India. TMDB has no dates for streaming arrivals. */
    static String upcomingUrl() {
        return Tmdb.API + "movie/upcoming?region=" + Tmdb.REGION + "&language=" + Tmdb.LANGUAGE + "&page=1";
    }

    /** Only titles still to come, soonest first. */
    static List<Hit> upcoming(List<Hit> list, LocalDate today, int max) {
        List<Hit> out = new ArrayList<>();
        for (Hit h : list) if (h.date.length() >= 10 && h.date.compareTo(today.toString()) > 0) out.add(h);
        Collections.sort(out, (a, b) -> a.date.compareTo(b.date));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** "4 Dec" from "2026-12-04"; empty when the date is not a full date. */
    static String shortDate(String date) {
        if (date == null || date.length() < 10) return "";
        try {
            LocalDate d = LocalDate.parse(date.substring(0, 10));
            return d.getDayOfMonth() + " " + MONTHS[d.getMonthValue() - 1];
        } catch (java.time.format.DateTimeParseException e) {
            return "";
        }
    }

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    // ---- browse: genres and languages ----

    /** A genre as Browse shows it; TMDB numbers movie and series genres apart. 0: no series genre of that kind. */
    static final class Genre {
        final String name;
        final int movie, tv;

        Genre(String name, int movie, int tv) {
            this.name = name;
            this.movie = movie;
            this.tv = tv;
        }

        int idFor(String kind) {
            return "movie".equals(kind) ? movie : tv;
        }
    }

    static final List<Genre> GENRES = java.util.Arrays.asList(
            new Genre("Action", 28, 10759), new Genre("Comedy", 35, 35), new Genre("Drama", 18, 18),
            new Genre("Thriller", 53, 9648), new Genre("Crime", 80, 80), new Genre("Sci-Fi", 878, 10765),
            new Genre("Romance", 10749, 0), new Genre("Horror", 27, 0), new Genre("Family", 10751, 10751),
            new Genre("Animation", 16, 16), new Genre("Documentary", 99, 99));

    /** A language as Browse shows it: TMDB's original-language code, its own name and its English name. */
    static final class Language {
        final String code, own, english;

        Language(String code, String own, String english) {
            this.code = code;
            this.own = own;
            this.english = english;
        }
    }

    static final List<Language> LANGUAGES = java.util.Arrays.asList(
            new Language("hi", "\u0939\u093f\u0928\u094d\u0926\u0940", "Hindi"),
            new Language("ta", "\u0ba4\u0bae\u0bbf\u0bb4\u0bcd", "Tamil"),
            new Language("te", "\u0c24\u0c46\u0c32\u0c41\u0c17\u0c41", "Telugu"),
            new Language("ml", "\u0d2e\u0d32\u0d2f\u0d3e\u0d33\u0d02", "Malayalam"),
            new Language("kn", "\u0c95\u0ca8\u0ccd\u0ca8\u0ca1", "Kannada"),
            new Language("bn", "\u09ac\u09be\u0982\u09b2\u09be", "Bengali"),
            new Language("mr", "\u092e\u0930\u093e\u0920\u0940", "Marathi"),
            new Language("en", "English", "English"),
            new Language("ko", "\ud55c\uad6d\uc5b4", "Korean"));

    static final String SORT_POPULAR = "popular", SORT_TOP = "top", SORT_NEW = "new";

    /**
     * A page of a Browse grid. {@code genre} 0 and {@code language} null mean any; {@code providers} null means
     * every service, else only those (Only my apps). Top rated asks for enough votes to mean something; Newest
     * leaves out what isn't out yet.
     */
    static String browseUrl(String kind, int genre, String language, String sort, Set<Integer> providers, int page, LocalDate today) {
        boolean movie = "movie".equals(kind);
        String date = movie ? "primary_release_date" : "first_air_date";
        StringBuilder u = new StringBuilder(Tmdb.API + "discover/" + (movie ? "movie" : "tv") + "?watch_region=" + Tmdb.REGION
                + "&language=" + Tmdb.LANGUAGE + "&include_adult=false&page=" + page);
        if (SORT_TOP.equals(sort)) u.append("&sort_by=vote_average.desc&vote_count.gte=").append(movie ? 300 : 150);
        else if (SORT_NEW.equals(sort)) u.append("&sort_by=").append(date).append(".desc&").append(date).append(".lte=").append(today);
        else u.append("&sort_by=popularity.desc");
        if (genre > 0) u.append("&with_genres=").append(genre);
        if (language != null && !language.isEmpty()) u.append("&with_original_language=").append(language);
        if (!movie) u.append("&without_genres=").append(enc(Tmdb.SKIP_TV_GENRES));
        if (providers != null) u.append("&with_watch_monetization_types=flatrate&with_watch_providers=").append(enc(ids(providers)));
        return u.toString();
    }

    /** total_results of a list answer, 0 when missing. */
    static int total(JSONObject answer) {
        return answer == null ? 0 : answer.optInt("total_results");
    }

    // ---- people ----

    static final class PersonPage {
        String name = "", dept = "", born = "", bio = "", photo = "";
        final List<Hit> knownFor = new ArrayList<>(), all = new ArrayList<>();
    }

    static String personUrl(int id) {
        return Tmdb.API + "person/" + id + "?language=" + Tmdb.LANGUAGE + "&append_to_response=combined_credits";
    }

    /** A person's page: their work as actor, or as crew for everyone else; known for = most voted, all = newest first. */
    static PersonPage parsePerson(JSONObject o) {
        PersonPage p = new PersonPage();
        if (o == null) return p;
        p.name = o.optString("name", "").trim();
        p.dept = o.optString("known_for_department", "");
        p.bio = o.optString("biography", "").trim();
        p.photo = path(o, "profile_path");
        String birthday = o.optString("birthday", ""), place = o.optString("place_of_birth", "");
        String day = longDate(birthday);
        if (!day.isEmpty()) p.born = "Born " + day + (place == null || place.isEmpty() || "null".equals(place) ? "" : ", " + place.trim());
        JSONObject credits = o.optJSONObject("combined_credits");
        JSONArray list = credits == null ? null : credits.optJSONArray("Acting".equals(p.dept) ? "cast" : "crew");
        if (list == null && credits != null) list = credits.optJSONArray("cast");
        List<Hit> work = new ArrayList<>();
        List<Integer> votes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject r = list.optJSONObject(i);
                Hit h = hit(r, null);
                if (h == null || h.isPerson() || !seen.add(h.kind + h.id)) continue;
                work.add(h);
                votes.add(r.optInt("vote_count"));
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < work.size(); i++) order.add(i);
        Collections.sort(order, (a, b) -> Integer.compare(votes.get(b), votes.get(a)));
        for (int i = 0; i < Math.min(8, order.size()); i++) p.knownFor.add(work.get(order.get(i)));
        List<Hit> byDate = new ArrayList<>(work);
        Collections.sort(byDate, (a, b) -> b.date.compareTo(a.date));
        for (int i = 0; i < Math.min(40, byDate.size()); i++) p.all.add(byDate.get(i));
        return p;
    }

    /** "14 March 1990" from "1990-03-14"; empty when not a full date. */
    static String longDate(String date) {
        if (date == null || date.length() < 10) return "";
        try {
            LocalDate d = LocalDate.parse(date.substring(0, 10));
            return d.getDayOfMonth() + " " + LONG_MONTHS[d.getMonthValue() - 1] + " " + d.getYear();
        } catch (java.time.format.DateTimeParseException e) {
            return "";
        }
    }

    private static final String[] LONG_MONTHS = {"January", "February", "March", "April", "May", "June", "July",
            "August", "September", "October", "November", "December"};

    // ---- seasons ----

    static final class Episode {
        final int number, minutes;
        final String name, date, still;

        Episode(int number, String name, int minutes, String date, String still) {
            this.number = number;
            this.name = name;
            this.minutes = minutes;
            this.date = date;
            this.still = still;
        }
    }

    static String seasonUrl(int tvId, int season) {
        return Tmdb.API + "tv/" + tvId + "/season/" + season + "?language=" + Tmdb.LANGUAGE;
    }

    static List<Episode> parseSeason(JSONObject o) {
        List<Episode> out = new ArrayList<>();
        JSONArray eps = o == null ? null : o.optJSONArray("episodes");
        if (eps == null) return out;
        for (int i = 0; i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e == null) continue;
            String name = e.optString("name", "").trim();
            int n = e.optInt("episode_number");
            out.add(new Episode(n, name.isEmpty() ? "Episode " + n : name, e.optInt("runtime"), e.optString("air_date", ""),
                    path(e, "still_path")));
        }
        return out;
    }

    /** "52m · 12 Jun 2026", or what's known of it. */
    static String episodeLine(Episode e) {
        List<String> parts = new ArrayList<>();
        if (e.minutes > 0) parts.add(runtime(e.minutes));
        String d = shortDate(e.date);
        if (!d.isEmpty()) parts.add(d + " " + e.date.substring(0, 4));
        return String.join(" \u00b7 ", parts);
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
        int directorId;
        final List<String> genres = new ArrayList<>();
        final List<String> cast = new ArrayList<>();
        /** Cast with their roles, then the director or creator, for the Cast and crew row. */
        final List<Hit> people = new ArrayList<>();
        /** Season numbers and names, specials left out. */
        final List<int[]> seasonList = new ArrayList<>();
        final List<String> seasonNames = new ArrayList<>();
        final List<Hit> more = new ArrayList<>();
        Availability where = new Availability();
    }

    static String detailsUrl(String kind, int id) {
        boolean movie = "movie".equals(kind);
        return Tmdb.API + (movie ? "movie/" : "tv/") + id + "?language=" + Tmdb.LANGUAGE
                + "&append_to_response=credits,watch/providers,recommendations," + (movie ? "release_dates" : "content_ratings");
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
            for (int i = 0; i < cast.length() && d.people.size() < 12; i++) {
                JSONObject c = cast.optJSONObject(i);
                if (c == null || c.optString("name").isEmpty()) continue;
                if (d.cast.size() < 2) d.cast.add(c.optString("name"));
                d.people.add(new Hit("person", c.optInt("id"), c.optString("name").trim(), "", "", path(c, "profile_path"), 0,
                        c.optString("character", "").trim()));
            }
        }
        if (movie) {
            JSONArray crew = credits == null ? null : credits.optJSONArray("crew");
            if (crew != null) {
                for (int i = 0; i < crew.length(); i++) {
                    JSONObject c = crew.optJSONObject(i);
                    if (c != null && "Director".equals(c.optString("job"))) {
                        d.director = c.optString("name");
                        d.directorId = c.optInt("id");
                        d.people.add(new Hit("person", d.directorId, d.director, "", "", path(c, "profile_path"), 0, "Director"));
                        break;
                    }
                }
            }
        } else {
            JSONArray by = o.optJSONArray("created_by");
            JSONObject first = by == null ? null : by.optJSONObject(0);
            if (first != null) {
                d.director = first.optString("name", "");
                d.directorId = first.optInt("id");
                d.people.add(new Hit("person", d.directorId, d.director, "", "", path(first, "profile_path"), 0, "Creator"));
            }
            JSONArray seasons = o.optJSONArray("seasons");
            if (seasons != null) {
                for (int i = 0; i < seasons.length(); i++) {
                    JSONObject se = seasons.optJSONObject(i);
                    if (se == null || se.optInt("season_number") <= 0 || se.optInt("episode_count") <= 0) continue;
                    d.seasonList.add(new int[]{se.optInt("season_number"), se.optInt("episode_count")});
                    d.seasonNames.add("Season " + se.optInt("season_number"));
                }
            }
        }
        d.more.addAll(parseList(o.optJSONObject("recommendations"), null, 12));
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

    private static String ids(Set<Integer> providers) {
        StringJoiner j = new StringJoiner("|");
        for (int id : providers) j.add(String.valueOf(id));
        return j.toString();
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

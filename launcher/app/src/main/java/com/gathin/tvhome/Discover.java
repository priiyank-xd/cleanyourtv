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
        /** IMDb's id (for OMDb's scores) and a YouTube trailer's video id; empty when unknown. */
        String imdbId = "", trailer = "";
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
                + "&include_video_language=en,hi,null"
                + "&append_to_response=credits,watch/providers,recommendations,external_ids,videos," + (movie ? "release_dates" : "content_ratings");
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
        d.imdbId = o.optString("imdb_id", "");
        JSONObject ext = o.optJSONObject("external_ids");
        if ((d.imdbId.isEmpty() || "null".equals(d.imdbId)) && ext != null) d.imdbId = ext.optString("imdb_id", "");
        if ("null".equals(d.imdbId)) d.imdbId = "";
        d.trailer = trailer(o.optJSONObject("videos"));
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

    /** The YouTube trailer to play: an official trailer first, then any trailer, then a teaser. Empty when none. */
    static String trailer(JSONObject videos) {
        JSONArray list = videos == null ? null : videos.optJSONArray("results");
        if (list == null) return "";
        String any = "", teaser = "";
        for (int i = 0; i < list.length(); i++) {
            JSONObject v = list.optJSONObject(i);
            if (v == null || !"YouTube".equals(v.optString("site")) || v.optString("key").isEmpty()) continue;
            String type = v.optString("type");
            if ("Trailer".equals(type) && v.optBoolean("official")) return v.optString("key");
            if ("Trailer".equals(type) && any.isEmpty()) any = v.optString("key");
            if ("Teaser".equals(type) && teaser.isEmpty()) teaser = v.optString("key");
        }
        return !any.isEmpty() ? any : teaser;
    }

    // ---- OMDb: IMDb and Rotten Tomatoes ----

    /** Scores and awards from OMDb; each empty when OMDb doesn't have it. */
    static final class Ratings {
        String imdb = "", votes = "", tomatoes = "", awards = "";

        boolean isEmpty() {
            return imdb.isEmpty() && tomatoes.isEmpty();
        }
    }

    static String omdbUrl(String imdbId, String key) {
        return "https://www.omdbapi.com/?i=" + enc(imdbId) + "&apikey=" + enc(key);
    }

    static Ratings parseOmdb(JSONObject o) {
        Ratings r = new Ratings();
        if (o == null || !"True".equals(o.optString("Response"))) return r;
        r.imdb = known(o.optString("imdbRating"));
        r.votes = votes(known(o.optString("imdbVotes")));
        JSONArray list = o.optJSONArray("Ratings");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject x = list.optJSONObject(i);
                if (x != null && "Rotten Tomatoes".equals(x.optString("Source"))) r.tomatoes = known(x.optString("Value"));
            }
        }
        r.awards = awards(known(o.optString("Awards")));
        return r;
    }

    /** "1,740,231" as "1.7M", "175,440" as "175K", "950" as is. */
    static String votes(String raw) {
        String digits = raw == null ? "" : raw.replace(",", "").trim();
        if (digits.isEmpty()) return "";
        try {
            long n = Long.parseLong(digits);
            if (n >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0).replace(".0M", "M");
            if (n >= 1_000) return (n / 1_000) + "K";
            return String.valueOf(n);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /** "Won 13 Primetime Emmys. 125 wins & 345 nominations total" as "Won 13 Primetime Emmys · 125 wins". */
    static String awards(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (String p : raw.split("\\.\\s*")) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+) wins?\\b.*").matcher(t);
            if (m.matches()) t = m.group(1) + (m.group(1).equals("1") ? " win" : " wins");
            else if (t.matches("^\\d+ nominations? total$")) t = t.replace(" total", "");
            parts.add(t);
        }
        return String.join(" \u00b7 ", parts);
    }

    private static String known(String v) {
        return v == null || "N/A".equals(v.trim()) ? "" : v.trim();
    }

    // ---- JustWatch: the app's own link to a title ----

    /** JustWatch's API, which apps use but JustWatch doesn't offer publicly: it can change or stop answering. */
    static final String JUSTWATCH = "https://apis.justwatch.com/graphql";

    /** A search for {@code name} in India, asking each offer for its Android TV link and its web page. */
    static String justWatchBody(String name) {
        try {
            JSONObject vars = new JSONObject().put("country", Tmdb.REGION).put("language", "en").put("first", 5)
                    .put("filter", new JSONObject().put("searchQuery", name));
            return new JSONObject().put("query", "query S($country: Country!, $language: Language!, $first: Int!, $filter: TitleFilter) {"
                    + " popularTitles(country: $country, filter: $filter, first: $first, sortBy: POPULAR, sortRandomSeed: 0) {"
                    + " edges { node { objectType content(country: $country, language: $language) { externalIds { tmdbId } }"
                    + " offers(country: $country, platform: WEB) { monetizationType standardWebURL"
                    + " tv: deeplinkURL(platform: ANDROID_TV) package { packageId } } } } } }")
                    .put("variables", vars).toString();
        } catch (org.json.JSONException e) {
            return "{}";
        }
    }

    /**
     * Each provider's link to the title with this TMDB id and kind: the Android TV link when JustWatch has one,
     * else the web page. Streaming offers come before rent and buy. Empty when the title isn't found.
     */
    static Map<Integer, String> parseJustWatch(JSONObject answer, String kind, int tmdbId) {
        Map<Integer, String> out = new HashMap<>();
        JSONObject data = answer == null ? null : answer.optJSONObject("data");
        JSONObject titles = data == null ? null : data.optJSONObject("popularTitles");
        JSONArray edges = titles == null ? null : titles.optJSONArray("edges");
        if (edges == null) return out;
        String type = "movie".equals(kind) ? "MOVIE" : "SHOW";
        for (int i = 0; i < edges.length(); i++) {
            JSONObject e = edges.optJSONObject(i);
            JSONObject node = e == null ? null : e.optJSONObject("node");
            JSONObject content = node == null ? null : node.optJSONObject("content");
            JSONObject ids = content == null ? null : content.optJSONObject("externalIds");
            if (ids == null || !type.equals(node.optString("objectType")) || !String.valueOf(tmdbId).equals(ids.optString("tmdbId"))) continue;
            JSONArray offers = node.optJSONArray("offers");
            if (offers == null) return out;
            for (int pass = 0; pass < 2; pass++) {   // streaming and free first, then rent and buy
                for (int j = 0; j < offers.length(); j++) {
                    JSONObject o = offers.optJSONObject(j);
                    JSONObject pkg = o == null ? null : o.optJSONObject("package");
                    if (pkg == null) continue;
                    String money = o.optString("monetizationType");
                    boolean watchNow = "FLATRATE".equals(money) || "FREE".equals(money) || "ADS".equals(money);
                    if ((pass == 0) != watchNow) continue;
                    int id = pkg.optInt("packageId");
                    String link = o.optString("tv", "");
                    if (link.isEmpty() || "null".equals(link)) link = o.optString("standardWebURL", "");
                    if (id != 0 && !link.isEmpty() && !"null".equals(link) && !out.containsKey(id)) out.put(id, link);
                }
            }
            return out;
        }
        return out;
    }

    // ---- your TMDB account: sign-in, Watch later, Favourites, lists ----

    static final String V4 = "https://api.themoviedb.org/4/";
    static final String WATCHLIST = "watchlist", FAVORITE = "favorite";

    static String requestTokenUrl() {
        return V4 + "auth/request_token";
    }

    static String accessTokenUrl() {
        return V4 + "auth/access_token";
    }

    /** Where the phone approves the sign-in; shown on the TV as a QR code. */
    static String approveUrl(String requestToken) {
        return "https://www.themoviedb.org/auth/access?request_token=" + enc(requestToken);
    }

    static String convertUrl() {
        return Tmdb.API + "authentication/session/convert/4";
    }

    static String accountUrl(String session) {
        return Tmdb.API + "account?session_id=" + enc(session);
    }

    /** Whether a title is on your watchlist and in your favourites (v3, with the session). */
    static String statesUrl(String kind, int id, String session) {
        return Tmdb.API + ("movie".equals(kind) ? "movie/" : "tv/") + id + "/account_states?session_id=" + enc(session);
    }

    /** Adds to or takes off the watchlist or favourites: {@code what} is WATCHLIST or FAVORITE. */
    static String markUrl(String what, int accountId, String session) {
        return Tmdb.API + "account/" + accountId + "/" + what + "?session_id=" + enc(session);
    }

    static String markBody(String what, String kind, int id, boolean on) {
        try {
            return new JSONObject().put("media_type", "movie".equals(kind) ? "movie" : "tv").put("media_id", id).put(what, on).toString();
        } catch (org.json.JSONException e) {
            return "{}";
        }
    }

    /** Your watchlist or favourites of one kind, newest first (v4, with your access token). */
    static String savedUrl(String objectId, String what, String kind, int page) {
        return V4 + "account/" + objectId + "/" + ("movie".equals(kind) ? "movie/" : "tv/")
                + (WATCHLIST.equals(what) ? "watchlist" : "favorites") + "?page=" + page + "&sort_by=created_at.desc&language=" + Tmdb.LANGUAGE;
    }

    static String listsUrl(String objectId, int page) {
        return V4 + "account/" + objectId + "/lists?page=" + page;
    }

    static String listUrl(int listId, int page) {
        return V4 + "list/" + listId + "?page=" + page + "&language=" + Tmdb.LANGUAGE;
    }

    static String newListUrl() {
        return V4 + "list";
    }

    static String newListBody(String name) {
        try {
            return new JSONObject().put("name", name.trim()).put("iso_639_1", "en").put("public", false).toString();
        } catch (org.json.JSONException e) {
            return "{}";
        }
    }

    static String itemsUrl(int listId) {
        return V4 + "list/" + listId + "/items";
    }

    static String itemsBody(String kind, int id) {
        try {
            return new JSONObject().put("items", new JSONArray().put(new JSONObject()
                    .put("media_type", "movie".equals(kind) ? "movie" : "tv").put("media_id", id))).toString();
        } catch (org.json.JSONException e) {
            return "{}";
        }
    }

    /** One of your own lists. */
    static final class UserList {
        final int id, count;
        final String name;

        UserList(int id, String name, int count) {
            this.id = id;
            this.name = name;
            this.count = count;
        }
    }

    static List<UserList> parseLists(JSONObject answer) {
        List<UserList> out = new ArrayList<>();
        JSONArray results = answer == null ? null : answer.optJSONArray("results");
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject l = results.optJSONObject(i);
            if (l == null || l.optInt("id") == 0) continue;
            String name = l.optString("name", "").trim();
            out.add(new UserList(l.optInt("id"), name.isEmpty() ? "List" : name, l.optInt("number_of_items")));
        }
        return out;
    }

    /** {watchlist, favourite} from an account_states answer. */
    static boolean[] parseStates(JSONObject answer) {
        return new boolean[]{answer != null && answer.optBoolean("watchlist"), answer != null && answer.optBoolean("favorite")};
    }

    /** Movies and series of your watchlist (or favourites) together: newest first within each, taken in turn. */
    static List<Hit> interleave(List<Hit> a, List<Hit> b, int max) {
        List<Hit> out = new ArrayList<>();
        for (int i = 0; out.size() < max && (i < a.size() || i < b.size()); i++) {
            if (i < a.size()) out.add(a.get(i));
            if (i < b.size() && out.size() < max) out.add(b.get(i));
        }
        return out;
    }

    // ---- Ask in plain words (Gemini) ----

    /** A fast model alias Google keeps current. Ask only turns a sentence into filters, so the lightest one is enough. */
    static final String GEMINI_MODEL = "gemini-flash-lite-latest";

    /** Streaming services Ask can name, with their TMDB provider ids in India. */
    static final Map<String, int[]> SERVICES = new java.util.LinkedHashMap<>();
    static {
        SERVICES.put("Netflix", new int[]{8});
        SERVICES.put("Prime Video", new int[]{119});
        SERVICES.put("JioHotstar", new int[]{2336});
        SERVICES.put("Zee5", new int[]{232});
        SERVICES.put("SonyLIV", new int[]{237});
        SERVICES.put("Apple TV", new int[]{350});
    }

    /** What Gemini understood of a sentence, as filters TMDB can search with. Empty or 0: not asked for. */
    static final class Ask {
        String kind = "any", language = "", similarTo = "", sort = SORT_POPULAR;
        int maxMinutes, fromYear, toYear;
        final List<String> genres = new ArrayList<>(), services = new ArrayList<>();

        Ask copy() {
            Ask a = new Ask();
            a.kind = kind;
            a.language = language;
            a.similarTo = similarTo;
            a.sort = sort;
            a.maxMinutes = maxMinutes;
            a.fromYear = fromYear;
            a.toYear = toYear;
            a.genres.addAll(genres);
            a.services.addAll(services);
            return a;
        }
    }

    static String geminiUrl() {
        return "https://generativelanguage.googleapis.com/v1beta/models/" + GEMINI_MODEL + ":generateContent";
    }

    /** The request: the sentence, today's date, and a schema that only allows the filters Discover can use. */
    static String geminiBody(String sentence, LocalDate today) {
        try {
            JSONArray genreNames = new JSONArray();
            for (Genre g : GENRES) genreNames.put(g.name);
            JSONArray serviceNames = new JSONArray();
            for (String s : SERVICES.keySet()) serviceNames.put(s);
            JSONObject props = new JSONObject()
                    .put("kind", new JSONObject().put("type", "STRING").put("enum", new JSONArray().put("movie").put("tv").put("any")))
                    .put("genres", new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "STRING").put("enum", genreNames)))
                    .put("language", new JSONObject().put("type", "STRING").put("description", "ISO 639-1 code of the original language asked for (hi for Hindi or Bollywood, ta, te, ml, ko...), else empty"))
                    .put("maxMinutes", new JSONObject().put("type", "INTEGER").put("description", "longest runtime asked for, in minutes, else 0"))
                    .put("fromYear", new JSONObject().put("type", "INTEGER").put("description", "earliest release year asked for, else 0"))
                    .put("toYear", new JSONObject().put("type", "INTEGER").put("description", "latest release year asked for, else 0"))
                    .put("services", new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "STRING").put("enum", serviceNames)))
                    .put("similarTo", new JSONObject().put("type", "STRING").put("description", "one title the person wants something like, else empty"))
                    .put("sort", new JSONObject().put("type", "STRING").put("enum", new JSONArray().put(SORT_POPULAR).put(SORT_TOP).put(SORT_NEW)));
            JSONArray required = new JSONArray();
            for (java.util.Iterator<String> it = props.keys(); it.hasNext(); ) required.put(it.next());
            JSONObject schema = new JSONObject().put("type", "OBJECT").put("properties", props).put("required", required);
            String instruction = "You turn a TV viewer's request for something to watch, in India, into search filters. "
                    + "Use only what the request asks for and leave everything else empty or 0. Moods map to genres "
                    + "(light or feel-good: Comedy; scary: Horror; mind-bending: Sci-Fi). 'Recent' means from "
                    + (today.getYear() - 2) + " on. Today is " + today + ".";
            return new JSONObject()
                    .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", instruction))))
                    .put("contents", new JSONArray().put(new JSONObject().put("role", "user")
                            .put("parts", new JSONArray().put(new JSONObject().put("text", sentence.trim())))))
                    .put("generationConfig", new JSONObject().put("responseMimeType", "application/json")
                            .put("responseSchema", schema).put("temperature", 0))
                    .toString();
        } catch (org.json.JSONException e) {
            return "{}";
        }
    }

    /** The filters from Gemini's answer, null when it had none. Anything outside Discover's choices is dropped. */
    static Ask parseGemini(JSONObject answer) {
        JSONArray candidates = answer == null ? null : answer.optJSONArray("candidates");
        JSONObject first = candidates == null ? null : candidates.optJSONObject(0);
        JSONObject content = first == null ? null : first.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        JSONObject part = parts == null ? null : parts.optJSONObject(0);
        if (part == null) return null;
        JSONObject f;
        try {
            f = new JSONObject(part.optString("text", ""));
        } catch (org.json.JSONException e) {
            return null;
        }
        Ask a = new Ask();
        String kind = f.optString("kind");
        a.kind = "movie".equals(kind) || "tv".equals(kind) ? kind : "any";
        JSONArray genres = f.optJSONArray("genres");
        if (genres != null) {
            for (int i = 0; i < genres.length(); i++) {
                for (Genre g : GENRES) if (g.name.equals(genres.optString(i)) && !a.genres.contains(g.name)) a.genres.add(g.name);
            }
        }
        String lang = f.optString("language", "").trim().toLowerCase(java.util.Locale.ROOT);
        a.language = lang.matches("[a-z]{2}") ? lang : "";
        a.maxMinutes = Math.max(0, f.optInt("maxMinutes"));
        a.fromYear = year(f.optInt("fromYear"));
        a.toYear = year(f.optInt("toYear"));
        JSONArray services = f.optJSONArray("services");
        if (services != null) {
            for (int i = 0; i < services.length(); i++) {
                String s = services.optString(i);
                if (SERVICES.containsKey(s) && !a.services.contains(s)) a.services.add(s);
            }
        }
        a.similarTo = f.optString("similarTo", "").trim();
        String sort = f.optString("sort");
        a.sort = SORT_TOP.equals(sort) || SORT_NEW.equals(sort) ? sort : SORT_POPULAR;
        return a;
    }

    private static int year(int y) {
        return y >= 1900 && y <= 2100 ? y : 0;
    }

    /** The provider ids an Ask searches: the services it names, else {@code mine} (null for every service). */
    static Set<Integer> askProviders(Ask a, Set<Integer> mine) {
        if (a.services.isEmpty()) return mine;
        Set<Integer> ids = new LinkedHashSet<>();
        for (String s : a.services) for (int id : SERVICES.get(s)) ids.add(id);
        return ids;
    }

    /**
     * A TMDB discover page for an Ask, for {@code kind} movie or tv; null when that kind can't match (a series
     * asked of a movie-only genre, or the Ask wants only the other kind).
     */
    static String askUrl(Ask a, String kind, Set<Integer> providers, LocalDate today) {
        boolean movie = "movie".equals(kind);
        if (!"any".equals(a.kind) && !a.kind.equals(kind)) return null;
        StringJoiner genres = new StringJoiner(",");
        for (String name : a.genres) {
            for (Genre g : GENRES) {
                if (!g.name.equals(name)) continue;
                if (g.idFor(kind) == 0) return null;
                genres.add(String.valueOf(g.idFor(kind)));
            }
        }
        String date = movie ? "primary_release_date" : "first_air_date";
        StringBuilder u = new StringBuilder(Tmdb.API + "discover/" + (movie ? "movie" : "tv") + "?watch_region=" + Tmdb.REGION
                + "&language=" + Tmdb.LANGUAGE + "&include_adult=false&page=1");
        if (SORT_TOP.equals(a.sort)) u.append("&sort_by=vote_average.desc&vote_count.gte=").append(movie ? 200 : 100);
        else if (SORT_NEW.equals(a.sort)) u.append("&sort_by=").append(date).append(".desc");
        else u.append("&sort_by=popularity.desc");
        if (genres.length() > 0) u.append("&with_genres=").append(enc(genres.toString()));
        if (!a.language.isEmpty()) u.append("&with_original_language=").append(a.language);
        if (a.maxMinutes > 0 && movie) u.append("&with_runtime.lte=").append(a.maxMinutes);
        if (a.fromYear > 0) u.append("&").append(date).append(".gte=").append(a.fromYear).append("-01-01");
        int to = a.toYear > 0 ? a.toYear : 0;
        if (to > 0) u.append("&").append(date).append(".lte=").append(to).append("-12-31");
        else if (SORT_NEW.equals(a.sort) || a.fromYear > 0) u.append("&").append(date).append(".lte=").append(today);
        if (!movie) u.append("&without_genres=").append(enc(Tmdb.SKIP_TV_GENRES));
        if (providers != null && !providers.isEmpty()) {
            u.append("&with_watch_monetization_types=flatrate&with_watch_providers=").append(enc(ids(providers)));
        }
        return u.toString();
    }

    /** The understood filters as capsules: {key, label}. Removing one is {@link #without}. */
    static List<String[]> chips(Ask a) {
        List<String[]> out = new ArrayList<>();
        if ("movie".equals(a.kind)) out.add(new String[]{"kind", "Movies"});
        if ("tv".equals(a.kind)) out.add(new String[]{"kind", "Series"});
        if (!a.similarTo.isEmpty()) out.add(new String[]{"like", "Like " + a.similarTo});
        for (String g : a.genres) out.add(new String[]{"genre:" + g, g});
        if (!a.language.isEmpty()) {
            String name = a.language;
            for (Language l : LANGUAGES) if (l.code.equals(a.language)) name = l.english;
            out.add(new String[]{"language", name});
        }
        if (a.maxMinutes > 0) out.add(new String[]{"minutes", "Under " + runtime(a.maxMinutes)});
        if (a.fromYear > 0 && a.toYear > 0) out.add(new String[]{"years", a.fromYear == a.toYear ? String.valueOf(a.fromYear) : a.fromYear + "\u2013" + a.toYear});
        else if (a.fromYear > 0) out.add(new String[]{"years", "Since " + a.fromYear});
        else if (a.toYear > 0) out.add(new String[]{"years", "Before " + (a.toYear + 1)});
        for (String s : a.services) out.add(new String[]{"service:" + s, s});
        if (SORT_TOP.equals(a.sort)) out.add(new String[]{"sort", "Top rated"});
        if (SORT_NEW.equals(a.sort)) out.add(new String[]{"sort", "Newest"});
        return out;
    }

    /** The Ask without one of its filters. */
    static Ask without(Ask a, String key) {
        Ask b = a.copy();
        if ("kind".equals(key)) b.kind = "any";
        else if ("like".equals(key)) b.similarTo = "";
        else if (key.startsWith("genre:")) b.genres.remove(key.substring(6));
        else if ("language".equals(key)) b.language = "";
        else if ("minutes".equals(key)) b.maxMinutes = 0;
        else if ("years".equals(key)) { b.fromYear = 0; b.toYear = 0; }
        else if (key.startsWith("service:")) b.services.remove(key.substring(8));
        else if ("sort".equals(key)) b.sort = SORT_POPULAR;
        return b;
    }

    /** Whether a search is a sentence worth asking about, not a name: three words or more. */
    static boolean isSentence(String q) {
        return q != null && q.trim().split("\\s+").length >= 3;
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

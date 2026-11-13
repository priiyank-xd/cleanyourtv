package com.gathin.tvhome;

import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TmdbTest {
    @Test public void installedAppsBecomeIndiaProviderIds() {
        Set<Integer> ids = Tmdb.providerIds(Arrays.asList("com.netflix.ninja", "in.startv.hotstar", "com.example.other",
                "com.mxtech.videoplayer.television"));
        assertEquals(Arrays.asList(8, 2336, 515, 1898), new java.util.ArrayList<>(ids));
        assertTrue(Tmdb.providerIds(Collections.singletonList("com.example.other")).isEmpty());
    }

    @Test public void providerIdsMapBackToTheirApp() {
        assertEquals("com.amazon.amazonvideo.livingroom", Tmdb.packageFor(119));
        assertEquals("com.mxtech.videoplayer.television", Tmdb.packageFor(1898));
        assertNull(Tmdb.packageFor(99999));
    }

    @Test public void newThisWeekUrlsAreForIndiaOnYourAppsAndRecent() {
        Set<Integer> mine = Tmdb.providerIds(Arrays.asList("com.netflix.ninja", "com.sonyliv"));
        LocalDate today = LocalDate.of(2026, 11, 12);
        String movies = Tmdb.newMoviesUrl(mine, today);
        assertTrue(movies.startsWith(Tmdb.API + "discover/movie?"));
        assertTrue(movies.contains("watch_region=IN"));
        assertTrue(movies.contains("with_watch_providers=8%7C237"));
        assertTrue(movies.contains("with_watch_monetization_types=flatrate"));
        assertTrue(movies.contains("primary_release_date.gte=2026-10-13"));
        String series = Tmdb.newSeriesUrl(mine, today);
        assertTrue(series.contains("first_air_date.gte=2026-09-28"));
        assertTrue(series.contains("without_genres=10767%7C10763%7C10764"));
    }

    @Test public void parsesDiscoverAndTrendingAndSkipsPeopleAndMissingArt() throws Exception {
        JSONObject trending = new JSONObject("{\"results\":["
                + "{\"media_type\":\"tv\",\"id\":1,\"name\":\"Show A\",\"first_air_date\":\"2026-11-01\",\"backdrop_path\":\"/a.jpg\",\"popularity\":50},"
                + "{\"media_type\":\"person\",\"id\":2,\"name\":\"Someone\"},"
                + "{\"media_type\":\"movie\",\"id\":3,\"title\":\"Film B\",\"release_date\":\"2026-10-30\",\"backdrop_path\":\"/b.jpg\",\"popularity\":80},"
                + "{\"media_type\":\"movie\",\"id\":4,\"title\":\"No Art\",\"backdrop_path\":null}]}");
        List<Tmdb.Title> list = Tmdb.parseTitles(trending, null);
        assertEquals(2, list.size());
        assertEquals("Show A", list.get(0).name);
        assertEquals("tv", list.get(0).kind);
        assertEquals("Film B", list.get(1).name);
        assertEquals("2026-10-30", list.get(1).date);
        List<Tmdb.Title> movies = Tmdb.parseTitles(new JSONObject("{\"results\":[{\"id\":5,\"title\":\"Film C\",\"backdrop_path\":\"/c.jpg\"}]}"), "movie");
        assertEquals("movie", movies.get(0).kind);
        assertTrue(Tmdb.parseTitles(new JSONObject("{}"), "movie").isEmpty());
    }

    @Test public void streamingOnPicksYourFirstFlatrateServiceInIndia() throws Exception {
        JSONObject answer = new JSONObject("{\"results\":{"
                + "\"US\":{\"flatrate\":[{\"provider_id\":15}]},"
                + "\"IN\":{\"rent\":[{\"provider_id\":2}],\"flatrate\":[{\"provider_id\":122},{\"provider_id\":119},{\"provider_id\":8}]}}}");
        Set<Integer> mine = Tmdb.providerIds(Arrays.asList("com.netflix.ninja", "com.amazon.amazonvideo.livingroom"));
        assertEquals(119, Tmdb.streamingOn(answer, mine));
        assertEquals(0, Tmdb.streamingOn(answer, Tmdb.providerIds(Collections.singletonList("com.sonyliv"))));
        assertEquals(0, Tmdb.streamingOn(new JSONObject("{\"results\":{}}"), mine));
    }

    @Test public void mergeKeepsTheMostPopularWithoutRepeats() {
        Tmdb.Title a = new Tmdb.Title(1, "movie", "A", "", "/a", 10);
        Tmdb.Title b = new Tmdb.Title(2, "tv", "B", "", "/b", 30);
        Tmdb.Title c = new Tmdb.Title(1, "movie", "A again", "", "/a", 5);
        Tmdb.Title d = new Tmdb.Title(1, "tv", "Same id, other kind", "", "/d", 20);
        List<Tmdb.Title> out = Tmdb.merge(Arrays.asList(a, c), Arrays.asList(b, d), 3);
        assertEquals(Arrays.asList("B", "Same id, other kind", "A"), Arrays.asList(out.get(0).name, out.get(1).name, out.get(2).name));
    }

    @Test public void movieArtRotatesEveryPeriodFromTheClock() {
        long period = 20 * 60 * 1000L;
        assertEquals(0, Tmdb.artIndex(0, period, 5));
        assertEquals(0, Tmdb.artIndex(period - 1, period, 5));
        assertEquals(1, Tmdb.artIndex(period, period, 5));
        assertEquals(0, Tmdb.artIndex(5 * period, period, 5));     // wraps around the list
        assertEquals(3, Tmdb.artIndex(13 * period + 5, period, 5));
        assertEquals(0, Tmdb.artIndex(123456789L, period, 0));     // no titles: index 0, never a divide by zero
        assertEquals(0, Tmdb.artIndex(123456789L, 0, 4));
    }

    @Test public void titlesSurviveTheDiskCache() throws Exception {
        Tmdb.Title t = new Tmdb.Title(7, "tv", "Show", "2026-11-02", "/x.jpg", 12.5);
        t.provider = 2336;
        Tmdb.Title back = Tmdb.Title.fromJson(new JSONObject(t.toJson().toString()));
        assertEquals(7, back.id);
        assertEquals("tv", back.kind);
        assertEquals("Show", back.name);
        assertEquals("/x.jpg", back.backdrop);
        assertEquals(2336, back.provider);
    }
}

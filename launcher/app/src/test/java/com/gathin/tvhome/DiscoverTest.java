package com.gathin.tvhome;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DiscoverTest {
    @Test public void searchUrlAsksForIndiaAndNoAdultTitles() {
        String url = Discover.searchUrl("  copper sky ");
        assertTrue(url.startsWith(Tmdb.API + "search/multi?query=copper+sky"));
        assertTrue(url.contains("region=IN"));
        assertTrue(url.contains("include_adult=false"));
    }

    @Test public void searchKeepsMoviesAndSeriesInOrder() throws Exception {
        JSONObject a = new JSONObject("{\"results\":["
                + "{\"media_type\":\"person\",\"id\":1,\"name\":\"Meera Iyer\"},"
                + "{\"media_type\":\"tv\",\"id\":2,\"name\":\"Monsoon Signal\",\"first_air_date\":\"2026-06-12\",\"backdrop_path\":\"/b.jpg\"},"
                + "{\"media_type\":\"movie\",\"id\":3,\"title\":\"Copper Sky\",\"release_date\":\"2025-01-01\",\"backdrop_path\":null,\"poster_path\":\"/p.jpg\"},"
                + "{\"media_type\":\"movie\",\"id\":4,\"title\":\"  \"},"
                + "{\"media_type\":\"movie\",\"id\":5,\"title\":\"Adult\",\"adult\":true}]}");
        List<Discover.Hit> hits = Discover.parseSearch(a, 20);
        assertEquals(2, hits.size());
        assertEquals("Monsoon Signal", hits.get(0).name);
        assertEquals("2026", hits.get(0).year);
        assertEquals("/b.jpg", hits.get(0).picture());
        assertEquals("movie", hits.get(1).kind);
        assertEquals("/p.jpg", hits.get(1).picture());
        assertEquals(1, Discover.parseSearch(a, 1).size());
        assertEquals(0, Discover.parseSearch(null, 20).size());
    }

    @Test public void recentSearchesPutTheNewestFirstWithoutRepeats() {
        List<String> r = Discover.addRecent(Arrays.asList("ganga blue", "Copper Sky"), " copper sky ", 8);
        assertEquals(Arrays.asList("copper sky", "ganga blue"), r);
        assertEquals(Arrays.asList("a", "b"), Discover.addRecent(Arrays.asList("a", "b"), "  ", 8));
        assertEquals(2, Discover.addRecent(Arrays.asList("b", "c", "d"), "a", 2).size());
        assertEquals(Arrays.asList("x", "y"), Discover.splitRecent(Discover.joinRecent(Arrays.asList("x", "y"))));
        assertEquals(0, Discover.splitRecent(null).size());
    }

    private static final String MOVIE = "{\"id\":3,\"title\":\"Copper Sky\",\"release_date\":\"2025-03-07\",\"runtime\":138,"
            + "\"overview\":\"A pilot.\",\"backdrop_path\":\"/b.jpg\",\"genres\":[{\"name\":\"Science Fiction\"},{\"name\":\"Adventure\"},{\"name\":\"Drama\"}],"
            + "\"credits\":{\"cast\":[{\"name\":\"Tara Fernandes\"},{\"name\":\"Arjun Sethi\"},{\"name\":\"Third\"}],"
            + "\"crew\":[{\"job\":\"Writer\",\"name\":\"W\"},{\"job\":\"Director\",\"name\":\"Ritu Saran\"}]},"
            + "\"release_dates\":{\"results\":[{\"iso_3166_1\":\"US\",\"release_dates\":[{\"certification\":\"PG-13\"}]},"
            + "{\"iso_3166_1\":\"IN\",\"release_dates\":[{\"certification\":\"\"},{\"certification\":\"UA 13+\"}]}]},"
            + "\"watch/providers\":{\"results\":{\"IN\":{"
            + "\"flatrate\":[{\"provider_id\":350,\"provider_name\":\"Apple TV\",\"display_priority\":5,\"logo_path\":\"/a.jpg\"},"
            + "{\"provider_id\":8,\"provider_name\":\"Netflix\",\"display_priority\":1,\"logo_path\":\"/n.jpg\"}],"
            + "\"rent\":[{\"provider_id\":119,\"provider_name\":\"Amazon Prime Video\",\"display_priority\":2}],"
            + "\"ads\":[{\"provider_id\":11,\"provider_name\":\"MX Player\",\"display_priority\":3}]}}}}";

    @Test public void movieDetailsReadEverythingTheTitlePageShows() throws Exception {
        Discover.Details d = Discover.parseDetails(new JSONObject(MOVIE), "movie");
        assertEquals("Copper Sky", d.name);
        assertEquals("2025", d.year);
        assertEquals("U/A 13+", d.cert);
        assertEquals("Movie · 2025 · Science Fiction, Adventure · 2h 18m · U/A 13+", Discover.facts(d));
        assertEquals("Ritu Saran · Tara Fernandes, Arjun Sethi", Discover.credits(d));
        assertEquals(8, d.where.stream.get(0).id);   // display order, not answer order
        assertEquals(350, d.where.stream.get(1).id);
        assertEquals(1, d.where.rent.size());
        assertEquals("MX Player", d.where.free.get(0).name);   // ads count as free
    }

    @Test public void seriesDetailsUseSeasonsCreatorAndTheirRating() throws Exception {
        Discover.Details d = Discover.parseDetails(new JSONObject("{\"id\":2,\"name\":\"Monsoon Signal\",\"first_air_date\":\"2026-06-12\","
                + "\"number_of_seasons\":1,\"created_by\":[{\"name\":\"Kabir Nanda\"}],\"genres\":[{\"name\":\"Mystery\"}],"
                + "\"content_ratings\":{\"results\":[{\"iso_3166_1\":\"IN\",\"rating\":\"UA 16+\"}]}}"), "tv");
        assertEquals("Series · 2026 · Mystery · 1 season · U/A 16+", Discover.facts(d));
        assertEquals("Kabir Nanda", Discover.credits(d));
        assertTrue(d.where.isEmpty());
    }

    @Test public void watchUsesAnInstalledAppFirstThenOneToGet() throws Exception {
        Discover.Availability a = Discover.parseDetails(new JSONObject(MOVIE), "movie").where;
        assertEquals(8, Discover.primary(a, Collections.singletonList("com.netflix.ninja")).id);
        assertEquals(350, Discover.primary(a, Collections.singletonList("com.apple.atve.androidtv.appletv")).id);
        assertEquals(8, Discover.primary(a, Collections.emptyList()).id);   // none installed: offer the first to get
        assertNull(Discover.primary(new Discover.Availability(), Collections.emptyList()));
        List<Discover.Provider> also = Discover.others(a, Discover.primary(a, Collections.emptyList()), 3);
        assertEquals(Arrays.asList(350, 119, 11), Arrays.asList(also.get(0).id, also.get(1).id, also.get(2).id));
    }

    @Test public void runtimeAndCertificationReadAsPeopleWriteThem() {
        assertEquals("2h 18m", Discover.runtime(138));
        assertEquals("52m", Discover.runtime(52));
        assertEquals("2h", Discover.runtime(120));
        assertEquals("", Discover.runtime(0));
        assertEquals("U/A 7+", Discover.cert("UA 7+"));
        assertEquals("A", Discover.cert(" A "));
    }

    @Test public void youTubeRentalsHaveAnApp() {
        assertEquals("com.google.android.youtube.tv", Discover.packageFor(192));
        assertEquals("com.netflix.ninja", Discover.packageFor(8));
        assertNull(Discover.packageFor(99999));
    }

    @Test public void searchFindsPeopleSeparately() throws Exception {
        JSONObject a = new JSONObject("{\"results\":[{\"media_type\":\"person\",\"id\":1,\"name\":\"Meera Iyer\","
                + "\"profile_path\":\"/m.jpg\",\"known_for_department\":\"Acting\"},{\"media_type\":\"tv\",\"id\":2,\"name\":\"X\"}]}");
        List<Discover.Hit> people = Discover.parsePeople(a, 6);
        assertEquals(1, people.size());
        assertEquals("/m.jpg", people.get(0).poster);
        assertEquals("Acting", people.get(0).sub);
        assertTrue(people.get(0).isPerson());
    }

    @Test public void listsMergeByPopularityWithoutRepeats() throws Exception {
        List<Discover.Hit> movies = Discover.parseList(new JSONObject("{\"results\":[{\"id\":1,\"title\":\"A\",\"popularity\":5},"
                + "{\"id\":2,\"title\":\"B\",\"popularity\":50}]}"), "movie", 20);
        List<Discover.Hit> tv = Discover.parseList(new JSONObject("{\"results\":[{\"id\":1,\"name\":\"C\",\"popularity\":20}]}"), "tv", 20);
        List<Discover.Hit> both = Discover.merge(movies, tv, 10);
        assertEquals(Arrays.asList("B", "C", "A"), Arrays.asList(both.get(0).name, both.get(1).name, both.get(2).name));
        assertEquals(2, Discover.merge(both, movies, 2).size());
    }

    @Test public void shelvesAskForYourAppsInIndia() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 16);
        String hero = Discover.popularUrl("movie", new java.util.LinkedHashSet<>(Arrays.asList(8, 119)), 90, today);
        assertTrue(hero.contains("discover/movie?watch_region=IN"));
        assertTrue(hero.contains("with_watch_providers=8%7C119"));
        assertTrue(hero.contains("primary_release_date.gte=2026-08-18"));
        String top = Discover.popularUrl("tv", new java.util.LinkedHashSet<>(Arrays.asList(8)), 0, today);
        assertTrue(top.contains("without_genres="));
        assertTrue(!top.contains("first_air_date.gte"));
    }

    @Test public void comingSoonKeepsOnlyFutureDatesSoonestFirst() throws Exception {
        List<Discover.Hit> list = Discover.parseList(new JSONObject("{\"results\":[{\"id\":1,\"title\":\"Later\",\"release_date\":\"2026-12-25\"},"
                + "{\"id\":2,\"title\":\"Past\",\"release_date\":\"2026-11-01\"},{\"id\":3,\"title\":\"Soon\",\"release_date\":\"2026-12-04\"},"
                + "{\"id\":4,\"title\":\"Undated\"}]}"), "movie", 20);
        List<Discover.Hit> soon = Discover.upcoming(list, java.time.LocalDate.of(2026, 11, 16), 10);
        assertEquals(Arrays.asList("Soon", "Later"), Arrays.asList(soon.get(0).name, soon.get(1).name));
        assertEquals("4 Dec", Discover.shortDate("2026-12-04"));
        assertEquals("", Discover.shortDate("2026"));
        assertEquals("14 March 1990", Discover.longDate("1990-03-14"));
    }

    @Test public void browseAsksForTheGenreLanguageSortAndApps() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 16);
        String u = Discover.browseUrl("movie", 53, "hi", Discover.SORT_TOP, new java.util.LinkedHashSet<>(Arrays.asList(8)), 2, today);
        assertTrue(u.contains("with_genres=53"));
        assertTrue(u.contains("with_original_language=hi"));
        assertTrue(u.contains("sort_by=vote_average.desc&vote_count.gte=300"));
        assertTrue(u.contains("with_watch_providers=8"));
        assertTrue(u.contains("page=2"));
        String all = Discover.browseUrl("tv", 0, null, Discover.SORT_NEW, null, 1, today);
        assertTrue(all.contains("sort_by=first_air_date.desc&first_air_date.lte=2026-11-16"));
        assertTrue(!all.contains("with_watch_providers"));
        assertEquals(9648, Discover.GENRES.get(3).idFor("tv"));
        assertEquals(0, Discover.GENRES.get(6).idFor("tv"));   // no Romance for series
    }

    @Test public void aPersonPageListsKnownForByVotesAndAllByDate() throws Exception {
        Discover.PersonPage p = Discover.parsePerson(new JSONObject("{\"name\":\"Meera Iyer\",\"known_for_department\":\"Acting\","
                + "\"birthday\":\"1990-03-14\",\"place_of_birth\":\"Chennai\",\"combined_credits\":{\"cast\":["
                + "{\"media_type\":\"movie\",\"id\":1,\"title\":\"Old\",\"release_date\":\"2014-01-01\",\"vote_count\":900},"
                + "{\"media_type\":\"tv\",\"id\":2,\"name\":\"New\",\"first_air_date\":\"2026-06-12\",\"vote_count\":10},"
                + "{\"media_type\":\"movie\",\"id\":1,\"title\":\"Old\",\"release_date\":\"2014-01-01\",\"vote_count\":900}]}}"));
        assertEquals("Born 14 March 1990, Chennai", p.born);
        assertEquals(2, p.all.size());
        assertEquals("Old", p.knownFor.get(0).name);
        assertEquals("New", p.all.get(0).name);
    }

    @Test public void seasonsAndEpisodesReadAsTheSeriesPageShowsThem() throws Exception {
        Discover.Details d = Discover.parseDetails(new JSONObject("{\"id\":2,\"name\":\"S\",\"seasons\":["
                + "{\"season_number\":0,\"episode_count\":3},{\"season_number\":1,\"episode_count\":8},{\"season_number\":2,\"episode_count\":0}],"
                + "\"credits\":{\"cast\":[{\"id\":7,\"name\":\"Meera Iyer\",\"character\":\"Anjali\"}]},"
                + "\"recommendations\":{\"results\":[{\"media_type\":\"tv\",\"id\":9,\"name\":\"Ganga Blue\"}]}}"), "tv");
        assertEquals(1, d.seasonList.size());
        assertEquals(1, d.seasonList.get(0)[0]);
        assertEquals("Anjali", d.people.get(0).sub);
        assertEquals("Ganga Blue", d.more.get(0).name);
        List<Discover.Episode> eps = Discover.parseSeason(new JSONObject("{\"episodes\":[{\"episode_number\":1,\"name\":\"The First Call\","
                + "\"runtime\":52,\"air_date\":\"2026-06-12\"},{\"episode_number\":2,\"name\":\"\"}]}"));
        assertEquals("52m \u00b7 12 Jun 2026", Discover.episodeLine(eps.get(0)));
        assertEquals("Episode 2", eps.get(1).name);
    }

    @Test public void theTrailerIsAnOfficialYouTubeTrailerFirst() throws Exception {
        assertEquals("off", Discover.trailer(new JSONObject("{\"results\":[{\"site\":\"YouTube\",\"type\":\"Teaser\",\"key\":\"t\"},"
                + "{\"site\":\"Vimeo\",\"type\":\"Trailer\",\"key\":\"v\",\"official\":true},{\"site\":\"YouTube\",\"type\":\"Trailer\",\"key\":\"any\"},"
                + "{\"site\":\"YouTube\",\"type\":\"Trailer\",\"key\":\"off\",\"official\":true}]}")));
        assertEquals("t", Discover.trailer(new JSONObject("{\"results\":[{\"site\":\"YouTube\",\"type\":\"Teaser\",\"key\":\"t\"}]}")));
        assertEquals("", Discover.trailer(null));
    }

    @Test public void detailsKnowTheImdbIdForMoviesAndSeries() throws Exception {
        assertEquals("tt1", Discover.parseDetails(new JSONObject("{\"id\":1,\"title\":\"M\",\"imdb_id\":\"tt1\"}"), "movie").imdbId);
        assertEquals("tt2", Discover.parseDetails(new JSONObject("{\"id\":2,\"name\":\"S\",\"external_ids\":{\"imdb_id\":\"tt2\"}}"), "tv").imdbId);
        assertEquals("", Discover.parseDetails(new JSONObject("{\"id\":3,\"name\":\"S\",\"external_ids\":{\"imdb_id\":null}}"), "tv").imdbId);
        assertTrue(Discover.detailsUrl("tv", 2).contains("external_ids,videos"));
    }

    @Test public void omdbScoresReadAsTheyShow() throws Exception {
        Discover.Ratings r = Discover.parseOmdb(new JSONObject("{\"Response\":\"True\",\"imdbRating\":\"7.6\",\"imdbVotes\":\"849,165\","
                + "\"Ratings\":[{\"Source\":\"Internet Movie Database\",\"Value\":\"7.6/10\"},{\"Source\":\"Rotten Tomatoes\",\"Value\":\"85%\"}],"
                + "\"Awards\":\"Nominated for 1 Oscar. 15 wins & 62 nominations total\"}"));
        assertEquals("7.6", r.imdb);
        assertEquals("849K", r.votes);
        assertEquals("85%", r.tomatoes);
        assertEquals("Nominated for 1 Oscar · 15 wins", r.awards);
        Discover.Ratings none = Discover.parseOmdb(new JSONObject("{\"Response\":\"True\",\"imdbRating\":\"N/A\",\"Awards\":\"N/A\"}"));
        assertTrue(none.isEmpty());
        assertEquals("", none.awards);
        assertTrue(Discover.parseOmdb(new JSONObject("{\"Response\":\"False\",\"Error\":\"Invalid API key!\"}")).isEmpty());
    }

    @Test public void votesAndAwardsAreShortened() {
        assertEquals("1.7M", Discover.votes("1,740,231"));
        assertEquals("2M", Discover.votes("2,000,000"));
        assertEquals("950", Discover.votes("950"));
        assertEquals("", Discover.votes("N/A"));
        assertEquals("Won 13 Primetime Emmys · 125 wins", Discover.awards("Won 13 Primetime Emmys. 125 wins & 345 nominations total"));
        assertEquals("1 win", Discover.awards("1 win & 2 nominations total"));
        assertEquals("4 nominations", Discover.awards("4 nominations total"));
    }

    @Test public void justWatchAsksForIndiaAndAndroidTvLinks() throws Exception {
        JSONObject body = new JSONObject(Discover.justWatchBody("Sacred \"Games\""));
        assertEquals("IN", body.getJSONObject("variables").getString("country"));
        assertEquals("Sacred \"Games\"", body.getJSONObject("variables").getJSONObject("filter").getString("searchQuery"));
        assertTrue(body.getString("query").contains("deeplinkURL(platform: ANDROID_TV)"));
    }

    @Test public void justWatchLinksMatchTheTmdbTitleAndPreferStreaming() throws Exception {
        JSONObject a = new JSONObject("{\"data\":{\"popularTitles\":{\"edges\":["
                + "{\"node\":{\"objectType\":\"MOVIE\",\"content\":{\"externalIds\":{\"tmdbId\":\"79352\"}},\"offers\":[]}},"
                + "{\"node\":{\"objectType\":\"SHOW\",\"content\":{\"externalIds\":{\"tmdbId\":\"79352\"}},\"offers\":["
                + "{\"monetizationType\":\"BUY\",\"standardWebURL\":\"https://buy\",\"tv\":null,\"package\":{\"packageId\":8}},"
                + "{\"monetizationType\":\"FLATRATE\",\"standardWebURL\":\"https://www.netflix.com/title/1\",\"tv\":\"intent://www.netflix.com/watch/1#Intent;scheme=https;package=com.netflix.ninja;end\",\"package\":{\"packageId\":8}},"
                + "{\"monetizationType\":\"ADS\",\"standardWebURL\":\"https://www.hotstar.com/in/2\",\"tv\":null,\"package\":{\"packageId\":2336}}]}}]}}}");
        java.util.Map<Integer, String> links = Discover.parseJustWatch(a, "tv", 79352);
        assertTrue(links.get(8).startsWith("intent://www.netflix.com/watch/1"));
        assertEquals("https://www.hotstar.com/in/2", links.get(2336));
        assertTrue(Discover.parseJustWatch(a, "movie", 79352).isEmpty());   // the movie with that id has no offers
        assertTrue(Discover.parseJustWatch(a, "tv", 1).isEmpty());
        assertTrue(Discover.parseJustWatch(null, "tv", 1).isEmpty());
    }
}

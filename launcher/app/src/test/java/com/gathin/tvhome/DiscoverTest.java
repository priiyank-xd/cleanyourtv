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
}

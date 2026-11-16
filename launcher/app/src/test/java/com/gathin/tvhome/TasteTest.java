package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TasteTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

    private static Discover.Hit hit(int id, String name, String date, String lang, int... genres) {
        Discover.Hit h = new Discover.Hit("movie", id, name, date, "", "", 0, "");
        h.language = lang;
        h.genres = genres;
        return h;
    }

    private static List<Integer> ids(List<Discover.Hit> hits) {
        List<Integer> out = new ArrayList<>();
        for (Discover.Hit h : hits) out.add(h.id);
        return out;
    }

    @Test public void listsGiveGenresAndLanguage() throws Exception {
        Discover.Hit h = Discover.hit(new JSONObject("{\"id\":7,\"title\":\"Drishyam\",\"genre_ids\":[80,53],"
                + "\"original_language\":\"hi\"}"), "movie");
        assertEquals(2, h.genres.length);
        assertEquals(80, h.genres[0]);
        assertEquals("hi", h.language);
    }

    @Test public void yourGenresAndLanguageRiseAboveMerePopularity() {
        List<Discover.Hit> pool = new ArrayList<>();
        for (int i = 1; i <= 20; i++) pool.add(hit(i, "Title" + i, "2020-01-01", "en", 28));
        pool.add(hit(99, "Crime story", "2020-01-01", "hi", 80));   // least popular
        Taste.Signals s = new Taste.Signals();
        s.genres.put(80, 5.0);
        s.languages.put("hi", 5.0);
        List<Discover.Hit> out = Taste.rank(pool, s, TODAY);
        assertTrue(ids(out).indexOf(99) < 3);
    }

    @Test public void withNoHistoryItIsPopularWithADailyMix() {
        List<Discover.Hit> pool = new ArrayList<>();
        for (int i = 1; i <= 40; i++) pool.add(hit(i, "Title" + i, "2020-01-01", "en", 18));
        Taste.Signals none = new Taste.Signals();
        List<Integer> today = ids(Taste.rank(pool, none, TODAY));
        assertEquals(today, ids(Taste.rank(pool, none, TODAY)));   // same all day
        assertNotEquals(today, ids(Taste.rank(pool, none, TODAY.plusDays(1))));   // a new mix tomorrow
        assertTrue(today.indexOf(1) < 10);   // still mostly popular first
        assertTrue(today.indexOf(40) > 25);
    }

    @Test public void passedOverAndJustOpenedTitlesSink() {
        List<Discover.Hit> pool = new ArrayList<>();
        for (int i = 1; i <= 100; i++) pool.add(hit(i, "Title" + i, "", "en"));
        List<Integer> before = ids(Taste.rank(pool, new Taste.Signals(), TODAY));
        Taste.Signals s = new Taste.Signals();
        s.shown.put("movie:1", 6);
        s.openedRecently.add("movie:2");
        s.engaged.add("movie:2");
        List<Integer> after = ids(Taste.rank(pool, s, TODAY));
        assertTrue(after.indexOf(1) > before.indexOf(1) + 20);
        assertTrue(after.indexOf(2) > before.indexOf(2) + 30);
    }

    @Test public void thumbsAreSavedAsTmdbScoresAndReadBack() throws Exception {
        assertEquals(2.0, Discover.thumbScore(Discover.THUMB_DOWN), 1e-9);
        assertEquals(7.0, Discover.thumbScore(Discover.THUMB_UP), 1e-9);
        assertEquals(10.0, Discover.thumbScore(Discover.THUMB_LOVE), 1e-9);
        assertEquals(Discover.THUMB_DOWN, Discover.thumbOf(3.5));
        assertEquals(Discover.THUMB_UP, Discover.thumbOf(6));
        assertEquals(Discover.THUMB_LOVE, Discover.thumbOf(9.5));
        assertEquals(Discover.THUMB_NONE, Discover.thumbOf(0));
        assertEquals(7.0, Discover.parseRated(new JSONObject("{\"rated\":{\"value\":7}}")), 1e-9);
        assertEquals(0.0, Discover.parseRated(new JSONObject("{\"rated\":false}")), 1e-9);
        assertEquals("{\"value\":7}", Discover.ratingBody(7).replace(".0", ""));
    }

    @Test public void aThumbsDownLeavesForYouAndPullsItsGenresDown() {
        List<Discover.Hit> pool = new ArrayList<>();
        for (int i = 1; i <= 30; i++) pool.add(hit(i, "Title" + i, "", "en", i <= 15 ? 27 : 35));   // horror, then comedy
        Taste.Signals s = new Taste.Signals();
        s.genres.put(27, -3.0);
        s.genres.put(35, 1.0);
        s.disliked.add("movie:1");
        List<Integer> out = ids(Taste.rank(pool, s, TODAY));
        assertTrue(!out.contains(1));
        assertTrue(out.indexOf(16) < out.indexOf(2));   // the least popular comedy beats the most popular horror
    }

    @Test public void newTitlesGetABoostThatFades() {
        assertEquals(1.0, Taste.fresh("2026-11-16", TODAY), 1e-9);
        assertEquals(0.5, Taste.fresh("2026-10-17", TODAY), 1e-9);
        assertEquals(0.0, Taste.fresh("2025-01-01", TODAY), 1e-9);
        assertEquals(0.0, Taste.fresh("", TODAY), 1e-9);
        assertEquals(0.0, Taste.fresh("2026-12-01", TODAY), 1e-9);   // not out yet
    }

    @Test public void noMoreThanTwoInARowFromOneSeries() {
        List<Discover.Hit> order = Arrays.asList(hit(1, "Golmaal: Fun Unlimited", "", "hi"), hit(2, "Golmaal Returns", "", "hi"),
                hit(3, "Golmaal 3", "", "hi"), hit(4, "Andhadhun", "", "hi"));
        assertEquals(Arrays.asList(1, 2, 4, 3), ids(Taste.spread(order)));
        assertEquals("golmaal", Taste.series("The Golmaal Again"));
        assertEquals("", Taste.series("Up"));
    }

    @Test public void rowsKeepTheFocusedItemWholeAwayFromTheEdge() {
        // a 1000-wide window at 0 over 3000 of content, 100 in from each side
        assertEquals(0, DiscoverUi.keepInside(200, 400, 0, 1000, 3000, 100));
        assertEquals(150, DiscoverUi.keepInside(850, 1050, 0, 1000, 3000, 100));   // last on screen: room on its right
        assertEquals(-150, DiscoverUi.keepInside(250, 450, 300, 1000, 3000, 100));
        assertEquals(2000, DiscoverUi.keepInside(2900, 3000, 0, 1000, 3000, 100) );   // never past the end
        assertEquals(0, DiscoverUi.keepInside(0, 100, 0, 1000, 3000, 100));   // nor before the start
    }
}

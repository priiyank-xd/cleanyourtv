package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LgBackgroundTest {
    @Test public void onTvChoiceWinsOverTheTheme() {
        assertEquals("picture", Config.lgBackgroundFrom("picture", "movie_art"));
        assertEquals("movie_art", Config.lgBackgroundFrom("movie_art", "picture"));
    }

    @Test public void themeAppliesUntilAChoiceIsMade() {
        assertEquals("movie_art", Config.lgBackgroundFrom(null, " Movie_Art "));
        assertEquals("picture", Config.lgBackgroundFrom(null, "picture"));
        assertEquals("picture", Config.lgBackgroundFrom(null, null));
        assertEquals("picture", Config.lgBackgroundFrom(null, "something else"));
    }
}

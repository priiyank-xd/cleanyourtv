package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class ArtStatusTest {
    private static final long NOW = 1_000_000_000L;

    @Test public void nothingActiveIsEmpty() {
        assertEquals(Collections.emptyList(), ArtStatus.lines(NOW, 0, null));
        assertEquals(Collections.emptyList(), ArtStatus.lines(NOW, NOW - 1, "  "));
    }

    @Test public void timerFirstThenSound() {
        assertEquals(Arrays.asList("Sleep in 23 min", "Sound on Sony WH-1000XM4"),
                ArtStatus.lines(NOW, NOW + 22 * 60_000L + 1, " Sony WH-1000XM4 "));
    }

    @Test public void leftRoundsUpAndShowsHours() {
        assertEquals("1 min", ArtStatus.left(5_000));
        assertEquals("1 min", ArtStatus.left(60_000));
        assertEquals("2 min", ArtStatus.left(60_001));
        assertEquals("1 h", ArtStatus.left(60 * 60_000L));
        assertEquals("1 h 5 min", ArtStatus.left(65 * 60_000L));
    }

    @Test public void acceptsOnlyBelievableEnds() {
        assertEquals(NOW + 60_000, ArtStatus.accept(NOW + 60_000, NOW));
        assertEquals(0, ArtStatus.accept(0, NOW));
        assertEquals(0, ArtStatus.accept(NOW - 1, NOW));
        assertEquals(0, ArtStatus.accept(NOW + ArtStatus.MAX_AHEAD_MS + 1, NOW));
    }
}

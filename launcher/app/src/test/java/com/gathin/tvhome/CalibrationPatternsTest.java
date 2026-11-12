package com.gathin.tvhome;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CalibrationPatternsTest {
    @Test public void levelsAreAscendingAndInRange() {
        for (int[] levels : new int[][]{CalibrationPatterns.BLACK_LEVELS, CalibrationPatterns.WHITE_LEVELS, CalibrationPatterns.GRAY_STEPS}) {
            for (int i = 0; i < levels.length; i++) {
                assertTrue(levels[i] >= 0 && levels[i] <= 255);
                if (i > 0) assertTrue(levels[i] > levels[i - 1]);
            }
        }
        assertEquals(0, CalibrationPatterns.BLACK_LEVELS[0]);
        assertEquals(255, CalibrationPatterns.WHITE_LEVELS[CalibrationPatterns.WHITE_LEVELS.length - 1]);
        assertEquals(0, CalibrationPatterns.GRAY_STEPS[0]);
        assertEquals(255, CalibrationPatterns.GRAY_STEPS[CalibrationPatterns.GRAY_STEPS.length - 1]);
        assertEquals(11, CalibrationPatterns.GRAY_STEPS.length);
    }

    @Test public void grayStepsAreTenPercentApart() {
        for (int i = 0; i < CalibrationPatterns.GRAY_STEPS.length; i++) {
            assertEquals(Math.round(255 * i / 10f), CalibrationPatterns.GRAY_STEPS[i]);
        }
    }

    @Test public void grayBuildsAnOpaqueNeutralColour() {
        assertEquals(0xFF000000, CalibrationPatterns.gray(0));
        assertEquals(0xFF808080, CalibrationPatterns.gray(128));
        assertEquals(0xFFFFFFFF, CalibrationPatterns.gray(255));
    }

    @Test public void barsAreSevenOpaqueColoursEach() {
        assertEquals(7, CalibrationPatterns.BARS_75.length);
        assertEquals(7, CalibrationPatterns.BARS_100.length);
        for (int c : CalibrationPatterns.BARS_75) assertEquals(0xFF, c >>> 24);
        assertEquals(0xFFBFBFBF, CalibrationPatterns.BARS_75[0]);   // 75% white
    }

    @Test public void everyScreenHasATitleAndHintAndNavigationWraps() {
        Set<String> titles = new HashSet<>();
        for (int p = 0; p < CalibrationPatterns.COUNT; p++) {
            assertFalse(CalibrationPatterns.title(p).isEmpty());
            assertFalse(CalibrationPatterns.hint(p).isEmpty());
            titles.add(CalibrationPatterns.title(p));
        }
        assertEquals(CalibrationPatterns.COUNT, titles.size());
        assertEquals(1, CalibrationPatterns.next(0, 1));
        assertEquals(CalibrationPatterns.COUNT - 1, CalibrationPatterns.next(0, -1));
        assertEquals(0, CalibrationPatterns.next(CalibrationPatterns.COUNT - 1, 1));
    }

    @Test public void onlyTheFullFieldScreensAreSolidColours() {
        int solids = 0;
        for (int p = 0; p < CalibrationPatterns.COUNT; p++) if (CalibrationPatterns.field(p) != 0) solids++;
        assertEquals(6, solids);
        assertEquals(0, CalibrationPatterns.field(CalibrationPatterns.FOCUS));
        assertEquals(0xFF000000, CalibrationPatterns.field(CalibrationPatterns.FIELD_BLACK));
    }

    @Test public void overscanBordersStartAtTheEdge() {
        assertEquals(0f, CalibrationPatterns.OVERSCAN_PERCENT[0], 0f);
        for (int i = 1; i < CalibrationPatterns.OVERSCAN_PERCENT.length; i++) {
            assertTrue(CalibrationPatterns.OVERSCAN_PERCENT[i] > CalibrationPatterns.OVERSCAN_PERCENT[i - 1]);
        }
    }
}

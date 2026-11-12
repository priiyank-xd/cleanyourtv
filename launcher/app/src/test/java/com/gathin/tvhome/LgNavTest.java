package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LgNavTest {
    private static final int APPS = 15, CHIPS = 4, QUICK = 5;

    private static String go(String from, char dir) {
        return LgNav.next(from, dir, APPS, CHIPS, QUICK, 0);
    }

    @Test public void featureCardLeadsToQuickSettingsAppsAndSearch() {
        assertEquals("q0", go("feat", LgNav.RIGHT));
        assertEquals("a0", go("feat", LgNav.DOWN));
        assertEquals("top0", go("feat", LgNav.UP));
        assertEquals("feat", go("feat", LgNav.LEFT));
    }

    @Test public void quickSettingsSitAboveTheArtClock() {
        assertEquals("q1", go("q0", LgNav.RIGHT));
        assertEquals("q4", go("q3", LgNav.RIGHT));
        assertEquals("q4", go("q4", LgNav.RIGHT));
        assertEquals("feat", go("q0", LgNav.LEFT));
        assertEquals("clock", go("q2", LgNav.DOWN));
        assertEquals("top1", go("q2", LgNav.UP));
        assertEquals("q0", go("clock", LgNav.UP));
        assertEquals("feat", go("clock", LgNav.LEFT));
    }

    @Test public void appBandConnectsToTheSquareWidgetAndTheChips() {
        assertEquals("w1", go("a0", LgNav.LEFT));
        assertEquals("a0", go("w1", LgNav.RIGHT));
        assertEquals("a14", go("a14", LgNav.RIGHT));
        assertEquals("c0", go("a1", LgNav.DOWN));
        assertEquals("c3", go("a6", LgNav.DOWN));
        assertEquals("feat", go("a2", LgNav.UP));
        assertEquals("clock", go("a5", LgNav.UP));
        assertEquals("w1", go("c0", LgNav.LEFT));
        assertEquals("c3", go("c3", LgNav.RIGHT));
    }

    @Test public void upFromTheBandUsesTheShownPositionNotTheIndex() {
        // With apps 5..11 shown, app 6 is the second one on screen: Up goes to the feature card.
        assertEquals("feat", LgNav.next("a6", LgNav.UP, APPS, CHIPS, QUICK, 5));
        assertEquals("clock", LgNav.next("a10", LgNav.UP, APPS, CHIPS, QUICK, 5));
        assertEquals("a10", LgNav.next("clock", LgNav.DOWN, APPS, CHIPS, QUICK, 5));
    }

    @Test public void topButtons() {
        assertEquals("top1", go("top0", LgNav.RIGHT));
        assertEquals("feat", go("top0", LgNav.DOWN));
        assertEquals("q4", go("top1", LgNav.DOWN));
    }

    @Test public void noAppsNeverStrandsFocus() {
        assertEquals("w1", LgNav.next("feat", LgNav.DOWN, 0, 0, QUICK, 0));
        assertEquals("w1", LgNav.next("clock", LgNav.DOWN, 0, 0, QUICK, 0));
        assertEquals("w1", LgNav.next("w1", LgNav.RIGHT, 0, 0, QUICK, 0));
    }

    @Test public void everyMoveLandsOnAKeyThatExists() {
        List<String> keys = new java.util.ArrayList<>(Arrays.asList("top0", "top1", "feat", "clock", "w1"));
        for (int i = 0; i < QUICK; i++) keys.add("q" + i);
        for (int i = 0; i < APPS; i++) keys.add("a" + i);
        for (int i = 0; i < CHIPS; i++) keys.add("c" + i);
        for (int first = 0; first <= APPS - 7; first++) {
            for (String k : keys) {
                for (char d : new char[]{LgNav.LEFT, LgNav.RIGHT, LgNav.UP, LgNav.DOWN}) {
                    String to = LgNav.next(k, d, APPS, CHIPS, QUICK, first);
                    assertTrue(k + " " + d + " -> " + to, keys.contains(to));
                }
            }
        }
    }

    @Test public void bandScrollsSoSevenWholeAppsShow() {
        assertEquals(0, LgNav.firstShown(0, APPS, 7));
        assertEquals(0, LgNav.firstShown(3, APPS, 7));
        assertEquals(1, LgNav.firstShown(4, APPS, 7));
        assertEquals(8, LgNav.firstShown(14, APPS, 7));
        assertEquals(0, LgNav.firstShown(5, 5, 7));
    }

    @Test public void dockAppsComeFirstThenByName() {
        List<String> dock = Arrays.asList("com.netflix.ninja", "in.startv.hotstar");
        assertTrue(LgApps.compare(dock, "com.netflix.ninja", "Netflix", "in.startv.hotstar", "JioHotstar") < 0);
        assertTrue(LgApps.compare(dock, "com.plexapp.android", "Plex", "in.startv.hotstar", "JioHotstar") > 0);
        assertTrue(LgApps.compare(dock, "a.b", "apple", "c.d", "Banana") < 0);
    }
}

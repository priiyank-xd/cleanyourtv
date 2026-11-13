package com.gathin.tvhome;

import android.view.KeyEvent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MuteGestureTest {
    private static final int UP = KeyEvent.KEYCODE_VOLUME_UP, DOWN = KeyEvent.KEYCODE_VOLUME_DOWN;

    private static boolean press(MuteGesture g, int key, long t) {
        return g.feed(key, KeyEvent.ACTION_DOWN, 0, t);
    }

    @Test public void upDownUpDownQuicklyMutes() {
        MuteGesture g = new MuteGesture();
        assertFalse(press(g, UP, 0));
        assertFalse(press(g, DOWN, 300));
        assertFalse(press(g, UP, 600));
        assertTrue(press(g, DOWN, 900));
    }

    @Test public void theSamePatternAgainToggles() {
        MuteGesture g = new MuteGesture();
        press(g, UP, 0); press(g, DOWN, 200); press(g, UP, 400);
        assertTrue(press(g, DOWN, 600));
        press(g, UP, 5000); press(g, DOWN, 5200); press(g, UP, 5400);
        assertTrue(press(g, DOWN, 5600));
    }

    @Test public void normalVolumeChangesNeverMute() {
        MuteGesture g = new MuteGesture();
        long t = 0;
        for (int i = 0; i < 10; i++) assertFalse(press(g, UP, t += 250));
        for (int i = 0; i < 10; i++) assertFalse(press(g, DOWN, t += 250));
        assertFalse(press(g, UP, t += 250));
        assertFalse(press(g, UP, t += 250));
        assertFalse(press(g, DOWN, t += 250));
        assertFalse(press(g, DOWN, t += 250));
    }

    @Test public void tooSlowStartsOver() {
        MuteGesture g = new MuteGesture();
        press(g, UP, 0); press(g, DOWN, 300); press(g, UP, 600);
        assertFalse(press(g, DOWN, 600 + MuteGesture.GAP_MS + 1));
        MuteGesture h = new MuteGesture();
        press(h, UP, 0); press(h, DOWN, 650); press(h, UP, 1300);
        assertFalse(press(h, DOWN, 1300 + 650 + 100));   // each gap fine, whole pattern over 2 s
    }

    @Test public void anotherKeyInTheMiddleStartsOver() {
        MuteGesture g = new MuteGesture();
        press(g, UP, 0); press(g, DOWN, 200);
        assertFalse(g.feed(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 0, 300));
        press(g, UP, 400);
        assertFalse(press(g, DOWN, 600));
    }

    @Test public void heldRepeatsAndKeyUpsDoNotCount() {
        MuteGesture g = new MuteGesture();
        press(g, UP, 0);
        assertFalse(g.feed(UP, KeyEvent.ACTION_DOWN, 1, 50));
        assertFalse(g.feed(UP, KeyEvent.ACTION_UP, 0, 80));
        press(g, DOWN, 200); press(g, UP, 400);
        assertTrue(press(g, DOWN, 600));
    }

    @Test public void aWrongPressCanBeginANewAttempt() {
        MuteGesture g = new MuteGesture();
        press(g, UP, 0); press(g, DOWN, 200);
        press(g, DOWN, 400);              // breaks the pattern
        press(g, UP, 600); press(g, DOWN, 800); press(g, UP, 1000);
        assertTrue(press(g, DOWN, 1200));
    }
}

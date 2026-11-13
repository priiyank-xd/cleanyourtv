package com.gathin.tvhome;

import android.view.KeyEvent;

/**
 * Mute for remotes without a mute button (fork, Lumio Arc 5): Volume Up, Down, Up, Down pressed quickly toggles
 * mute. HomeKeyService feeds it every key it sees in any app and lets the keys through unchanged, so the four
 * presses cancel out and the volume is back where it was when the mute lands. Pure Java, unit tested.
 */
final class MuteGesture {
    /** The longest gap between two presses of the pattern. */
    static final long GAP_MS = 700;
    /** The longest the whole pattern may take, first press to last. */
    static final long WINDOW_MS = 2000;
    private static final int[] PATTERN = {KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN};

    private int matched;
    private long firstAt, lastAt;

    /** How many presses of the pattern are matched so far; 0 before a press would start a new attempt. */
    int progress() {
        return matched;
    }

    /**
     * Feeds one key event; true when it completes the pattern. Only fresh presses count: a held key's repeats and
     * key-ups are ignored, and any other key or a pause too long starts over.
     */
    boolean feed(int keyCode, int action, int repeatCount, long timeMs) {
        if (action != KeyEvent.ACTION_DOWN || repeatCount != 0) return false;
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            matched = 0;
            return false;
        }
        if (matched > 0 && (timeMs - lastAt > GAP_MS || timeMs - firstAt > WINDOW_MS)) matched = 0;
        if (keyCode != PATTERN[matched]) {
            // A wrong key may still be the start of a new attempt.
            matched = 0;
            if (keyCode != PATTERN[0]) return false;
        }
        if (matched == 0) firstAt = timeMs;
        lastAt = timeMs;
        matched++;
        if (matched < PATTERN.length) return false;
        matched = 0;
        return true;
    }
}

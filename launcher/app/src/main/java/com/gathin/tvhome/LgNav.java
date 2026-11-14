package com.gathin.tvhome;

/**
 * Remote navigation for the LG-style home (home_layout "lg"). Every focusable part has a key:
 * "top0".."top2" (search, settings, profile), "feat" (feature card), "q0".."qN" (quick settings), "clock" (art clock),
 * "w1" (square widget), "a0".."aN" (apps in the band) and "c0".."cN" (chips in the wide widget).
 * Pure Java so the whole map is unit tested; LgHome only draws the result.
 */
final class LgNav {
    static final char LEFT = 'L', RIGHT = 'R', UP = 'U', DOWN = 'D';

    private LgNav() {
    }

    /**
     * The key focus moves to from {@code key} in direction {@code dir}, or {@code key} itself when nothing is
     * that way. {@code first} is the first app fully shown in the band, so Up from the left half of the band goes
     * to the feature card and from the right half to the art clock.
     */
    static String next(String key, char dir, int apps, int chips, int quick, int first) {
        int lastApp = apps - 1;
        String appEntry = apps > 0 ? "a0" : "w1";
        if (key.startsWith("top")) {
            int t = parse(key);
            if (dir == LEFT) return "top" + Math.max(0, t - 1);
            if (dir == RIGHT) return "top" + Math.min(2, t + 1);
            if (dir == DOWN) return t > 0 ? (quick > 0 ? "q" + (quick - 1) : "clock") : "feat";
            return key;
        }
        if (key.equals("feat")) {
            if (dir == UP) return "top0";
            if (dir == DOWN) return appEntry;
            if (dir == RIGHT) return quick > 0 ? "q0" : "clock";
            return key;
        }
        if (key.equals("clock")) {
            if (dir == LEFT) return "feat";
            if (dir == UP) return quick > 0 ? "q0" : "top1";
            if (dir == DOWN) return apps > 0 ? "a" + Math.min(lastApp, first + 5) : "w1";
            return key;
        }
        if (key.equals("w1")) {
            if (dir == RIGHT) return appEntry.equals("w1") ? (chips > 0 ? "c0" : key) : "a" + first;
            if (dir == UP) return "feat";
            return key;
        }
        char kind = key.charAt(0);
        int n = parse(key);
        if (kind == 'q') {
            if (dir == LEFT) return n > 0 ? "q" + (n - 1) : "feat";
            if (dir == RIGHT) return n < quick - 1 ? "q" + (n + 1) : key;
            if (dir == UP) return "top1";
            return "clock";
        }
        if (kind == 'a') {
            if (dir == LEFT) return n > 0 ? "a" + (n - 1) : "w1";
            if (dir == RIGHT) return "a" + Math.min(lastApp, n + 1);
            if (dir == UP) return n - first < 4 ? "feat" : "clock";
            return chips > 0 ? "c" + Math.min(chips - 1, Math.max(0, (n - first) / 2)) : key;
        }
        if (kind == 'c') {
            if (dir == LEFT) return n > 0 ? "c" + (n - 1) : "w1";
            if (dir == RIGHT) return "c" + Math.min(chips - 1, n + 1);
            if (dir == UP) return apps > 0 ? "a" + Math.min(lastApp, first + n * 2) : "w1";
            return key;
        }
        return key;
    }

    /** First app fully shown in the band when app {@code n} has focus: the band keeps a few apps on each side. */
    static int firstShown(int n, int apps, int visible) {
        return Math.max(0, Math.min(apps - visible, n - 3));
    }

    static int parse(String key) {
        try {
            return Integer.parseInt(key.substring(key.startsWith("top") ? 3 : 1));
        } catch (RuntimeException e) {
            return 0;
        }
    }
}

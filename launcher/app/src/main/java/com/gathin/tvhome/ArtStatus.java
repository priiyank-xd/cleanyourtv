package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

/**
 * Fork: the live line on the LG Home's Art mode card: the sleep timer's countdown and where the sound goes, one at a
 * time. The Sleep Timer add-on tells the launcher when its timer ends ({@link Receiver}); the sound device is read
 * by LgHome from AudioManager.
 */
final class ArtStatus {
    private ArtStatus() { }

    static final String ACTION_SLEEP_TIMER = "com.gathin.tvhome.SLEEP_TIMER";
    static final String EXTRA_END = "end";
    private static final String PREFS = "art_status";
    /** Longest timer accepted from a broadcast; the add-on's longest is 120 minutes. */
    static final long MAX_AHEAD_MS = 24 * 60 * 60_000L;

    /** The lines to show, in order; empty means the card shows "Art mode". */
    static List<String> lines(long now, long sleepEnd, String soundOn) {
        List<String> out = new ArrayList<>();
        if (sleepEnd > now) out.add("Sleep in " + left(sleepEnd - now));
        if (soundOn != null && !soundOn.trim().isEmpty()) out.add("Sound on " + soundOn.trim());
        return out;
    }

    /** "1 min" for anything under a minute, whole minutes rounded up, "1 h 5 min" from an hour. */
    static String left(long ms) {
        long min = Math.max(1, (ms + 59_999) / 60_000);
        if (min < 60) return min + " min";
        return min / 60 + " h" + (min % 60 == 0 ? "" : " " + min % 60 + " min");
    }

    /** A broadcast end time, or 0 when it is off or not believable. */
    static long accept(long end, long now) {
        return end > now && end - now <= MAX_AHEAD_MS ? end : 0;
    }

    static long sleepEnd(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(EXTRA_END, 0);
    }

    /**
     * Sleep Timer sends its end time (0: off) whenever a timer starts, is pushed back or is cancelled. Any app could
     * send this; the worst it can do is show a wrong countdown, so it is bounded and nothing else.
     */
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            if (!ACTION_SLEEP_TIMER.equals(i.getAction())) return;
            long end = accept(i.getLongExtra(EXTRA_END, 0), System.currentTimeMillis());
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(EXTRA_END, end).apply();
        }
    }
}

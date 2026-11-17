package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Fork: the title last seen playing in each app, for Home's Recent row. MediaListener saves what an app's media
 * session reports (title, subtitle, artwork); a title with no artwork may get a TMDB picture later. Nothing is sent
 * anywhere but the TMDB title search. Android keeps other apps' Continue watching rows from third-party launchers, so
 * this is the closest the launcher can get.
 */
final class LastPlayed {
    private static final String PREFS = "last_played_by_app", OLD_ART = "last_played.jpg";
    private static final int ART_WIDTH = 640;

    final String pkg, title, subtitle;
    final long when;

    private LastPlayed(String pkg, String title, String subtitle, long when) {
        this.pkg = pkg;
        this.title = title;
        this.subtitle = subtitle;
        this.when = when;
    }

    /** Every app's last title, newest first. */
    static List<LastPlayed> all(Context ctx) {
        List<LastPlayed> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs(ctx).getAll().entrySet()) {
            String key = e.getKey();
            if (!key.endsWith("|title")) continue;
            String pkg = key.substring(0, key.length() - "|title".length());
            LastPlayed p = of(ctx, pkg);
            if (p != null) out.add(p);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.when, a.when));
        return out;
    }

    /** The app's last title, or null when nothing has been seen playing in it. */
    static LastPlayed of(Context ctx, String pkg) {
        SharedPreferences p = prefs(ctx);
        String title = p.getString(pkg + "|title", null);
        if (title == null) return null;
        return new LastPlayed(pkg, title, p.getString(pkg + "|subtitle", ""), p.getLong(pkg + "|when", 0L));
    }

    /** True when this is a different title from the one saved for the app (its artwork is then rewritten). */
    static boolean isNew(Context ctx, String pkg, String title) {
        return !title.equals(prefs(ctx).getString(pkg + "|title", null));
    }

    /** Saves the app's title; {@code art} may be null, which clears an older title's artwork. */
    static void save(Context ctx, String pkg, String title, String subtitle, Bitmap art) {
        writeArt(ctx, pkg, art);
        touch(ctx, pkg, title, subtitle);
    }

    /** Sets the artwork for the app's title only if that title is still the saved one (a late TMDB picture). */
    static void saveArtIfCurrent(Context ctx, String pkg, String title, Bitmap art) {
        if (!isNew(ctx, pkg, title) && !hasArt(ctx, pkg)) writeArt(ctx, pkg, art);
    }

    /** Notes that the app's saved title is playing now, keeping its artwork. */
    static void touch(Context ctx, String pkg, String title, String subtitle) {
        prefs(ctx).edit().putString(pkg + "|title", title)
                .putString(pkg + "|subtitle", subtitle == null ? "" : subtitle)
                .putLong(pkg + "|when", System.currentTimeMillis()).apply();
        //noinspection ResultOfMethodCallIgnored
        new File(ctx.getFilesDir(), OLD_ART).delete();   // the single-title file of the earlier version
    }

    /** Whether artwork is saved for the app's current title. */
    static boolean hasArt(Context ctx, String pkg) {
        return file(ctx, pkg).isFile();
    }

    /** Changes whenever the app's artwork file is rewritten, so Home knows to decode it again. */
    static long artStamp(Context ctx, String pkg) {
        return file(ctx, pkg).lastModified();
    }

    /** The app's saved artwork, or null. */
    static Bitmap art(Context ctx, String pkg) {
        File f = file(ctx, pkg);
        return f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    private static void writeArt(Context ctx, String pkg, Bitmap art) {
        File f = file(ctx, pkg);
        if (art == null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return;
        }
        Bitmap scaled = art.getWidth() > ART_WIDTH
                ? Bitmap.createScaledBitmap(art, ART_WIDTH, Math.round(art.getHeight() * ART_WIDTH / (float) art.getWidth()), true)
                : art;
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    private static File file(Context ctx, String pkg) {
        return new File(ctx.getFilesDir(), "played_" + pkg + ".jpg");
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

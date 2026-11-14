package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Fork: the title last seen playing in another app, for Home's "last watched" card. MediaListener saves what an
 * app's media session reports (app, title, subtitle, artwork); nothing is sent anywhere. Android keeps other apps'
 * Continue watching rows from third-party launchers, so this is the closest the launcher can get.
 */
final class LastPlayed {
    private static final String PREFS = "last_played", ART = "last_played.jpg";
    private static final int ART_WIDTH = 640;

    final String pkg, title, subtitle;
    final long when;

    private LastPlayed(String pkg, String title, String subtitle, long when) {
        this.pkg = pkg;
        this.title = title;
        this.subtitle = subtitle;
        this.when = when;
    }

    /** The saved title, or null when nothing has been seen playing. */
    static LastPlayed load(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String pkg = p.getString("pkg", null), title = p.getString("title", null);
        if (pkg == null || title == null) return null;
        return new LastPlayed(pkg, title, p.getString("subtitle", ""), p.getLong("when", 0L));
    }

    /** True when this is a different title from the saved one (the artwork is then rewritten). */
    static boolean isNew(Context ctx, String pkg, String title) {
        SharedPreferences p = prefs(ctx);
        return !pkg.equals(p.getString("pkg", null)) || !title.equals(p.getString("title", null));
    }

    /** Saves the title; {@code art} may be null, which clears an older title's artwork. */
    static void save(Context ctx, String pkg, String title, String subtitle, Bitmap art) {
        File file = new File(ctx.getFilesDir(), ART);
        if (art == null) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } else {
            Bitmap scaled = art.getWidth() > ART_WIDTH
                    ? Bitmap.createScaledBitmap(art, ART_WIDTH, Math.round(art.getHeight() * ART_WIDTH / (float) art.getWidth()), true)
                    : art;
            try (FileOutputStream out = new FileOutputStream(file)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out);
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
        touch(ctx, pkg, title, subtitle);
    }

    /** Notes that the saved title is playing now, keeping its artwork. */
    static void touch(Context ctx, String pkg, String title, String subtitle) {
        prefs(ctx).edit().putString("pkg", pkg).putString("title", title)
                .putString("subtitle", subtitle == null ? "" : subtitle)
                .putLong("when", System.currentTimeMillis()).apply();
    }

    /** Whether artwork is saved for the current title. */
    static boolean hasArt(Context ctx) {
        return new File(ctx.getFilesDir(), ART).isFile();
    }

    /** Changes whenever the artwork file is rewritten, so Home knows to decode it again. */
    static long artStamp(Context ctx) {
        return new File(ctx.getFilesDir(), ART).lastModified();
    }

    /** The saved artwork, or null. */
    static Bitmap art(Context ctx) {
        File file = new File(ctx.getFilesDir(), ART);
        return file.isFile() ? BitmapFactory.decodeFile(file.getPath()) : null;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

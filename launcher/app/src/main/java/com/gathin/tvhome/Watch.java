package com.gathin.tvhome;

import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.util.HashSet;
import java.util.Set;

/**
 * Where Discover's buttons go (fork only): a streaming app, its Play Store page, or Google TV's own search for a
 * title. TMDB says which service has a title but not the app's link to it, so Watch opens the app; Google TV's
 * search is the way to land on the exact title.
 */
final class Watch {
    private Watch() {
    }

    /** Packages of the given ones that are installed and can be opened. */
    static Set<String> installed(Context ctx, Iterable<String> packages) {
        Set<String> out = new HashSet<>();
        for (String p : packages) if (p != null && launchIntent(ctx, p) != null) out.add(p);
        return out;
    }

    /** Opens an installed app on its home screen. */
    static boolean openApp(Context ctx, String pkg) {
        Intent i = launchIntent(ctx, pkg);
        if (i == null || !start(ctx, i)) return false;
        LastOpened.record(ctx, pkg);
        return true;
    }

    /** The app's page in the Play Store, to install it. */
    static boolean getApp(Context ctx, String pkg) {
        return start(ctx, new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)))
                || start(ctx, new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
    }

    /**
     * Watch with a provider: open its app when installed, offer it in the Play Store when not, and search Google TV
     * for the title when no app is known for the service. False when nothing could be opened.
     */
    static boolean watch(Context ctx, Discover.Provider p, Set<String> installed, String title) {
        String pkg = Discover.packageFor(p.id);
        if (pkg == null) return googleSearch(ctx, title);
        return installed.contains(pkg) ? openApp(ctx, pkg) : getApp(ctx, pkg);
    }

    /** Every package a title's providers could open, installed or not. */
    static java.util.List<String> packages(Discover.Availability a) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (java.util.List<Discover.Provider> g : java.util.Arrays.asList(a.stream, a.rent, a.buy, a.free)) {
            for (Discover.Provider p : g) out.add(Discover.packageFor(p.id));
        }
        return out;
    }

    /** Google TV search (Gemini on newer firmware), for a title when one is given. */
    static boolean googleSearch(Context ctx, String query) {
        Intent i = new Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH);
        if (query != null) i.putExtra(SearchManager.QUERY, query);
        return start(ctx, i) || start(ctx, new Intent(Intent.ACTION_ASSIST));
    }

    private static Intent launchIntent(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    private static boolean start(Context ctx, Intent i) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}

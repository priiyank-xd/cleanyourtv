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
        return watch(ctx, p, installed, title, null);
    }

    /** As above, opening the app on the title itself when {@code link} (from JustWatch) is known. */
    static boolean watch(Context ctx, Discover.Provider p, Set<String> installed, String title, String link) {
        String pkg = Discover.packageFor(p.id);
        if (pkg == null) return googleSearch(ctx, title);
        if (!installed.contains(pkg)) return getApp(ctx, pkg);
        if (link != null && openLink(ctx, link, pkg)) {
            LastOpened.record(ctx, pkg);
            return true;
        }
        return openApp(ctx, pkg);
    }

    /**
     * Opens {@code pkg} on a title from a JustWatch link: an Android TV intent:// link or a web page. Whatever the
     * link says, only that app is asked to open it, as a plain view: no component, selector or permission grants.
     */
    static boolean openLink(Context ctx, String link, String pkg) {
        Intent i;
        try {
            if (link.startsWith("intent:")) {
                Intent parsed = Intent.parseUri(link, Intent.URI_INTENT_SCHEME);
                if (parsed.getData() == null) return false;
                i = new Intent(Intent.ACTION_VIEW, parsed.getData());
                if (parsed.getExtras() != null) i.putExtras(parsed.getExtras());   // e.g. Netflix's source tag
            } else if (link.startsWith("https://") || link.startsWith("http://")) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
            } else {
                return false;
            }
        } catch (java.net.URISyntaxException e) {
            return false;
        }
        i.setPackage(pkg);
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        if (ctx.getPackageManager().resolveActivity(i, 0) == null) {
            i.removeCategory(Intent.CATEGORY_BROWSABLE);   // some TV apps take the link without BROWSABLE
            if (ctx.getPackageManager().resolveActivity(i, 0) == null) return false;
        }
        return start(ctx, i);
    }

    /** A YouTube trailer, in the YouTube app when there is one. */
    static boolean trailer(Context ctx, String key) {
        Uri u = Uri.parse("https://www.youtube.com/watch?v=" + Uri.encode(key));
        return start(ctx, new Intent(Intent.ACTION_VIEW, u).setPackage("com.google.android.youtube.tv"))
                || start(ctx, new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + Uri.encode(key))))
                || start(ctx, new Intent(Intent.ACTION_VIEW, u));
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

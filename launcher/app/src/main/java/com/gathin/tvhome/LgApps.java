package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The TV apps the LG-style home shows, in dock order and then by name, with their artwork and colour. */
final class LgApps {
    static final class App {
        final String pkg;
        final String label;
        final ComponentName component;
        final Drawable art;
        /** Fork: the app's minimal glyph (Glyphs), the same size for every app, for the band and the chips. */
        final Drawable glyph;
        final int color;
        long lastOpened;

        App(String pkg, String label, ComponentName component, Drawable art, Drawable glyph, int color) {
            this.pkg = pkg;
            this.label = label;
            this.component = component;
            this.art = art;
            this.glyph = glyph;
            this.color = color;
        }
    }

    private LgApps() {
    }

    static List<App> load(Context ctx, Config cfg) {
        PackageManager pm = ctx.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(query, 0);
        Set<String> hidden = new HashSet<>(cfg.hiddenApps);
        Set<String> seen = new HashSet<>();
        List<App> apps = new ArrayList<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || !seen.add(pkg)) continue;
            String label = cfg.appLabels.containsKey(pkg) ? cfg.appLabels.get(pkg) : String.valueOf(ri.loadLabel(pm));
            Drawable glyph = Glyphs.get(ctx, pkg, label);
            // A bundled wordmark where there is one (feature card, provider logos); otherwise the glyph, never the
            // app's own banner or icon with its square background.
            Drawable banner = bundledBanner(ctx, ri, cfg.appBanners);
            Drawable art = banner != null ? banner : glyph;
            apps.add(new App(pkg, label, new ComponentName(pkg, ri.activityInfo.name), art, glyph, AppAdapter.vividColor(glyph)));
        }
        for (App a : apps) a.lastOpened = LastOpened.get(ctx, a.pkg);
        Collections.sort(apps, order(savedOrder(ctx), cfg.dockApps));
        return apps;
    }

    /** Fork: the band order the person set with the reorder circle; apps not in it follow, in dock/name order. */
    static Comparator<App> order(List<String> saved, List<String> dock) {
        return (a, b) -> {
            int ia = saved.indexOf(a.pkg), ib = saved.indexOf(b.pkg);
            if (ia >= 0 && ib >= 0) return Integer.compare(ia, ib);
            if (ia >= 0 || ib >= 0) return ia >= 0 ? -1 : 1;
            return compare(dock, a.pkg, a.label, b.pkg, b.label);
        };
    }

    private static final String ORDER_PREFS = "lg_app_order", ORDER_KEY = "order";

    static List<String> savedOrder(Context ctx) {
        String s = ctx.getSharedPreferences(ORDER_PREFS, Context.MODE_PRIVATE).getString(ORDER_KEY, "");
        return s.isEmpty() ? new ArrayList<>() : java.util.Arrays.asList(s.split(","));
    }

    static void saveOrder(Context ctx, List<App> apps) {
        StringBuilder sb = new StringBuilder();
        for (App a : apps) sb.append(sb.length() > 0 ? "," : "").append(a.pkg);
        ctx.getSharedPreferences(ORDER_PREFS, Context.MODE_PRIVATE).edit().putString(ORDER_KEY, sb.toString()).apply();
    }

    /** Dock apps first in their configured order, then the rest by name. */
    static int compare(List<String> dock, String pa, String la, String pb, String lb) {
        int ia = dock.indexOf(pa), ib = dock.indexOf(pb);
        if (ia >= 0 || ib >= 0) {
            if (ia < 0) return 1;
            if (ib < 0) return -1;
            return Integer.compare(ia, ib);
        }
        return la.toLowerCase(Locale.ROOT).compareTo(lb.toLowerCase(Locale.ROOT));
    }

    /** Apps opened from Home, newest first, at most {@code max}. */
    /** Fork: the TV's own Settings app. It stays in the band but never counts as recently opened or watched. */
    static boolean isSystemSettings(String pkg) {
        return pkg.equals("com.android.tv.settings") || pkg.equals("com.android.settings");
    }

    static List<App> recent(List<App> apps, int max) {
        List<App> out = new ArrayList<>();
        for (App a : apps) if (a.lastOpened > 0 && !isSystemSettings(a.pkg)) out.add(a);
        Collections.sort(out, (a, b) -> Long.compare(b.lastOpened, a.lastOpened));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    static boolean launch(Context ctx, App app) {
        Intent i = new Intent(Intent.ACTION_MAIN).setComponent(app.component)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
            LastOpened.record(ctx, app.pkg);
            app.lastOpened = System.currentTimeMillis();
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    /** The bundled banner_* wordmark from app_banners (the fork's bare logos), or null. */
    private static Drawable bundledBanner(Context ctx, ResolveInfo ri, Map<String, String> banners) {
        String name = banners.get(ri.activityInfo.packageName);
        if (name == null || !name.startsWith("banner_")) return null;
        int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        return id != 0 ? ctx.getDrawable(id) : null;
    }
}

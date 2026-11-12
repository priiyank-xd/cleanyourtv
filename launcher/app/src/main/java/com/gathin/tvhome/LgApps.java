package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

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
        final int color;
        long lastOpened;

        App(String pkg, String label, ComponentName component, Drawable art, int color) {
            this.pkg = pkg;
            this.label = label;
            this.component = component;
            this.art = art;
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
            Drawable art = artwork(ctx, pm, ri, cfg.appBanners);
            apps.add(new App(pkg, label, new ComponentName(pkg, ri.activityInfo.name), art, AppAdapter.vividColor(art)));
        }
        for (App a : apps) a.lastOpened = LastOpened.get(ctx, a.pkg);
        Collections.sort(apps, order(cfg.dockApps));
        return apps;
    }

    /** Dock apps first in their configured order, then the rest by name. */
    static Comparator<App> order(List<String> dock) {
        return (a, b) -> compare(dock, a.pkg, a.label, b.pkg, b.label);
    }

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
    static List<App> recent(List<App> apps, int max) {
        List<App> out = new ArrayList<>();
        for (App a : apps) if (a.lastOpened > 0) out.add(a);
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

    /** A bundled banner_* from app_banners first (the fork's bare logos), then the app's own banner, then its icon. */
    private static Drawable artwork(Context ctx, PackageManager pm, ResolveInfo ri, Map<String, String> banners) {
        String name = banners.get(ri.activityInfo.packageName);
        if (name != null && name.startsWith("banner_")) {
            int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
            if (id != 0) return ctx.getDrawable(id);
        }
        int id = ri.activityInfo.banner != 0 ? ri.activityInfo.banner : ri.activityInfo.applicationInfo.banner;
        if (id != 0) {
            try {
                Resources res = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                Drawable d = res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XHIGH, null);
                if (d != null) return d;
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        return ri.loadIcon(pm);
    }
}

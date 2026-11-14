package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;

/** The standard settings intent, or, when nothing usable answers it, the first vendor settings entry
 *  from settings_fallback_components that is installed (Xiaomi China, TCL / FFALCON, Fire OS), else the
 *  AOSP TV settings app's main screen where it is installed. */
final class SystemSettings {
    private SystemSettings() {}

    static Intent intent(Context context, String action) {
        PackageManager pm = context.getPackageManager();
        Intent standard = new Intent(action);
        ResolveInfo resolved = pm.resolveActivity(standard, PackageManager.MATCH_DEFAULT_ONLY);
        // Fire OS resolves the standard actions to a CTS compliance stub that opens nothing;
        // treat it as missing so the vendor fallback runs.
        if (resolved != null && !resolved.activityInfo.name.contains("CTSDummy")) return standard;
        Intent vendor = vendorIntent(context);
        if (vendor != null) return vendor;
        // Some firmware ships the AOSP TV settings app with no filter for the standard actions (HY300 Pro+
        // projector, rk3566, Android 14): open its main screen directly. Not a vendor entry, so the Settings
        // menu's "Native settings" row stays as it was.
        Intent aosp = new Intent().setComponent(AOSP_TV_SETTINGS);
        try {
            pm.getPackageInfo(AOSP_TV_SETTINGS.getPackageName(), 0);
            return aosp;
        } catch (PackageManager.NameNotFoundException e) {
            return standard;
        }
    }

    private static final ComponentName AOSP_TV_SETTINGS =
            new ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings");

    /** The first vendor settings entry from settings_fallback_components that is installed, or null
     *  when none is. China-market firmware keeps pages the AOSP TV settings has no row for -
     *  Xiaomi's Account & Security, where the ADB switch lives - in the vendor's own settings app;
     *  MainActivity's Settings menu offers it as "Native settings" while one is installed. */
    static Intent vendorIntent(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String value : context.getResources().getStringArray(R.array.settings_fallback_components)) {
            ComponentName component = ComponentName.unflattenFromString(value);
            if (component == null) continue;
            // Explicit resolveActivity() always succeeds, so check the package exists instead.
            try {
                pm.getPackageInfo(component.getPackageName(), 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            return new Intent().setComponent(component);
        }
        return null;
    }

    /** The TV's App info page for pkg, or null where the firmware has none (CVTE's settings app, #1202). */
    static Intent appInfo(Context context, String pkg) {
        Intent info = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        return context.getPackageManager().resolveActivity(info, PackageManager.MATCH_DEFAULT_ONLY) != null ? info : null;
    }

    /**
     * The TV's display or picture settings. On Xiaomi sets, the AOSP DISPLAY_SETTINGS action resolves to
     * an empty stub in DroidTvSettings; prefer Xiaomi's own PictureActivity where installed.
     */
    static Intent displayIntent(Context context) {
        ComponentName xiaomi = new ComponentName("com.android.tv.settings",
                "com.xiaomi.mitv.settings.picture.PictureActivity");
        if (context.getPackageManager().resolveActivity(new Intent().setComponent(xiaomi), 0) != null) {
            return new Intent().setComponent(xiaomi);
        }
        return intent(context, Settings.ACTION_DISPLAY_SETTINGS);
    }

    /**
     * Fork: picture settings for the LG-style home. Google TV (Android 14) answers no DISPLAY_SETTINGS, but its
     * "Display & Sound" page answers SOUND_SETTINGS; use that before the main settings screen.
     */
    static Intent displaySoundIntent(Context context) {
        PackageManager pm = context.getPackageManager();
        if (pm.resolveActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY) == null) {
            Intent sound = new Intent(Settings.ACTION_SOUND_SETTINGS);
            if (pm.resolveActivity(sound, PackageManager.MATCH_DEFAULT_ONLY) != null) return sound;
        }
        return displayIntent(context);
    }

    /**
     * Fork: the Picture quick setting. Google TV has no public intent for its Picture page, so look in the TV's
     * settings app for an exported activity named like "Picture..." (picture mode, brightness, colour; vendors
     * name it so, Xiaomi's PictureActivity included). Display & Sound when there is none.
     */
    static Intent pictureIntent(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String action : new String[]{Settings.ACTION_SETTINGS, Settings.ACTION_SOUND_SETTINGS}) {
            ResolveInfo ri = pm.resolveActivity(new Intent(action), PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null) continue;
            try {
                android.content.pm.ActivityInfo[] all = pm.getPackageInfo(ri.activityInfo.packageName,
                        PackageManager.GET_ACTIVITIES).activities;
                if (all == null) continue;
                for (android.content.pm.ActivityInfo a : all) {
                    String name = a.name.substring(a.name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
                    if (a.exported && a.enabled && a.permission == null
                            && name.startsWith("picture") && !name.contains("inpicture")) {
                        return new Intent().setComponent(new ComponentName(a.packageName, a.name));
                    }
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return displaySoundIntent(context);
    }

    /**
     * Fork: the Google account and profile page for the Home's profile button. Google TV's "Accounts & profiles"
     * answers SYNC_SETTINGS or ADD_ACCOUNT_SETTINGS depending on firmware; main settings when neither does.
     */
    static Intent profileIntent(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String action : new String[]{Settings.ACTION_SYNC_SETTINGS, Settings.ACTION_ADD_ACCOUNT}) {
            Intent i = new Intent(action);
            if (pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null) return i;
        }
        return intent(context, Settings.ACTION_SETTINGS);
    }

    /**
     * Fork: Bluetooth for the LG-style home. Google TV answers no BLUETOOTH_SETTINGS; its "Remotes & Accessories"
     * page is a slice in TV Settings, opened through SLICE_SETTINGS. Main settings when neither is there.
     */
    static Intent bluetoothIntent(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent bt = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        if (pm.resolveActivity(bt, PackageManager.MATCH_DEFAULT_ONLY) != null) return bt;
        Intent slice = new Intent("android.settings.SLICE_SETTINGS").setPackage("com.android.tv.settings")
                .putExtra("slice_uri", "content://" + ACCESSORIES_SLICES + "/general");
        if (pm.resolveActivity(slice, PackageManager.MATCH_DEFAULT_ONLY) != null
                && pm.resolveContentProvider(ACCESSORIES_SLICES, 0) != null) return slice;
        return intent(context, Settings.ACTION_BLUETOOTH_SETTINGS);
    }

    private static final String ACCESSORIES_SLICES = "com.android.tv.settings.accessories.sliceprovider";

    /** Fork: Cast for the LG-style home. Google TV answers no CAST_SETTINGS; its Chromecast built-in page does. */
    static Intent castIntent(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent cast = new Intent(Settings.ACTION_CAST_SETTINGS);
        if (pm.resolveActivity(cast, PackageManager.MATCH_DEFAULT_ONLY) != null) return cast;
        Intent receiver = new Intent("com.google.android.settings.CAST_RECEIVER_SETTINGS");
        if (pm.resolveActivity(receiver, PackageManager.MATCH_DEFAULT_ONLY) != null) return receiver;
        return intent(context, Settings.ACTION_CAST_SETTINGS);
    }
}

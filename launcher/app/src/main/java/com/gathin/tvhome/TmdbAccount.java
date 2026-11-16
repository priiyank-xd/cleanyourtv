package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The TMDB account Discover keeps Watch later, Favourites and your own lists in (fork only): no database of ours.
 * Signed in once from the TV with a QR code approved on a phone (ConnectActivity). The tokens stay in the
 * launcher's private settings on the projector (backups are off) and are never logged.
 */
final class TmdbAccount {
    private static final String PREFS = "tmdb_account";

    /** v4 access token and account id (lists, reading the watchlist); v3 session and account id (marking). */
    final String access, objectId, session, name;
    final int accountId;

    private TmdbAccount(String access, String objectId, String session, int accountId, String name) {
        this.access = access;
        this.objectId = objectId;
        this.session = session;
        this.accountId = accountId;
        this.name = name;
    }

    /** The account when signed in, else null. */
    static TmdbAccount load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String access = p.getString("access", ""), objectId = p.getString("object", ""), session = p.getString("session", "");
        int accountId = p.getInt("account", 0);
        if (access.isEmpty() || objectId.isEmpty() || session.isEmpty() || accountId == 0) return null;
        return new TmdbAccount(access, objectId, session, accountId, p.getString("name", ""));
    }

    static void save(Context ctx, String access, String objectId, String session, int accountId, String name) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("access", access).putString("object", objectId)
                .putString("session", session).putInt("account", accountId).putString("name", name == null ? "" : name).apply();
    }

    static void clear(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}

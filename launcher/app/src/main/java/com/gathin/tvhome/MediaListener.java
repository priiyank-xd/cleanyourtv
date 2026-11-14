package com.gathin.tvhome;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Fork: notes what other apps are playing, for Home's "last watched" card (LastPlayed). It reads only media
 * sessions, never notifications. Android lists other apps' media sessions only to an enabled notification listener,
 * so it is switched on once, with the remote in Settings or over ADB:
 * cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaListener
 */
public class MediaListener extends NotificationListenerService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<MediaController> watched = new ArrayList<>();
    private final List<MediaController.Callback> callbacks = new ArrayList<>();
    private MediaSessionManager sessions;
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::watch;

    @Override
    public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        if (sessions == null) return;
        ComponentName me = new ComponentName(this, MediaListener.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me, handler);
            watch(sessions.getActiveSessions(me));
        } catch (SecurityException ignored) {
            // Access was switched off between binding and now.
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        unwatch();
    }

    private void watch(List<MediaController> controllers) {
        unwatch();
        if (controllers == null) return;
        for (MediaController c : controllers) {
            if (getPackageName().equals(c.getPackageName())) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    note(c);
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    note(c);
                }
            };
            c.registerCallback(cb, handler);
            watched.add(c);
            callbacks.add(cb);
            note(c);
        }
    }

    private void unwatch() {
        for (int i = 0; i < watched.size(); i++) watched.get(i).unregisterCallback(callbacks.get(i));
        watched.clear();
        callbacks.clear();
    }

    /** Saves the session's title while it plays; a paused or stopped session leaves the last title as it was. */
    private void note(MediaController c) {
        PlaybackState state = c.getPlaybackState();
        MediaMetadata m = c.getMetadata();
        if (state == null || state.getState() != PlaybackState.STATE_PLAYING || m == null) return;
        String title = text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        if (TextUtils.isEmpty(title)) return;
        String subtitle = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST);
        String pkg = c.getPackageName();
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        // Many apps send the title first and its artwork in a later update: save the art when it first arrives.
        if (!LastPlayed.isNew(this, pkg, title) && (art == null || LastPlayed.hasArt(this))) {
            LastPlayed.touch(this, pkg, title, subtitle);
            return;
        }
        LastPlayed.save(this, pkg, title, subtitle, art);
    }

    private static String text(MediaMetadata m, String first, String second) {
        CharSequence s = m.getText(first);
        if (TextUtils.isEmpty(s)) s = m.getText(second);
        return s == null ? null : s.toString().trim();
    }
}

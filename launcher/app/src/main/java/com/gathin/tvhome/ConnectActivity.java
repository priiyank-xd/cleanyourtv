package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.json.JSONObject;

import java.util.EnumMap;
import java.util.Map;

/**
 * Connect TMDB (fork only), from the Settings menu or Discover's Save and My lists: a QR code to approve the
 * sign-in on a phone, no typing with the remote. The screen asks TMDB every few seconds whether the phone said
 * yes, then turns the approval into the tokens Discover needs and closes. Signed in, it offers Disconnect.
 */
public class ConnectActivity extends Activity {
    private static final long POLL_MS = 4000, EXPIRES_MS = 10 * 60 * 1000L;

    private DiscoverUi ui;
    private DiscoverNet net;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout panel;
    private String requestToken;
    private long started;
    private boolean finishing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new DiscoverUi(this);
        net = DiscoverNet.get(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xB3000000);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setBackground(ui.sheet(28));
        panel.setPadding(ui.px(32), ui.px(28), ui.px(32), ui.px(28));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        root.addView(panel, new FrameLayout.LayoutParams(ui.px(400), -2, Gravity.CENTER));
        setContentView(root);
        if (TmdbAccount.load(this) != null) showConnected(false);
        else begin();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---- signing in ----

    private void begin() {
        handler.removeCallbacksAndMessages(null);
        requestToken = null;
        showMessage(getString(R.string.dc_connect_title), getString(R.string.dc_loading), null, null);
        if (!net.enabled()) {
            showMessage(getString(R.string.dc_connect_title), getString(R.string.dc_no_key), null, null);
            return;
        }
        net.call("POST", Discover.requestTokenUrl(), "{}", net.appToken(), j -> {
            if (isDestroyed()) return;
            String token = j == null ? "" : j.optString("request_token", "");
            if (token.isEmpty()) {
                showMessage(getString(R.string.dc_connect_title), getString(R.string.dc_offline), getString(R.string.dc_retry), v -> begin());
                return;
            }
            requestToken = token;
            started = System.currentTimeMillis();
            showCode(Discover.approveUrl(token));
            handler.postDelayed(this::poll, POLL_MS);
        });
    }

    /** Has the phone approved? Then the v4 token, the v3 session and the account, and done. */
    private void poll() {
        if (requestToken == null || isDestroyed()) return;
        if (System.currentTimeMillis() - started > EXPIRES_MS) {
            showMessage(getString(R.string.dc_connect_title), getString(R.string.dc_code_expired), getString(R.string.dc_new_code), v -> begin());
            return;
        }
        String body = json("request_token", requestToken);
        net.call("POST", Discover.accessTokenUrl(), body, net.appToken(), j -> {
            if (isDestroyed() || requestToken == null) return;
            String access = j == null ? "" : j.optString("access_token", "");
            String objectId = j == null ? "" : j.optString("account_id", "");
            if (access.isEmpty() || objectId.isEmpty()) {
                handler.postDelayed(this::poll, POLL_MS);   // not approved yet
                return;
            }
            requestToken = null;
            finishSignIn(access, objectId);
        });
    }

    private void finishSignIn(String access, String objectId) {
        showMessage(getString(R.string.dc_connect_title), getString(R.string.dc_connecting), null, null);
        net.call("POST", Discover.convertUrl(), json("access_token", access), net.appToken(), s -> {
            String session = s == null ? "" : s.optString("session_id", "");
            if (isDestroyed()) return;
            if (session.isEmpty()) {
                showMessage(getString(R.string.dc_connect_title), getString(R.string.dc_connect_failed), getString(R.string.dc_retry), v -> begin());
                return;
            }
            net.call("GET", Discover.accountUrl(session), null, net.appToken(), a -> {
                if (isDestroyed()) return;
                int id = a == null ? 0 : a.optInt("id");
                if (id == 0) {
                    showMessage(getString(R.string.dc_connect_title), getString(R.string.dc_connect_failed), getString(R.string.dc_retry), v -> begin());
                    return;
                }
                String name = a.optString("username", "");
                TmdbAccount.save(this, access, objectId, session, id, name);
                setResult(RESULT_OK);
                showConnected(true);
            });
        });
    }

    private void disconnect() {
        TmdbAccount a = TmdbAccount.load(this);
        if (a != null) {
            // best effort: end the tokens at TMDB too; the projector forgets them either way
            net.call("DELETE", Discover.accessTokenUrl(), json("access_token", a.access), net.appToken(), j -> { });
            net.call("DELETE", Tmdb.API + "authentication/session", json("session_id", a.session), net.appToken(), j -> { });
        }
        TmdbAccount.clear(this);
        setResult(RESULT_OK);
        begin();
    }

    private static String json(String key, String value) {
        try {
            return new JSONObject().put(key, value).toString();
        } catch (org.json.JSONException e) {
            return "{}";
        }
    }

    // ---- what the panel shows ----

    private void showCode(String url) {
        panel.removeAllViews();
        title(getString(R.string.dc_connect_title));
        ImageView code = new ImageView(this);
        code.setImageBitmap(qr(url, ui.px(196)));
        code.setBackground(DiscoverUi.round(0xFFFFFFFF, ui.px(16)));
        code.setPadding(ui.px(12), ui.px(12), ui.px(12), ui.px(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(220), ui.px(220));
        lp.topMargin = ui.px(18);
        panel.addView(code, lp);
        body(getString(R.string.dc_connect_steps));
    }

    private void showConnected(boolean justNow) {
        TmdbAccount a = TmdbAccount.load(this);
        String name = a == null || a.name.isEmpty() ? getString(R.string.dc_connected_plain) : getString(R.string.dc_connected_as, a.name);
        panel.removeAllViews();
        title(name);
        body(getString(R.string.dc_connected_note));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setClipChildren(false);
        TextView done = ui.capsule(getString(R.string.dc_done));
        done.setOnClickListener(v -> finish());
        buttons.addView(done, new LinearLayout.LayoutParams(-2, ui.px(44)));
        TextView off = ui.capsule(getString(R.string.dc_disconnect));
        off.setOnClickListener(v -> disconnect());
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, ui.px(44));
        olp.leftMargin = ui.px(12);
        buttons.addView(off, olp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = ui.px(22);
        panel.addView(buttons, blp);
        done.requestFocus();
        if (justNow && !finishing) {
            finishing = true;
            handler.postDelayed(this::finish, 1800);
        }
    }

    private void showMessage(String head, String text, String button, View.OnClickListener click) {
        panel.removeAllViews();
        title(head);
        body(text);
        if (button != null) {
            TextView b = ui.capsule(button);
            b.setOnClickListener(click);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, ui.px(44));
            lp.topMargin = ui.px(22);
            panel.addView(b, lp);
            b.requestFocus();
        }
    }

    private void title(String s) {
        TextView t = ui.text(s, 20, R.string.font_medium, DiscoverUi.INK);
        t.setGravity(Gravity.CENTER);
        panel.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    private void body(String s) {
        TextView t = ui.text(s, 13, R.string.font_regular, DiscoverUi.INK_SOFT);
        t.setSingleLine(false);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = ui.px(16);
        panel.addView(t, lp);
    }

    /** A black-on-white QR code of {@code text}, {@code size} pixels square. */
    private static Bitmap qr(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (com.google.zxing.WriterException e) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }
}

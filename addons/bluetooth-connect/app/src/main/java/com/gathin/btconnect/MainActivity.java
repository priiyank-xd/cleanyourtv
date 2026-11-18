package com.gathin.btconnect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;

/**
 * The tile and module card open this: the paired speakers and headphones, each with its state, and OK on one
 * connects it or, when it is connected, disconnects it. The TV's own Bluetooth settings only offer Connect and Unpair.
 */
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView status;
    private BluetoothAdapter adapter;
    private BluetoothA2dp a2dp;
    /** Addresses asked to change a moment ago, shown as "Working…" until the state change arrives. */
    private final Set<String> pending = new HashSet<>();
    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device != null) pending.remove(device.getAddress());
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Drawn like the launcher's Settings panel (SettingsHubActivity): a glass side panel over the dimmed Home.
        FrameLayout root = new FrameLayout(this);
        View dim = new View(this);
        dim.setBackgroundColor(0x99000000);
        root.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable tint = new GradientDrawable();
        tint.setColor(0xB310141E);
        tint.setCornerRadius(px(26));
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x2EFFFFFF, 0x0FFFFFFF, 0x08FFFFFF});
        sheen.setCornerRadius(px(26));
        sheen.setStroke(Math.max(1, px(1)), 0x3DFFFFFF);
        page.setBackground(new LayerDrawable(new Drawable[]{tint, sheen}));
        page.setClipToOutline(true);
        page.setPadding(0, px(26), 0, px(16));
        FrameLayout.LayoutParams pageLp = new FrameLayout.LayoutParams(px(400), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        pageLp.setMargins(0, px(16), px(16), px(16));
        root.addView(page, pageLp);

        TextView title = Row.text(this, 22, true, 0xFFFFFFFF);
        title.setText(R.string.app_name);
        title.setPadding(px(28), 0, px(28), px(8));
        status = Row.text(this, 12, false, 0x8CFFFFFF);
        status.setPadding(px(28), 0, px(28), px(12));
        page.addView(title);
        page.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(px(28));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(px(16), 0, px(16), px(8));
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        adapter = getSystemService(BluetoothManager.class).getAdapter();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
            return;
        }
        start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) start();
        else status.setText("Needs the Nearby devices permission: run install.sh once, or allow it in the app's settings.");
    }

    private void start() {
        if (adapter == null || !adapter.isEnabled()) {
            status.setText("Bluetooth is off. Turn it on in the TV's settings.");
            return;
        }
        registerReceiver(changed, new IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED));
        if (a2dp != null) {
            refresh();
            return;
        }
        adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                a2dp = (BluetoothA2dp) proxy;
                refresh();
            }
            @Override public void onServiceDisconnected(int profile) {
                a2dp = null;
            }
        }, BluetoothProfile.A2DP);
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(changed);
        } catch (IllegalArgumentException notRegistered) {
        }
        main.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (a2dp != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
        super.onDestroy();
    }

    /** Only runs after onResume has checked Nearby devices on Android 12+. */
    @SuppressLint("MissingPermission")
    private void refresh() {
        if (a2dp == null) return;
        int focus = 0;
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocused()) focus = i;
        list.removeAllViews();
        Set<String> on = new HashSet<>();
        for (BluetoothDevice d : a2dp.getConnectedDevices()) on.add(d.getAddress());
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            if (!Link.isAudio(device.getBluetoothClass())) continue;
            String address = device.getAddress();
            boolean connected = on.contains(address);
            Row row = new Row(this, device.getName() == null ? address : device.getName(),
                    pending.contains(address) ? "Working…" : connected ? "Connected" : "Not connected",
                    () -> toggle(device, connected));
            row.setValue(connected ? "Disconnect" : "Connect");
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        status.setText(list.getChildCount() == 0
                ? "No paired speakers. Pair one in the TV's Bluetooth settings first."
                : "OK connects or disconnects a speaker.");
        if (list.getChildCount() > 0) list.getChildAt(Math.min(focus, list.getChildCount() - 1)).requestFocus();
    }

    /** Launcher units (1/960 of the screen width) to pixels. */
    private int px(int units) {
        return Math.round(units * Row.unit(this));
    }

    private void toggle(BluetoothDevice device, boolean connected) {
        if (a2dp == null || pending.contains(device.getAddress())) return;
        if (!Link.set(a2dp, device, !connected)) {
            status.setText("Android refused. Use the TV's Bluetooth settings: "
                    + Settings.ACTION_BLUETOOTH_SETTINGS.substring(Settings.ACTION_BLUETOOTH_SETTINGS.lastIndexOf('.') + 1));
            return;
        }
        pending.add(device.getAddress());
        refresh();
        // A state change normally clears it; if none arrives (a speaker that is off), stop saying Working.
        main.postDelayed(() -> {
            pending.remove(device.getAddress());
            refresh();
        }, 12000);
    }
}

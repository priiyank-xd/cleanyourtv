package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Calibration screens for the projector (focus, geometry, overscan, black and white level, grayscale, colour bars,
 * full fields), drawn once per screen at the display's own pixels with antialiasing off, so a one-pixel line is
 * one pixel. Left and Right change the screen, OK shows or hides the caption, Back leaves. Nothing runs in the
 * background: the activity only exists while it is open.
 */
public class CalibrationActivity extends Activity {
    private static final long CAPTION_MS = 5000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PatternView view;
    private int pattern;
    private boolean caption = true;
    private final Runnable hideCaption = () -> { caption = false; view.invalidate(); };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new PatternView(this);
        setContentView(view);
        showCaption();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                pattern = CalibrationPatterns.next(pattern, 1);
                showCaption();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
                pattern = CalibrationPatterns.next(pattern, -1);
                showCaption();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                handler.removeCallbacks(hideCaption);
                caption = !caption;
                view.invalidate();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void showCaption() {
        caption = true;
        view.invalidate();
        handler.removeCallbacks(hideCaption);
        handler.postDelayed(hideCaption, CAPTION_MS);
    }

    private final class PatternView extends View {
        private final Paint fill = new Paint();
        private final Paint line = new Paint();
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        PatternView(Context c) {
            super(c);
            fill.setAntiAlias(false);
            fill.setStyle(Paint.Style.FILL);
            line.setAntiAlias(false);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(0);   // hairline: exactly one device pixel
            text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            text.setTextSize(h / 45f);
            int field = CalibrationPatterns.field(pattern);
            if (field != 0) {
                c.drawColor(field);
            } else {
                switch (pattern) {
                    case CalibrationPatterns.FOCUS: focus(c, w, h); break;
                    case CalibrationPatterns.GEOMETRY: geometry(c, w, h); break;
                    case CalibrationPatterns.OVERSCAN: overscan(c, w, h); break;
                    case CalibrationPatterns.BLACK: patches(c, w, h, 0xFF000000, CalibrationPatterns.BLACK_LEVELS, 0xFF808080); break;
                    case CalibrationPatterns.WHITE: patches(c, w, h, 0xFFFFFFFF, CalibrationPatterns.WHITE_LEVELS, 0xFF404040); break;
                    case CalibrationPatterns.GRAYSCALE: grayscale(c, w, h); break;
                    default: bars(c, w, h); break;
                }
            }
            if (caption) caption(c, w, h);
        }

        /** One-pixel stripe blocks (vertical and horizontal) in the centre and the four corners, and a centre cross. */
        private void focus(Canvas c, int w, int h) {
            c.drawColor(0xFF000000);
            int s = Math.round(h * 0.18f);
            int m = Math.round(h * 0.05f);
            int[][] at = {{(w - s) / 2, (h - s) / 2}, {m, m}, {w - m - s, m}, {m, h - m - s}, {w - m - s, h - m - s}};
            line.setColor(0xFFFFFFFF);
            for (int[] p : at) {
                int x0 = p[0], y0 = p[1], half = s / 2;
                for (int x = 0; x < half; x += 2) c.drawLine(x0 + x, y0, x0 + x, y0 + s, line);
                for (int y = 0; y < s; y += 2) c.drawLine(x0 + half, y0 + y, x0 + s, y0 + y, line);
                c.drawRect(x0 - 1, y0 - 1, x0 + s, y0 + s, line);
            }
            line.setColor(0xFF808080);
            c.drawLine(w / 2f, h * 0.30f, w / 2f, h * 0.70f, line);
            c.drawLine(w * 0.30f, h / 2f, w * 0.70f, h / 2f, line);
        }

        /** A 16 by 9 grid of squares, a large centre circle, circles in the corners and a centre cross. */
        private void geometry(Canvas c, int w, int h) {
            c.drawColor(0xFF101010);
            line.setColor(0xFFC0C0C0);
            float cell = w / 16f;
            for (int i = 0; i <= 16; i++) c.drawLine(Math.min(w - 1, Math.round(i * cell)), 0, Math.min(w - 1, Math.round(i * cell)), h, line);
            for (float y = (h % cell) / 2f; y <= h; y += cell) c.drawLine(0, Math.round(y), w, Math.round(y), line);
            line.setColor(0xFFFFFFFF);
            c.drawCircle(w / 2f, h / 2f, h * 0.45f, line);
            float r = h * 0.12f;
            c.drawCircle(r + h * 0.03f, r + h * 0.03f, r, line);
            c.drawCircle(w - r - h * 0.03f, r + h * 0.03f, r, line);
            c.drawCircle(r + h * 0.03f, h - r - h * 0.03f, r, line);
            c.drawCircle(w - r - h * 0.03f, h - r - h * 0.03f, r, line);
            c.drawLine(w / 2f, 0, w / 2f, h, line);
            c.drawLine(0, h / 2f, w, h / 2f, line);
        }

        /** Borders at 0, 1, 2.5 and 5 percent inside the edge, labelled. */
        private void overscan(Canvas c, int w, int h) {
            c.drawColor(0xFF000000);
            int[] colours = {0xFFFFFFFF, 0xFFFF4040, 0xFFFFC040, 0xFF40C0FF};
            text.setColor(0xFFFFFFFF);
            for (int i = 0; i < CalibrationPatterns.OVERSCAN_PERCENT.length; i++) {
                float pct = CalibrationPatterns.OVERSCAN_PERCENT[i] / 100f;
                int dx = Math.round(w * pct), dy = Math.round(h * pct);
                line.setColor(colours[i]);
                c.drawRect(dx, dy, w - 1 - dx, h - 1 - dy, line);
                text.setColor(colours[i]);
                c.drawText(CalibrationPatterns.OVERSCAN_PERCENT[i] + "%", w / 2f + i * h * 0.08f - h * 0.12f, dy + text.getTextSize() * 1.4f, text);
            }
        }

        /** Labelled patches on a background, for black level (on 0) and white level (on 255). */
        private void patches(Canvas c, int w, int h, int background, int[] levels, int labelColour) {
            c.drawColor(background);
            int n = levels.length;
            float pw = w * 0.8f / n, ph = h * 0.3f, x0 = w * 0.1f, y0 = h * 0.35f;
            text.setColor(labelColour);
            for (int i = 0; i < n; i++) {
                fill.setColor(CalibrationPatterns.gray(levels[i]));
                float x = x0 + i * pw;
                c.drawRect(x + pw * 0.08f, y0, x + pw * 0.92f, y0 + ph, fill);
                c.drawText(String.valueOf(levels[i]), x + pw * 0.3f, y0 + ph + text.getTextSize() * 1.6f, text);
            }
        }

        /** Eleven steps from black to white on top, a smooth ramp below. */
        private void grayscale(Canvas c, int w, int h) {
            c.drawColor(0xFF000000);
            int n = CalibrationPatterns.GRAY_STEPS.length;
            float sw = (float) w / n;
            for (int i = 0; i < n; i++) {
                fill.setColor(CalibrationPatterns.gray(CalibrationPatterns.GRAY_STEPS[i]));
                c.drawRect(Math.round(i * sw), 0, Math.round((i + 1) * sw), h / 2f, fill);
            }
            for (int x = 0; x < w; x++) {
                line.setColor(CalibrationPatterns.gray(Math.round(255f * x / Math.max(1, w - 1))));
                c.drawLine(x, h / 2f, x, h, line);
            }
        }

        /** 75% bars on top, full primaries and secondaries below. */
        private void bars(Canvas c, int w, int h) {
            c.drawColor(0xFF000000);
            int n = CalibrationPatterns.BARS_75.length;
            float bw = (float) w / n;
            for (int i = 0; i < n; i++) {
                fill.setColor(CalibrationPatterns.BARS_75[i]);
                c.drawRect(Math.round(i * bw), 0, Math.round((i + 1) * bw), h * 0.67f, fill);
                fill.setColor(CalibrationPatterns.BARS_100[i]);
                c.drawRect(Math.round(i * bw), h * 0.67f, Math.round((i + 1) * bw), h, fill);
            }
        }

        private void caption(Canvas c, int w, int h) {
            float pad = h * 0.025f, size = text.getTextSize();
            float top = h - pad * 2 - size * 3.2f;
            fill.setColor(0xCC000000);
            c.drawRect(0, top, w, h, fill);
            text.setColor(0xFFFFFFFF);
            text.setFakeBoldText(true);
            c.drawText((pattern + 1) + "/" + CalibrationPatterns.COUNT + "  " + CalibrationPatterns.title(pattern), pad * 2, top + pad + size, text);
            text.setFakeBoldText(false);
            text.setColor(0xFFCCCCCC);
            c.drawText(CalibrationPatterns.hint(pattern), pad * 2, top + pad + size * 2.3f, text);
            c.drawText("Left / Right: next screen    OK: hide or show this text    Back: leave", pad * 2, top + pad + size * 3.2f + pad * 0.2f, text);
        }
    }
}

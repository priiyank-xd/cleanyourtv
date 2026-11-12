package com.gathin.tvhome;

/**
 * The calibration screens and the levels they draw (8-bit, full range 0-255, as Android renders them). Pure data so
 * it is unit tested; CalibrationActivity draws it. Only what can be judged by eye without a meter: focus, geometry,
 * overscan, black and white level, grayscale, gross colour errors and uniformity.
 */
final class CalibrationPatterns {
    static final int FOCUS = 0, GEOMETRY = 1, OVERSCAN = 2, BLACK = 3, WHITE = 4, GRAYSCALE = 5, BARS = 6,
            FIELD_WHITE = 7, FIELD_GREY = 8, FIELD_BLACK = 9, FIELD_RED = 10, FIELD_GREEN = 11, FIELD_BLUE = 12;
    static final int COUNT = 13;

    /** Near-black patches on a 0 background: adjust brightness so 0 and 2 merge and 4 is just visible. */
    static final int[] BLACK_LEVELS = {0, 2, 4, 6, 8, 12, 16, 24, 32};
    /** Near-white patches on a 255 background: adjust contrast so 250 still stands apart from 255. */
    static final int[] WHITE_LEVELS = {224, 232, 240, 245, 248, 250, 252, 254, 255};
    /** Grayscale steps from 0% to 100% in tens. */
    static final int[] GRAY_STEPS = {0, 26, 51, 77, 102, 128, 153, 179, 204, 230, 255};
    /** Borders drawn this far inside the picture, in percent of width and height. */
    static final float[] OVERSCAN_PERCENT = {0f, 1f, 2.5f, 5f};
    /** 75% colour bars: white, yellow, cyan, green, magenta, red, blue. */
    static final int[] BARS_75 = {0xFFBFBFBF, 0xFFBFBF00, 0xFF00BFBF, 0xFF00BF00, 0xFFBF00BF, 0xFFBF0000, 0xFF0000BF};
    /** 100% primaries and secondaries under the bars: red, green, blue, cyan, magenta, yellow, white. */
    static final int[] BARS_100 = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF00FFFF, 0xFFFF00FF, 0xFFFFFF00, 0xFFFFFFFF};

    private CalibrationPatterns() {
    }

    static String title(int p) {
        switch (p) {
            case FOCUS: return "Focus and sharpness";
            case GEOMETRY: return "Geometry and keystone";
            case OVERSCAN: return "Overscan";
            case BLACK: return "Black level";
            case WHITE: return "White level";
            case GRAYSCALE: return "Grayscale and banding";
            case BARS: return "Colour bars";
            case FIELD_WHITE: return "White field";
            case FIELD_GREY: return "50% grey field";
            case FIELD_BLACK: return "Black field";
            case FIELD_RED: return "Red field";
            case FIELD_GREEN: return "Green field";
            default: return "Blue field";
        }
    }

    static String hint(int p) {
        switch (p) {
            case FOCUS: return "The one-pixel stripes should look distinct in the centre and in every corner.";
            case GEOMETRY: return "Squares should be square and circles round. Digital keystone softens the picture; moving the projector does not.";
            case OVERSCAN: return "The outermost white line should be visible on all four sides. If it is cut off, the picture is overscanned.";
            case BLACK: return "Brightness: raise it until 4 is just visible, then lower it until 2 merges with the background.";
            case WHITE: return "Contrast: raise it until the patches start to disappear, then lower it until 250 stands apart again.";
            case GRAYSCALE: return "Steps evenly spaced from black to white; the ramp smooth, without bands or a colour tint.";
            case BARS: return "Bars clean and distinct. By eye this only catches gross colour errors; accuracy needs a meter.";
            case FIELD_BLACK: return "Compare with the room dark: this is the projector's black.";
            default: return "Corners as bright and even as the centre. Also shows dead or stuck pixels.";
        }
    }

    /** The solid colour of a full-field screen, or 0 when the screen is not a full field. */
    static int field(int p) {
        switch (p) {
            case FIELD_WHITE: return 0xFFFFFFFF;
            case FIELD_GREY: return 0xFF808080;
            case FIELD_BLACK: return 0xFF000000;
            case FIELD_RED: return 0xFFFF0000;
            case FIELD_GREEN: return 0xFF00FF00;
            case FIELD_BLUE: return 0xFF0000FF;
            default: return 0;
        }
    }

    static int next(int p, int step) {
        return ((p + step) % COUNT + COUNT) % COUNT;
    }

    static int gray(int v) {
        return 0xFF000000 | (v << 16) | (v << 8) | v;
    }
}

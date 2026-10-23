package dev.tilesmile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Candidate colors for the physical calibration sheets. Spaced evenly in
 * OKLCH (perceptual lightness, chroma, hue) rather than in RGB steps, so each
 * page covers one hue family from dark to light and muted to vivid.
 */
final class CalibrationColors {
    static final int ROWS = 10, COLUMNS = 6;
    static final String[] HUE_NAMES = {"pink", "red", "orange", "gold", "yellow", "yellow-green",
        "green", "teal", "cyan", "blue", "violet", "magenta"};
    /** Pages 0..11 are hues every 30 degrees; page 12 is neutral grays. */
    static final int PAGES = HUE_NAMES.length + 1;

    private CalibrationColors() { }

    static String pageName(int page) {
        return page < HUE_NAMES.length ? HUE_NAMES[page] + " (hue " + page * 30 + "°)" : "grays";
    }

    /** ROWS x COLUMNS colors, light rows first, muted columns first. Duplicates are removed. */
    static int[] page(int page) {
        Set<Integer> colors = new LinkedHashSet<>();
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                if (page >= HUE_NAMES.length) {
                    double l = 1 - (row * COLUMNS + column) / (double) (ROWS * COLUMNS - 1);
                    colors.add(oklch(l, 0, 0));
                    continue;
                }
                // Lightness from 0.95 down to 0.30; darker colors all looked alike on the panel.
                double l = 0.95 - row * (0.65 / (ROWS - 1));
                double hue = Math.toRadians(page * 30);
                double max = maxChroma(l, hue);
                double chroma = max * (column + 1) / COLUMNS;
                colors.add(oklch(l, chroma, hue));
            }
        }
        int[] result = new int[colors.size()];
        int i = 0;
        for (int color : colors) result[i++] = color;
        return result;
    }

    /** Sorts colors by hue family (grays last), then light to dark, so look-alikes sit together. */
    static List<Integer> sortForReview(Iterable<Integer> colors) {
        List<Integer> sorted = new ArrayList<>();
        for (int color : colors) sorted.add(color | 0xff000000);
        Collections.sort(sorted, (a, b) -> {
            int fa = family(a), fb = family(b);
            if (fa != fb) return Integer.compare(fa, fb);
            return Double.compare(oklab(b)[0], oklab(a)[0]);
        });
        return sorted;
    }

    /** Gray families after the 12 hues: warm, neutral, then cool. */
    static final int WARM_GRAY = 12, NEUTRAL_GRAY = 13, COOL_GRAY = 14, FAMILIES = 15;

    /**
     * Hue family 0-11 (30° OKLab sectors centered on multiples of 30°), or for
     * near-grays (chroma under 0.02): neutral under 0.005, else cool if bluish, else warm.
     */
    static int family(int color) {
        double[] lab = oklab(color);
        double chroma = Math.hypot(lab[1], lab[2]);
        if (chroma < 0.005) return NEUTRAL_GRAY;
        if (chroma < 0.02) return lab[2] < 0 ? COOL_GRAY : WARM_GRAY;
        return (int) Math.floor(((Math.toDegrees(Math.atan2(lab[2], lab[1])) + 360 + 15) % 360) / 30);
    }

    static double maxChroma(double l, double hue) {
        double lo = 0, hi = 0.4;
        for (int i = 0; i < 24; i++) {
            double mid = (lo + hi) / 2;
            if (inGamut(l, mid, hue)) lo = mid; else hi = mid;
        }
        return lo;
    }

    static boolean inGamut(double l, double c, double h) {
        double[] rgb = linearRgb(l, c * Math.cos(h), c * Math.sin(h));
        for (double v : rgb) if (v < -1e-4 || v > 1 + 1e-4) return false;
        return true;
    }

    static int oklch(double l, double c, double h) {
        double[] rgb = linearRgb(l, c * Math.cos(h), c * Math.sin(h));
        int out = 0xff000000;
        for (int i = 0; i < 3; i++) out |= encode(rgb[i]) << (16 - 8 * i);
        return out;
    }

    private static double[] linearRgb(double L, double a, double b) {
        double l = cube(L + 0.3963377774 * a + 0.2158037573 * b);
        double m = cube(L - 0.1055613458 * a - 0.0638541728 * b);
        double s = cube(L - 0.0894841775 * a - 1.2914855480 * b);
        return new double[]{
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s};
    }

    static double[] oklab(int color) {
        double r = decode((color >> 16) & 0xff), g = decode((color >> 8) & 0xff), b = decode(color & 0xff);
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return new double[]{
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s};
    }

    private static double cube(double v) { return v * v * v; }
    private static double decode(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
    private static int encode(double linear) {
        double v = Math.max(0, Math.min(1, linear));
        v = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return (int) Math.round(v * 255);
    }
}

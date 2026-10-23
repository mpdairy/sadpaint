package dev.tilesmile;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Picks the native fast-pen RGB request used while the pen is down. By default
 * it is the gray whose live stroke looks closest in CIELAB lightness to the
 * finished color (measured by the gray preview test, else the 0.7.1 survey's
 * visible grays at face value): the native preview's hues are unreliable, but its grays read
 * predictably, so a pale pink previews as light gray. A per-color override wins.
 */
final class PreviewColors {
    /** Visible requests from PREVIEW_VISIBILITY_RESULTS.md, used if the saved survey is missing. */
    static final int[] SURVEYED_VISIBLE = {
        0x000000, 0x000080, 0x0000FF, 0x008000, 0x008080, 0x0080FF, 0x00FF00, 0x00FF80,
        0x00FFFF, 0x202020, 0x2020FF, 0x2050FF, 0x2080FF, 0x20B0FF, 0x20E0FF, 0x315F88,
        0x404040, 0x5020FF, 0x5050FF, 0x5080FF, 0x50B0FF, 0x50E0FF, 0x566B78, 0x606060,
        0x6F9EC5, 0x800000, 0x800080, 0x8000FF, 0x8020FF, 0x8050FF, 0x808000, 0x808080,
        0x80FF00, 0xA0A0A0, 0xB020FF, 0xB050FF, 0xC0C0C0, 0xE020FF, 0xE050FF, 0xE0E0E0,
        0xF8F8F8, 0xFF0000, 0xFF0080, 0xFF00FF, 0xFF8000, 0xFFFF00,
    };

    /** Gray fast-pen requests measured by {@link GrayPreviewActivity}, dark to light. */
    static final int[] GRAY_REQUESTS = {
        0x000000, 0x202020, 0x404040, 0x606060, 0x808080, 0xA0A0A0, 0xC0C0C0, 0xD0D0D0,
        0xE0E0E0, 0xE8E8E8, 0xF0F0F0, 0xF4F4F4, 0xF8F8F8, 0xFAFAFA, 0xFCFCFC, 0xFEFEFE,
    };
    /**
     * Bright colored requests that might draw lighter than the grays' ~B3 floor.
     * Only their lightness is used: their live hue is unreliable.
     */
    static final int[] LIGHT_REQUESTS = {
        0xFFFF00, 0x00FFFF, 0x00FF00, 0x80FF00, 0x00FF80, 0x20E0FF, 0x50E0FF, 0x50B0FF,
        0xE050FF, 0xFF00FF, 0xFF8000, 0xFFFF40, 0x40FFFF, 0xFFE040, 0xC0FF40, 0x40FFC0,
    };
    /** Finished grays the user compares the live stroke against, light to dark. */
    static final int[] REFERENCE_GRAYS = {
        0xffffffff, 0xffe6e6e6, 0xffcccccc, 0xffb3b3b3, 0xff999999, 0xff808080,
        0xff666666, 0xff4d4d4d, 0xff333333, 0xff1a1a1a, 0xff000000,
    };
    /** Request hex → matched finished gray hex, or "invisible". */
    static final String APPARENT_PREFS = "preview-gray-v1";

    private PreviewColors() { }

    /**
     * The gray request whose live stroke looks closest in lightness to {@code color}.
     * Uses the gray preview test's answers where measured; otherwise the survey's
     * visible grays at their nominal lightness.
     */
    static int gray(Context context, int color) {
        // White paint previews as white: invisible on blank paper, but it should
        // cover ink while drawing, which no gray (all look B3 or darker) can do.
        if (isWhite(color)) return 0xffffffff;
        SharedPreferences measured = context.getSharedPreferences(APPARENT_PREFS, 0);
        java.util.Map<Integer, Integer> apparent = new java.util.HashMap<>();
        java.util.Set<Integer> visible = new java.util.HashSet<>();
        for (int v : visible(context)) visible.add(v | 0xff000000);
        for (int request : GRAY_REQUESTS) {
            int key = request | 0xff000000;
            String answer = measured.getString(Palette.hex(request), null);
            if (answer == null) { if (visible.contains(key)) apparent.put(key, key); }
            else if (!"invisible".equals(answer)) {
                try { apparent.put(key, Integer.parseInt(answer, 16) | 0xff000000); } catch (NumberFormatException ignored) { }
            }
        }
        // Colored requests only where grays can't go: lighter than the lightest gray looks.
        double lightestGray = 0;
        for (int look : apparent.values()) lightestGray = Math.max(lightestGray, lab(look)[0]);
        for (int request : LIGHT_REQUESTS) {
            String answer = measured.getString(Palette.hex(request), null);
            if (answer == null || "invisible".equals(answer)) continue;
            try {
                int look = Integer.parseInt(answer, 16) | 0xff000000;
                if (lab(look)[0] > lightestGray + 1) apparent.put(request | 0xff000000, look);
            } catch (NumberFormatException ignored) { }
        }
        return nearestApparentGray(color, apparent);
    }

    /** Paper white or indistinguishable from it (CIELAB L above 99). */
    static boolean isWhite(int color) { return lab(color)[0] > 99; }

    /** The request (key) whose apparent color (value) is closest in lightness to {@code color}; black if none. */
    static int nearestApparentGray(int color, java.util.Map<Integer, Integer> apparent) {
        double target = lab(color)[0];
        int best = 0xff000000;
        double bestDistance = Double.MAX_VALUE;
        for (java.util.Map.Entry<Integer, Integer> entry : apparent.entrySet()) {
            double d = Math.abs(lab(entry.getValue())[0] - target);
            // Ties go to the lighter request, which reads closer to paper white.
            if (d < bestDistance || (d == bestDistance && lab(entry.getKey())[0] > lab(best)[0])) {
                bestDistance = d;
                best = entry.getKey() | 0xff000000;
            }
        }
        return best;
    }

    static int[] visible(Context context) {
        SharedPreferences survey = context.getSharedPreferences("preview-visibility-v1", 0);
        List<Integer> found = new ArrayList<>();
        for (PreviewPalette.Candidate candidate : PreviewPalette.create())
            if ("visible".equals(survey.getString(candidate.hex(), ""))) found.add(candidate.color);
        if (found.isEmpty()) {
            int[] fallback = SURVEYED_VISIBLE.clone();
            for (int i = 0; i < fallback.length; i++) fallback[i] |= 0xff000000;
            return fallback;
        }
        int[] result = new int[found.size()];
        for (int i = 0; i < result.length; i++) result[i] = found.get(i);
        return result;
    }

    static int choose(Context context, Palette.Entry entry) {
        if (entry.preview != 0) return entry.preview;
        return gray(context, entry.color);
    }

    /** The neutral candidate whose lightness is closest to {@code color}'s; black if none. */
    static int nearestGray(int color, int[] candidates) {
        double target = lab(color)[0];
        int best = 0xff000000;
        double bestDistance = Double.MAX_VALUE;
        for (int candidate : candidates) {
            int r = (candidate >> 16) & 0xff, g = (candidate >> 8) & 0xff, b = candidate & 0xff;
            if (r != g || g != b) continue;
            double d = Math.abs(lab(candidate)[0] - target);
            if (d < bestDistance) { bestDistance = d; best = candidate | 0xff000000; }
        }
        return best;
    }

    static int nearest(int color, int[] candidates) {
        double[] target = lab(color);
        int best = 0xff000000;
        double bestDistance = Double.MAX_VALUE;
        for (int candidate : candidates) {
            double[] c = lab(candidate);
            double d = sq(c[0] - target[0]) + sq(c[1] - target[1]) + sq(c[2] - target[2]);
            if (d < bestDistance) { bestDistance = d; best = candidate | 0xff000000; }
        }
        return best;
    }

    static String hex(int color) { return String.format(Locale.US, "#%06X", color & 0xffffff); }

    private static double sq(double v) { return v * v; }

    /** sRGB (D65) to CIELAB. */
    static double[] lab(int color) {
        double r = linear((color >> 16) & 0xff), g = linear((color >> 8) & 0xff), b = linear(color & 0xff);
        double x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047;
        double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        double z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
        double fx = f(x), fy = f(y), fz = f(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }
    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
    private static double f(double t) {
        return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
    }
}

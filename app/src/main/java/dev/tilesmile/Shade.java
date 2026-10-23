package dev.tilesmile;

/**
 * A palette color mixed with black or white by a 1 px ordered (Bayer) dither,
 * the mix that keeps its tint on this panel (smooth blends looked worse).
 * Shade -{@link #STEPS} is all black, 0 the pure color, +{@link #STEPS} all
 * white; one step per Bayer level. The pattern tiles by canvas position, so
 * overlapping strokes of one shade join seamlessly.
 */
final class Shade {
    static final int STEPS = 64, SIZE = 8;
    private static final int[][] BAYER = {
        {0, 32, 8, 40, 2, 34, 10, 42}, {48, 16, 56, 24, 50, 18, 58, 26},
        {12, 44, 4, 36, 14, 46, 6, 38}, {60, 28, 52, 20, 62, 30, 54, 22},
        {3, 35, 11, 43, 1, 33, 9, 41}, {51, 19, 59, 27, 49, 17, 57, 25},
        {15, 47, 7, 39, 13, 45, 5, 37}, {63, 31, 55, 23, 61, 29, 53, 21},
    };

    /** Mix smoothly instead of dithering: every pixel is the true blended color. */
    static boolean solid;

    private Shade() { }

    static int clamp(int shade) { return Math.max(-STEPS, Math.min(STEPS, shade)); }

    /** {@link #SIZE}×{@link #SIZE} opaque pixels, row-major; pixel (x, y) is {@code [(y % 8) * 8 + x % 8]}. */
    static int[] pattern(int color, int shade) {
        int[] out = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++)
            for (int x = 0; x < SIZE; x++) out[y * SIZE + x] = pixel(color, shade, x, y);
        return out;
    }

    /** The shade's pixel at canvas position (x, y). */
    static int pixel(int color, int shade, int x, int y) {
        shade = clamp(shade);
        float f = Math.abs(shade) / (float) STEPS;
        if (solid) return mix(color, shade < 0 ? 0xff000000 : 0xffffffff, f);
        return (BAYER[y % SIZE][x % SIZE] + 0.5f) / 64 < f ? (shade < 0 ? 0xff000000 : 0xffffffff) : color | 0xff000000;
    }

    private static int mix(int from, int to, float f) {
        int r = Math.round(((from >> 16) & 0xff) * (1 - f) + ((to >> 16) & 0xff) * f);
        int g = Math.round(((from >> 8) & 0xff) * (1 - f) + ((to >> 8) & 0xff) * f);
        int b = Math.round((from & 0xff) * (1 - f) + (to & 0xff) * f);
        return 0xff000000 | r << 16 | g << 8 | b;
    }

    /** The pattern's average color, e.g. to pick a fast-preview gray of the same lightness. */
    static int average(int[] pattern) {
        long r = 0, g = 0, b = 0;
        for (int c : pattern) { r += (c >> 16) & 0xff; g += (c >> 8) & 0xff; b += c & 0xff; }
        int n = pattern.length;
        return 0xff000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }
}

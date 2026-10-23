package dev.tilesmile;

import com.onyx.android.sdk.data.note.TouchPoint;

/**
 * Carried-paint smudge: the brush picks up a patch of color on pen-down and,
 * at each dab, blends it into the canvas and picks up the result (plain sRGB
 * mixing). It pushes and pulls paint more than it mixes. Pressure sets how
 * far colors are dragged.
 */
final class Smudge extends LiveStroke {
    private final OpaqueRaster raster;
    private final float maxPressure, radius, spacing;
    private final int size;
    /** Carried color per brush-relative pixel, as floats so slow fades don't stall on rounding. */
    private final float[] red, green, blue, falloff;
    private boolean down;
    private float lastX, lastY, lastPressure, leftover;

    Smudge(OpaqueRaster raster, float width, float maxPressure) {
        this.raster = raster; this.maxPressure = maxPressure;
        radius = Math.max(2, width / 2);
        spacing = Math.max(1, radius * 0.1f);
        size = 2 * (int) Math.ceil(radius) + 1;
        red = new float[size * size]; green = new float[size * size]; blue = new float[size * size];
        falloff = new float[size * size];
        int half = size / 2;
        for (int j = 0; j < size; j++)
            for (int i = 0; i < size; i++) {
                float d = (float) Math.hypot(i - half, j - half) / radius;
                // Soft edge so the dab's circle doesn't print into the paint.
                falloff[j * size + i] = d >= 1 ? 0 : 1 - d * d;
            }
    }

    @Override void add(int action, TouchPoint sample) {
        float x = sample.x, y = sample.y;
        if (!Float.isFinite(x) || !Float.isFinite(y)) return;
        float pressure = Math.max(0, Math.min(1, sample.pressure / maxPressure));
        if (!down) {
            pickUp(x, y);
            lastX = x; lastY = y; lastPressure = pressure; leftover = 0; down = true;
            points++;
            return;
        }
        float dx = x - lastX, dy = y - lastY, distance = (float) Math.hypot(dx, dy);
        float at = spacing - leftover;
        while (at <= distance) {
            float t = at / distance;
            dab(lastX + dx * t, lastY + dy * t, lastPressure + (pressure - lastPressure) * t);
            at += spacing;
        }
        leftover = distance - (at - spacing);
        lastX = x; lastY = y; lastPressure = pressure;
        if (action == 2) down = false;
    }

    private void pickUp(float x, float y) {
        int cx = Math.round(x), cy = Math.round(y), half = size / 2;
        int[] pixels = raster.pixels;
        for (int j = 0; j < size; j++) {
            int py = Math.max(0, Math.min(raster.height - 1, cy - half + j));
            for (int i = 0; i < size; i++) {
                int px = Math.max(0, Math.min(raster.width - 1, cx - half + i));
                int c = pixels[py * raster.width + px], k = j * size + i;
                red[k] = (c >> 16) & 0xff; green[k] = (c >> 8) & 0xff; blue[k] = c & 0xff;
            }
        }
    }

    private void dab(float x, float y, float pressure) {
        // How much of the carried paint survives each dab: harder presses drag it farther.
        float reach = radius * (0.3f + 2.7f * pressure);
        float keep = (float) Math.exp(-spacing / reach);
        int cx = Math.round(x), cy = Math.round(y), half = size / 2;
        int[] pixels = raster.pixels;
        int x0 = Math.max(0, cx - half), x1 = Math.min(raster.width - 1, cx + half);
        int y0 = Math.max(0, cy - half), y1 = Math.min(raster.height - 1, cy + half);
        if (x0 > x1 || y0 > y1) return;
        for (int py = y0; py <= y1; py++) {
            int row = py * raster.width, k = (py - cy + half) * size - cx + half;
            for (int px = x0; px <= x1; px++) {
                float w = falloff[k + px];
                if (w <= 0) continue;
                w *= keep;
                int c = pixels[row + px], kk = k + px;
                float r = ((c >> 16) & 0xff) + (red[kk] - ((c >> 16) & 0xff)) * w;
                float g = ((c >> 8) & 0xff) + (green[kk] - ((c >> 8) & 0xff)) * w;
                float b = (c & 0xff) + (blue[kk] - (c & 0xff)) * w;
                red[kk] = r; green[kk] = g; blue[kk] = b;
                pixels[row + px] = 0xff000000 | Math.round(r) << 16 | Math.round(g) << 8 | Math.round(b);
            }
        }
        raster.markDirty(x0, y0, x1 + 1, y1 + 1);
        include(x, y, half + 1);
        points++;
    }
}

package dev.tilesmile;

import com.onyx.android.sdk.data.note.TouchPoint;
import java.util.BitSet;

/**
 * Watercolor glaze: round pressure dabs that tint what is already on the
 * canvas (multiply), so lines and earlier paint show through. On white it is
 * exactly the paint color; white paint changes nothing. Each pixel is tinted
 * once per stroke, however many dabs overlap it; a second stroke deepens it.
 */
final class Glaze extends LiveStroke {
    private final OpaqueRaster raster;
    private final int[] pattern;
    private final float width, maxPressure;
    private final BitSet touched = new BitSet();
    private boolean started;
    private float lastX, lastY, lastPressure, toNextDab;

    /** {@code pattern} is the paint's {@link Shade#pattern}; each pixel is tinted by its own dither pixel. */
    Glaze(OpaqueRaster raster, int[] pattern, float width, float maxPressure) {
        this.raster = raster; this.pattern = pattern; this.width = Math.max(2, width); this.maxPressure = maxPressure;
    }

    /** Pressure as 0–1; full pressure is reached a little before the pen's maximum. */
    private float press(float raw) { return Math.max(0, Math.min(1, raw / maxPressure * 1.25f)); }

    /** 35% of the width at a light touch, the full width at full pressure. */
    private float diameter(float p) { return Math.max(2, width * (.35f + .65f * p)); }

    @Override void add(int action, TouchPoint sample) {
        float x = sample.x, y = sample.y;
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(sample.pressure)) return;
        float p = press(sample.pressure);
        if (!started) {
            started = true;
            dab(x, y, p);
            toNextDab = spacing(diameter(p));
        } else {
            float distance = (float) Math.hypot(x - lastX, y - lastY), travel = toNextDab;
            while (travel <= distance && distance > 0) {
                float t = travel / distance, at = lastPressure + (p - lastPressure) * t;
                dab(lastX + (x - lastX) * t, lastY + (y - lastY) * t, at);
                travel += spacing(diameter(at));
            }
            toNextDab = travel - distance;
        }
        lastX = x; lastY = y; lastPressure = p;
    }

    private static float spacing(float diameter) { return Math.max(.5f, diameter * .15f); }

    private void dab(float cx, float cy, float p) {
        float radius = diameter(p) / 2;
        int l = Math.max(0, (int) Math.floor(cx - radius)), t = Math.max(0, (int) Math.floor(cy - radius));
        int r = Math.min(raster.width, (int) Math.ceil(cx + radius)), b = Math.min(raster.height, (int) Math.ceil(cy + radius));
        if (r <= l || b <= t) return;
        int[] pixels = raster.pixels;
        for (int y = t; y < b; y++) for (int x = l; x < r; x++) {
            float dx = x + .5f - cx, dy = y + .5f - cy;
            int at = y * raster.width + x;
            if (dx * dx + dy * dy > radius * radius || touched.get(at)) continue;
            touched.set(at);
            int c = pixels[at], paint = pattern[(y % Shade.SIZE) * Shade.SIZE + x % Shade.SIZE];
            int red = (paint >> 16) & 0xff, green = (paint >> 8) & 0xff, blue = paint & 0xff;
            pixels[at] = 0xff000000 | (((c >> 16) & 0xff) * red / 255) << 16
                | (((c >> 8) & 0xff) * green / 255) << 8 | (c & 0xff) * blue / 255;
        }
        raster.markDirty(l, t, r, b);
        include(cx, cy, radius + 1);
        points++;
    }
}

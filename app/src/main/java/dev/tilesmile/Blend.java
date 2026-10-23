package dev.tilesmile;

import com.onyx.android.sdk.data.note.TouchPoint;

/**
 * Blending stump, ported from the Supernote app's Soften tool to RGB: each dab
 * pulls pixels toward a small box-blurred average taken mostly from behind the
 * moving tip, so colors mix into each other and are carried along the rub.
 * Pressing harder blends more and widens the tip a little; strength sets how
 * much each pass blends. Past 100, each dab acts like several full-strength passes.
 */
final class Blend extends LiveStroke {
    private final OpaqueRaster raster;
    private final float maxPressure, width;
    private final int strength;
    private boolean started;
    private float lastX, lastY, lastPressure, toNextDab;
    private int[] sums = new int[0];
    static final int MAX_STRENGTH = 200;

    Blend(OpaqueRaster raster, float width, int strength, float maxPressure) {
        this.raster = raster; this.width = Math.max(2, width);
        this.strength = Math.max(0, Math.min(MAX_STRENGTH, strength)); this.maxPressure = maxPressure;
    }

    /** Pressure as 0–1; full pressure is reached a little before the pen's maximum. */
    private float press(float raw) { return Math.max(0, Math.min(1, raw / maxPressure * 1.25f)); }

    /** 60% of the width at a light touch, the full width at full pressure. */
    private float diameter(float p) { return Math.max(2, width * (.6f + .4f * p)); }

    @Override void add(int action, TouchPoint sample) {
        float x = sample.x, y = sample.y;
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(sample.pressure)) return;
        float p = press(sample.pressure);
        if (!started) {
            started = true;
            dab(x, y, p, 0, 0);
            toNextDab = spacing(diameter(p));
        } else {
            float distance = (float) Math.hypot(x - lastX, y - lastY), travel = toNextDab;
            while (travel <= distance && distance > 0) {
                float t = travel / distance, at = lastPressure + (p - lastPressure) * t;
                dab(lastX + (x - lastX) * t, lastY + (y - lastY) * t, at, (x - lastX) / distance, (y - lastY) / distance);
                travel += spacing(diameter(at));
            }
            toNextDab = travel - distance;
        }
        lastX = x; lastY = y; lastPressure = p;
    }

    private static float spacing(float diameter) { return Math.max(.5f, diameter * .12f); }

    private void dab(float cx, float cy, float p, float dx, float dy) {
        // A light touch blends a quarter as much as the setting; full pressure, one and a half times.
        float strength = this.strength * (.25f + 1.25f * p);
        if (strength <= 0) return;
        float radius = diameter(p) / 2;
        int w = raster.width, h = raster.height;
        int l = Math.max(0, (int) Math.floor(cx - radius)), t = Math.max(0, (int) Math.floor(cy - radius));
        int r = Math.min(w, (int) Math.ceil(cx + radius)), b = Math.min(h, (int) Math.ceil(cy + radius));
        if (r <= l || b <= t) return;
        int reach = Math.max(2, Math.min(8, Math.round(radius * .2f)));
        int dragX = Math.round(dx * radius * .25f), dragY = Math.round(dy * radius * .25f);
        int sl = Math.max(0, l - reach - Math.abs(dragX)), st = Math.max(0, t - reach - Math.abs(dragY));
        int sr = Math.min(w, r + reach + Math.abs(dragX)), sb = Math.min(h, b + reach + Math.abs(dragY));
        int stride = sr - sl + 1, count = stride * (sb - st + 1);
        // Summed-area tables for red, green and blue, interleaved.
        if (sums.length < count * 3) sums = new int[count * 3];
        else java.util.Arrays.fill(sums, 0, count * 3, 0);
        int[] pixels = raster.pixels;
        // Build the summed neighborhood before writing any destination pixel.
        for (int y = st; y < sb; y++) {
            int rowR = 0, rowG = 0, rowB = 0;
            for (int x = sl; x < sr; x++) {
                int c = pixels[y * w + x];
                rowR += (c >> 16) & 0xff; rowG += (c >> 8) & 0xff; rowB += c & 0xff;
                int at = ((y - st + 1) * stride + x - sl + 1) * 3, above = at - stride * 3;
                sums[at] = sums[above] + rowR; sums[at + 1] = sums[above + 1] + rowG; sums[at + 2] = sums[above + 2] + rowB;
            }
        }
        float amount = .7f * Math.min(100, strength) / 100f, passes = Math.max(1, strength / 100f);
        float[] local = new float[3], carried = new float[3];
        for (int y = t; y < b; y++) for (int x = l; x < r; x++) {
            float q = ((x + .5f - cx) * (x + .5f - cx) + (y + .5f - cy) * (y + .5f - cy)) / (radius * radius);
            if (q >= 1) continue;
            mean(x, y, reach, sl, st, sr, sb, stride, local);
            if (dragX != 0 || dragY != 0) {
                // Blend mostly from behind the moving tip, carrying colors forward.
                int sx = Math.max(0, Math.min(w - 1, x - dragX)), sy = Math.max(0, Math.min(h - 1, y - dragY));
                mean(sx, sy, reach, sl, st, sr, sb, stride, carried);
                for (int i = 0; i < 3; i++) local[i] = .2f * local[i] + .8f * carried[i];
            }
            int c = pixels[y * w + x];
            float k = amount * (1 - q) * (1 - q);
            if (passes > 1) k = 1 - (float) Math.pow(1 - k, passes);
            int red = (c >> 16) & 0xff, green = (c >> 8) & 0xff, blue = c & 0xff;
            red = Math.round(red + (local[0] - red) * k);
            green = Math.round(green + (local[1] - green) * k);
            blue = Math.round(blue + (local[2] - blue) * k);
            pixels[y * w + x] = 0xff000000 | red << 16 | green << 8 | blue;
        }
        raster.markDirty(l, t, r, b);
        include(cx, cy, radius + 1);
        points++;
    }

    private void mean(int x, int y, int reach, int sl, int st, int sr, int sb, int stride, float[] out) {
        int x0 = Math.max(sl, x - reach) - sl, y0 = Math.max(st, y - reach) - st;
        int x1 = Math.min(sr, x + reach + 1) - sl, y1 = Math.min(sb, y + reach + 1) - st;
        float area = (x1 - x0) * (y1 - y0);
        for (int i = 0; i < 3; i++)
            out[i] = (sums[(y1 * stride + x1) * 3 + i] - sums[(y0 * stride + x1) * 3 + i]
                - sums[(y1 * stride + x0) * 3 + i] + sums[(y0 * stride + x0) * 3 + i]) / area;
    }
}

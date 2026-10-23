package dev.tilesmile;

import java.util.Arrays;

/** Hard round brush: every covered pixel is replaced, never alpha blended. */
public final class OpaqueRaster {
    public final int width, height;
    public final int[] pixels;
    public int left, top, right, bottom;
    private float previousX, previousY, previousRadius;
    private boolean down;

    /** When set, fills use this {@link Shade#pattern} by canvas position instead of the color. */
    public int[] pattern;

    /** Multiplies {@link #diameter} for {@link #point}; 1 = up to 32 px. */
    public float sizeScale = 1;

    public OpaqueRaster(int width, int height) {
        this.width = width;
        this.height = height;
        pixels = new int[Math.multiplyExact(width, height)];
        clear();
    }

    public void clear() {
        Arrays.fill(pixels, 0xffffffff);
        down = false;
        left = 0; top = 0; right = width; bottom = height;
    }

    public void resetDirty() { left = width; top = height; right = bottom = 0; }
    public boolean isDirty() { return left < right && top < bottom; }
    public void end() { down = false; }

    public void markDirty(int l, int t, int r, int b) {
        left = Math.min(left, l); top = Math.min(top, t);
        right = Math.max(right, r); bottom = Math.max(bottom, b);
    }

    public static float diameter(float pressure) {
        if (!Float.isFinite(pressure)) pressure = 0;
        float p = Math.max(0, Math.min(1, pressure));
        return 1.5f + 30.5f * (float) Math.pow(p, 1.4);
    }

    public void point(float x, float y, float pressure, int color) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) return;
        // Reject corrupt coordinates before interpolation can produce excessive work.
        if (x < -64 || y < -64 || x > width + 64 || y > height + 64) return;
        float radius = diameter(pressure) / 2 * sizeScale;
        color |= 0xff000000;
        if (!down) {
            stamp(x, y, radius, color);
        } else {
            float distance = (float) Math.hypot(x - previousX, y - previousY);
            float spacing = Math.max(0.5f, Math.min(radius, previousRadius) * 0.4f);
            int steps = Math.max(1, (int) Math.ceil(distance / spacing));
            for (int i = 1; i <= steps; i++) {
                float t = (float) i / steps;
                stamp(previousX + (x - previousX) * t,
                    previousY + (y - previousY) * t,
                    previousRadius + (radius - previousRadius) * t, color);
            }
        }
        previousX = x; previousY = y; previousRadius = radius; down = true;
    }

    /**
     * Fills every pixel whose center lies within diameter/2 of the segment:
     * a round-capped line of constant width, matching the SDK's drawLine
     * rendering of fountain points, but opaque and without antialiasing.
     */
    public void segment(float x0, float y0, float x1, float y1, float diameter, int color) {
        if (!Float.isFinite(x0) || !Float.isFinite(y0) || !Float.isFinite(x1)
            || !Float.isFinite(y1) || !Float.isFinite(diameter)) return;
        float r = Math.max(MIN_SEGMENT_RADIUS, diameter / 2);
        color |= 0xff000000;
        float dx = x1 - x0, dy = y1 - y0, length = (float) Math.hypot(dx, dy);
        if (length < 1e-4f) { stamp(x0, y0, r, color); return; }
        float ux = dx / length, uy = dy / length;
        int rowStart = Math.max(0, (int) Math.ceil(Math.min(y0, y1) - r - 0.5f));
        int rowEnd = Math.min(height - 1, (int) Math.floor(Math.max(y0, y1) + r - 0.5f));
        for (int row = rowStart; row <= rowEnd; row++) {
            float yc = row + 0.5f;
            // The capsule is convex, so its intersection with this row is the
            // hull of the two end-circle chords and the central band's chord.
            float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
            for (int end = 0; end < 2; end++) {
                float cx = end == 0 ? x0 : x1, cy = end == 0 ? y0 : y1;
                float squared = r * r - (yc - cy) * (yc - cy);
                if (squared < 0) continue;
                float half = (float) Math.sqrt(squared);
                lo = Math.min(lo, cx - half); hi = Math.max(hi, cx + half);
            }
            // Band: |cross(u, p - a)| <= r and 0 <= dot(u, p - a) <= length.
            float bandLo = Float.NEGATIVE_INFINITY, bandHi = Float.POSITIVE_INFINITY;
            float ry = yc - y0;
            // cross = ux * ry - uy * (x - x0); dot = ux * (x - x0) + uy * ry.
            if (Math.abs(uy) < 1e-6f) {
                if (Math.abs(ux * ry) > r) bandHi = Float.NEGATIVE_INFINITY;
            } else {
                float a = x0 + (ux * ry - r) / uy, b = x0 + (ux * ry + r) / uy;
                bandLo = Math.max(bandLo, Math.min(a, b)); bandHi = Math.min(bandHi, Math.max(a, b));
            }
            if (Math.abs(ux) < 1e-6f) {
                float along = uy * ry;
                if (along < 0 || along > length) bandHi = Float.NEGATIVE_INFINITY;
            } else {
                float a = x0 - uy * ry / ux, b = x0 + (length - uy * ry) / ux;
                bandLo = Math.max(bandLo, Math.min(a, b)); bandHi = Math.min(bandHi, Math.max(a, b));
            }
            if (bandLo <= bandHi) { lo = Math.min(lo, bandLo); hi = Math.max(hi, bandHi); }
            if (lo > hi) continue;
            int left = Math.max(0, (int) Math.ceil(lo - 0.5f));
            int right = Math.min(width - 1, (int) Math.floor(hi - 0.5f));
            if (left > right) continue;
            fill(row, left, right, color);
            this.left = Math.min(this.left, left); top = Math.min(top, row);
            this.right = Math.max(this.right, right + 1); bottom = Math.max(bottom, row + 1);
        }
    }

    /** Thinner lines can fall between pixel centers and break up diagonally. */
    static final float MIN_SEGMENT_RADIUS = 0.75f;

    /** Exchanges a rectangle's pixels with {@code saved}; calling twice restores both. */
    public void swapRegion(int x, int y, int w, int h, int[] saved) {
        int[] row = new int[w];
        for (int i = 0; i < h; i++) {
            int offset = (y + i) * width + x;
            System.arraycopy(pixels, offset, row, 0, w);
            System.arraycopy(saved, i * w, pixels, offset, w);
            System.arraycopy(row, 0, saved, i * w, w);
        }
        left = Math.min(left, x); top = Math.min(top, y);
        right = Math.max(right, x + w); bottom = Math.max(bottom, y + h);
    }

    public int[] copyRegion(int x, int y, int w, int h) {
        int[] copy = new int[Math.multiplyExact(w, h)];
        for (int i = 0; i < h; i++) System.arraycopy(pixels, (y + i) * width + x, copy, i * w, w);
        return copy;
    }

    /** Pixels {@code from}..{@code to} (inclusive) of {@code row}. */
    private void fill(int row, int from, int to, int color) {
        int start = row * width;
        if (pattern == null) { Arrays.fill(pixels, start + from, start + to + 1, color); return; }
        int base = (row % Shade.SIZE) * Shade.SIZE;
        for (int x = from; x <= to; x++) pixels[start + x] = pattern[base + x % Shade.SIZE];
    }

    private void stamp(float x, float y, float radius, int color) {
        int y0 = Math.max(0, (int) Math.ceil(y - radius - 0.5f));
        int y1 = Math.min(height - 1, (int) Math.floor(y + radius - 0.5f));
        for (int row = y0; row <= y1; row++) {
            float dy = row + 0.5f - y;
            float squared = radius * radius - dy * dy;
            if (squared < 0) continue;
            float dx = (float) Math.sqrt(squared);
            int x0 = Math.max(0, (int) Math.ceil(x - dx - 0.5f));
            int x1 = Math.min(width - 1, (int) Math.floor(x + dx - 0.5f));
            if (x0 > x1) continue;
            fill(row, x0, x1, color);
            left = Math.min(left, x0); top = Math.min(top, row);
            right = Math.max(right, x1 + 1); bottom = Math.max(bottom, row + 1);
        }
    }
}

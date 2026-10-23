package dev.tilesmile;

/**
 * A finished stroke as the SDK fountain engine shapes it: points, each with
 * a diameter. Rendered like PenUtils.drawStrokeByPointSize (round-capped
 * segments, each as wide as its end point) but opaque and unantialiased.
 */
final class InkStroke {
    final float[] xs, ys, sizes;
    final int count;

    InkStroke(float[] xs, float[] ys, float[] sizes, int count) {
        this.xs = xs; this.ys = ys; this.sizes = sizes; this.count = count;
    }

    /** Pixel bounds {left, top, right, bottom} covering the stroke, clipped; null when empty. */
    int[] bounds(int width, int height) {
        if (count == 0) return null;
        float left = Float.POSITIVE_INFINITY, top = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            if (!Float.isFinite(xs[i]) || !Float.isFinite(ys[i]) || !Float.isFinite(sizes[i])) continue;
            float r = Math.max(OpaqueRaster.MIN_SEGMENT_RADIUS, sizes[i] / 2) + 1;
            left = Math.min(left, xs[i] - r); top = Math.min(top, ys[i] - r);
            right = Math.max(right, xs[i] + r); bottom = Math.max(bottom, ys[i] + r);
        }
        int l = Math.max(0, (int) Math.floor(left)), t = Math.max(0, (int) Math.floor(top));
        int r = Math.min(width, (int) Math.ceil(right)), b = Math.min(height, (int) Math.ceil(bottom));
        return l < r && t < b ? new int[]{l, t, r, b} : null;
    }

    void draw(OpaqueRaster raster, int color) {
        if (count == 1) { raster.segment(xs[0], ys[0], xs[0], ys[0], sizes[0], color); return; }
        for (int i = 0; i + 1 < count; i++)
            raster.segment(xs[i], ys[i], xs[i + 1], ys[i + 1], sizes[i + 1], color);
    }
}

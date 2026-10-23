package dev.tilesmile;

import com.onyx.android.sdk.data.note.TouchPoint;

/** A tool that paints into the raster sample by sample while the pen is down. */
abstract class LiveStroke {
    int points;
    float left = Float.POSITIVE_INFINITY, top = Float.POSITIVE_INFINITY;
    float right = Float.NEGATIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;

    /** action 0 = down, 1 = move, 2 = up. Caller holds the raster's lock. */
    abstract void add(int action, TouchPoint sample);

    void close() { }

    void include(float x, float y, float r) {
        left = Math.min(left, x - r); top = Math.min(top, y - r);
        right = Math.max(right, x + r); bottom = Math.max(bottom, y + r);
    }
}

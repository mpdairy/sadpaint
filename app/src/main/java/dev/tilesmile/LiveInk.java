package dev.tilesmile;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.onyx.android.sdk.pen.NeoFountainPen;
import com.onyx.android.sdk.pen.NeoPen;
import com.onyx.android.sdk.pen.NeoPenConfig;
import com.onyx.android.sdk.pen.PenPointInk;
import com.onyx.android.sdk.pen.PenPointResult;
import com.onyx.android.sdk.pen.PenResult;
import java.util.Collections;
import kotlin.Pair;

/**
 * Streams samples through the SDK fountain engine as they arrive, the same
 * calls NeoPenUtils.computeStrokePoints makes in one batch, and paints each
 * shaped point immediately. What is drawn live is the finished stroke.
 */
final class LiveInk extends LiveStroke {
    private final OpaqueRaster raster;
    private final int color;
    private final int[] pattern;
    private final float maxPressure;
    private NeoPen pen;
    private boolean hasLast;
    private float lastX, lastY;

    LiveInk(OpaqueRaster raster, int color, int[] pattern, float width, float maxPressure) {
        this.raster = raster; this.color = color; this.pattern = pattern; this.maxPressure = maxPressure;
        NeoPenConfig config = new NeoPenConfig();
        config.setWidth(width);
        pen = NeoFountainPen.Companion.create(config);
    }

    @Override void add(int action, TouchPoint sample) {
        if (pen == null) return;
        TouchPoint point = new TouchPoint(sample);
        point.pressure /= maxPressure;
        Pair<PenResult, PenResult> result = action == 0 ? pen.onPenDown(point, true)
            : action == 1 ? pen.onPenMove(Collections.singletonList(point), null, true)
            : pen.onPenUp(point, true);
        if (result != null && result.getFirst() instanceof PenPointResult)
            for (PenPointInk ink : ((PenPointResult) result.getFirst()).getPoints()) paint(ink);
        if (action == 2) close();
    }

    private void paint(PenPointInk ink) {
        float x = ink.getX(), y = ink.getY(), size = ink.getSize();
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(size)) return;
        // Same rendering as InkStroke: each segment is as wide as its end point.
        raster.pattern = pattern;
        if (hasLast) raster.segment(lastX, lastY, x, y, size, color);
        else raster.segment(x, y, x, y, size, color);
        raster.pattern = null;
        float r = Math.max(OpaqueRaster.MIN_SEGMENT_RADIUS, size / 2) + 1;
        include(x, y, r);
        lastX = x; lastY = y; hasLast = true; points++;
    }

    @Override void close() {
        if (pen != null) pen.destroy();
        pen = null;
    }
}

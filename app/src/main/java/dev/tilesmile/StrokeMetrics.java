package dev.tilesmile;

import java.util.Arrays;
import java.util.Locale;

/** App-side timings only. Surface submission is not physical panel latency. */
final class StrokeMetrics {
    private final long[] waits = new long[4096];
    private final long[] submits = new long[4096];
    private final long[] newest = new long[4096];
    private int frames, samples;
    private long raster, copy, lock, draw, post;

    synchronized void sample(long nanos) { raster += nanos; samples++; }
    synchronized void frame(long wait, long copy, long lock, long draw, long post, long newestAge) {
        if (frames < waits.length) {
            waits[frames] = wait;
            submits[frames] = wait + copy + lock + draw + post;
            newest[frames] = newestAge;
        }
        frames++;
        this.copy += copy; this.lock += lock; this.draw += draw; this.post += post;
    }

    synchronized String summary() {
        return String.format(Locale.US,
            "frames=%d rasterAvgMs=%.3f copyAvgMs=%.3f lockAvgMs=%.3f drawAvgMs=%.3f postAvgMs=%.3f waitP50Ms=%.2f waitP95Ms=%.2f submitP50Ms=%.2f submitP95Ms=%.2f newestP50Ms=%.2f newestP95Ms=%.2f",
            frames, ms(raster, samples), ms(copy, frames), ms(lock, frames),
            ms(draw, frames), ms(post, frames), percentile(waits, 0.5),
            percentile(waits, 0.95), percentile(submits, 0.5), percentile(submits, 0.95),
            percentile(newest, 0.5), percentile(newest, 0.95));
    }
    private double percentile(long[] data, double fraction) {
        int size = Math.min(frames, data.length);
        if (size == 0) return 0;
        long[] sorted = new long[size];
        int used = 0;
        for (int i = 0; i < size; i++) if (data[i] >= 0) sorted[used++] = data[i];
        if (used == 0) return 0;
        sorted = Arrays.copyOf(sorted, used);
        Arrays.sort(sorted);
        return sorted[Math.max(0, (int) Math.ceil(used * fraction) - 1)] / 1e6;
    }
    private double ms(long nanos, int count) { return nanos / 1e6 / Math.max(1, count); }
}

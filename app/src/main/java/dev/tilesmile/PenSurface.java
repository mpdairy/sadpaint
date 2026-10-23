package dev.tilesmile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;
import com.onyx.android.sdk.api.device.epd.EpdController;
import com.onyx.android.sdk.api.device.epd.UpdateMode;
import com.onyx.android.sdk.data.note.TouchPoint;
import com.onyx.android.sdk.pen.RawInputCallback;
import com.onyx.android.sdk.pen.TouchHelper;
import com.onyx.android.sdk.pen.data.TouchPointList;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Input and SDK lifecycle stay on main; fast surface submissions use a worker. */
public class PenSurface extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "TileSmile";
    private static final String[] MODES = {"A Native color", "B Live bitmap", "C Black preview", "Android control"};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView status;
    private StrokeMetrics metrics = new StrokeMetrics();
    private long pendingSince, latestPendingSince;
    private final Object pixelsLock = new Object(), renderLock = new Object();
    private final HandlerThread renderThread = new HandlerThread("TileSmile-render");
    private final Handler render;
    private final AtomicBoolean renderQueued = new AtomicBoolean();
    private volatile boolean renderAllowed;
    private boolean fastUpdates = true;
    private TouchHelper helper;
    private OpaqueRaster raster;
    private Bitmap bitmap;
    private int mode = 1, color = 0xff6f9ec5, generation;
    private Integer previewColor;
    private boolean resumed, focused, ready, drawing, scheduled, rawSeen;
    public boolean liveStats = false;
    interface RawStrokeListener { void finished(int previewColor); }
    private RawStrokeListener rawStrokeListener;
    void setRawStrokeListener(RawStrokeListener listener) { rawStrokeListener = listener; }
    private float maxPressure = 4096, pressure, peakPressure;
    /** Native preview width in px; the finished stroke scales to match (32 = default). */
    private float strokeWidth = 32;
    private long strokeStart, strokeEnd, lastStats, count, rawCount;
    private String sdkState = "Waiting for surface", failure = "";
    private final Runnable frame = () -> { scheduled = false; present(false); };
    private final Runnable asyncFrame = () -> {
        boolean submitted = false;
        try {
            submitted = presentFast();
        } catch (RuntimeException e) {
            renderAllowed = false;
            main.post(() -> fail("Bitmap renderer", e));
        } finally {
            renderQueued.set(false);
            synchronized (pixelsLock) {
                if (submitted && renderAllowed && raster != null && raster.isDirty()) scheduleFastFrame();
            }
        }
    };

    public PenSurface(Context context, TextView status) {
        super(context);
        this.status = status;
        renderThread.start();
        render = new Handler(renderThread.getLooper());
        // SurfaceView defaults to RGB_565, which shifts palette colors (e.g. gray -> pinkish).
        getHolder().setFormat(PixelFormat.RGBA_8888);
        getHolder().addCallback(this);
    }

    public void resume() { resumed = true; open(); }
    public void pause() { resumed = false; close(); }
    public void release() { close(); renderThread.quitSafely(); }
    public void focus(boolean value) { focused = value; if (value) open(); else close(); }

    @Override public void surfaceCreated(SurfaceHolder holder) { ready = true; }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        close();
        if (raster == null || raster.width != width || raster.height != height) {
            OpaqueRaster old = raster;
            raster = new OpaqueRaster(width, height);
            raster.sizeScale = strokeWidth / 32;
            if (old != null) {
                for (int row = 0; row < Math.min(height, old.height); row++)
                    System.arraycopy(old.pixels, row * old.width, raster.pixels,
                        row * width, Math.min(width, old.width));
            }
            if (bitmap != null) bitmap.recycle();
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        present(true);
        open();
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { ready = false; close(); }

    /** Call before the surface is shown. */
    public void setStrokeWidth(float width) {
        strokeWidth = width;
        if (raster != null) raster.sizeScale = width / 32;
    }

    public void selectMode(int next) {
        close(); mode = next; failure = ""; rawSeen = false; count = rawCount = 0;
        present(true); open();
    }
    public int getInkColor() { return color; }
    public int getPreviewColor() {
        return mode == 2 ? 0xff000000 : previewColor == null ? color : previewColor;
    }
    public void selectPreviewColor(int selectedColor) {
        int opaque = selectedColor | 0xff000000;
        if (previewColor != null && previewColor == opaque) return;
        close(); previewColor = opaque;
        present(true); open();
    }
    public void setFastUpdates(boolean enabled) {
        close();
        fastUpdates = enabled;
        present(true); open();
    }
    public void selectColor(int selectedColor) {
        int opaque = selectedColor | 0xff000000;
        if (color == opaque) return;
        close(); color = opaque;
        present(true); open();
    }
    public void clear() {
        close(); if (raster != null) raster.clear(); present(true); open();
    }
    public void cleanRefresh() {
        close(); present(true);
        try { EpdController.refreshScreen(this, UpdateMode.GC); }
        catch (RuntimeException | LinkageError e) { fail("Clean refresh", e); }
        open();
    }

    private void open() {
        if (!ready || raster == null || !resumed || !focused || helper != null) return;
        renderAllowed = fastUpdates && (mode == 1 || mode == 3);
        if (mode == 3) {
            sdkState = "Android MotionEvent control (not BOOX raw)";
            stats(true);
            if (renderAllowed) scheduleFastFrame();
            return;
        }
        final int token = ++generation;
        try {
            float reportedMax = EpdController.getMaxTouchPressure();
            maxPressure = reportedMax > 0 ? reportedMax : 4096;
            helper = TouchHelper.create(this, callback(token));
            helper.setStrokeWidth(strokeWidth)
                .setLimitRect(new Rect(0, 0, getWidth(), getHeight()), Collections.emptyList())
                .openRawDrawing();
            helper.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN);
            helper.setStrokeWidth(strokeWidth).setStrokeColor(getPreviewColor());
            helper.enableFingerTouch(false);
            helper.enableSideBtnErase(false);
            enableRawInput();
            sdkState = "SDK created=" + helper.isRawDrawingCreated() + "; draw with pen to verify raw input";
            Log.i(TAG, sdkState + " maxPressure=" + maxPressure + " mode=" + mode
                + " nativeRender=" + helper.isRawDrawingRenderEnabled()
                + " rawInput=" + helper.isRawDrawingInputEnabled()
                + String.format(Locale.US, " final=#%06X preview=#%06X", color & 0xffffff,
                    getPreviewColor() & 0xffffff));
        } catch (RuntimeException | LinkageError e) {
            fail("BOOX raw unavailable", e);
            close();
            sdkState = "Raw failed; select Android control";
        }
        stats(true);
        if (renderAllowed) scheduleFastFrame();
    }

    private void enableRawInput() {
        // SDK 1.5.4.4 enables BOTH input and native rendering here. Apply the
        // mode-specific flag afterwards, including when resuming after pen-up.
        helper.setRawDrawingEnabled(true);
        helper.setRawDrawingRenderEnabled(mode != 1);
    }

    private void close() {
        generation++;
        renderAllowed = false;
        render.removeCallbacks(asyncFrame);
        // Drain an in-flight surface lock before replacing/recycling its bitmap.
        synchronized (renderLock) { renderQueued.set(false); }
        cancelFrame(); drawing = false;
        if (raster != null) raster.end();
        TouchHelper old = helper; helper = null;
        if (old != null) {
            try { old.setRawDrawingEnabled(false); }
            catch (RuntimeException | LinkageError e) { fail("Pause raw", e); }
            try { old.closeRawDrawing(); }
            catch (RuntimeException | LinkageError e) { fail("Close raw", e); }
        }
    }

    private void fail(String operation, Throwable error) {
        failure = operation + ": " + error.getClass().getSimpleName();
        Log.e(TAG, failure, error);
        stats(true);
    }

    private void dispatch(int token, int action, TouchPoint point) {
        if (point == null) return;
        final float x = point.getX(), y = point.getY(), p = point.getPressure();
        Runnable receive = () -> {
            if (token != generation || helper == null || !ready) return;
            rawSeen = true; rawCount++;
            boolean completed = action == 2 && drawing && peakPressure > 0;
            receive(action, x, y, p, maxPressure);
            if (completed && rawStrokeListener != null && helper != null && mode == 0
                && helper.isRawDrawingRenderEnabled()) rawStrokeListener.finished(getPreviewColor());
        };
        if (Looper.myLooper() == Looper.getMainLooper()) receive.run(); else main.post(receive);
    }

    private RawInputCallback callback(int token) {
        return new RawInputCallback() {
            @Override public void onBeginRawDrawing(boolean b, TouchPoint p) { dispatch(token, 0, p); }
            @Override public void onRawDrawingTouchPointMoveReceived(TouchPoint p) { dispatch(token, 1, p); }
            @Override public void onEndRawDrawing(boolean b, TouchPoint p) { dispatch(token, 2, p); }
            // This list repeats the begin/move samples; replaying it would count/draw them twice.
            @Override public void onRawDrawingTouchPointListReceived(TouchPointList p) { }
            @Override public void onBeginRawErasing(boolean b, TouchPoint p) { }
            @Override public void onEndRawErasing(boolean b, TouchPoint p) { }
            @Override public void onRawErasingTouchPointMoveReceived(TouchPoint p) { }
            @Override public void onRawErasingTouchPointListReceived(TouchPointList p) { }
        };
    }

    private void receive(int action, float x, float y, float rawPressure, float scale) {
        if (raster == null) return;
        if (action == 0 || (!drawing && action == 1)) {
            raster.end(); drawing = true; count = 0; peakPressure = 0;
            synchronized (pixelsLock) { metrics = new StrokeMetrics(); }
            strokeStart = SystemClock.uptimeMillis();
        }
        if (!drawing) return;
        count++; pressure = rawPressure; peakPressure = Math.max(peakPressure, pressure);
        // Pen-up often reports zero pressure; do not introduce an artificial thin endpoint.
        long rasterStart = System.nanoTime();
        synchronized (pixelsLock) {
            if (action != 2 || rawPressure > 0) {
                if (!raster.isDirty()) pendingSince = rasterStart;
                latestPendingSince = rasterStart;
                raster.point(x, y, rawPressure / scale, color);
            }
            metrics.sample(System.nanoTime() - rasterStart);
        }
        if (action == 2) {
            drawing = false; raster.end(); strokeEnd = SystemClock.uptimeMillis();
            cancelFrame();
            // Only native-preview modes need to release the EPD lock. Bitmap
            // mode keeps raw capture running and native rendering disabled.
            if (helper != null && mode != 1) helper.setRawDrawingEnabled(false);
            if (renderAllowed) scheduleFastFrame(); else present(false);
            if (helper != null && mode != 1 && resumed && focused) enableRawInput();
            stats(true);
            Log.i(TAG, "stroke mode=" + mode + " raw=" + rawSeen + " samples=" + count
                + " durationMs=" + (strokeEnd - strokeStart) + " peakPressure=" + peakPressure
                + " nativeRender=" + (helper != null && helper.isRawDrawingRenderEnabled())
                + String.format(Locale.US, " color=#%06X", color & 0xffffff));
            StrokeMetrics completed = metrics;
            String prefix = "perf mode=" + mode + " fast=" + fastUpdates + " liveStats=" + liveStats + " ";
            if (renderAllowed) render.post(() -> Log.i(TAG, prefix + completed.summary()));
            else Log.i(TAG, prefix + completed.summary());
        } else {
            if (mode == 1 || mode == 3) scheduleFrame();
            stats(false);
        }
    }

    private void scheduleFrame() {
        if (renderAllowed) { scheduleFastFrame(); return; }
        if (scheduled) return;
        scheduled = true;
        main.postDelayed(frame, 16);
    }

    private void scheduleFastFrame() {
        if (renderAllowed && renderQueued.compareAndSet(false, true)) render.post(asyncFrame);
    }

    private void cancelFrame() {
        main.removeCallbacks(frame);
        scheduled = false;
    }

    private boolean presentFast() {
        synchronized (renderLock) {
            if (!renderAllowed || !getHolder().getSurface().isValid()) return false;
            Rect requested;
            synchronized (pixelsLock) {
                if (raster == null || !raster.isDirty()) return false;
                // Reserve nearby damage so samples arriving during lockCanvas's
                // wait can join this frame. This predicts no ink or coordinates.
                requested = new Rect(Math.max(0, raster.left - 128), Math.max(0, raster.top - 128),
                    Math.min(raster.width, raster.right + 128), Math.min(raster.height, raster.bottom + 128));
            }
            long start = System.nanoTime();
            Canvas target = getHolder().lockCanvas(requested);
            long locked = System.nanoTime();
            if (target == null) return false;
            try {
                StrokeMetrics measured;
                long oldest, newest;
                boolean covered;
                synchronized (pixelsLock) {
                    Rect changed = new Rect(raster.left, raster.top, raster.right, raster.bottom);
                    oldest = pendingSince; newest = latestPendingSince; measured = metrics;
                    bitmap.setPixels(raster.pixels, changed.top * raster.width + changed.left, raster.width,
                        changed.left, changed.top, changed.width(), changed.height());
                    covered = requested.contains(changed);
                    // A fast jump outside the reserved clip must remain dirty
                    // for another frame; never discard pixels not yet submitted.
                    if (covered) { raster.resetDirty(); pendingSince = 0; }
                }
                long copied = System.nanoTime();
                target.drawBitmap(bitmap, 0, 0, null);
                long drawn = System.nanoTime();
                getHolder().unlockCanvasAndPost(target);
                target = null;
                long posted = System.nanoTime();
                measured.frame(Math.max(0, start - oldest), copied - locked, locked - start,
                    drawn - copied, posted - drawn, covered ? posted - newest : -1);
            } finally {
                if (target != null) getHolder().unlockCanvasAndPost(target);
            }
            return true;
        }
    }

    private void present(boolean full) {
        if (!ready || raster == null || bitmap == null || !getHolder().getSurface().isValid()) return;
        if (!full && !raster.isDirty()) return;
        Rect dirty = full ? new Rect(0, 0, raster.width, raster.height)
            : new Rect(raster.left, raster.top, raster.right, raster.bottom);
        long start = System.nanoTime();
        long wait = pendingSince == 0 ? 0 : start - pendingSince;
        bitmap.setPixels(raster.pixels, dirty.top * raster.width + dirty.left, raster.width,
            dirty.left, dirty.top, dirty.width(), dirty.height());
        long copied = System.nanoTime(), locked = copied, drawn = copied;
        Canvas target = null;
        try {
            // SurfaceHolder may expand dirty to cover a new buffer; redraw its entire clip.
            target = getHolder().lockCanvas(dirty);
            locked = System.nanoTime();
            if (target == null) return;
            target.drawBitmap(bitmap, 0, 0, null);
            drawn = System.nanoTime();
            raster.resetDirty();
            pendingSince = 0;
        } finally {
            if (target != null) {
                getHolder().unlockCanvasAndPost(target);
                long posted = System.nanoTime();
                if (!full) metrics.frame(wait, copied - start, locked - copied,
                    drawn - locked, posted - drawn,
                    latestPendingSince == 0 ? 0 : posted - latestPendingSince);
            }
        }
    }

    private void stats(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && (!liveStats || now - lastStats < 250)) return;
        lastStats = now;
        long duration = (drawing ? now : strokeEnd) - strokeStart;
        double hz = duration > 0 && count > 1 ? (count - 1) * 1000.0 / duration : 0;
        status.setText(String.format(Locale.US,
            "%s • %s • %s\n%s\nPressure %.0f / %.0f (peak %.0f) • %.0f samples/s • raw callbacks %d%s",
            MODES[mode], String.format(Locale.US, "#%06X", color & 0xffffff), Build.MODEL,
            (rawSeen ? "BOOX raw callbacks received" : sdkState)
                + (helper == null ? "" : " • Native preview "
                    + (helper.isRawDrawingRenderEnabled() ? "ON" : "OFF")),
            pressure, mode == 3 ? 1f : maxPressure, peakPressure, hz, rawCount,
            failure.isEmpty() ? "" : "\n" + failure));
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (mode != 3) return true;
        int index = event.getActionIndex();
        if (event.getToolType(index) != MotionEvent.TOOL_TYPE_STYLUS
            && event.getToolType(index) != MotionEvent.TOOL_TYPE_ERASER) return true;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            drawing = false;
            if (raster != null) raster.end();
            if (renderAllowed) scheduleFastFrame(); else present(false);
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) receive(0, event.getX(index), event.getY(index), event.getPressure(index), 1);
        else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getHistorySize(); i++)
                receive(1, event.getHistoricalX(index, i), event.getHistoricalY(index, i), event.getHistoricalPressure(index, i), 1);
            receive(1, event.getX(index), event.getY(index), event.getPressure(index), 1);
        } else if (action == MotionEvent.ACTION_UP)
            receive(2, event.getX(index), event.getY(index), event.getPressure(index), 1);
        return true;
    }
}

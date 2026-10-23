package dev.tilesmile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import com.onyx.android.sdk.api.device.epd.EpdController;
import com.onyx.android.sdk.api.device.epd.UpdateMode;
import com.onyx.android.sdk.data.note.TouchPoint;
import com.onyx.android.sdk.pen.NeoFountainPenWrapper;
import com.onyx.android.sdk.pen.RawInputCallback;
import com.onyx.android.sdk.pen.TouchHelper;
import com.onyx.android.sdk.pen.data.TouchPointList;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The painting canvas, with two ways to show a stroke while the pen is down:
 * <ul>
 * <li>Fast: BOOX's native fountain pen draws an approximate-color preview. On
 * pen-up the same samples go through the SDK's fountain engine, so the
 * finished stroke has the preview's shape, rasterized as opaque color.</li>
 * <li>True color: native rendering is off; samples stream through the same
 * fountain engine and the exact paint is drawn into the surface as it goes.
 * Slower on E Ink, but what you see while drawing is the finished stroke.</li>
 * <li>Fast, no flash: the same native preview, but on pen-up the finished
 * stroke is posted directly (as BOOX Notes' InkRenderManager does) instead of
 * pausing raw drawing, which flashes. Posting disables SurfaceFlinger's
 * scribble mode; the next pen-down re-enables it.</li>
 * </ul>
 * Input and SDK lifecycle stay on the main thread; live frames use a worker.
 */
public class PaintSurface extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "TileSmile";
    private static final int MAX_UNDO_PIXELS = 8_000_000, MAX_UNDO_STEPS = 200;
    private static final long SAVE_DELAY_MS = 4000;

    enum LiveMode { FAST, TRUE_COLOR, NO_FLASH }
    /** Brush lays down color; Blend and Smudge move paint; Watercolor glazes over the canvas.
     *  All but the brush stream without a native preview. */
    enum Tool { BRUSH, BLEND, SMUDGE, WATERCOLOR }
    private static final ExecutorService files = Project.IO;

    interface Listener {
        void strokeFinished(String summary);
        void historyChanged();
    }

    private static final class Edit {
        final int x, y, w, h;
        final int[] saved;
        Edit(int x, int y, int w, int h, int[] saved) { this.x = x; this.y = y; this.w = w; this.h = h; this.saved = saved; }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread renderThread = new HandlerThread("TileSmile-live");
    private final Handler render;
    private final AtomicBoolean frameQueued = new AtomicBoolean();
    /** pixelsLock guards raster/bitmap pixels; surfaceLock serializes lockCanvas users. */
    private final Object pixelsLock = new Object(), surfaceLock = new Object();
    private File saveFile;
    private final ArrayDeque<Edit> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final List<TouchPoint> stroke = new ArrayList<>();
    private final Runnable saveLater = this::save;
    private Listener listener;
    private TouchHelper helper;
    private OpaqueRaster raster;
    private Bitmap bitmap;
    private LiveStroke live;
    private int[] beforeStroke;
    private int color = 0xff000000, preview = 0xff000000, generation;
    /** The paint as laid down: {@link #color} dithered with black or white. */
    private int[] pattern = Shade.pattern(0xff000000, 0);
    private float brushWidth = 24, maxPressure = 4096;
    private boolean resumed, focused, ready, drawing, loaded, suspended, unsaved;
    private Tool tool = Tool.BRUSH;
    private int blendStrength = 35;
    private LiveMode mode = LiveMode.FAST;
    private volatile boolean framesAllowed;
    private long strokeStart;
    private int listSize, pageToken;
    private final Runnable liveFrame = () -> {
        try {
            presentLive();
        } catch (RuntimeException e) {
            Log.e(TAG, "Live frame", e);
        } finally {
            frameQueued.set(false);
            synchronized (pixelsLock) {
                if (framesAllowed && raster != null && raster.isDirty()) scheduleFrame();
            }
        }
    };

    public PaintSurface(Context context) {
        super(context);
        renderThread.start();
        render = new Handler(renderThread.getLooper());
        // SurfaceView defaults to RGB_565, which shifts palette colors (e.g. gray -> pinkish).
        getHolder().setFormat(PixelFormat.RGBA_8888);
        getHolder().addCallback(this);
    }

    void setListener(Listener listener) { this.listener = listener; }

    /** The page file to load when the surface first appears. */
    void setPage(File file) { saveFile = file; }

    /**
     * Stops pen input while pages are copied or replaced. {@code keep} queues a
     * save of the current page first; otherwise its unsaved changes are dropped.
     */
    void suspend(boolean keep) {
        suspended = true;
        close();
        if (keep) save();
        else { main.removeCallbacks(saveLater); unsaved = false; }
    }

    /** Saves the current page (unless suspended without keeping it), then loads {@code file}. */
    void showPage(File file) {
        if (!suspended) suspend(true);
        final int token = ++pageToken;
        saveFile = file;
        files.execute(() -> {
            Bitmap page = file.exists() ? BitmapFactory.decodeFile(file.getPath()) : null;
            main.post(() -> {
                if (token != pageToken) { if (page != null) page.recycle(); return; }
                if (raster != null) {
                    synchronized (pixelsLock) { java.util.Arrays.fill(raster.pixels, 0xffffffff); }
                    if (page != null) {
                        int[] pixels = new int[page.getWidth() * page.getHeight()];
                        page.getPixels(pixels, 0, page.getWidth(), 0, 0, page.getWidth(), page.getHeight());
                        synchronized (pixelsLock) { copyInto(pixels, page.getWidth(), page.getHeight()); }
                    }
                    undo.clear(); redo.clear();
                    if (listener != null) listener.historyChanged();
                    present(null);
                    try { EpdController.refreshScreen(this, UpdateMode.GC); }
                    catch (RuntimeException | LinkageError e) { Log.e(TAG, "Page refresh", e); }
                } else {
                    loaded = false;
                }
                if (page != null) page.recycle();
                suspended = false;
                open();
            });
        });
    }

    void setBlendStrength(int value) { blendStrength = value; }

    /** Queues a save of the open page on {@link Project#IO}. */
    void saveNow() { save(); }
    public void resume() { resumed = true; open(); }
    public void pause() { resumed = false; close(); save(); }
    public void release() { close(); renderThread.quitSafely(); }
    public void focus(boolean value) { focused = value; if (value) open(); else close(); }

    boolean canUndo() { return !undo.isEmpty(); }
    boolean canRedo() { return !redo.isEmpty(); }

    /** Finished color and the native preview request shown while the pen is down. */
    public void setInk(int finished, int previewRequest) { setInk(finished, 0, previewRequest); }

    /** As {@link #setInk(int, int)}, with the finished color dithered to {@code shade} (see {@link Shade}). */
    public void setInk(int finished, int shade, int previewRequest) {
        finished |= 0xff000000; previewRequest |= 0xff000000;
        int[] next = Shade.pattern(finished, shade);
        if (finished == color && java.util.Arrays.equals(next, pattern) && previewRequest == preview) return;
        close(); color = finished; pattern = next; preview = previewRequest; open();
    }

    public void setBrushWidth(float width) {
        if (width == brushWidth) return;
        close(); brushWidth = width; open();
    }

    public void setTool(Tool value) {
        if (value == tool) return;
        close(); tool = value; open();
    }

    public void setLiveMode(LiveMode value) {
        if (value == mode) return;
        close(); mode = value; open();
    }

    /** Samples stream through LiveInk into the raster while the pen is down. */
    private boolean streams() { return tool != Tool.BRUSH || mode == LiveMode.TRUE_COLOR; }
    /** The firmware draws its own preview while the pen is down. */
    private boolean nativeRender() { return !streams(); }

    @Override public void surfaceCreated(SurfaceHolder holder) { ready = true; }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        close();
        if (raster == null || raster.width != width || raster.height != height) {
            OpaqueRaster old = raster;
            raster = new OpaqueRaster(width, height);
            if (old != null) copyInto(old.pixels, old.width, old.height);
            else if (!loaded) load();
            loaded = true;
            if (bitmap != null) bitmap.recycle();
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            beforeStroke = null;
            undo.clear(); redo.clear();
            if (listener != null) listener.historyChanged();
        }
        present(new Rect(0, 0, width, height));
        open();
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { ready = false; close(); }

    public void undo() { step(undo, redo); }
    public void redo() { step(redo, undo); }

    private void step(ArrayDeque<Edit> from, ArrayDeque<Edit> to) {
        if (raster == null || from.isEmpty()) return;
        Edit edit = from.pop();
        synchronized (pixelsLock) { raster.swapRegion(edit.x, edit.y, edit.w, edit.h, edit.saved); }
        to.push(edit);
        showWithRawPaused(new Rect(edit.x, edit.y, edit.x + edit.w, edit.y + edit.h));
        changed();
    }

    public void clear() {
        if (raster == null) return;
        record(0, 0, raster.width, raster.height, raster.copyRegion(0, 0, raster.width, raster.height));
        synchronized (pixelsLock) { java.util.Arrays.fill(raster.pixels, 0xffffffff); }
        showWithRawPaused(new Rect(0, 0, raster.width, raster.height));
        changed();
    }

    public void cleanRefresh() {
        close();
        present(null);
        try { EpdController.refreshScreen(this, UpdateMode.GC); }
        catch (RuntimeException | LinkageError e) { Log.e(TAG, "Clean refresh", e); }
        open();
    }

    private void record(int x, int y, int w, int h, int[] saved) {
        undo.push(new Edit(x, y, w, h, saved));
        redo.clear();
        long total = 0;
        for (Edit edit : undo) total += edit.saved.length;
        while (undo.size() > 1 && (total > MAX_UNDO_PIXELS || undo.size() > MAX_UNDO_STEPS))
            total -= undo.removeLast().saved.length;
    }

    private void changed() {
        unsaved = true;
        if (listener != null) listener.historyChanged();
        main.removeCallbacks(saveLater);
        main.postDelayed(saveLater, SAVE_DELAY_MS);
    }

    private void open() {
        if (!ready || raster == null || !resumed || !focused || suspended || helper != null) return;
        final int token = ++generation;
        try {
            float reportedMax = EpdController.getMaxTouchPressure();
            maxPressure = reportedMax > 0 ? reportedMax : 4096;
            helper = TouchHelper.create(this, callback(token));
            helper.setStrokeWidth(brushWidth)
                .setLimitRect(new Rect(0, 0, getWidth(), getHeight()), Collections.emptyList())
                .openRawDrawing();
            helper.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN);
            helper.setStrokeWidth(brushWidth).setStrokeColor(preview);
            helper.enableFingerTouch(false);
            helper.enableSideBtnErase(false);
            enableRaw();
            framesAllowed = streams();
            Log.i(TAG, String.format(Locale.US, "paint open mode=%s tool=%s maxPressure=%.0f width=%.1f final=#%06X preview=#%06X",
                mode, tool, maxPressure, brushWidth, color & 0xffffff, preview & 0xffffff));
        } catch (RuntimeException | LinkageError e) {
            Log.e(TAG, "BOOX raw unavailable", e);
            closeHelper();
        }
    }

    private void enableRaw() {
        // SDK 1.5.4.4 enables native rendering together with raw input, so the
        // render flag must be applied afterwards.
        helper.setRawDrawingEnabled(true);
        helper.setRawDrawingRenderEnabled(nativeRender());
    }

    private void close() {
        generation++;
        framesAllowed = false;
        render.removeCallbacks(liveFrame);
        // Drain an in-flight live frame before the bitmap can be replaced.
        synchronized (surfaceLock) { frameQueued.set(false); }
        if (drawing && streams()) abandonLiveStroke();
        drawing = false;
        stroke.clear();
        if (live != null) { live.close(); live = null; }
        closeHelper();
    }

    private void closeHelper() {
        TouchHelper old = helper; helper = null;
        if (old == null) return;
        try { old.setRawDrawingEnabled(false); } catch (RuntimeException | LinkageError e) { Log.e(TAG, "Pause raw", e); }
        try { old.closeRawDrawing(); } catch (RuntimeException | LinkageError e) { Log.e(TAG, "Close raw", e); }
    }

    /** The firmware holds ordinary surface updates while native-rendered raw drawing is active. */
    private void showWithRawPaused(Rect dirty) {
        if (!nativeRender()) { present(dirty); return; }
        if (mode == LiveMode.NO_FLASH) {
            try { EpdController.enablePost(this, 1); }
            catch (RuntimeException | LinkageError e) { Log.e(TAG, "Enable post", e); }
            present(dirty);
            return;
        }
        if (helper != null) helper.setRawDrawingEnabled(false);
        present(dirty);
        if (helper != null && resumed && focused) enableRaw();
    }

    private RawInputCallback callback(int token) {
        return new RawInputCallback() {
            @Override public void onBeginRawDrawing(boolean b, TouchPoint p) { dispatch(token, 0, p); }
            @Override public void onRawDrawingTouchPointMoveReceived(TouchPoint p) { dispatch(token, 1, p); }
            @Override public void onEndRawDrawing(boolean b, TouchPoint p) { dispatch(token, 2, p); }
            // Repeats the begin/move samples; only its size is logged, for comparison.
            @Override public void onRawDrawingTouchPointListReceived(TouchPointList p) {
                int size = p == null ? 0 : p.size();
                main.post(() -> listSize = size);
            }
            @Override public void onBeginRawErasing(boolean b, TouchPoint p) { }
            @Override public void onEndRawErasing(boolean b, TouchPoint p) { }
            @Override public void onRawErasingTouchPointMoveReceived(TouchPoint p) { }
            @Override public void onRawErasingTouchPointListReceived(TouchPointList p) { }
        };
    }

    private void dispatch(int token, int action, TouchPoint point) {
        if (point == null) return;
        final TouchPoint copy = new TouchPoint(point);
        Runnable receive = () -> {
            if (token != generation || helper == null || !ready) return;
            receive(action, copy);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) receive.run(); else main.post(receive);
    }

    private void receive(int action, TouchPoint point) {
        if (action == 0 || !drawing) {
            if (action == 2) return;
            if (drawing && streams()) finishLive();
            drawing = true; stroke.clear(); listSize = -1;
            strokeStart = SystemClock.uptimeMillis();
            if (streams()) startLive();
            action = 0;
        }
        stroke.add(point);
        if (streams()) {
            if (live != null) {
                try {
                    synchronized (pixelsLock) { live.add(action, point); }
                } catch (RuntimeException | LinkageError e) {
                    Log.e(TAG, "SDK fountain live", e);
                    live.close(); live = null;
                }
                scheduleFrame();
            }
            if (action == 2) finishLive();
        } else if (action == 2) {
            finishNative();
        }
    }

    // ---- True color: stream through the fountain engine, draw live ----

    private void startLive() {
        // Snapshot so the stroke's region can be recorded for undo once its extent is known.
        synchronized (pixelsLock) {
            if (beforeStroke == null || beforeStroke.length != raster.pixels.length)
                beforeStroke = new int[raster.pixels.length];
            System.arraycopy(raster.pixels, 0, beforeStroke, 0, raster.pixels.length);
        }
        try { live = tool == Tool.BLEND ? new Blend(raster, brushWidth, blendStrength, maxPressure)
            : tool == Tool.SMUDGE ? new Smudge(raster, brushWidth, maxPressure)
            : tool == Tool.WATERCOLOR ? new Glaze(raster, pattern, brushWidth, maxPressure)
            : new LiveInk(raster, color, pattern, brushWidth, maxPressure); }
        catch (RuntimeException | LinkageError e) { Log.e(TAG, "SDK fountain live", e); live = null; }
    }

    private void finishLive() {
        drawing = false;
        LiveStroke done = live; live = null;
        if (done != null) done.close();
        if (done != null && done.points > 0) {
            int l = Math.max(0, (int) Math.floor(done.left)), t = Math.max(0, (int) Math.floor(done.top));
            int r = Math.min(raster.width, (int) Math.ceil(done.right)), b = Math.min(raster.height, (int) Math.ceil(done.bottom));
            if (l < r && t < b) {
                int w = r - l, h = b - t;
                int[] saved = new int[w * h];
                for (int row = 0; row < h; row++)
                    System.arraycopy(beforeStroke, (t + row) * raster.width + l, saved, row * w, w);
                record(l, t, w, h, saved);
                changed();
            }
        }
        report(done == null ? 0 : done.points, tool != Tool.BRUSH ? "dabs" : "live");
    }


    private void abandonLiveStroke() {
        // Interrupted mid-stroke (pause, focus loss): keep what was painted.
        finishLive();
    }

    private void scheduleFrame() {
        if (!framesAllowed || !frameQueued.compareAndSet(false, true)) return;
        render.post(liveFrame);
    }

    private void presentLive() {
        synchronized (surfaceLock) {
            if (!framesAllowed || !getHolder().getSurface().isValid()) return;
            Rect requested;
            synchronized (pixelsLock) {
                if (raster == null || !raster.isDirty()) return;
                // Reserve nearby space so samples arriving during lockCanvas's wait join this frame.
                requested = new Rect(Math.max(0, raster.left - 96), Math.max(0, raster.top - 96),
                    Math.min(raster.width, raster.right + 96), Math.min(raster.height, raster.bottom + 96));
            }
            Canvas target = getHolder().lockCanvas(requested);
            if (target == null) return;
            try {
                synchronized (pixelsLock) {
                    // lockCanvas may have grown the clip; refresh all of it from the raster.
                    Rect copy = new Rect(requested);
                    copy.union(raster.left, raster.top, raster.right, raster.bottom);
                    copy.intersect(0, 0, raster.width, raster.height);
                    bitmap.setPixels(raster.pixels, copy.top * raster.width + copy.left, raster.width,
                        copy.left, copy.top, copy.width(), copy.height());
                    // Damage outside the clip stays dirty for the next frame.
                    if (requested.contains(raster.left, raster.top, raster.right, raster.bottom)) raster.resetDirty();
                }
                target.drawBitmap(bitmap, 0, 0, null);
            } finally {
                getHolder().unlockCanvasAndPost(target);
            }
        }
    }

    // ---- Fast: native preview, shape and rasterize on pen-up ----

    private void finishNative() {
        drawing = false;
        boolean sdkShape = true;
        InkStroke shaped = null;
        try { shaped = sdkShape(stroke); }
        catch (RuntimeException | LinkageError e) { Log.e(TAG, "SDK fountain shape", e); }
        if (shaped == null) { sdkShape = false; shaped = fallbackShape(stroke); }
        int[] b = shaped.bounds(raster.width, raster.height);
        if (b != null) {
            record(b[0], b[1], b[2] - b[0], b[3] - b[1], raster.copyRegion(b[0], b[1], b[2] - b[0], b[3] - b[1]));
            synchronized (pixelsLock) { raster.pattern = pattern; shaped.draw(raster, color); raster.pattern = null; }
            // Pad so the native preview's slightly different edges are repainted too.
            int pad = (int) Math.ceil(brushWidth / 8) + 4;
            showWithRawPaused(new Rect(b[0] - pad, b[1] - pad, b[2] + pad, b[3] + pad));
            changed();
        }
        report(shaped.count, sdkShape ? "fountain" : "fallback");
    }

    private void report(int shapedPoints, String kind) {
        long took = SystemClock.uptimeMillis() - strokeStart;
        String summary = String.format(Locale.US, "%s • %d samples → %d %s points • width %.0f",
            tool == Tool.BLEND ? "Blend" : tool == Tool.SMUDGE ? "Smudge" : tool == Tool.WATERCOLOR ? "Watercolor" : mode == LiveMode.TRUE_COLOR ? "True color" : mode == LiveMode.NO_FLASH ? "No flash" : "Fast", stroke.size(), shapedPoints, kind, brushWidth);
        Log.i(TAG, "paint stroke " + summary + " list=" + listSize + " durationMs=" + took
            + String.format(Locale.US, " final=#%06X preview=#%06X", color & 0xffffff, preview & 0xffffff));
        stroke.clear();
        if (listener != null) listener.strokeFinished(summary);
    }

    /** The SDK path used by BOOX's own demo to redraw a fountain stroke after pen-up. */
    private InkStroke sdkShape(List<TouchPoint> input) {
        if (input.size() < 2) return null;
        List<TouchPoint> copies = new ArrayList<>(input.size());
        // computeStrokePoints normalizes pressure in place.
        for (TouchPoint point : input) copies.add(new TouchPoint(point));
        List<TouchPoint> out = NeoFountainPenWrapper.computeStrokePoints(copies, 1f, brushWidth, maxPressure);
        if (out == null || out.isEmpty()) return null;
        int n = out.size();
        float[] xs = new float[n], ys = new float[n], sizes = new float[n];
        for (int i = 0; i < n; i++) { TouchPoint p = out.get(i); xs[i] = p.x; ys[i] = p.y; sizes[i] = p.size; }
        return new InkStroke(xs, ys, sizes, n);
    }

    /** Taps and SDK failures: width proportional to pressure, never wider than the brush. */
    private InkStroke fallbackShape(List<TouchPoint> input) {
        int n = input.size();
        float[] xs = new float[n], ys = new float[n], sizes = new float[n];
        for (int i = 0; i < n; i++) {
            TouchPoint p = input.get(i);
            xs[i] = p.x; ys[i] = p.y;
            sizes[i] = brushWidth * Math.max(0, Math.min(1, p.pressure / maxPressure));
        }
        return new InkStroke(xs, ys, sizes, n);
    }

    private void present(Rect dirty) {
        synchronized (surfaceLock) {
            if (!ready || raster == null || bitmap == null || !getHolder().getSurface().isValid()) return;
            Rect full = new Rect(0, 0, raster.width, raster.height);
            if (dirty == null || !dirty.intersect(full)) dirty = full;
            Canvas target = getHolder().lockCanvas(dirty);
            if (target == null) return;
            try {
                // lockCanvas may enlarge dirty to cover a new buffer, so copy that entire clip.
                synchronized (pixelsLock) {
                    dirty.intersect(full);
                    bitmap.setPixels(raster.pixels, dirty.top * raster.width + dirty.left, raster.width,
                        dirty.left, dirty.top, dirty.width(), dirty.height());
                    raster.resetDirty();
                }
                target.drawBitmap(bitmap, 0, 0, null);
            } finally {
                getHolder().unlockCanvasAndPost(target);
            }
        }
    }

    private void copyInto(int[] pixels, int width, int height) {
        for (int row = 0; row < Math.min(height, raster.height); row++)
            System.arraycopy(pixels, row * width, raster.pixels, row * raster.width, Math.min(width, raster.width));
    }

    private void load() {
        try {
            // Wait for any save still being written by an earlier activity.
            final File file = saveFile;
            Bitmap saved = files.submit(() -> file != null && file.exists() ? BitmapFactory.decodeFile(file.getPath()) : null).get();
            if (saved == null) return;
            int[] pixels = new int[saved.getWidth() * saved.getHeight()];
            saved.getPixels(pixels, 0, saved.getWidth(), 0, 0, saved.getWidth(), saved.getHeight());
            copyInto(pixels, saved.getWidth(), saved.getHeight());
            saved.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Load canvas", e);
        }
    }

    private void save() {
        main.removeCallbacks(saveLater);
        if (raster == null || !loaded || !unsaved || saveFile == null) return;
        unsaved = false;
        final int[] pixels;
        synchronized (pixelsLock) { pixels = raster.pixels.clone(); }
        final int width = raster.width, height = raster.height;
        final File target = saveFile, temp = new File(saveFile.getPath() + ".tmp");
        files.execute(() -> {
            File folder = target.getParentFile();
            if (folder != null && !folder.isDirectory() && !folder.mkdirs()) { Log.e(TAG, "Save canvas: no folder"); return; }
            Bitmap image = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
            try (FileOutputStream out = new FileOutputStream(temp)) {
                image.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.getFD().sync();
            } catch (IOException e) {
                Log.e(TAG, "Save canvas", e);
                return;
            } finally {
                image.recycle();
            }
            if (!temp.renameTo(target)) Log.e(TAG, "Save canvas: rename failed");
        });
    }
}

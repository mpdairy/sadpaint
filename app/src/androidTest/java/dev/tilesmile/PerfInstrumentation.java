package dev.tilesmile;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.PowerManager;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.onyx.android.sdk.pen.TouchHelper;

/** Replays a deterministic stroke on-device; never claims to simulate the EMR or panel. */
public class PerfInstrumentation extends Instrumentation {
    private boolean preview;
    private boolean nativeProbe;
    private boolean visibility;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        preview = arguments != null && "true".equals(arguments.getString("preview"));
        nativeProbe = arguments != null && "true".equals(arguments.getString("nativeProbe"));
        visibility = arguments != null && "true".equals(arguments.getString("visibility"));
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (visibility) {
                result.putString("result", VisibilityChecks.run(this));
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (nativeProbe) {
                result.putString("result", NativePreviewProbe.run(this));
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (preview) {
                result.putString("result", PreviewChecks.run(this));
                finish(Activity.RESULT_OK, result);
                return;
            }
            Activity activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            PowerManager power = getTargetContext().getSystemService(PowerManager.class);
            if (!power.isInteractive()) throw new AssertionError("Wake the BOOX before benchmarking");
            Field field = MainActivity.class.getDeclaredField("canvas");
            field.setAccessible(true);
            PenSurface surface = (PenSurface) field.get(activity);
            Method receive = PenSurface.class.getDeclaredMethod("receive", int.class,
                float.class, float.class, float.class, float.class);
            receive.setAccessible(true);
            runOnMainSync(() -> { surface.selectMode(1); surface.selectColor(0xff9cbbd6); });
            SystemClock.sleep(500);
            for (int trial = 0; trial < 6; trial++) {
                if (!power.isInteractive()) throw new AssertionError("Device slept during benchmark");
                final boolean fast = trial % 2 == 1;
                runOnMainSync(() -> {
                    surface.setFastUpdates(fast);
                    surface.liveStats = false;
                    surface.clear();
                });
                long start = SystemClock.uptimeMillis();
                for (int i = 0; i <= 960; i++) {
                    final int sample = i;
                    long due = start + Math.round(i * 1000.0 / 480);
                    long delay = due - SystemClock.uptimeMillis();
                    if (delay > 0) SystemClock.sleep(delay);
                    runOnMainSync(() -> {
                        try {
                            float x = 200 + sample * (surface.getWidth() - 400f) / 960;
                            float y = 500 + (float) Math.sin(sample / 50.0) * 150;
                            receive.invoke(surface, sample == 0 ? 0 : sample == 960 ? 2 : 1,
                                x, y, sample == 960 ? 0f : 0.6f, 1f);
                        } catch (Exception e) { throw new RuntimeException(e); }
                    });
                }
                Log.i("TileSmile", "replay trial=" + trial + " fast=" + fast + " wallMs=" + (SystemClock.uptimeMillis() - start));
                OpaqueRaster expected = new OpaqueRaster(surface.getWidth(), surface.getHeight());
                for (int i = 0; i < 960; i++) expected.point(
                    200 + i * (surface.getWidth() - 400f) / 960,
                    500 + (float) Math.sin(i / 50.0) * 150, 0.6f, 0xff9cbbd6);
                verifyPixels(surface, expected);
                runOnMainSync(() -> {
                    try {
                        TouchHelper helper = (TouchHelper) read(surface, "helper");
                        if (helper == null || !helper.isRawDrawingInputEnabled() || helper.isRawDrawingRenderEnabled())
                            throw new AssertionError("B must retain raw input ON, native preview OFF");
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
            }
            // Rapid movement beyond the reserved dirty region, then recoloring
            // and lifecycle changes while rendering may still be in flight.
            runOnMainSync(() -> { surface.setFastUpdates(true); surface.clear(); });
            OpaqueRaster jump = new OpaqueRaster(surface.getWidth(), surface.getHeight());
            for (int i = 0; i < 20; i++) {
                final int sample = i;
                float x = i % 2 == 0 ? 50 : surface.getWidth() - 50;
                float y = 300 + i * 20;
                jump.point(x, y, 0.6f, 0xff9cbbd6);
                runOnMainSync(() -> {
                    try { receive.invoke(surface, sample == 0 ? 0 : 1, x, y, 0.6f, 1f); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
                SystemClock.sleep(2);
            }
            runOnMainSync(() -> {
                try { receive.invoke(surface, 2, 50f, 680f, 0f, 1f); }
                catch (Exception e) { throw new RuntimeException(e); }
                surface.pause(); surface.resume(); surface.focus(true);
            });
            verifyPixels(surface, jump);
            runOnMainSync(() -> surface.selectColor(0xff315f88));
            verifyPixels(surface, jump);
            if (!power.isInteractive()) throw new AssertionError("Device slept during benchmark");
            result.putString("result", "PASS: 6 replay pixel comparisons, B input/render state, fast-jump damage, recolor and pause/resume");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("result", "FAIL: " + Log.getStackTraceString(e));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private Object read(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private void verifyPixels(PenSurface surface, OpaqueRaster expected) throws Exception {
        Object pixelsLock = read(surface, "pixelsLock");
        Object renderLock = read(surface, "renderLock");
        long deadline = SystemClock.uptimeMillis() + 3000;
        while (true) {
            synchronized (renderLock) {
                synchronized (pixelsLock) {
                    OpaqueRaster actual = (OpaqueRaster) read(surface, "raster");
                    if (!actual.isDirty()) {
                        Bitmap bitmap = (Bitmap) read(surface, "bitmap");
                        int[] row = new int[actual.width];
                        for (int y = 0; y < actual.height; y++) {
                            bitmap.getPixels(row, 0, actual.width, 0, y, actual.width, 1);
                            for (int x = 0; x < actual.width; x++) {
                                int offset = y * actual.width + x;
                                if (row[x] != expected.pixels[offset] || actual.pixels[offset] != expected.pixels[offset])
                                    throw new AssertionError("Pixel mismatch at " + x + "," + y);
                            }
                        }
                        return;
                    }
                }
            }
            if (SystemClock.uptimeMillis() > deadline) throw new AssertionError("Renderer did not drain dirty pixels");
            SystemClock.sleep(5);
        }
    }
}

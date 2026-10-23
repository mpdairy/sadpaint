package dev.tilesmile;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import com.onyx.android.sdk.api.device.epd.EpdController;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;

/** Probe native stroke commands WITHOUT drawing into the application bitmap. */
final class NativePreviewProbe {
    static String run(Instrumentation test) throws Exception {
        PreviewActivity activity = (PreviewActivity) test.startActivitySync(
            new Intent(test.getTargetContext(), PreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.waitForIdleSync();
        PenSurface surface = (PenSurface) read(activity, "canvas");
        int initial = (Integer) read(activity, "index");
        int[] location = new int[2];
        float[] start = new float[2], last = new float[2];
        StringBuilder report = new StringBuilder();
        Class<?> bridge = Class.forName("android.onyx.ViewUpdateHelper");
        // These wrappers can silently return the requested width when unsupported.
        // Check that the firmware methods exist, but don't treat existence as drawing proof.
        for (String name : new String[]{"startStroke", "addStrokePoint", "finishStroke"})
            bridge.getDeclaredMethod(name, float.class, float.class, float.class,
                float.class, float.class, float.class);
        try {
            for (int color : new int[]{0xff000000, 0xff6f9ec5, 0xffc7d8e8}) {
                String hex = String.format(java.util.Locale.US, "#%06X", color & 0xffffff);
                test.runOnMainSync(() -> {
                    surface.selectPreviewColor(color);
                    surface.clear();
                    surface.getLocationOnScreen(location);
                    EpdController.mapToEpd(surface, new float[]{100, surface.getHeight() / 2f}, start);
                });
                test.waitForIdleSync();
                SystemClock.sleep(300);
                Bitmap before = test.getUiAutomation().takeScreenshot();
                if (before == null) throw new AssertionError("Screenshot unavailable");
                test.runOnMainSync(() -> {
                    EpdController.enterScribbleMode(surface);
                    float width = EpdController.startStroke(32, start[0], start[1], .8f, .5f, SystemClock.uptimeMillis());
                    android.util.Log.i("TileSmile", "native probe " + hex + " start=" + start[0] + "," + start[1]
                        + " returnedWidth=" + width);
                });
                for (int point = 1; point <= 120; point++) {
                    final int step = point;
                    test.runOnMainSync(() -> {
                        EpdController.mapToEpd(surface, new float[]{100 + step * (surface.getWidth() - 200f) / 120,
                            surface.getHeight() / 2f + 80 * (float) Math.sin(step / 12f)}, last);
                        EpdController.addStrokePoint(32, last[0], last[1], .8f, .5f, SystemClock.uptimeMillis());
                    });
                    SystemClock.sleep(8);
                }
                Bundle status = new Bundle();
                status.putString("native_probe", "Holding " + hex + " native-only stroke for screenshot");
                test.sendStatus(0, status);
                SystemClock.sleep(1500);
                Bitmap during = test.getUiAutomation().takeScreenshot();
                if (during == null) throw new AssertionError("Screenshot unavailable during native stroke");
                int changed = 0, nonwhite = 0;
                for (int y = location[1]; y < location[1] + surface.getHeight(); y++)
                    for (int x = location[0]; x < location[0] + surface.getWidth(); x++) {
                        if (before.getPixel(x, y) != during.getPixel(x, y)) changed++;
                        if ((during.getPixel(x, y) & 0xffffff) != 0xffffff) nonwhite++;
                    }
                try (FileOutputStream output = new FileOutputStream(new File(test.getTargetContext().getFilesDir(),
                    "native-probe-" + hex.substring(1) + ".png"))) {
                    during.compress(Bitmap.CompressFormat.PNG, 100, output);
                }
                Bitmap backing = (Bitmap) read(surface, "bitmap");
                for (int y = 0; y < backing.getHeight(); y++)
                    for (int x = 0; x < backing.getWidth(); x++)
                        if (backing.getPixel(x, y) != 0xffffffff)
                            throw new AssertionError("Probe contaminated with application bitmap ink");
                report.append(hex).append(" screenshotChangedPixels=").append(changed)
                    .append(" screenshotNonwhitePixels=").append(nonwhite).append("; ");
                before.recycle(); during.recycle();
                test.runOnMainSync(() -> {
                    EpdController.finishStroke(32, last[0], last[1], 0, .5f, SystemClock.uptimeMillis());
                    EpdController.leaveScribbleMode(surface);
                    surface.clear();
                });
            }
        } finally {
            test.runOnMainSync(() -> {
                EpdController.finishStroke(32, last[0], last[1], 0, .5f, SystemClock.uptimeMillis());
                EpdController.leaveScribbleMode(surface);
                surface.clear();
                activity.selectTrial(initial);
            });
        }
        return report + "Application bitmap stayed white. Blank screenshots do not prove invisible physical ink.";
    }
    private static Object read(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
}

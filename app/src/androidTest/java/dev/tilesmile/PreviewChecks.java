package dev.tilesmile;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.PowerManager;
import android.widget.Button;
import com.onyx.android.sdk.pen.TouchHelper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** Exercises configuration and final pixels, never labels simulated input as a panel observation. */
final class PreviewChecks {
    static String run(Instrumentation test) throws Exception {
        PreviewActivity activity = (PreviewActivity) test.startActivitySync(
            new Intent(test.getTargetContext(), PreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.waitForIdleSync();
        PowerManager power = test.getTargetContext().getSystemService(PowerManager.class);
        check(power.isInteractive(), "Wake the tablet before testing");
        PenSurface surface = (PenSurface) read(activity, "canvas");
        int initial = (Integer) read(activity, "index");
        int initialTarget = (Integer) read(activity, "targetIndex");
        int finishedColor = surface.getInkColor();
        SharedPreferences matches = (SharedPreferences) read(activity, "matches");
        Map<String, ?> before = new HashMap<>(matches.getAll());
        SharedPreferences visibility = activity.getSharedPreferences("preview-visibility-v1", 0);
        Map<String, ?> visibilityBefore = new HashMap<>(visibility.getAll());
        for (PreviewPalette.Candidate candidate : activity.candidates)
            check(!"invisible".equals(visibility.getString(candidate.hex(), "")), "Invisible request was not filtered");
        check(activity.finishedColors.size() == 6 && activity.finishedColors.get(0).color == 0xffc7d8e8
            && activity.finishedColors.get(1).color == 0xff9cbbd6, "Filtering removed finished colors");
        Method receive = PenSurface.class.getDeclaredMethod("receive", int.class,
            float.class, float.class, float.class, float.class);
        receive.setAccessible(true);
        for (int i = 0; i < activity.candidates.size(); i++) {
            final int trial = i;
            test.runOnMainSync(() -> {
                try {
                    ((List<Button>) read(activity, "inkSwatches")).get(trial).performClick();
                    int expectedPreview = activity.candidates.get(trial).color;
                    check((Integer) read(activity, "index") == trial, "Swatch did not select preview");
                    check(surface.getPreviewColor() == expectedPreview, "Wrong preview RGB");
                    check(surface.getInkColor() == finishedColor, "Preview swatch changed finished color");
                    verifyNative(surface);
                    check(((String) read(surface, "failure")).isEmpty(), "SDK failure");
                    int y = 80 + trial * Math.max(1, (surface.getHeight() - 120) / activity.candidates.size());
                    if (trial > 0) check(((Bitmap) read(surface, "bitmap")).getPixel(140, 80) == finishedColor,
                        "Changing preview cleared or recolored existing strokes");
                    receive.invoke(surface, 0, 120f, (float) y, 1f, 1f);
                    receive.invoke(surface, 1, 180f, (float) y, 1f, 1f);
                    receive.invoke(surface, 2, 180f, (float) y, 0f, 1f);
                    check(((Bitmap) read(surface, "bitmap")).getPixel(140, y) == finishedColor,
                        "Final bitmap color mismatch");
                    ((List<Button>) read(activity, "inkSwatches")).get(trial).performClick();
                    check(((Bitmap) read(surface, "bitmap")).getPixel(140, y) == finishedColor,
                        "Reselecting current swatch cleared the drawing");
                    verifyNative(surface);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            test.waitForIdleSync();
            check((Integer) read(activity, "index") == trial, "Delayed picker event changed candidate");
        }
        test.runOnMainSync(() -> {
            try {
                int anotherTarget = (initialTarget + 1) % 6;
                activity.selectTarget(anotherTarget);
                activity.selectTrial(0);
                int expectedPreview = activity.candidates.get(0).color;
                int expected = activity.finishedColors.get(anotherTarget).color;
                receive.invoke(surface, 0, 120f, 120f, 1f, 1f);
                receive.invoke(surface, 1, 180f, 120f, 1f, 1f);
                receive.invoke(surface, 2, 180f, 120f, 0f, 1f);
                check(((Bitmap) read(surface, "bitmap")).getPixel(140, 120) == expected,
                    "Changing finished target did not update final pixels");
                surface.pause();
                check(read(surface, "helper") == null, "Raw helper survived pause");
                surface.resume();
                verifyNative(surface);
                check(surface.getPreviewColor() == expectedPreview && surface.getInkColor() == expected,
                    "Pause/resume lost the independent colors");
                activity.selectTarget(initialTarget);
                activity.selectTrial(initial);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        test.waitForIdleSync();
        check((Integer) read(activity, "index") == initial, "Selected candidate was not restored");
        check(before.equals(matches.getAll()), "Checks changed saved preferences");
        check(visibilityBefore.equals(visibility.getAll()), "Visibility results changed");
        check(power.isInteractive(), "Tablet slept during tests");
        return "PASS: " + activity.candidates.size() + " RGB requests, native input/render before and after "
            + "synthetic pen-up, independent preview selection, fixed final RGB, preserved strokes, "
            + "finished-target change, invisible requests filtered, all six finished colors retained, unchanged results/favorites, pause/resume. "
            + "Native panel colors require physical pen observations.";
    }
    private static void verifyNative(PenSurface surface) throws Exception {
        TouchHelper helper = (TouchHelper) read(surface, "helper");
        check(helper != null && helper.isRawDrawingCreated() && helper.isRawDrawingInputEnabled()
            && helper.isRawDrawingRenderEnabled(), "Native input/render unavailable");
    }
    private static Object read(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

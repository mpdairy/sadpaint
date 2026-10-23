package dev.tilesmile;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Button;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** UI flow fixtures are isolated from the user's physical observations. */
final class VisibilityChecks {
    static String run(Instrumentation test) throws Exception {
        VisibilityActivity activity = (VisibilityActivity) test.startActivitySync(
            new Intent(test.getTargetContext(), VisibilityActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.waitForIdleSync();
        PenSurface surface = (PenSurface) read(activity, "canvas");
        SharedPreferences real = (SharedPreferences) read(activity, "results");
        Map<String, ?> before = new HashMap<>(real.getAll());
        int initial = (Integer) read(activity, "index");
        SharedPreferences fixture = activity.getSharedPreferences("visibility-ui-check-only", 0);
        fixture.edit().clear().commit();
        try {
            test.runOnMainSync(() -> {
                try {
                    field(activity, "results").set(activity, fixture);
                    activity.selectTrial(0);
                    Button yes = (Button) read(activity, "visible"), no = (Button) read(activity, "invisible");
                    check(!yes.isEnabled() && !no.isEnabled(), "Answers enabled before pen stroke");
                    Method receive = PenSurface.class.getDeclaredMethod("receive", int.class,
                        float.class, float.class, float.class, float.class);
                    receive.setAccessible(true);
                    receive.invoke(surface, 0, 120f, 120f, 1f, 1f);
                    receive.invoke(surface, 1, 180f, 120f, 1f, 1f);
                    receive.invoke(surface, 2, 180f, 120f, 0f, 1f);
                    check(!yes.isEnabled() && !no.isEnabled(), "Bitmap replay unlocked physical answers");
                    activity.record(true);
                    check(!fixture.contains(activity.candidates.get(0).hex()), "Ungated answer was saved");
                    PenSurface.RawStrokeListener listener = (PenSurface.RawStrokeListener) read(surface, "rawStrokeListener");
                    // Explicit UI fixture, never a real visibility measurement.
                    listener.finished(activity.candidates.get(0).color);
                    check(yes.isEnabled() && no.isEnabled(), "Completed-stroke notification did not unlock answers");
                    yes.performClick();
                    check("visible".equals(fixture.getString(activity.candidates.get(0).hex(), "")), "Visible not saved");
                    check((Integer) read(activity, "index") == 1, "Did not advance after answer");
                    check(!yes.isEnabled() && !no.isEnabled(), "New sample accepted stale stroke");
                    listener.finished(activity.candidates.get(1).color);
                    no.performClick();
                    check("invisible".equals(fixture.getString(activity.candidates.get(1).hex(), "")), "Invisible not saved");
                    check((Integer) read(activity, "index") == 2, "Second answer did not advance");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        } finally {
            test.runOnMainSync(() -> {
                try {
                    field(activity, "results").set(activity, real);
                    activity.selectTrial(initial);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            fixture.edit().clear().commit();
            activity.deleteSharedPreferences("visibility-ui-check-only");
        }
        check(before.equals(real.getAll()), "User observations changed during UI checks");
        return "PASS: physical-stroke gate, replay rejection, Visible/Invisible save and advance, "
            + "fresh stroke required per sample, unchanged user observations. Fixtures were isolated and deleted.";
    }
    private static Field field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static Object read(Object object, String name) throws Exception { return field(object, name).get(object); }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

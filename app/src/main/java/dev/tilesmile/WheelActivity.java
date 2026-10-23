package dev.tilesmile;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.onyx.android.sdk.api.device.epd.EpdController;
import com.onyx.android.sdk.api.device.epd.UpdateMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Continuous color wheel to see how smooth gradients render on the panel; tap
 * to read a color. "Pure" is HSV (hue around, white center fading out to full
 * color) at an adjustable brightness. "Lights + darks" is HSL: white center,
 * pure hues halfway out, black rim, with adjustable saturation for muted tones.
 * "Mixed" has the same layout but draws only the pure hue plus white or black
 * pixels in a 1 px ordered mix, which the panel shows cleaner than blends.
 * "Clean mix" is the same, but its hues are the most colorful calibration pick
 * in each 30° hue sector, mixed with their neighbors: arbitrary pure hues get
 * dithered by the panel itself, and the two dithers interfere. Its pure band
 * is wide and its rim only 60% black; see cleanEdgeFraction.
 */
public class WheelActivity extends Activity {
    private static final String[] MODES = {"Pure", "Lights + darks", "Mixed", "Clean mix"};
    private int mode;
    /** Brightness in pure mode, saturation in the other modes. */
    private float value = 1f, saturation = 1f;
    private Button less, more;
    private TextView title;
    private WheelView wheel;
    private int tapped;
    /** Clean-mix anchors sorted by OKLab hue: colors and their hues in degrees. */
    private int[] anchors = new int[0];
    private float[] anchorHues = new float[0];

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        LinearLayout bar = new LinearLayout(this);
        bar.addView(button("Mode", v -> { mode = (mode + 1) % MODES.length; redraw(); }));
        less = button("", v -> step(-0.1f));
        more = button("", v -> step(0.1f));
        bar.addView(less);
        bar.addView(more);
        title = new TextView(this);
        title.setTextColor(Color.BLACK);
        title.setTextSize(15);
        title.setPadding(12, 0, 12, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(button("Refresh", v -> cleanRefresh()));
        bar.addView(button("Done", v -> finish()));
        root.addView(bar);
        wheel = new WheelView(this);
        root.addView(wheel, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        loadAnchors();
        updateTitle();
    }

    private Button button(String text, View.OnClickListener click) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setOnClickListener(click);
        return button;
    }

    private static float clampStep(float next) { return Math.max(0.1f, Math.min(1f, Math.round(next * 10) / 10f)); }

    private void step(float delta) {
        if (mode > 0) saturation = clampStep(saturation + delta);
        else value = clampStep(value + delta);
        redraw();
    }

    private void redraw() {
        wheel.bitmap = null;
        updateTitle();
        wheel.invalidate();
        wheel.postDelayed(this::cleanRefresh, 300);
    }

    private void updateTitle() {
        less.setText(mode > 0 ? "Less color" : "Darker");
        more.setText(mode > 0 ? "More color" : "Brighter");
        title.setText(String.format(Locale.US, "%s, %s%s", MODES[mode], mode == 3
                ? anchors.length + " hues from your picks"
                : String.format(Locale.US, "%s %d%%", mode > 0 ? "color" : "brightness", Math.round((mode > 0 ? saturation : value) * 100)),
            tapped == 0 ? " • tap the wheel to read a color" : " • tapped #" + Palette.hex(tapped)));
    }

    private void loadAnchors() {
        // Best pick per 30° sector: colorful and mid-light, so neighbors match in brightness.
        int[] best = new int[12];
        double[] bestScore = new double[12];
        for (int color : CalibrateActivity.load(this)) {
            double[] lab = CalibrationColors.oklab(color);
            double chroma = Math.hypot(lab[1], lab[2]), score = chroma * (1 - 1.5 * Math.abs(lab[0] - 0.7));
            int sector = (int) (hue(lab) / 30) % 12;
            if (chroma > 0.08 && score > bestScore[sector]) { best[sector] = color; bestScore[sector] = score; }
        }
        // Drop an anchor within 20° of a better-scoring one; near-equal hues make hard edges.
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            if (bestScore[i] <= 0) continue;
            double h = hue(CalibrationColors.oklab(best[i]));
            boolean beaten = false;
            for (int j = 0; j < 12; j++) {
                if (j == i || bestScore[j] <= 0) continue;
                double gap = Math.abs(h - hue(CalibrationColors.oklab(best[j])));
                if (Math.min(gap, 360 - gap) < 20 && bestScore[j] > bestScore[i]) beaten = true;
            }
            if (!beaten) found.add(best[i]);
        }
        anchors = new int[found.size()];
        anchorHues = new float[found.size()];
        for (int i = 0; i < anchors.length; i++) {
            anchors[i] = found.get(i);
            anchorHues[i] = (float) hue(CalibrationColors.oklab(anchors[i]));
        }
    }

    private static double hue(double[] lab) { return (Math.toDegrees(Math.atan2(lab[2], lab[1])) + 360) % 360; }

    private void cleanRefresh() {
        try { EpdController.refreshScreen(wheel, UpdateMode.GC); }
        catch (RuntimeException | LinkageError e) { Log.e("TileSmile", "Wheel refresh", e); }
    }

    private final class WheelView extends View {
        Bitmap bitmap;

        WheelView(Context context) { super(context); }

        private float radius() { return Math.min(getWidth(), getHeight()) / 2f - 16; }

        /** Nominal color at (dx, dy) from the center, or 0 outside the wheel. */
        private int colorAt(float dx, float dy) {
            float r = radius(), d = (float) Math.hypot(dx, dy);
            if (d > r) return 0;
            float hue = (float) ((Math.toDegrees(Math.atan2(-dy, dx)) + 360) % 360), t = d / r;
            if (mode == 0) return Color.HSVToColor(new float[] {hue, t, value});
            if (mode == 1) return hsl(hue, saturation, lightness(t));
            if (mode == 2) return hsl(hue, saturation, 1 - t);
            return cleanMix(hue, t, -1, -1);
        }

        /**
         * Clean-mix wheel at wheel angle {@code hue} and radius fraction {@code t}:
         * the two neighboring anchors, plus white inside or black outside. Returns
         * the ordered 1 px pixel at (x, y), or the average color when x &lt; 0.
         */
        /**
         * Fraction of white (inner) or black (outer) pixels at radius fraction t.
         * The panel's colors are soft, so pure anchors get a wide band (0.3 to
         * 0.65), and black stops at 60%: past that the rim reads as coal.
         */
        private float cleanEdgeFraction(float t) {
            if (t < 0.3f) return 1 - t / 0.3f;
            if (t <= 0.65f) return 0;
            return 0.6f * (t - 0.65f) / 0.35f;
        }

        private int cleanMix(float hue, float t, int x, int y) {
            int n = anchors.length;
            if (n == 0) return Color.GRAY;
            int next = 0;
            while (next < n && anchorHues[next] <= hue) next++;
            int a = (next + n - 1) % n, b = next % n;
            float span = (anchorHues[b] - anchorHues[a] + 360) % 360, into = (hue - anchorHues[a] + 360) % 360;
            float w = n == 1 || span == 0 ? 0 : into / span;
            int edge = t <= 0.5f ? Color.WHITE : Color.BLACK;
            float k = cleanEdgeFraction(t), wa = (1 - k) * (1 - w), wb = (1 - k) * w;
            if (x < 0) {
                return Color.rgb(Math.round(k * Color.red(edge) + wa * Color.red(anchors[a]) + wb * Color.red(anchors[b])),
                    Math.round(k * Color.green(edge) + wa * Color.green(anchors[a]) + wb * Color.green(anchors[b])),
                    Math.round(k * Color.blue(edge) + wa * Color.blue(anchors[a]) + wb * Color.blue(anchors[b])));
            }
            float u = (GradientActivity.BAYER[y % 8][x % 8] + 0.5f) / 64;
            return u < k ? edge : u < k + wa ? anchors[a] | 0xff000000 : anchors[b] | 0xff000000;
        }

        /** Displayed pixel at screen (x, y): the nominal color, or in mixed mode its 1 px mix. */
        private int pixelAt(int x, int y, float dx, float dy) {
            if (mode < 2) return colorAt(dx, dy);
            float r = radius(), d = (float) Math.hypot(dx, dy);
            if (d > r) return 0;
            float hue = (float) ((Math.toDegrees(Math.atan2(-dy, dx)) + 360) % 360), t = d / r;
            if (mode == 3) return cleanMix(hue, t, x, y);
            int pure = hsl(hue, saturation, 0.5f);
            return t <= 0.5f ? GradientActivity.mix(pure, Color.WHITE, 1 - 2 * t, x, y)
                : GradientActivity.mix(pure, Color.BLACK, 2 * t - 1, x, y);
        }

        /**
         * White center to pure hue at half radius, then darkening to HSL
         * lightness 0.2 at the rim (HSV brightness 40% at full saturation).
         * On the panel, full-strength colors below about 40% brightness all
         * read as near-black, so the wheel stops there.
         */
        private float lightness(float t) {
            return t <= 0.5f ? 1 - t : 0.5f - 0.3f * (t - 0.5f) / 0.5f;
        }

        private int hsl(float hue, float s, float l) {
            float c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs(hue / 60 % 2 - 1)), m = l - c / 2;
            float r, g, b;
            if (hue < 60) { r = c; g = x; b = 0; }
            else if (hue < 120) { r = x; g = c; b = 0; }
            else if (hue < 180) { r = 0; g = c; b = x; }
            else if (hue < 240) { r = 0; g = x; b = c; }
            else if (hue < 300) { r = x; g = 0; b = c; }
            else { r = c; g = 0; b = x; }
            return Color.rgb(Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255));
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                int[] pixels = new int[w * h];
                float cx = w / 2f, cy = h / 2f;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int color = pixelAt(x, y, x - cx, y - cy);
                        pixels[y * w + x] = color == 0 ? Color.WHITE : color;
                    }
                }
                bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
            }
            canvas.drawBitmap(bitmap, 0, 0, null);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
            int color = colorAt(event.getX() - getWidth() / 2f, event.getY() - getHeight() / 2f);
            if (color != 0) {
                tapped = color;
                updateTitle();
            }
            performClick();
            return true;
        }

        @Override public boolean performClick() { return super.performClick(); }
    }
}

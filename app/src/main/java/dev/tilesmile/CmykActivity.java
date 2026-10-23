package dev.tilesmile;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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

/**
 * CMYK pixel-mixing experiment. Top: candidate cyans, magentas and yellows;
 * tap the most solid, vivid one per row. Below: 1 px ordered mixes of only the
 * chosen C, M, Y plus black, each beside the plain RGB average it approximates.
 * Side-by-side pixels average rather than layer like ink, so mixes lose chroma.
 */
public class CmykActivity extends Activity {
    private static final String PREFS = "cmyk-v1";
    /** Candidate variations: the "off" channel, then the two "on" channels. */
    private static final int[] OFF = {0x00, 0x44};
    private static final int[][] ON = {{0xFF, 0xFF}, {0xFF, 0xCC}, {0xCC, 0xFF}, {0xDD, 0xDD}, {0xBB, 0xBB}};
    private static final String[] NAMES = {"Cyan", "Magenta", "Yellow"};
    private final int[][] candidates = new int[3][];
    private final int[] chosen = new int[3];
    private SharedPreferences prefs;
    private SheetView sheet;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        for (int p = 0; p < 3; p++) {
            candidates[p] = new int[OFF.length * ON.length];
            int i = 0;
            for (int off : OFF) for (int[] on : ON) candidates[p][i++] = primary(p, off, on[0], on[1]);
            chosen[p] = prefs.getInt(NAMES[p], candidates[p][0]);
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        LinearLayout bar = new LinearLayout(this);
        TextView title = new TextView(this);
        title.setTextColor(Color.BLACK);
        title.setTextSize(15);
        title.setPadding(12, 0, 12, 0);
        title.setText("Tap the most solid, vivid swatch in each top row (framed = chosen). "
            + "Below: pixel mix (left) vs. plain blend (right).");
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(button("Refresh", v -> cleanRefresh()));
        bar.addView(button("Done", v -> finish()));
        root.addView(bar);
        sheet = new SheetView(this);
        root.addView(sheet, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        sheet.postDelayed(this::cleanRefresh, 500);
    }

    /** Cyan (p=0), magenta (1) or yellow (2) with the given off and on channel values. */
    static int primary(int p, int off, int a, int b) {
        if (p == 0) return Color.rgb(off, a, b);
        if (p == 1) return Color.rgb(a, off, b);
        return Color.rgb(a, b, off);
    }

    private Button button(String text, View.OnClickListener click) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setOnClickListener(click);
        return button;
    }

    private void cleanRefresh() {
        try { EpdController.refreshScreen(sheet, UpdateMode.GC); }
        catch (RuntimeException | LinkageError e) { Log.e("TileSmile", "CMYK refresh", e); }
    }

    /** Ordered 1 px mix of {@code colors} with {@code weights} (summing to 1) at pixel (x, y). */
    static int mix(int[] colors, float[] weights, int x, int y) {
        float u = (GradientActivity.BAYER[y % 8][x % 8] + 0.5f) / 64, sum = 0;
        for (int i = 0; i < colors.length; i++) {
            sum += weights[i];
            if (u < sum) return colors[i] | 0xff000000;
        }
        return colors[colors.length - 1] | 0xff000000;
    }

    static int average(int[] colors, float[] weights) {
        float r = 0, g = 0, b = 0;
        for (int i = 0; i < colors.length; i++) {
            r += weights[i] * Color.red(colors[i]);
            g += weights[i] * Color.green(colors[i]);
            b += weights[i] * Color.blue(colors[i]);
        }
        return Color.rgb(Math.round(r), Math.round(g), Math.round(b));
    }

    /** A labeled mix: color indexes into {C, M, Y, K} with weights. */
    private static final class Recipe {
        final String name; final int[] parts; final float[] weights;
        Recipe(String name, int[] parts, float... weights) { this.name = name; this.parts = parts; this.weights = weights; }
    }

    private static final Recipe[] RECIPES = {
        new Recipe("C+M", new int[]{0, 1}, .5f, .5f), new Recipe("M+Y", new int[]{1, 2}, .5f, .5f),
        new Recipe("Y+C", new int[]{2, 0}, .5f, .5f), new Recipe("C+M+Y", new int[]{0, 1, 2}, 1 / 3f, 1 / 3f, 1 / 3f),
        new Recipe("2C+M", new int[]{0, 1}, 2 / 3f, 1 / 3f), new Recipe("C+2M", new int[]{0, 1}, 1 / 3f, 2 / 3f),
        new Recipe("2M+Y", new int[]{1, 2}, 2 / 3f, 1 / 3f), new Recipe("M+2Y", new int[]{1, 2}, 1 / 3f, 2 / 3f),
        new Recipe("2Y+C", new int[]{2, 0}, 2 / 3f, 1 / 3f), new Recipe("Y+2C", new int[]{2, 0}, 1 / 3f, 2 / 3f),
        new Recipe("C+25%K", new int[]{0, 3}, .75f, .25f), new Recipe("M+25%K", new int[]{1, 3}, .75f, .25f),
        new Recipe("Y+25%K", new int[]{2, 3}, .75f, .25f), new Recipe("C+50%K", new int[]{0, 3}, .5f, .5f),
        new Recipe("M+50%K", new int[]{1, 3}, .5f, .5f), new Recipe("Y+50%K", new int[]{2, 3}, .5f, .5f),
    };

    private final class SheetView extends View {
        private static final int RECIPE_COLUMNS = 4;
        private final Paint fill = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG), frame = new Paint();
        private Bitmap mixes;
        private int mixKey;

        SheetView(Context context) {
            super(context);
            text.setColor(Color.BLACK);
            text.setTextSize(24);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(8);
        }

        // Layout: 3 picker rows, a label gap, 2 hue strips, then recipe rows.
        private float pickerHeight() { return getHeight() * 0.09f; }
        private float stripTop() { return 3 * pickerHeight() + 40; }
        private float stripHeight() { return getHeight() * 0.07f; }
        private float recipesTop() { return stripTop() + 2 * stripHeight() + 50; }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            canvas.drawColor(Color.WHITE);
            float cellW = (float) w / candidates[0].length, ph = pickerHeight();
            for (int p = 0; p < 3; p++) {
                for (int i = 0; i < candidates[p].length; i++) {
                    float left = i * cellW, top = p * ph;
                    fill.setColor(candidates[p][i]);
                    canvas.drawRect(left + 6, top + 6, left + cellW - 6, top + ph - 6, fill);
                    if (candidates[p][i] == chosen[p]) {
                        frame.setColor(Color.BLACK);
                        canvas.drawRect(left + 2, top + 2, left + cellW - 2, top + ph - 2, frame);
                    }
                }
            }
            int key = chosen[0] * 31 * 31 + chosen[1] * 31 + chosen[2];
            if (mixes == null || mixes.getWidth() != w || mixes.getHeight() != h || key != mixKey) {
                mixes = renderMixes(w, h);
                mixKey = key;
            }
            canvas.drawBitmap(mixes, 0, 0, null);
            canvas.drawText("Rainbow from C, M, Y only: pixel mix (top) vs. plain blend (bottom)", 8, stripTop() - 12, text);
            int rows = (RECIPES.length + RECIPE_COLUMNS - 1) / RECIPE_COLUMNS;
            float rw = (float) w / RECIPE_COLUMNS, rh = (h - recipesTop()) / rows;
            for (int i = 0; i < RECIPES.length; i++)
                canvas.drawText(RECIPES[i].name, (i % RECIPE_COLUMNS) * rw + 12, recipesTop() + (i / RECIPE_COLUMNS) * rh + 28, text);
        }

        private Bitmap renderMixes(int w, int h) {
            int[] pixels = new int[w * h];
            int[] cmyk = {chosen[0], chosen[1], chosen[2], Color.BLACK};
            // Hue strips: around C -> M -> Y -> C, two neighbors at a time.
            int s0 = Math.round(stripTop()), sh = Math.round(stripHeight());
            for (int x = 0; x < w; x++) {
                float pos = 3f * x / w;
                int a = (int) pos % 3, b = (a + 1) % 3;
                float f = pos - (int) pos;
                int[] pair = {cmyk[a], cmyk[b]};
                float[] weights = {1 - f, f};
                int blend = average(pair, weights);
                for (int y = s0; y < s0 + sh - 6; y++) pixels[y * w + x] = mix(pair, weights, x, y);
                for (int y = s0 + sh; y < s0 + 2 * sh - 6; y++) pixels[y * w + x] = blend;
            }
            // Recipes: pixel mix on the left half of each cell, plain blend on the right.
            int rows = (RECIPES.length + RECIPE_COLUMNS - 1) / RECIPE_COLUMNS;
            float rw = (float) w / RECIPE_COLUMNS, rh = (h - recipesTop()) / rows;
            for (int i = 0; i < RECIPES.length; i++) {
                Recipe recipe = RECIPES[i];
                int[] colors = new int[recipe.parts.length];
                for (int j = 0; j < colors.length; j++) colors[j] = cmyk[recipe.parts[j]];
                int blend = average(colors, recipe.weights);
                int left = Math.round((i % RECIPE_COLUMNS) * rw) + 8, right = Math.round((i % RECIPE_COLUMNS + 1) * rw) - 8;
                int top = Math.round(recipesTop() + (i / RECIPE_COLUMNS) * rh) + 40, bottom = Math.round(recipesTop() + (i / RECIPE_COLUMNS + 1) * rh) - 8;
                int middle = (left + right) / 2;
                for (int y = top; y < bottom; y++)
                    for (int x = left; x < right; x++)
                        pixels[y * w + x] = x < middle - 3 ? mix(colors, recipe.weights, x, y) : x > middle + 3 ? blend : 0;
            }
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
            return bitmap;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
            int p = (int) (event.getY() / pickerHeight());
            int i = (int) (event.getX() / (getWidth() / (float) candidates[0].length));
            if (p >= 0 && p < 3 && i >= 0 && i < candidates[p].length) {
                chosen[p] = candidates[p][i];
                prefs.edit().putInt(NAMES[p], chosen[p]).apply();
                invalidate();
                postDelayed(CmykActivity.this::cleanRefresh, 300);
            }
            performClick();
            return true;
        }

        @Override public boolean performClick() { return super.performClick(); }
    }
}

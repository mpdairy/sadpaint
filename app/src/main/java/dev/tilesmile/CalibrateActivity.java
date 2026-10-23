package dev.tilesmile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Physical color calibration: one sheet per hue family; tap every swatch that
 * looks clean on the panel. The last page reviews all picks, sorted so
 * look-alikes sit together; tap to drop duplicates. Only picked colors are
 * offered in the palette editor.
 */
public class CalibrateActivity extends Activity {
    static final String PREFS = "swatch-library-v1", KEY = "good";
    private static final int REVIEW = CalibrationColors.PAGES;
    private SharedPreferences prefs;
    private final Set<Integer> good = new HashSet<>();
    private int page;
    private int[] colors = new int[0];
    private TextView title;
    private GridView grid;

    static Set<Integer> load(Context context) {
        Set<Integer> result = new HashSet<>();
        for (String hex : context.getSharedPreferences(PREFS, 0).getStringSet(KEY, new HashSet<>())) {
            try { result.add(Integer.parseInt(hex, 16) | 0xff000000); } catch (NumberFormatException ignored) { }
        }
        return result;
    }

    static void save(Context context, Set<Integer> colors) {
        Set<String> hex = new HashSet<>();
        for (int c : colors) hex.add(Palette.hex(c));
        context.getSharedPreferences(PREFS, 0).edit().putStringSet(KEY, hex).apply();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        good.addAll(load(this));
        page = Math.max(0, Math.min(REVIEW, prefs.getInt("page", 0)));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        LinearLayout bar = new LinearLayout(this);
        bar.addView(button("◀ Prev", v -> show(page - 1)));
        title = new TextView(this);
        title.setTextColor(Color.BLACK);
        title.setTextSize(15);
        title.setPadding(12, 0, 12, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(button("Refresh", v -> cleanRefresh()));
        bar.addView(button("Next ▶", v -> show(page + 1)));
        bar.addView(button("Done", v -> finish()));
        root.addView(bar);
        LinearLayout note = new LinearLayout(this);
        TextView setting = new TextView(this);
        setting.setTextColor(0xff444444);
        setting.setTextSize(13);
        setting.setPadding(12, 0, 12, 0);
        setting.setText("Calibrating for the E-Ink display setting \"Vivid\". Changing that setting "
            + "changes which colors look clean, so redo this if you switch.");
        note.addView(setting, new LinearLayout.LayoutParams(0, -2, 1));
        note.addView(button("Reset all", v -> new AlertDialog.Builder(this)
            .setMessage("Forget all " + good.size() + " picked colors and start over?")
            .setPositiveButton("Reset", (d, w) -> {
                good.clear();
                prefs.edit().remove(KEY).apply();
                show(0);
            })
            .setNegativeButton("Cancel", null)
            .show()));
        root.addView(note);
        grid = new GridView(this);
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        show(page);
    }

    private Button button(String text, View.OnClickListener click) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setOnClickListener(click);
        return button;
    }

    private void show(int next) {
        page = Math.max(0, Math.min(REVIEW, next));
        prefs.edit().putInt("page", page).apply();
        if (page == REVIEW) {
            List<Integer> sorted = CalibrationColors.sortForReview(good);
            colors = new int[sorted.size()];
            for (int i = 0; i < colors.length; i++) colors[i] = sorted.get(i);
        } else {
            colors = CalibrationColors.page(page);
        }
        updateTitle();
        grid.invalidate();
        grid.postDelayed(this::cleanRefresh, 300);
    }

    private void updateTitle() {
        int picked = 0;
        for (int color : colors) if (good.contains(color)) picked++;
        title.setText(page == REVIEW
            ? String.format(Locale.US, "Review: %d picked. Tap look-alikes to drop them (✓ = kept).", picked)
            : String.format(Locale.US, "Page %d/%d: %s • tap clean-looking swatches • %d picked here, %d total",
                page + 1, CalibrationColors.PAGES, CalibrationColors.pageName(page), picked, good.size()));
    }

    private void toggle(int color) {
        if (!good.remove(color)) good.add(color);
        save(this, good);
        updateTitle();
        grid.invalidate();
    }

    private void cleanRefresh() {
        try { EpdController.refreshScreen(grid, UpdateMode.GC); }
        catch (RuntimeException | LinkageError e) { Log.e("TileSmile", "Calibration refresh", e); }
    }

    private final class GridView extends View {
        private final Paint fill = new Paint(), mark = new Paint(Paint.ANTI_ALIAS_FLAG);

        GridView(Context context) {
            super(context);
            mark.setStrokeWidth(8);
        }

        private int columns() {
            return page == REVIEW ? Math.max(CalibrationColors.COLUMNS,
                (int) Math.ceil(Math.sqrt(colors.length * getWidth() / (double) Math.max(1, getHeight()))))
                : CalibrationColors.COLUMNS;
        }

        private int rows() { return Math.max(1, (colors.length + columns() - 1) / columns()); }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.WHITE);
            int columns = columns(), rows = page == REVIEW ? rows() : CalibrationColors.ROWS;
            float w = (float) getWidth() / columns, h = (float) getHeight() / rows, gap = 8;
            mark.setTextSize(Math.min(w, h) * 0.18f);
            for (int i = 0; i < colors.length; i++) {
                float left = (i % columns) * w, top = (i / columns) * h;
                fill.setColor(colors[i]);
                canvas.drawRect(left + gap, top + gap, left + w - gap, top + h - gap, fill);
                if (!good.contains(colors[i])) continue;
                // Mark with a contrasting check and frame, kept to the edge to avoid hiding the color.
                boolean light = CalibrationColors.oklab(colors[i])[0] > 0.6;
                mark.setColor(light ? Color.BLACK : Color.WHITE);
                mark.setStyle(Paint.Style.STROKE);
                canvas.drawRect(left + gap + 4, top + gap + 4, left + w - gap - 4, top + h - gap - 4, mark);
                mark.setStyle(Paint.Style.FILL);
                canvas.drawText("✓", left + gap + 14, top + gap + mark.getTextSize() + 8, mark);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
            int columns = columns(), rows = page == REVIEW ? rows() : CalibrationColors.ROWS;
            int column = (int) (event.getX() / (getWidth() / (float) columns));
            int row = (int) (event.getY() / (getHeight() / (float) rows));
            int index = row * columns + column;
            if (column >= 0 && column < columns && index >= 0 && index < colors.length) {
                toggle(colors[index]);
                performClick();
            }
            return true;
        }

        @Override public boolean performClick() { return super.performClick(); }
    }
}

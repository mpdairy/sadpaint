package dev.tilesmile;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
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
 * Tests the guess that colors whose channels are multiples of 0x11 (the 16
 * levels of a "4096 color" panel) render without dithering. Each "on" row is
 * snapped to that grid; the "off" row below it puts every channel halfway
 * between two levels. Judge after the clean refresh.
 */
public class GridTestActivity extends Activity {
    static final class Row {
        final String label;
        final int[] colors;
        Row(String label, int[] colors) { this.label = label; this.colors = colors; }
    }

    private View sheet;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Palette palette = Palette.parse(getSharedPreferences("paint-v1", MODE_PRIVATE)
            .getString("palette", null), 0);
        List<Row> rows = rows(palette);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        LinearLayout bar = new LinearLayout(this);
        TextView help = new TextView(this);
        help.setTextColor(Color.BLACK);
        help.setTextSize(14);
        help.setPadding(dp(8), 0, dp(8), 0);
        help.setText("ON = snapped to the 16-level grid (00 11 22 … FF). off = halfway between levels. "
            + "Is ON smoother / less grainy than off?");
        bar.addView(help, new LinearLayout.LayoutParams(0, -2, 1));
        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> cleanRefresh());
        bar.addView(refresh);
        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        root.addView(bar);
        sheet = new SheetView(this, rows);
        root.addView(sheet, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        sheet.postDelayed(this::cleanRefresh, 500);
    }

    private void cleanRefresh() {
        try { EpdController.refreshScreen(sheet, UpdateMode.GC); }
        catch (RuntimeException | LinkageError e) { Log.e("TileSmile", "Grid test refresh", e); }
    }

    static List<Row> rows(Palette palette) {
        List<Row> rows = new ArrayList<>();
        int[] grays = new int[16], grayMid = new int[15];
        for (int i = 0; i < 16; i++) grays[i] = i * 0x111111;
        for (int i = 0; i < 15; i++) grayMid[i] = (i * 17 + 8) * 0x010101;
        rows.add(new Row("gray ON", grays));
        rows.add(new Row("gray off", grayMid));
        int[] original = new int[palette.count()], snapped = new int[original.length];
        int n = 0;
        for (Palette.Entry entry : palette.entries) if (entry != null) original[n++] = entry.color;
        for (int i = 0; i < original.length; i++) snapped[i] = snap(original[i]);
        rows.add(new Row("your palette", original));
        rows.add(new Row("snapped ON", snapped));
        String[] names = {"red", "orange", "yellow", "green", "cyan", "blue", "violet", "magenta"};
        int[] hues = {0xff0000, 0xff8800, 0xffff00, 0x00ff00, 0x00ffff, 0x0000ff, 0x8800ff, 0xff00ff};
        float[] steps = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f};
        for (int h = 0; h < hues.length; h++) {
            int[] on = new int[steps.length], off = new int[steps.length];
            for (int s = 0; s < steps.length; s++) {
                on[s] = snap(ramp(hues[h], steps[s]));
                off[s] = between(on[s]);
            }
            rows.add(new Row(names[h] + " ON", on));
            rows.add(new Row(names[h] + " off", off));
        }
        return rows;
    }

    /** 0..1 darkens from black to the hue, 1..2 lightens from the hue to white. */
    static int ramp(int hue, float position) {
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int c = (hue >> shift) & 0xff;
            float v = position <= 1 ? c * position : c + (255 - c) * (position - 1);
            out |= Math.round(Math.max(0, Math.min(255, v))) << shift;
        }
        return out;
    }

    /** Nearest color whose channels are all multiples of 0x11. */
    static int snap(int color) {
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8)
            out |= Math.round(((color >> shift) & 0xff) / 17f) * 17 << shift;
        return out;
    }

    /** Moves every channel halfway to the neighboring level. */
    static int between(int onGrid) {
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int c = (onGrid >> shift) & 0xff;
            out |= (c == 255 ? c - 8 : c + 8) << shift;
        }
        return out;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    static final class SheetView extends View {
        private final List<Row> rows;
        private final Paint fill = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG);

        SheetView(Context context, List<Row> rows) {
            super(context);
            this.rows = rows;
            text.setColor(Color.BLACK);
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.WHITE);
            float labelWidth = getWidth() * 0.11f, gap = 6;
            float rowHeight = (float) getHeight() / rows.size();
            float hexHeight = Math.min(26, rowHeight * 0.24f);
            text.setTextSize(hexHeight * 0.85f);
            for (int r = 0; r < rows.size(); r++) {
                Row row = rows.get(r);
                float top = r * rowHeight;
                // Rows come in pairs (grid, then off-grid); separate each pair.
                if (r % 2 == 0 && r > 0 && row.label.endsWith("ON")) canvas.drawLine(0, top, getWidth(), top, text);
                text.setTextSize(Math.min(30, rowHeight * 0.3f));
                canvas.drawText(row.label, 8, top + rowHeight * 0.45f, text);
                text.setTextSize(hexHeight * 0.85f);
                float cell = (getWidth() - labelWidth) / row.colors.length;
                for (int i = 0; i < row.colors.length; i++) {
                    float left = labelWidth + i * cell;
                    fill.setColor(row.colors[i] | 0xff000000);
                    canvas.drawRect(left + gap / 2, top + gap / 2, left + cell - gap / 2,
                        top + rowHeight - hexHeight - gap, fill);
                    canvas.drawText(String.format(Locale.US, "%06X", row.colors[i] & 0xffffff),
                        left + gap, top + rowHeight - gap, text);
                }
            }
        }
    }
}

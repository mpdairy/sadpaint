package dev.tilesmile;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
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

/**
 * Blue-to-black gradient test: each blue is drawn as a solid RGB blend and as
 * ordered (Bayer) mixes of only the pure blue and pure black pixels, in 1, 2 and
 * 4 pixel cells, to see whether pixel mixing gives smoother darks on the panel.
 */
public class GradientActivity extends Activity {
    static final int[] BLUES = {0x0085C4, 0x4D69FF, 0x88C8F5};
    static final int[] CELLS = {0, 1, 2, 4}; // 0 = solid blend
    static final int[][] BAYER = {
        {0, 32, 8, 40, 2, 34, 10, 42}, {48, 16, 56, 24, 50, 18, 58, 26},
        {12, 44, 4, 36, 14, 46, 6, 38}, {60, 28, 52, 20, 62, 30, 54, 22},
        {3, 35, 11, 43, 1, 33, 9, 41}, {51, 19, 59, 27, 49, 17, 57, 25},
        {15, 47, 7, 39, 13, 45, 5, 37}, {63, 31, 55, 23, 61, 29, 53, 21},
    };
    private StripsView strips;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        LinearLayout bar = new LinearLayout(this);
        TextView title = new TextView(this);
        title.setTextColor(Color.BLACK);
        title.setTextSize(15);
        title.setPadding(12, 0, 12, 0);
        title.setText("Blue → black, left to right. Per blue: solid blend, then blue/black pixel mixes in 1, 2, 4 px cells.");
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(button("Refresh", v -> cleanRefresh()));
        bar.addView(button("Done", v -> finish()));
        root.addView(bar);
        strips = new StripsView(this);
        root.addView(strips, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        strips.postDelayed(this::cleanRefresh, 500);
    }

    private Button button(String text, View.OnClickListener click) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setOnClickListener(click);
        return button;
    }

    private void cleanRefresh() {
        try { EpdController.refreshScreen(strips, UpdateMode.GC); }
        catch (RuntimeException | LinkageError e) { Log.e("TileSmile", "Gradient refresh", e); }
    }

    /** Pixel at (x, y) of a strip fading {@code blue} to black; {@code f} is the black fraction. */
    static int pixel(int blue, int cell, float f, int x, int y) {
        if (cell == 0) {
            float k = 1 - f;
            return Color.rgb(Math.round(Color.red(blue) * k), Math.round(Color.green(blue) * k),
                Math.round(Color.blue(blue) * k));
        }
        return mix(blue, Color.BLACK, f, x / cell, y / cell);
    }

    /** Ordered 8x8 Bayer mix at cell (x, y): {@code other} covers fraction {@code f}, else {@code base}. */
    static int mix(int base, int other, float f, int x, int y) {
        return (BAYER[y % 8][x % 8] + 0.5f) / 64 < f ? other | 0xff000000 : base | 0xff000000;
    }

    private static final class StripsView extends View {
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap bitmap;

        StripsView(Context context) {
            super(context);
            text.setColor(Color.BLACK);
            text.setTextSize(26);
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight(), label = 150, gap = 10;
            int rows = BLUES.length * CELLS.length;
            float rowHeight = (float) h / rows;
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                int[] pixels = new int[w * h];
                java.util.Arrays.fill(pixels, Color.WHITE);
                int span = w - label - gap;
                for (int row = 0; row < rows; row++) {
                    int blue = BLUES[row / CELLS.length], cell = CELLS[row % CELLS.length];
                    // Extra space above each new blue group.
                    int top = Math.round(row * rowHeight) + (row % CELLS.length == 0 ? 3 * gap : gap / 2);
                    int bottom = Math.round((row + 1) * rowHeight) - gap / 2;
                    for (int y = top; y < bottom; y++)
                        for (int x = label; x < w - gap; x++)
                            pixels[y * w + x] = pixel(blue, cell, (x - label) / (float) (span - 1), x, y);
                }
                bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
            }
            canvas.drawBitmap(bitmap, 0, 0, null);
            for (int row = 0; row < rows; row++) {
                int cell = CELLS[row % CELLS.length];
                String name = cell == 0 ? "#" + Palette.hex(BLUES[row / CELLS.length]) : "mix " + cell + "px";
                canvas.drawText(name, 8, row * rowHeight + rowHeight / 2 + 10, text);
            }
        }
    }
}

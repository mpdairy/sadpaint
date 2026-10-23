package dev.tilesmile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * The selected palette color's shades, under the palette and as wide as its
 * grid: one continuous gradient from black at the left through the pure color
 * in the middle to white at the right, drawn in the actual 1 px dither it
 * paints with. Tap or drag along it to choose a shade. A small icon in the
 * margin to its right switches between the dither and the true mixed color.
 */
final class ShadeView extends View {
    interface Listener { void picked(int shade); void toggledSolid(); }

    private final Paint line = new Paint();
    private PaletteView grid;
    private Listener listener;
    private int color = Color.BLACK, shade;
    private Bitmap bitmap;
    private boolean stale = true, onToggle;

    ShadeView(Context context) { super(context); }

    /** Matches the palette grid's left edge and width. */
    void setGrid(PaletteView grid) { this.grid = grid; }
    void setListener(Listener listener) { this.listener = listener; }

    void set(int color, int shade) {
        if (color == this.color && shade == this.shade) return;
        if (color != this.color) stale = true;
        this.color = color; this.shade = shade;
        invalidate();
    }

    /** The palette grid may change width (rows added or removed). */
    void gridChanged() { stale = true; invalidate(); }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int left() { return Math.round(grid != null && grid.getWidth() > 0 ? grid.gridLeft() : 0) + dp(2); }
    private int span() { return Math.round(grid != null && grid.getWidth() > 0 ? grid.gridWidth() : getWidth()) - dp(4); }
    /** The dither / true-color icon sits in the margin right of the grid. */
    private int toggleLeft() { return left() + span() + dp(2); }
    private int top() { return dp(2); }
    private int bottom() { return getHeight() - dp(2); }

    /** Shade at view position x. */
    private int shadeAt(float x) {
        float t = (x - left()) / Math.max(1, span() - 1);
        return Shade.clamp(Math.round(t * 2 * Shade.STEPS) - Shade.STEPS);
    }

    private float xOf(int shade) { return left() + (shade + Shade.STEPS) / (2f * Shade.STEPS) * (span() - 1) + .5f; }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.WHITE);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
            if (bitmap != null) bitmap.recycle();
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            stale = true;
        }
        int l = left(), r = Math.min(w, l + span()), t = top(), b = bottom();
        if (stale) {
            // Unscaled pixels, so the gradient shows the real 1 px pattern.
            int[] pixels = new int[w * h];
            java.util.Arrays.fill(pixels, Color.WHITE);
            for (int x = Math.max(0, l); x < r; x++) {
                int s = shadeAt(x);
                for (int y = Math.max(0, t); y < Math.min(h, b); y++) pixels[y * w + x] = Shade.pixel(color, s, x, y);
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
            stale = false;
        }
        canvas.drawBitmap(bitmap, 0, 0, null);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1);
        line.setColor(0xff777777);
        canvas.drawRect(l - .5f, t - .5f, r - .5f, b - .5f, line);
        drawToggle(canvas, t, b);
        // Ticks above and below mark the pure color.
        float mid = xOf(0);
        line.setStrokeWidth(dp(2));
        canvas.drawLine(mid, 0, mid, t, line);
        canvas.drawLine(mid, b, mid, h, line);
        // The chosen shade: a black bar edged in white, so it shows on dark and light.
        float x = xOf(shade);
        line.setStyle(Paint.Style.FILL);
        line.setColor(Color.WHITE);
        canvas.drawRect(x - dp(4), 0, x + dp(4), h, line);
        line.setColor(Color.BLACK);
        canvas.drawRect(x - dp(2), 0, x + dp(2), h, line);
    }

    /** A small square: a 2 px checkerboard while dithering, solid gray for true color. */
    private void drawToggle(Canvas canvas, int t, int b) {
        int size = Math.min(dp(20), Math.min(getWidth() - toggleLeft() - dp(2), b - t));
        if (size <= 0) return;
        int l = toggleLeft() + (getWidth() - toggleLeft() - size) / 2, top = (t + b - size) / 2;
        line.setStyle(Paint.Style.FILL);
        if (Shade.solid) {
            line.setColor(0xff808080);
            canvas.drawRect(l, top, l + size, top + size, line);
        } else {
            line.setColor(Color.BLACK);
            for (int y = 0; y < size; y += 2)
                for (int x = (y / 2) % 2 * 2; x < size; x += 4) canvas.drawRect(l + x, top + y, l + x + 2, top + y + 2, line);
        }
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(1));
        line.setColor(Color.BLACK);
        canvas.drawRect(l, top, l + size, top + size, line);
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) { stale = true; }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) onToggle = event.getX() >= toggleLeft();
        if (onToggle) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP && event.getX() >= toggleLeft()) {
                stale = true;
                if (listener != null) listener.toggledSolid();
                invalidate();
                performClick();
            }
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                // Follow the finger; the ink changes on lift.
                int at = shadeAt(event.getX());
                if (at != shade) { shade = at; invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
                shade = shadeAt(event.getX());
                invalidate();
                if (listener != null) listener.picked(shade);
                performClick();
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() { return super.performClick(); }
}

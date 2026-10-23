package dev.tilesmile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.DragEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * A palette's spots as rows of {@link Palette#PER_ROW} square boxes (1–4 rows,
 * centered in the view), empty spots outlined. When arrangeable, drag a
 * color to any spot to move it (swapping with what is there), and picks dragged
 * from a {@link PicksGrid} can be dropped on a spot.
 */
final class PaletteView extends View {
    interface Listener {
        void tapped(int spot);
        /** Only for filled spots. */
        void longPressed(int spot);
        /** After a color was moved by dragging. */
        void moved();
        /** A pick was dropped on {@code spot}. */
        void dropped(int spot, int color);
    }

    private final Paint fill = new Paint(), frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Palette palette = new Palette();
    private Listener listener;
    private boolean showSelection, showAddHint, arrangeable, selectEmpty;
    /** Pressed spot, and the spot a drag would land on (-1 = none). */
    private int downSpot = -1, target = -1;
    private boolean moving, longPressed;
    private float downX, downY;
    private final Runnable longPress = () -> {
        if (downSpot >= 0 && palette.get(downSpot) != null && !moving) {
            longPressed = true;
            if (listener != null) listener.longPressed(downSpot);
        }
    };

    PaletteView(Context context) {
        super(context);
        frame.setStyle(Paint.Style.STROKE);
    }

    void setPalette(Palette palette) { this.palette = palette; invalidate(); }
    void setListener(Listener listener) { this.listener = listener; }
    /** Allow dragging colors between spots and dropping picks on them. */
    void setArrangeable(boolean arrangeable) { this.arrangeable = arrangeable; }
    /** Draw a faint "+" in empty spots (tapping one adds a color). */
    void setShowAddHint(boolean show) { showAddHint = show; invalidate(); }
    /** Frame the selected spot even when it is empty (a cursor for filling spots). */
    void setSelectEmpty(boolean selectEmpty) { this.selectEmpty = selectEmpty; invalidate(); }
    /** Frame the painting color's spot. */
    void setShowSelection(boolean show) { showSelection = show; invalidate(); }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    /** Square cells, as large as both the width and the height allow. */
    private float cell() { return Math.min((float) getWidth() / Palette.PER_ROW, (float) getHeight() / palette.rows()); }
    /** The grid is centered in the view. */
    float gridLeft() { return (getWidth() - cell() * Palette.PER_ROW) / 2; }
    float gridWidth() { return cell() * Palette.PER_ROW; }
    private float gridTop() { return (getHeight() - cell() * palette.rows()) / 2; }

    private int spot(float x, float y) {
        int c = Math.max(0, Math.min(Palette.PER_ROW - 1, (int) ((x - gridLeft()) / cell())));
        int r = Math.max(0, Math.min(palette.rows() - 1, (int) ((y - gridTop()) / cell())));
        return r * Palette.PER_ROW + c;
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.WHITE);
        float w = cell(), h = w, gap = dp(2), x0 = gridLeft(), y0 = gridTop();
        for (int i = 0; i < palette.spots(); i++) {
            float left = x0 + (i % Palette.PER_ROW) * w + gap, top = y0 + (i / Palette.PER_ROW) * h + gap;
            float right = left + w - 2 * gap, bottom = top + h - 2 * gap;
            Palette.Entry entry = palette.get(i);
            if (entry != null) {
                fill.setColor(entry.color);
                canvas.drawRect(left, top, right, bottom, fill);
                frame.setColor(0xff777777);
                frame.setStrokeWidth(1);
                canvas.drawRect(left, top, right, bottom, frame);
            } else {
                frame.setColor(0xffbbbbbb);
                frame.setStrokeWidth(dp(1));
                canvas.drawRect(left, top, right, bottom, frame);
                if (showAddHint) {
                    float cx = (left + right) / 2, cy = (top + bottom) / 2, arm = Math.min(w, h) / 6;
                    canvas.drawLine(cx - arm, cy, cx + arm, cy, frame);
                    canvas.drawLine(cx, cy - arm, cx, cy + arm, frame);
                }
            }
            boolean selected = showSelection && (entry != null || selectEmpty) && i == palette.selected;
            if (selected || i == target) {
                // Black then white frame so it shows on dark and light colors.
                float inset = dp(2);
                frame.setStrokeWidth(dp(4));
                frame.setColor(Color.BLACK);
                canvas.drawRect(left + inset, top + inset, right - inset, bottom - inset, frame);
                inset = dp(5);
                frame.setStrokeWidth(dp(2));
                frame.setColor(Color.WHITE);
                canvas.drawRect(left + inset, top + inset, right - inset, bottom - inset, frame);
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downSpot = spot(event.getX(), event.getY());
                downX = event.getX();
                downY = event.getY();
                moving = longPressed = false;
                target = -1;
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                track(event);
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                // Moves can arrive coalesced or not at all; the lift point decides too.
                track(event);
                if (moving) {
                    if (target >= 0 && target != downSpot) {
                        palette.move(downSpot, target);
                        if (listener != null) listener.moved();
                    }
                } else if (!longPressed && downSpot >= 0 && listener != null) {
                    listener.tapped(downSpot);
                }
                reset();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                reset();
                return true;
            default:
                return true;
        }
    }

    /** Starts a move once the finger leaves the pressed spot's slop, and follows it. */
    private void track(MotionEvent event) {
        if (downSpot < 0 || longPressed) return;
        if (!moving && arrangeable && Math.hypot(event.getX() - downX, event.getY() - downY) > cell() / 3
                && palette.get(downSpot) != null) {
            moving = true;
            removeCallbacks(longPress);
        }
        if (moving) {
            int spot = spot(event.getX(), event.getY());
            if (spot != target) { target = spot; invalidate(); }
        }
    }

    private void reset() {
        downSpot = target = -1;
        moving = longPressed = false;
        invalidate();
    }

    @Override public boolean performClick() { return super.performClick(); }

    /** Picks dragged from a {@link PicksGrid} carry their color as the local state. */
    @Override public boolean onDragEvent(DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return arrangeable && event.getLocalState() instanceof Integer;
            case DragEvent.ACTION_DRAG_LOCATION:
                int spot = spot(event.getX(), event.getY());
                if (spot != target) { target = spot; invalidate(); }
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
            case DragEvent.ACTION_DRAG_ENDED:
                target = -1;
                invalidate();
                return true;
            case DragEvent.ACTION_DROP:
                int at = spot(event.getX(), event.getY());
                target = -1;
                invalidate();
                if (listener != null) listener.dropped(at, (Integer) event.getLocalState());
                return true;
            default:
                return true;
        }
    }
}

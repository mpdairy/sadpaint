package dev.tilesmile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * All calibration picks as solid swatches: one column per hue family (warm,
 * neutral and cool grays last), light to dark unless reordered. Tap selects a
 * color (tap again to clear); drag up or down reorders within its column.
 */
final class PicksGrid extends View {
    interface Listener { void selectionChanged(int color); }

    private static final String ORDER = "order";
    private final List<List<Integer>> columns = new ArrayList<>();
    private final Paint fill = new Paint(), mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Listener listener;
    /** Selected color, or 0. */
    private int selected;
    /** Drag state: the column and row pressed, and the row it would drop at (-1 = not dragging). */
    private int dragColumn = -1, dragRow = -1, dropRow = -1;
    private float downX, downY;
    /** Fixed row height in px, or 0 to fit 32 rows in the view. */
    private float rowHeight;
    /**
     * Tap-only mode: no reordering or selection. A tap is reported to the
     * listener; a drag starts a system drag carrying the color (for a {@link PaletteView}).
     */
    private boolean tapOnly;
    /** Colors drawn with a frame in tap-only mode (e.g. those already in a palette). */
    private Set<Integer> marked = new java.util.HashSet<>();

    /** Picks grouped by hue family, in saved order; picks without a saved spot follow, light to dark. */
    static List<List<Integer>> columns(Context context) {
        Map<Integer, Integer> rank = new HashMap<>();
        String order = context.getSharedPreferences(CalibrateActivity.PREFS, 0).getString(ORDER, "");
        for (String hex : order.split(",")) {
            try { rank.put(Integer.parseInt(hex, 16) | 0xff000000, rank.size()); } catch (NumberFormatException ignored) { }
        }
        List<List<Integer>> families = new ArrayList<>();
        for (int i = 0; i < CalibrationColors.FAMILIES; i++) families.add(new ArrayList<>());
        for (int color : CalibrationColors.sortForReview(CalibrateActivity.load(context)))
            families.get(column(color)).add(color);
        List<List<Integer>> result = new ArrayList<>();
        for (List<Integer> family : families) {
            if (family.isEmpty()) continue;
            List<Integer> ranked = new ArrayList<>(), rest = new ArrayList<>();
            for (int color : family) (rank.containsKey(color) ? ranked : rest).add(color);
            ranked.sort((a, b) -> Integer.compare(rank.get(a), rank.get(b)));
            ranked.addAll(rest);
            result.add(ranked);
        }
        return result;
    }

    /**
     * The picks' hue family, except that the 60° (orange) family shares the
     * 90° column: the only picks there are brown-grays that belong with the tans.
     */
    static int column(int color) {
        int family = CalibrationColors.family(color);
        return family == 2 ? 3 : family;
    }

    PicksGrid(Context context) {
        super(context);
        mark.setStrokeWidth(8);
        columns.addAll(columns(context));
    }

    /** Fixed row height: the view is then exactly as tall as its longest column. */
    void setRowHeight(float px) { rowHeight = px; requestLayout(); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (rowHeight <= 0) { super.onMeasure(widthSpec, heightSpec); return; }
        setMeasuredDimension(MeasureSpec.getSize(widthSpec),
            resolveSize(Math.round(rows() * rowHeight), heightSpec));
    }

    /** Each tap reports its color to the listener; no drag, no selection. */
    void setTapOnly(boolean tapOnly) { this.tapOnly = tapOnly; }

    void setMarked(Set<Integer> colors) { marked = colors; invalidate(); }

    void setListener(Listener listener) { this.listener = listener; }

    int selected() { return selected; }

    void select(int color) {
        selected = color;
        invalidate();
        if (listener != null) listener.selectionChanged(color);
    }

    int total() {
        int total = 0;
        for (List<Integer> column : columns) total += column.size();
        return total;
    }

    /** Removes the selected pick from the calibration library. */
    void deleteSelected() {
        if (selected == 0) return;
        Set<Integer> good = CalibrateActivity.load(getContext());
        good.remove(selected);
        CalibrateActivity.save(getContext(), good);
        for (List<Integer> column : columns) column.remove(Integer.valueOf(selected));
        columns.removeIf(List::isEmpty);
        saveOrder();
        requestLayout();
        select(0);
    }

    private void saveOrder() {
        StringBuilder order = new StringBuilder();
        for (List<Integer> column : columns)
            for (int color : column) order.append(order.length() == 0 ? "" : ",").append(Palette.hex(color));
        getContext().getSharedPreferences(CalibrateActivity.PREFS, Context.MODE_PRIVATE)
            .edit().putString(ORDER, order.toString()).apply();
    }

    private int rows() {
        int rows = 1;
        for (List<Integer> column : columns) rows = Math.max(rows, column.size());
        return rows;
    }

    private float cellWidth() { return (float) getWidth() / Math.max(1, columns.size()); }
    /** Short swatches read better side by side, so rows never get taller than 1/32 of the view. */
    private float cellHeight() { return rowHeight > 0 ? rowHeight : getHeight() / Math.max(32f, rows()); }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.WHITE);
        float w = cellWidth(), h = cellHeight(), gap = 4;
        for (int c = 0; c < columns.size(); c++) {
            List<Integer> column = columns.get(c);
            for (int r = 0; r < column.size(); r++) {
                int color = column.get(r);
                float left = c * w, top = r * h;
                fill.setColor(color);
                canvas.drawRect(left + gap, top + gap, left + w - gap, top + h - gap, fill);
                boolean light = CalibrationColors.oklab(color)[0] > 0.6;
                mark.setColor(light ? Color.BLACK : Color.WHITE);
                if (color == selected || marked.contains(color) || (c == dragColumn && r == dragRow && dropRow >= 0)) {
                    mark.setStyle(Paint.Style.STROKE);
                    canvas.drawRect(left + gap + 4, top + gap + 4, left + w - gap - 4, top + h - gap - 4, mark);
                    mark.setStyle(Paint.Style.FILL);
                }
            }
            if (c == dragColumn && dropRow >= 0) {
                // Insertion bar where the dragged swatch will land.
                float y = (dropRow > dragRow ? dropRow + 1 : dropRow) * h;
                fill.setColor(Color.BLACK);
                canvas.drawRect(c * w, y - 6, (c + 1) * w, y + 6, fill);
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int c = (int) (event.getX() / cellWidth());
        int r = (int) (event.getY() / cellHeight());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragColumn = c >= 0 && c < columns.size() && r >= 0 && r < columns.get(c).size() ? c : -1;
                dragRow = dragColumn >= 0 ? r : -1;
                dropRow = -1;
                downX = event.getX();
                downY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragColumn < 0) return true;
                if (tapOnly) {
                    if (Math.hypot(event.getX() - downX, event.getY() - downY) > cellHeight() / 2) {
                        int color = columns.get(dragColumn).get(dragRow);
                        dragColumn = dragRow = dropRow = -1;
                        startDragAndDrop(null, new SwatchShadow(color), color, 0);
                    }
                    return true;
                }
                if (dropRow < 0 && Math.abs(event.getY() - downY) < cellHeight() / 2) return true;
                int target = Math.max(0, Math.min(columns.get(dragColumn).size() - 1, r));
                if (target != dropRow) {
                    dropRow = target;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (tapOnly) {
                    int tapped = dragColumn >= 0 ? columns.get(dragColumn).get(dragRow) : 0;
                    dragColumn = dragRow = dropRow = -1;
                    if (tapped != 0 && listener != null) listener.selectionChanged(tapped);
                    performClick();
                    return true;
                }
                int chosen = 0;
                if (dragColumn >= 0) {
                    List<Integer> column = columns.get(dragColumn);
                    int color = column.get(dragRow);
                    if (dropRow >= 0 && dropRow != dragRow) {
                        column.remove(dragRow);
                        column.add(dropRow, color);
                        saveOrder();
                    }
                    // A tap toggles selection; a drag selects the moved swatch.
                    chosen = dropRow < 0 && selected == color ? 0 : color;
                }
                dragColumn = dragRow = dropRow = -1;
                select(chosen);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragColumn = dragRow = dropRow = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() { return super.performClick(); }

    /** A framed swatch under the finger while a pick is dragged. */
    private final class SwatchShadow extends DragShadowBuilder {
        private final int color;
        SwatchShadow(int color) { this.color = color; }

        @Override public void onProvideShadowMetrics(android.graphics.Point size, android.graphics.Point touch) {
            int w = Math.round(cellWidth()), h = Math.round(cellHeight() * 1.5f);
            size.set(w, h);
            touch.set(w / 2, h / 2);
        }

        @Override public void onDrawShadow(Canvas canvas) {
            Paint paint = new Paint();
            paint.setColor(color);
            canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), paint);
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(6);
            canvas.drawRect(3, 3, canvas.getWidth() - 3, canvas.getHeight() - 3, paint);
        }
    }
}

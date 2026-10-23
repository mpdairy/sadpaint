package dev.tilesmile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.onyx.android.sdk.api.device.epd.EpdController;
import com.onyx.android.sdk.api.device.epd.UpdateMode;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Popup (dialog-themed) palette builder: the calibration picks grid on top, the
 * palette being edited beneath it. Tap a pick to add it, tap again or tap its
 * box to take it out. Opens on the active painting palette.
 */
public class PaletteBuilderActivity extends Activity {
    private PaletteLibrary library;
    /** Saved name of the palette being edited, or null if it has never been saved. */
    private String name;
    private Palette draft;
    private boolean dirty;
    private PicksGrid grid;
    private PaletteView row;
    private Button nameButton;
    private TextView status, rowLabel, rowsLabel;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        library = PaletteLibrary.load(this);
        android.content.SharedPreferences active = getSharedPreferences(PaletteLibrary.ACTIVE_PREFS, MODE_PRIVATE);
        name = library.migrate(active.getString("palette", null), PaletteLibrary.activeName(this));
        library.save(this);
        draft = Palette.parse(active.getString("palette", null), active.getInt("selected", 0));
        startCursor();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // Solid black frame: e-ink shows no dialog shadow or dimming.
        android.graphics.drawable.GradientDrawable frame = new android.graphics.drawable.GradientDrawable();
        frame.setColor(Color.WHITE);
        frame.setStroke(dp(3), Color.BLACK);
        frame.setCornerRadius(dp(6));
        root.setBackground(frame);
        root.setPadding(dp(12), dp(10), dp(12), dp(4));

        // Same top bar as the Picks screen.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        status = new TextView(this);
        status.setTextColor(Color.BLACK);
        status.setTextSize(15);
        status.setPadding(12, 0, 12, 0);
        top.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        button(top, "Refresh", v -> cleanRefresh());
        button(top, "Done", v -> done());
        root.addView(top);

        grid = new PicksGrid(this);
        // Rows as tall as on the Picks screen, so the palette sits right under the grid.
        grid.setRowHeight(getResources().getDisplayMetrics().heightPixels / 34f);
        grid.setTapOnly(true);
        grid.setListener(this::placePick);
        root.addView(grid, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout paletteBar = new LinearLayout(this);
        paletteBar.setGravity(Gravity.CENTER_VERTICAL);
        paletteBar.setPadding(0, dp(12), 0, 0);
        nameButton = new Button(this);
        nameButton.setAllCaps(false);
        nameButton.setTextSize(17);
        nameButton.setTextColor(Color.BLACK);
        nameButton.setBackgroundColor(Color.TRANSPARENT);
        nameButton.setPadding(12, 0, dp(8), 0);
        nameButton.setMinWidth(0);
        nameButton.setMinimumWidth(0);
        nameButton.setOnClickListener(v -> rename());
        paletteBar.addView(nameButton, new LinearLayout.LayoutParams(-2, -2));
        rowLabel = new TextView(this);
        rowLabel.setTextColor(Color.BLACK);
        rowLabel.setTextSize(15);
        paletteBar.addView(rowLabel, new LinearLayout.LayoutParams(0, -2, 1));
        rowsLabel = new TextView(this);
        rowsLabel.setTextColor(Color.BLACK);
        rowsLabel.setTextSize(15);
        rowsLabel.setPadding(dp(8), 0, dp(4), 0);
        paletteBar.addView(rowsLabel);
        small(paletteBar, "−", v -> changeRows(-1));
        small(paletteBar, "+", v -> changeRows(1));
        button(paletteBar, "Save", v -> save());
        button(paletteBar, "Save as new", v -> saveAsNew());
        button(paletteBar, "Open", v -> showOpen());
        button(paletteBar, "Use", v -> use());
        root.addView(paletteBar);
        row = new PaletteView(this);
        row.setArrangeable(true);
        row.setShowSelection(true);
        row.setSelectEmpty(true);
        row.setListener(new PaletteView.Listener() {
            /** Tapping a color deletes it; either way the tapped spot becomes the selected one. */
            @Override public void tapped(int spot) {
                draft.selected = spot;
                if (draft.get(spot) != null) { draft.set(spot, null); changed(); }
                else refresh();
            }
            @Override public void longPressed(int spot) { }
            @Override public void moved() { changed(); }
            @Override public void dropped(int spot, int color) { dropPick(spot, color); }
        });
        // Fixed height, like the paint screen's bar: more rows make shorter boxes.
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(Palette.MAX_ROWS * 44));
        rowParams.setMargins(0, 0, 0, dp(8));
        root.addView(row, rowParams);

        setContentView(root);
        // A popup over the paint screen; outside taps don't close it, so unsaved edits aren't lost.
        setFinishOnTouchOutside(false);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        getWindow().setLayout(Math.round(getResources().getDisplayMetrics().widthPixels * 0.94f),
            WindowManager.LayoutParams.WRAP_CONTENT);
        refresh();
        grid.postDelayed(this::cleanRefresh, 500);
    }

    private void refresh() {
        status.setText(String.format(Locale.US, "%d picks • tap a color to put it in the selected spot, or drag it to any spot",
            grid.total()));
        nameButton.setText((name == null ? "Untitled" : name) + (dirty ? " •" : "") + " ✎");
        rowLabel.setText(String.format(Locale.US, " %d/%d • tap a color to delete it, a spot to select it, drag to move",
            draft.count(), draft.spots()));
        rowsLabel.setText("Rows " + draft.rows());
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (Palette.Entry entry : draft.entries) if (entry != null) used.add(entry.color);
        grid.setMarked(used);
        row.setPalette(draft);
    }

    private void changed() { dirty = true; refresh(); }

    /** Tapping a pick adds it to the palette, or takes it out if it is already there. */
    /**
     * Puts a tapped pick in the selected spot (replacing what is there; a pick
     * already elsewhere in the palette moves), then selects the next spot.
     */
    private void placePick(int pick) {
        int spot = Math.max(0, Math.min(draft.spots() - 1, draft.selected));
        int index = draft.indexOf(pick);
        if (index != spot) {
            if (index >= 0) draft.set(index, null);
            draft.set(spot, new Palette.Entry(pick, 0));
        }
        if (spot + 1 < draft.spots()) spot++;
        draft.selected = spot;
        changed();
    }

    /** The selection starts at the first empty spot (or the first spot if full). */
    private void startCursor() {
        int empty = draft.firstEmpty();
        draft.selected = empty >= 0 ? empty : 0;
    }

    /**
     * A pick dropped on a spot: if it is already in the palette it moves there
     * (swapping); otherwise it goes there, replacing any color in that spot.
     */
    private void dropPick(int spot, int pick) {
        int index = draft.indexOf(pick);
        if (index == spot) return;
        if (index >= 0) draft.move(index, spot);
        else draft.set(spot, new Palette.Entry(pick, 0));
        changed();
    }

    private void changeRows(int delta) {
        int rows = draft.rows() + delta;
        if (rows < 1 || rows > Palette.MAX_ROWS) return;
        if (delta < 0 && draft.rowUsed(rows)) {
            toast("Row " + (rows + 1) + " has colors. Move or remove them first.");
            return;
        }
        draft.rows = rows;
        draft.selected = Math.min(draft.selected, draft.spots() - 1);
        changed();
    }

    // Saving and switching palettes.

    private boolean canSave() {
        if (draft.count() > 0) return true;
        toast("Add at least one color first.");
        return false;
    }

    /** Stores the draft under {@code as}; keeps painting in sync if it is the active palette. */
    private void store(String as) {
        library.put(as, draft);
        library.save(this);
        name = as;
        dirty = false;
        if (as.equals(PaletteLibrary.activeName(this))) activate();
        refresh();
    }

    /** Makes the draft the painting palette, keeping the painting color selected if it is still there. */
    private void activate() {
        android.content.SharedPreferences prefs = getSharedPreferences(PaletteLibrary.ACTIVE_PREFS, MODE_PRIVATE);
        int painting = Palette.parse(prefs.getString("palette", null), prefs.getInt("selected", 0)).current().color;
        Palette active = draft.copy();
        int spot = draft.indexOf(painting);
        active.selected = spot >= 0 ? spot : active.first();
        PaletteLibrary.activate(this, name, active);
    }

    private void save() {
        if (!canSave()) return;
        if (name == null) saveAsNew();
        else store(name);
    }

    private void saveAsNew() {
        if (!canSave()) return;
        askName("Save as new palette", library.uniqueName(name == null ? "Palette" : name), chosen -> {
            if (library.contains(chosen)) throw new IllegalArgumentException("That name is taken");
            store(chosen);
        });
    }

    private void use() {
        if (!canSave()) return;
        if (name == null) {
            askName("Name this palette", library.uniqueName("Palette"), chosen -> {
                if (library.contains(chosen)) throw new IllegalArgumentException("That name is taken");
                store(chosen);
                activate();
                finish();
            });
            return;
        }
        store(name);
        activate();
        finish();
    }

    private void rename() {
        if (name == null) { saveAsNew(); return; }
        final String from = name;
        askName("Rename palette", from, chosen -> {
            library.rename(from, chosen);
            library.save(this);
            if (from.equals(PaletteLibrary.activeName(this)))
                getSharedPreferences(PaletteLibrary.ACTIVE_PREFS, MODE_PRIVATE).edit().putString("activeName", chosen).apply();
            name = chosen;
            refresh();
        });
    }

    private void showOpen() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(8), dp(16), dp(8));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Open palette")
            .setView(wrap(list))
            .setNeutralButton("New empty", (d, w) -> discardThen(() -> {
                draft = new Palette();
                startCursor();
                name = null;
                dirty = true;
                refresh();
            }))
            .setNegativeButton("Cancel", null).create();
        String activeName = PaletteLibrary.activeName(this);
        for (String saved : library.names()) {
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            Button open = new Button(this);
            open.setAllCaps(false);
            open.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            open.setText(saved + (saved.equals(activeName) ? "  (painting)" : "")
                + String.format(Locale.US, "  · %d colors", library.get(saved).count()));
            open.setOnClickListener(v -> { dialog.dismiss(); discardThen(() -> load(saved)); });
            line.addView(open, new LinearLayout.LayoutParams(0, dp(52), 1));
            if (!saved.equals(activeName)) {
                Button delete = new Button(this);
                delete.setAllCaps(false);
                delete.setText("Delete");
                delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage("Delete palette “" + saved + "”?")
                    .setPositiveButton("Delete", (d, w) -> {
                        library.delete(saved);
                        library.save(this);
                        if (saved.equals(name)) { name = null; dirty = true; refresh(); }
                        list.removeView(line);
                    })
                    .setNegativeButton("Cancel", null).show());
                line.addView(delete, new LinearLayout.LayoutParams(-2, dp(52)));
            }
            list.addView(line);
        }
        dialog.show();
    }

    private void load(String saved) {
        Palette palette = library.get(saved);
        if (palette == null) return;
        draft = palette;
        startCursor();
        name = saved;
        dirty = false;
        refresh();
    }

    private void done() { discardThen(this::finish); }

    @Override public void onBackPressed() { done(); }

    private void discardThen(Runnable action) {
        if (!dirty) { action.run(); return; }
        new AlertDialog.Builder(this).setMessage("Discard unsaved changes to this palette?")
            .setPositiveButton("Discard", (d, w) -> action.run())
            .setNegativeButton("Keep editing", null).show();
    }

    private void askName(String title, String initial, Consumer<String> action) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(initial);
        field.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(field)
            .setPositiveButton("OK", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                action.accept(PaletteLibrary.validName(field.getText().toString()));
                dialog.dismiss();
            } catch (IllegalArgumentException error) { field.setError(error.getMessage()); }
        }));
        dialog.show();
    }

    private void toast(String text) { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show(); }

    private View wrap(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        return scroll;
    }

    private void small(LinearLayout parent, String text, View.OnClickListener click) {
        Button button = new Button(this);
        button.setText(text);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setOnClickListener(click);
        parent.addView(button, new LinearLayout.LayoutParams(dp(48), -2));
    }

    private Button button(LinearLayout parent, String text, View.OnClickListener click) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setOnClickListener(click);
        parent.addView(button);
        return button;
    }

    private void cleanRefresh() {
        try { EpdController.refreshScreen(getWindow().getDecorView(), UpdateMode.GC); }
        catch (RuntimeException | LinkageError e) { Log.e("TileSmile", "Palette builder refresh", e); }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}

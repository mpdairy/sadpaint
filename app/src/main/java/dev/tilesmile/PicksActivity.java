package dev.tilesmile;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.onyx.android.sdk.api.device.epd.EpdController;
import com.onyx.android.sdk.api.device.epd.UpdateMode;
import java.util.Locale;

/** All calibration picks ({@link PicksGrid}): review, reorder and delete them. */
public class PicksActivity extends Activity {
    private TextView title;
    private Button delete;
    private PicksGrid grid;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        LinearLayout bar = new LinearLayout(this);
        title = new TextView(this);
        title.setTextColor(Color.BLACK);
        title.setTextSize(15);
        title.setPadding(12, 0, 12, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        delete = button("Delete", v -> grid.deleteSelected());
        bar.addView(delete);
        bar.addView(button("Refresh", v -> cleanRefresh()));
        bar.addView(button("Done", v -> finish()));
        root.addView(bar);
        grid = new PicksGrid(this);
        grid.setListener(color -> refresh());
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        refresh();
        grid.postDelayed(this::cleanRefresh, 500);
    }

    private void refresh() {
        int selected = grid.selected();
        delete.setVisibility(selected != 0 ? View.VISIBLE : View.GONE);
        title.setText(String.format(Locale.US, "%d picks • drag up/down to reorder%s", grid.total(),
            selected == 0 ? " • tap to select" : " • selected #" + Palette.hex(selected)));
    }

    private Button button(String text, View.OnClickListener click) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setOnClickListener(click);
        return button;
    }

    private void cleanRefresh() {
        try { EpdController.refreshScreen(grid, UpdateMode.GC); }
        catch (RuntimeException | LinkageError e) { Log.e("TileSmile", "Picks refresh", e); }
    }
}

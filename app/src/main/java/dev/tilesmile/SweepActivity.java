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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Steps one color family from dark to light in increments of 2 so the panel's
 * real level boundaries (where hue or brightness jumps) can be read off by eye.
 */
public class SweepActivity extends Activity {
    private static final String[] NAMES = {"Yellow", "Gray", "Magenta", "Cyan"};
    /** Channel mask per family: which of R, G, B follow the sweep value. */
    private static final int[] MASKS = {0x010100, 0x010101, 0x010001, 0x000101};
    private LinearLayout root;
    private View sheet;
    private TextView title;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        LinearLayout bar = new LinearLayout(this);
        title = new TextView(this);
        title.setTextColor(Color.BLACK);
        title.setTextSize(14);
        title.setPadding(8, 0, 8, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        for (int i = 0; i < NAMES.length; i++) {
            final int family = i;
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setText(NAMES[i]);
            button.setOnClickListener(v -> show(family));
            bar.addView(button);
        }
        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> cleanRefresh());
        bar.addView(refresh);
        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        root.addView(bar);
        setContentView(root);
        show(0);
    }

    private void show(int family) {
        if (sheet != null) root.removeView(sheet);
        title.setText(NAMES[family] + " sweep, steps of 2. Where does the hue or brightness jump?");
        sheet = new GridTestActivity.SheetView(this, rows(MASKS[family]));
        root.addView(sheet, new LinearLayout.LayoutParams(-1, 0, 1));
        sheet.postDelayed(this::cleanRefresh, 500);
    }

    static List<GridTestActivity.Row> rows(int mask) {
        List<GridTestActivity.Row> rows = new ArrayList<>();
        for (int start = 0; start < 256; start += 16) {
            int[] colors = new int[8];
            for (int i = 0; i < 8; i++) colors[i] = (start + i * 2) * mask;
            rows.add(new GridTestActivity.Row(String.format(Locale.US, "%02X–%02X", start, start + 14), colors));
        }
        return rows;
    }

    private void cleanRefresh() {
        try { EpdController.refreshScreen(sheet, UpdateMode.GC); }
        catch (RuntimeException | LinkageError e) { Log.e("TileSmile", "Sweep refresh", e); }
    }
}

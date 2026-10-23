package dev.tilesmile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private PenSurface canvas;
    private static final String[] INK_NAMES = {"Pale blue", "Light blue", "Blue", "Deep blue", "Blue-gray", "Black"};
    private static final int[] INK_COLORS = {0xffc7d8e8, 0xff9cbbd6, 0xff6f9ec5, 0xff315f88, 0xff566b78, 0xff000000};
    private final Button[] swatches = new Button[INK_COLORS.length];

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xffffffff);
        TextView status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(0xff000000);
        status.setPadding(12, 4, 12, 4);
        status.setMinLines(3);
        canvas = new PenSurface(this, status);
        if (PreviewPreferences.hasKnownBlue(this)) canvas.selectMode(0);

        LinearLayout modes = new LinearLayout(this);
        String[] names = {"A: Native color", "B: Live bitmap", "C: Black preview", "Android control"};
        for (int i = 0; i < names.length; i++) {
            final int mode = i;
            Button button = new Button(this);
            button.setText(names[i]);
            button.setTextSize(12);
            button.setOnClickListener(v -> canvas.selectMode(mode));
            modes.addView(button, new LinearLayout.LayoutParams(0,
                Math.round(56 * getResources().getDisplayMetrics().density), 1));
        }
        root.addView(modes);
        LinearLayout palette = new LinearLayout(this);
        palette.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < INK_COLORS.length; i++) {
            final int ink = i;
            Button swatch = new Button(this);
            swatch.setAllCaps(false);
            swatch.setTextSize(13);
            swatch.setTextColor(i < 3 ? 0xff000000 : 0xffffffff);
            swatch.setPadding(dp(4), 0, dp(4), 0);
            swatch.setStateListAnimator(null);
            swatch.setOnClickListener(v -> {
                canvas.selectColor(INK_COLORS[ink]);
                canvas.selectPreviewColor(PreviewPreferences.choose(this, INK_COLORS[ink]));
                updateSwatches();
            });
            swatches[i] = swatch;
            LinearLayout.LayoutParams slot = new LinearLayout.LayoutParams(0, dp(56), 1);
            slot.setMargins(dp(3), 0, dp(3), 0);
            palette.addView(swatch, slot);
        }
        updateSwatches();
        root.addView(palette);
        LinearLayout actions = new LinearLayout(this);
        Button clear = new Button(this);
        clear.setText("Clear");
        clear.setOnClickListener(v -> canvas.clear());
        actions.addView(clear);
        Button refresh = new Button(this);
        refresh.setText("Clean refresh");
        refresh.setOnClickListener(v -> canvas.cleanRefresh());
        actions.addView(refresh);
        CheckBox live = new CheckBox(this);
        live.setText("Live stats");
        live.setChecked(false);
        live.setOnCheckedChangeListener((v, checked) -> canvas.liveStats = checked);
        actions.addView(live);
        CheckBox fast = new CheckBox(this);
        fast.setText("Fast ink");
        fast.setChecked(true);
        fast.setOnCheckedChangeListener((v, checked) -> canvas.setFastUpdates(checked));
        actions.addView(fast);
        root.addView(actions);
        Button visibility = new Button(this);
        visibility.setText("Preview visibility test");
        visibility.setOnClickListener(v -> startActivity(new Intent(this, VisibilityActivity.class)));
        root.addView(visibility, new LinearLayout.LayoutParams(-1, dp(48)));
        Button preview = new Button(this);
        preview.setText("Match preview colors");
        preview.setOnClickListener(v -> startActivity(new Intent(this, PreviewActivity.class)));
        root.addView(preview, new LinearLayout.LayoutParams(-1, dp(48)));
        root.addView(status);
        root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void updateSwatches() {
        for (int i = 0; i < swatches.length; i++) {
            boolean selected = canvas.getInkColor() == INK_COLORS[i];
            swatches[i].setText((selected ? "✓ " : "") + INK_NAMES[i]);
            swatches[i].setSelected(selected);
            swatches[i].setContentDescription(INK_NAMES[i] + (selected ? ", selected" : ""));
            GradientDrawable background = new GradientDrawable();
            background.setColor(INK_COLORS[i]);
            background.setCornerRadius(dp(4));
            background.setStroke(dp(selected ? 3 : 1), selected ? 0xff000000 : 0xff777777);
            swatches[i].setBackground(background);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        canvas.selectPreviewColor(PreviewPreferences.choose(this, canvas.getInkColor()));
        canvas.resume();
    }
    @Override protected void onPause() { canvas.pause(); super.onPause(); }
    @Override protected void onDestroy() { canvas.release(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (canvas != null) canvas.focus(focused);
    }
}

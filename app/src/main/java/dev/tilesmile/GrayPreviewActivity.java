package dev.tilesmile;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Physical test of how dark each gray fast-pen request really looks while
 * drawing. The user draws with one request, then taps the finished gray swatch
 * that matched the live stroke. Saved in {@link PreviewColors#APPARENT_PREFS}.
 */
public class GrayPreviewActivity extends Activity {
    /** Intent extra: true tests the bright colored requests instead of the grays. */
    static final String EXTRA_LIGHT = "light";
    private int[] requests;
    private PenSurface canvas;
    private SharedPreferences results;
    private TextView sample, progress;
    private LinearLayout answers;
    private int index;
    private boolean penStrokeSeen;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        results = getSharedPreferences(PreviewColors.APPARENT_PREFS, MODE_PRIVATE);
        boolean light = getIntent().getBooleanExtra(EXTRA_LIGHT, false);
        requests = light ? PreviewColors.LIGHT_REQUESTS : PreviewColors.GRAY_REQUESTS;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.addView(label(light
            ? "Light colors: draw a stroke. WHILE the pen is moving, how LIGHT does it look?\n"
                + "Tap the gray of the same lightness, whatever its hue. Ignore how it looks after you lift."
            : "Draw a stroke below. WHILE the pen is moving, which gray does it look like?\n"
                + "Tap that swatch. Ignore how it looks after you lift.", 20));
        sample = label("", 18);
        root.addView(sample);
        progress = label("", 16);
        progress.setMinLines(2);
        root.addView(progress);
        answers = new LinearLayout(this);
        for (int gray : PreviewColors.REFERENCE_GRAYS) {
            Button swatch = new Button(this);
            swatch.setStateListAnimator(null);
            swatch.setAllCaps(false);
            swatch.setTextSize(13);
            swatch.setText(Palette.hex(gray).substring(0, 2));
            swatch.setTextColor(PreviewColors.lab(gray)[0] > 55 ? Color.BLACK : Color.WHITE);
            GradientDrawable background = new GradientDrawable();
            background.setColor(gray);
            background.setStroke(dp(1), Color.BLACK);
            swatch.setBackground(background);
            swatch.setOnClickListener(v -> record(Palette.hex(gray)));
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(96), 1);
            cell.setMargins(dp(2), 0, dp(2), 0);
            answers.addView(swatch, cell);
        }
        root.addView(answers);
        LinearLayout actions = new LinearLayout(this);
        action(actions, "Invisible", () -> record("invisible"));
        action(actions, "Previous", () -> selectTrial((index + requests.length - 1) % requests.length));
        action(actions, "Skip", () -> selectTrial((index + 1) % requests.length));
        action(actions, "Clear + refresh", () -> { penStrokeSeen = false; canvas.clear(); canvas.cleanRefresh(); updateProgress(); });
        action(actions, "Back", this::finish);
        root.addView(actions);
        TextView status = label("", 12);
        canvas = new PenSurface(this, status);
        canvas.setStrokeWidth(96);   // wide strokes make the live gray easy to judge
        canvas.selectMode(0);
        canvas.setRawStrokeListener(color -> {
            if (color == (requests[index] | 0xff000000)) { penStrokeSeen = true; updateProgress(); }
        });
        root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        int first = 0;
        while (first < requests.length - 1 && results.contains(key(first))) first++;
        selectTrial(first);
    }

    private String key(int i) { return Palette.hex(requests[i]); }

    private void selectTrial(int next) {
        index = next;
        penStrokeSeen = false;
        int request = requests[index] | 0xff000000;
        canvas.selectColor(request);
        canvas.selectPreviewColor(request);
        canvas.clear();
        sample.setText("Sample " + (index + 1) + " of " + requests.length
            + ": request #" + key(index) + " (after you lift, the stroke is redrawn in exactly this color)");
        updateProgress();
    }

    private void record(String answer) {
        if (!penStrokeSeen) return;
        results.edit().putString(key(index), answer)
            .putLong(key(index) + ":time", System.currentTimeMillis())
            .putString(key(index) + ":firmware", Build.FINGERPRINT).apply();
        for (int offset = 1; offset <= requests.length; offset++) {
            int next = (index + offset) % requests.length;
            if (!results.contains(key(next))) { selectTrial(next); return; }
        }
        penStrokeSeen = false;
        updateProgress();
    }

    private void updateProgress() {
        int done = 0;
        for (int i = 0; i < requests.length; i++) if (results.contains(key(i))) done++;
        progress.setText(done + "/" + requests.length + " done, saved automatically. This one: "
            + results.getString(key(index), "not tested") + ".\n"
            + (penStrokeSeen ? "Now tap the matching gray (or Invisible)."
                : done == requests.length ? "All done: the painting app now uses these answers."
                : "Draw one stroke first."));
        for (int i = 0; i < answers.getChildCount(); i++) answers.getChildAt(i).setEnabled(penStrokeSeen);
    }

    private TextView label(String text, int size) {
        TextView label = new TextView(this);
        label.setText(text); label.setTextSize(size); label.setTextColor(Color.BLACK);
        label.setPadding(dp(8), dp(5), dp(8), dp(5));
        return label;
    }

    private void action(LinearLayout row, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text); button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        button.setGravity(Gravity.CENTER);
        row.addView(button, new LinearLayout.LayoutParams(0, dp(52), 1));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onResume() { super.onResume(); canvas.resume(); }
    @Override protected void onPause() { canvas.pause(); super.onPause(); }
    @Override protected void onDestroy() { canvas.release(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (canvas != null) canvas.focus(focused);
    }
}

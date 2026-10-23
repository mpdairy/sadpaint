package dev.tilesmile;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** A physical pen test: no RGB request is automatically labeled visible. */
public class VisibilityActivity extends Activity {
    final List<PreviewPalette.Candidate> candidates = PreviewPalette.create();
    private PenSurface canvas;
    private SharedPreferences results;
    private TextView sample, progress;
    private Button visible, invisible;
    private int index;
    private boolean penStrokeSeen;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Start with a black control, then test the remaining RGB requests.
        candidates.add(0, candidates.remove(5));
        results = getSharedPreferences("preview-visibility-v1", MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        TextView instruction = label("Draw below. Did ink appear WHILE your pen was moving?\n"
            + "Ignore the color that appears after you lift.", 22);
        root.addView(instruction);
        sample = label("", 20);
        root.addView(sample, new LinearLayout.LayoutParams(-1, dp(62)));
        progress = label("", 17);
        progress.setMinLines(3);
        root.addView(progress);
        LinearLayout answers = new LinearLayout(this);
        visible = button("VISIBLE", () -> record(true));
        invisible = button("INVISIBLE", () -> record(false));
        visible.setTextSize(28);
        invisible.setTextSize(28);
        answers.addView(visible, new LinearLayout.LayoutParams(0, dp(100), 1));
        answers.addView(invisible, new LinearLayout.LayoutParams(0, dp(100), 1));
        root.addView(answers);
        LinearLayout actions = new LinearLayout(this);
        action(actions, "Previous", () -> selectTrial((index + candidates.size() - 1) % candidates.size()));
        action(actions, "Skip", () -> selectTrial((index + 1) % candidates.size()));
        action(actions, "Clear + refresh", () -> {
            penStrokeSeen = false;
            canvas.clear(); canvas.cleanRefresh(); updateProgress();
        });
        action(actions, "Back", this::finish);
        root.addView(actions);
        TextView status = label("", 14);
        status.setMinLines(3);
        canvas = new PenSurface(this, status);
        canvas.selectMode(0);
        canvas.setRawStrokeListener(color -> {
            if (color == candidates.get(index).color) {
                penStrokeSeen = true;
                updateProgress();
            }
        });
        root.addView(status);
        root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        selectTrial(Math.max(0, Math.min(candidates.size() - 1, results.getInt("position", 0))));
    }

    void selectTrial(int next) {
        index = next;
        penStrokeSeen = false;
        PreviewPalette.Candidate candidate = candidates.get(index);
        canvas.selectColor(candidate.color);
        canvas.selectPreviewColor(candidate.color);
        canvas.clear();
        sample.setText("Sample " + (index + 1) + " of " + candidates.size() + ": " + candidate);
        sample.setTextColor(Color.luminance(candidate.color) > .179f ? Color.BLACK : Color.WHITE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(candidate.color);
        background.setStroke(dp(1), Color.BLACK);
        sample.setBackground(background);
        results.edit().putInt("position", index).apply();
        updateProgress();
    }

    void record(boolean wasVisible) {
        if (!penStrokeSeen) return;
        String key = candidates.get(index).hex();
        results.edit().putString(key, wasVisible ? "visible" : "invisible")
            .putLong(key + ":time", System.currentTimeMillis())
            .putString(key + ":firmware", Build.FINGERPRINT).apply();
        for (int offset = 1; offset <= candidates.size(); offset++) {
            int next = (index + offset) % candidates.size();
            if (!results.contains(candidates.get(next).hex())) { selectTrial(next); return; }
        }
        penStrokeSeen = false;
        updateProgress();
    }

    private void updateProgress() {
        int yes = 0, no = 0;
        for (PreviewPalette.Candidate candidate : candidates) {
            String answer = results.getString(candidate.hex(), "");
            if (answer.equals("visible")) yes++;
            if (answer.equals("invisible")) no++;
        }
        String answer = results.getString(candidates.get(index).hex(), "not tested");
        boolean done = yes + no == candidates.size();
        progress.setText((yes + no) + "/" + candidates.size() + " tested — " + yes + " visible, " + no
            + " invisible. Saved automatically.\nThis sample: " + answer + ".\n"
            + (penStrokeSeen ? "Tap VISIBLE or INVISIBLE to save and move to the next sample."
                : done ? "All samples tested. Visible samples may include duplicate preview colors."
                : "Draw one stroke first, then choose. Stop whenever you like; progress is saved."));
        visible.setEnabled(penStrokeSeen);
        invisible.setEnabled(penStrokeSeen);
    }

    private TextView label(String text, int size) {
        TextView label = new TextView(this);
        label.setText(text); label.setTextSize(size); label.setTextColor(Color.BLACK);
        label.setPadding(dp(8), dp(5), dp(8), dp(5));
        return label;
    }
    private Button button(String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text); button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        return button;
    }
    private void action(LinearLayout row, String text, Runnable action) {
        row.addView(button(text, action), new LinearLayout.LayoutParams(0, dp(48), 1));
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

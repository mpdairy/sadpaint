package dev.tilesmile;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Compare fast native preview requests against one fixed finished ink color. */
public class PreviewActivity extends Activity {
    final List<PreviewPalette.Candidate> candidates = PreviewPalette.create();
    final List<PreviewPalette.Candidate> finishedColors = new ArrayList<>(candidates.subList(0, 6));
    private final List<Button> inkSwatches = new ArrayList<>();
    private PenSurface canvas;
    private SharedPreferences matches, visibilityResults;
    private Spinner targetPicker;
    private TextView targetReference, previewReference, favorite;
    private int index = -1, targetIndex = -1;
    private int hiddenCount;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        matches = getSharedPreferences("preview-matches-v1", MODE_PRIVATE);
        visibilityResults = getSharedPreferences("preview-visibility-v1", MODE_PRIVATE);
        int total = candidates.size();
        candidates.removeIf(candidate -> "invisible".equals(visibilityResults.getString(candidate.hex(), "")));
        hiddenCount = total - candidates.size();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        if (candidates.isEmpty()) {
            root.addView(label("All preview requests were marked invisible. Recheck them in Preview visibility test."));
            root.addView(button("Back", this::finish));
            setContentView(root);
            return;
        }
        root.addView(label("A preview is chosen automatically. Draw to try it; changing swatches is optional. "
            + "After lifting, strokes become the fixed finished color."));
        root.addView(label("Finished color — stays fixed while you try preview swatches:"));
        targetPicker = new Spinner(this);
        List<String> targets = new ArrayList<>();
        for (PreviewPalette.Candidate color : finishedColors) targets.add(color.toString());
        targetPicker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, targets));
        root.addView(targetPicker, new LinearLayout.LayoutParams(-1, dp(44)));
        targetReference = label("");
        root.addView(targetReference, new LinearLayout.LayoutParams(-1, dp(44)));
        previewReference = label("");
        root.addView(previewReference);
        for (int row = 0; row < (candidates.size() + 8) / 9; row++) {
            LinearLayout choices = new LinearLayout(this);
            for (int col = 0; col < 9; col++) {
                LinearLayout.LayoutParams slot = new LinearLayout.LayoutParams(0, dp(48), 1);
                slot.setMargins(dp(2), dp(2), dp(2), dp(2));
                if (row * 9 + col >= candidates.size()) {
                    choices.addView(new View(this), slot);
                    continue;
                }
                Button button = button("", () -> { });
                button.setBackgroundTintList(null);
                button.setStateListAnimator(null);
                inkSwatches.add(button);
                choices.addView(button, slot);
            }
            root.addView(choices);
        }
        LinearLayout actions = new LinearLayout(this);
        addAction(actions, "Save favorite", this::saveFavorite);
        addAction(actions, "Clear + refresh", () -> { canvas.clear(); canvas.cleanRefresh(); });
        addAction(actions, "Back", this::finish);
        root.addView(actions);
        favorite = label("");
        root.addView(favorite);
        TextView status = label("");
        status.setMinLines(3);
        canvas = new PenSurface(this, status);
        canvas.selectMode(0);
        root.addView(status);
        root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        selectTarget(Math.max(0, Math.min(5, matches.getInt("target", 0))));
        targetPicker.setOnItemSelectedListener(selection(position -> {
            if (position == targetPicker.getSelectedItemPosition() && position != targetIndex) selectTarget(position);
        }));
    }

    void selectTarget(int target) {
        targetIndex = target;
        PreviewPalette.Candidate finished = finishedColors.get(target);
        targetPicker.setSelection(target);
        canvas.selectColor(finished.color);
        canvas.clear();
        targetReference.setText("AFTER LIFTING: " + finished);
        targetReference.setTextColor(contrastingText(finished.color));
        targetReference.setBackground(background(finished.color, false));
        matches.edit().putInt("target", target).apply();
        int preferred = PreviewPreferences.choose(this, finished.color);
        int preferredIndex = 0;
        for (int i = 0; i < candidates.size(); i++)
            if (candidates.get(i).color == preferred) preferredIndex = i;
        selectTrial(preferredIndex);
    }

    void selectTrial(int next) {
        index = next;
        canvas.selectPreviewColor(candidates.get(index).color);
        previewReference.setText("WHILE DRAWING: preview request " + candidates.get(index).hex()
            + " — " + candidates.size() + " requests below; " + hiddenCount + " marked invisible hidden.");
        for (int slot = 0; slot < inkSwatches.size(); slot++) {
            Button button = inkSwatches.get(slot);
            final int candidateIndex = slot;
            PreviewPalette.Candidate candidate = candidates.get(candidateIndex);
            boolean selected = candidateIndex == index;
            button.setText((selected ? "✓ " : "") + candidate.name + "\n" + candidate.hex());
            button.setTextColor(contrastingText(candidate.color));
            button.setSelected(selected);
            button.setBackground(background(candidate.color, selected));
            button.setOnClickListener(v -> { if (candidateIndex != index) selectTrial(candidateIndex); });
        }
        updateFavorite();
    }

    void saveFavorite() {
        matches.edit().putInt(favoriteKey(), candidates.get(index).color).apply();
        updateFavorite();
    }
    private String favoriteKey() { return "favorite:" + finishedColors.get(targetIndex).hex(); }
    private void updateFavorite() {
        String savedHex = String.format(Locale.US, "#%06X", matches.getInt(favoriteKey(), 0) & 0xffffff);
        if (matches.contains(favoriteKey()) && "invisible".equals(visibilityResults.getString(savedHex, ""))) {
            favorite.setText("Your saved preview was marked invisible. Try another and save a new favorite.");
            return;
        }
        favorite.setText(matches.contains(favoriteKey())
            ? String.format(Locale.US, "Saved preview for this finished color: #%06X. Keep trying or save another.",
                matches.getInt(favoriteKey(), 0) & 0xffffff)
            : "Using the automatic preview. Save a favorite only if you prefer another; no matching survey required.");
    }
    private GradientDrawable background(int color, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(4));
        background.setStroke(dp(selected ? 3 : 1), Color.BLACK);
        return background;
    }
    private static int contrastingText(int color) {
        return Color.luminance(color) > 0.179f ? Color.BLACK : Color.WHITE;
    }
    private interface Selection { void select(int position); }
    private AdapterView.OnItemSelectedListener selection(Selection action) {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                action.select(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
    }
    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(15); view.setTextColor(Color.BLACK);
        view.setPadding(dp(8), dp(4), dp(8), dp(4));
        return view;
    }
    private Button button(String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title); button.setTextSize(12); button.setAllCaps(false);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setOnClickListener(v -> action.run());
        return button;
    }
    private void addAction(LinearLayout row, String title, Runnable action) {
        row.addView(button(title, action), new LinearLayout.LayoutParams(0, dp(48), 1));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onResume() { super.onResume(); if (canvas != null) canvas.resume(); }
    @Override protected void onPause() { if (canvas != null) canvas.pause(); super.onPause(); }
    @Override protected void onDestroy() { if (canvas != null) canvas.release(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (canvas != null) canvas.focus(focused);
    }
}

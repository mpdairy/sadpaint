package dev.tilesmile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * The painting app, laid out like the Manta app: the palette along the top bar
 * (tap the name to switch palettes), an icon tool rail on the left, canvas.
 */
public class PaintActivity extends Activity implements PaintSurface.Listener {
    /** Top bar height in dp; holds up to 4 palette rows. */
    private static final int BAR_HEIGHT = 128;
    /** Width of the top bar's left and right columns in dp. */
    private static final int SIDE_WIDTH = 208;
    private static final float[] WIDTHS = {2, 4, 8, 12, 16, 24, 32, 48, 64, 80, 100, 128, 160, 200, 240};
    private PaintSurface canvas;
    private SharedPreferences prefs;
    private Palette palette;
    private String paletteName;
    private PaletteView swatches;
    private ShadeView shades;
    private LinearLayout middle;
    private TextView status, sizeLabel, pageNumber;
    private Button paletteButton, liveButton;
    private android.widget.ImageButton menuButton, undoButton, redoButton, previousPage, nextPage, addPage;
    private Project project;
    private PaintSurface.LiveMode liveMode;
    private boolean grayPreview;
    private Button grayButton;
    private int widthIndex, blendWidthIndex, smudgeWidthIndex, watercolorWidthIndex, blendStrength;
    private PaintSurface.Tool tool = PaintSurface.Tool.BRUSH;
    private ToolButton brushButton, blendButton, smudgeButton, watercolorButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(PaletteLibrary.ACTIVE_PREFS, MODE_PRIVATE);
        loadPalette();
        widthIndex = Math.max(0, Math.min(WIDTHS.length - 1, prefs.getInt("widthIndex", 5)));
        blendWidthIndex = Math.max(0, Math.min(WIDTHS.length - 1, prefs.getInt("blendWidthIndex", 6)));
        smudgeWidthIndex = Math.max(0, Math.min(WIDTHS.length - 1, prefs.getInt("smudgeWidthIndex", 6)));
        watercolorWidthIndex = Math.max(0, Math.min(WIDTHS.length - 1, prefs.getInt("watercolorWidthIndex", 8)));
        blendStrength = Math.max(0, Math.min(Blend.MAX_STRENGTH, prefs.getInt("blendStrength", 35)));
        project = new Project(this);
        liveMode = loadLiveMode();
        grayPreview = prefs.getBoolean("grayPreview", true);
        Shade.solid = prefs.getBoolean("solidShades", false);
        try { tool = PaintSurface.Tool.valueOf(prefs.getString("tool", "BRUSH")); }
        catch (IllegalArgumentException ignored) { }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // Top bar, as in the Supernote app: file menu and page actions on the left,
        // colors in the middle, page switcher on the right.
        LinearLayout top = new LinearLayout(this);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout actions = new LinearLayout(this);
        menuButton = headerButton(actions, "File menu", R.drawable.ic_menu, dp(64), () -> fileMenu(menuButton));
        undoButton = headerButton(actions, "Undo", R.drawable.ic_undo, dp(48), () -> canvas.undo());
        redoButton = headerButton(actions, "Redo", R.drawable.ic_redo, dp(48), () -> canvas.redo());
        headerButton(actions, "Clear page", R.drawable.ic_clear, dp(48), this::clearPage);
        left.addView(actions, new LinearLayout.LayoutParams(-1, dp(48)));
        paletteButton = new Button(this);
        paletteButton.setAllCaps(false);
        paletteButton.setTextSize(14);
        paletteButton.setSingleLine(true);
        paletteButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
        paletteButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        paletteButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_files, 0, 0, 0);
        paletteButton.setCompoundDrawablePadding(dp(6));
        paletteButton.setPadding(dp(10), 0, dp(6), 0);
        paletteButton.setBackgroundColor(Color.WHITE);
        paletteButton.setStateListAnimator(null);
        paletteButton.setOnClickListener(v -> showPalettes());
        left.addView(paletteButton, new LinearLayout.LayoutParams(-1, 0, 1));
        top.addView(left, new LinearLayout.LayoutParams(dp(SIDE_WIDTH), dp(BAR_HEIGHT)));
        // The palette's 1–4 rows share a fixed height, so switching palettes never resizes
        // (and crops) the canvas. Arranging happens in the palette popup.
        swatches = new PaletteView(this);
        swatches.setShowSelection(true);
        swatches.setListener(swatchActions);
        swatches.setContentDescription("Palette. Tap a color to paint with it, long-press to edit it. "
            + "Tap an empty spot, or the palette icon, to arrange the palette.");
        // The selected color's shades sit right under the palette, as wide as its grid.
        middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setGravity(Gravity.CENTER_VERTICAL);
        middle.addView(swatches, new LinearLayout.LayoutParams(-1, 0));
        shades = new ShadeView(this);
        shades.setGrid(swatches);
        shades.setListener(new ShadeView.Listener() {
            @Override public void picked(int shade) {
                prefs.edit().putInt(shadeKey(palette.current().color), shade).apply();
                applyInk(); buildSwatches();
                if (tool != PaintSurface.Tool.WATERCOLOR) setTool(PaintSurface.Tool.BRUSH);
            }
            @Override public void toggledSolid() {
                Shade.solid = !Shade.solid;
                prefs.edit().putBoolean("solidShades", Shade.solid).apply();
                applyInk();
            }
        });
        shades.setContentDescription("Shades of the selected color: black at the left, the pure color in the middle, "
            + "white at the right. Tap one to paint with it. The toggle at the right end switches between dither and true color.");
        middle.addView(shades, new LinearLayout.LayoutParams(-1, 0));
        middle.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) v.post(this::fitPalette);
        });
        LinearLayout.LayoutParams middleParams = new LinearLayout.LayoutParams(0, dp(BAR_HEIGHT - 4), 1);
        middleParams.setMargins(dp(4), dp(2), dp(4), dp(2));
        top.addView(middle, middleParams);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout pages = new LinearLayout(this);
        pages.setGravity(Gravity.END);
        previousPage = headerButton(pages, "Previous page", R.drawable.ic_previous, dp(40), () -> changePage(project.index - 1));
        pageNumber = new TextView(this);
        pageNumber.setTextSize(15);
        pageNumber.setTextColor(Color.BLACK);
        pageNumber.setTypeface(null, android.graphics.Typeface.BOLD);
        pageNumber.setGravity(Gravity.CENTER);
        pages.addView(pageNumber, new LinearLayout.LayoutParams(dp(76), dp(48)));
        nextPage = headerButton(pages, "Next page", R.drawable.ic_chevron, dp(40), () -> changePage(project.index + 1));
        addPage = headerButton(pages, "Add page", R.drawable.ic_new, dp(48), this::addPage);
        right.addView(pages, new LinearLayout.LayoutParams(-1, dp(48)));
        Button arrange = new Button(this);
        arrange.setAllCaps(false);
        arrange.setTextSize(14);
        arrange.setText("Palette settings");
        arrange.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_palette, 0, 0, 0);
        arrange.setCompoundDrawablePadding(dp(6));
        arrange.setPadding(dp(10), 0, dp(6), 0);
        arrange.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        arrange.setBackgroundColor(Color.WHITE);
        arrange.setStateListAnimator(null);
        arrange.setContentDescription("Palette settings: pick and arrange colors");
        arrange.setOnClickListener(v -> openBuilder());
        right.addView(arrange, new LinearLayout.LayoutParams(-1, 0, 1));
        top.addView(right, new LinearLayout.LayoutParams(dp(SIDE_WIDTH), dp(BAR_HEIGHT)));
        root.addView(top);
        View rule = new View(this);
        rule.setBackgroundColor(0xffaaaaaa);
        root.addView(rule, new LinearLayout.LayoutParams(-1, 1));

        LinearLayout body = new LinearLayout(this);
        ScrollView railScroll = new ScrollView(this);
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        railScroll.addView(rail);
        brushButton = toolButton(rail, "Brush", R.drawable.ic_brush, PaintSurface.Tool.BRUSH);
        blendButton = toolButton(rail, "Blend", R.drawable.ic_soften, PaintSurface.Tool.BLEND);
        smudgeButton = toolButton(rail, "Smudge", R.drawable.ic_smudge, PaintSurface.Tool.SMUDGE);
        watercolorButton = toolButton(rail, "Watercolor", R.drawable.ic_watercolor, PaintSurface.Tool.WATERCOLOR);
        body.addView(railScroll, new LinearLayout.LayoutParams(dp(64), -1));
        View railRule = new View(this);
        railRule.setBackgroundColor(0xffaaaaaa);
        body.addView(railRule, new LinearLayout.LayoutParams(1, -1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(Color.BLACK);
        status.setPadding(dp(8), dp(2), dp(8), dp(2));
        status.setSingleLine(true);
        status.setText("Draw with the pen. Long-press a color to edit it.");
        content.addView(status);
        canvas = new PaintSurface(this);
        canvas.setListener(this);
        canvas.setPage(project.current());
        content.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
        applyWidth();
        applyInk();
        applyLiveMode();
        canvas.setBlendStrength(blendStrength);
        setTool(tool);
        buildSwatches();
        historyChanged();
        updatePages();
    }

    /** The active palette from prefs; on first run it is saved as "Default" in the palette library. */
    private void loadPalette() {
        palette = Palette.parse(prefs.getString("palette", null), prefs.getInt("selected", 0));
        PaletteLibrary library = PaletteLibrary.load(this);
        String name = library.migrate(prefs.getString("palette", null), prefs.getString("activeName", null));
        if (!name.equals(prefs.getString("activeName", null))) {
            library.save(this);
            prefs.edit().putString("activeName", name).apply();
        }
        paletteName = name;
        if (swatches != null) swatches.setPalette(palette);
    }

    /** Icon-only rail tool: tap to select it, tap again (or long-press) for its settings. */
    private ToolButton toolButton(LinearLayout rail, String name, int icon, PaintSurface.Tool which) {
        ToolButton button = new ToolButton(icon);
        button.setTooltipText(name);
        button.chevron = getDrawable(R.drawable.ic_chevron);
        button.setOnClickListener(v -> {
            if (tool == which) toolSettings(); else setTool(which);
        });
        button.setOnLongClickListener(v -> {
            if (tool != which) setTool(which);
            toolSettings();
            return true;
        });
        markActive(button, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(60), dp(60));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        rail.addView(button, params);
        return button;
    }

    private android.widget.ImageButton headerButton(LinearLayout parent, String name, int icon, int width, Runnable action) {
        android.widget.ImageButton button = new android.widget.ImageButton(this);
        button.setImageResource(icon);
        button.setBackgroundColor(Color.WHITE);
        button.setContentDescription(name);
        button.setTooltipText(name);
        button.setOnClickListener(v -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(width, dp(48)));
        return button;
    }

    private static void enable(View button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1 : .3f);
    }

    private void markActive(View button, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setStroke(selected ? dp(2) : 1, selected ? Color.BLACK : 0xffaaaaaa);
        background.setCornerRadius(dp(4));
        button.setBackground(background);
    }

    /** Icon-only rail button with a settings chevron at the right edge. */
    private final class ToolButton extends Button {
        private final android.graphics.drawable.Drawable icon;
        android.graphics.drawable.Drawable chevron;

        ToolButton(int resource) {
            super(PaintActivity.this);
            icon = getDrawable(resource);
            setStateListAnimator(null);
            setElevation(0);
            setMinWidth(0);
            setMinimumWidth(0);
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            super.onDraw(c);
            int half = dp(14), x = getWidth() / 2, y = getHeight() / 2;
            icon.setBounds(x - half, y - half, x + half, y + half);
            icon.draw(c);
            if (chevron != null) {
                int cx = getWidth() - dp(10);
                chevron.setBounds(cx - dp(8), y - dp(10), cx + dp(8), y + dp(10));
                chevron.draw(c);
            }
        }
    }

    private void toolSettings() {
        if (tool == PaintSurface.Tool.BLEND) showBlendSettings();
        else if (tool == PaintSurface.Tool.SMUDGE) showSmudgeSettings();
        else if (tool == PaintSurface.Tool.WATERCOLOR) showWatercolorSettings();
        else showBrushSettings();
    }

    private void showSmudgeSettings() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(8));
        body.addView(widthRow());
        TextView hint = new TextView(this);
        hint.setTextColor(0xff444444);
        hint.setText("Picks up the paint where you touch down and drags it along. Press harder to drag it farther.");
        body.addView(hint);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Smudge").setView(body)
            .setPositiveButton("Done", null).create();
        dialog.setOnDismissListener(d -> sizeLabel = null);
        applyWidth();
        dialog.show();
    }

    private void showWatercolorSettings() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(8));
        body.addView(widthRow());
        TextView hint = new TextView(this);
        hint.setTextColor(0xff444444);
        hint.setText("A see-through glaze: the color tints what is already there, so lines and earlier paint show through. "
            + "The glaze appears as you draw. Paint over it again to deepen it. "
            + "Press harder for a wider stroke.");
        body.addView(hint);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Watercolor").setView(body)
            .setPositiveButton("Done", null).create();
        dialog.setOnDismissListener(d -> sizeLabel = null);
        applyWidth();
        dialog.show();
    }

    private void showBlendSettings() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(8));
        body.addView(widthRow());
        TextView strengthTitle = new TextView(this);
        strengthTitle.setTextColor(Color.BLACK);
        strengthTitle.setText("Strength " + blendStrength);
        body.addView(strengthTitle);
        SeekBar strength = new SeekBar(this);
        strength.setMax(Blend.MAX_STRENGTH);
        strength.setProgress(blendStrength);
        strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (!user) return;
                blendStrength = value;
                strengthTitle.setText("Strength " + value);
                prefs.edit().putInt("blendStrength", value).apply();
                canvas.setBlendStrength(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        body.addView(strength, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView hint = new TextView(this);
        hint.setTextColor(0xff444444);
        hint.setText("Rub over edges to blend colors, pulling them the way you move. Press harder to blend more, with a slightly wider tip. "
            + "Lower strength blends gently; repeat passes to build it up. Above 100, each dab blends like several passes.");
        body.addView(hint);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Blend").setView(body)
            .setPositiveButton("Done", null).create();
        dialog.setOnDismissListener(d -> sizeLabel = null);
        applyWidth();
        dialog.show();
    }

    /** Width title and −/+ row for the selected tool. */
    private LinearLayout widthRow() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView widthTitle = new TextView(this);
        widthTitle.setText(tool == PaintSurface.Tool.BLEND || tool == PaintSurface.Tool.WATERCOLOR ? "Largest width, at full pressure (px)" : "Width (px)");
        widthTitle.setTextColor(Color.BLACK);
        column.addView(widthTitle);
        LinearLayout size = new LinearLayout(this);
        sizeLabel = new TextView(this);
        sizeLabel.setGravity(Gravity.CENTER);
        sizeLabel.setTextColor(Color.BLACK);
        sizeLabel.setTextSize(20);
        size.addView(small("−", v -> changeWidth(-1)), new LinearLayout.LayoutParams(0, dp(56), 1));
        size.addView(sizeLabel, new LinearLayout.LayoutParams(0, dp(56), 1));
        size.addView(small("+", v -> changeWidth(1)), new LinearLayout.LayoutParams(0, dp(56), 1));
        column.addView(size);
        return column;
    }

    private void showBrushSettings() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(8));
        body.addView(widthRow());
        liveButton = small("", v -> {
            PaintSurface.LiveMode[] modes = PaintSurface.LiveMode.values();
            liveMode = modes[(liveMode.ordinal() + 1) % modes.length];
            prefs.edit().putString("liveMode", liveMode.name()).apply();
            applyLiveMode();
        });
        body.addView(liveButton, new LinearLayout.LayoutParams(-1, dp(56)));
        grayButton = small("", v -> {
            grayPreview = !grayPreview;
            prefs.edit().putBoolean("grayPreview", grayPreview).apply();
            applyInk();
            updateGrayButton();
            updateBrushLabel();
        });
        body.addView(grayButton, new LinearLayout.LayoutParams(-1, dp(56)));
        updateGrayButton();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Brush").setView(body)
            .setPositiveButton("Done", null).create();
        body.addView(small("Refresh screen", v -> { dialog.dismiss(); canvas.cleanRefresh(); }),
            new LinearLayout.LayoutParams(-1, dp(56)));
        dialog.setOnDismissListener(d -> { sizeLabel = null; liveButton = null; grayButton = null; });
        applyWidth();
        applyLiveMode();
        dialog.show();
    }

    private Button small(String text, View.OnClickListener click) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(click);
        return button;
    }

    /** Each tool remembers its own width. */
    private void changeWidth(int delta) {
        if (tool == PaintSurface.Tool.BLEND) {
            blendWidthIndex = Math.max(0, Math.min(WIDTHS.length - 1, blendWidthIndex + delta));
            prefs.edit().putInt("blendWidthIndex", blendWidthIndex).apply();
        } else if (tool == PaintSurface.Tool.SMUDGE) {
            smudgeWidthIndex = Math.max(0, Math.min(WIDTHS.length - 1, smudgeWidthIndex + delta));
            prefs.edit().putInt("smudgeWidthIndex", smudgeWidthIndex).apply();
        } else if (tool == PaintSurface.Tool.WATERCOLOR) {
            watercolorWidthIndex = Math.max(0, Math.min(WIDTHS.length - 1, watercolorWidthIndex + delta));
            prefs.edit().putInt("watercolorWidthIndex", watercolorWidthIndex).apply();
        } else {
            widthIndex = Math.max(0, Math.min(WIDTHS.length - 1, widthIndex + delta));
            prefs.edit().putInt("widthIndex", widthIndex).apply();
        }
        applyWidth();
    }

    private float width() {
        return WIDTHS[tool == PaintSurface.Tool.BLEND ? blendWidthIndex : tool == PaintSurface.Tool.SMUDGE ? smudgeWidthIndex
            : tool == PaintSurface.Tool.WATERCOLOR ? watercolorWidthIndex : widthIndex];
    }

    private void applyWidth() {
        canvas.setBrushWidth(width());
        if (sizeLabel != null) sizeLabel.setText(String.valueOf((int) width()));
        updateBrushLabel();
    }

    /** Older installs stored only the true-color toggle. */
    private PaintSurface.LiveMode loadLiveMode() {
        String saved = prefs.getString("liveMode", null);
        if (saved != null) {
            try { return PaintSurface.LiveMode.valueOf(saved); } catch (IllegalArgumentException ignored) { }
        }
        return prefs.getBoolean("trueColor", false) ? PaintSurface.LiveMode.TRUE_COLOR : PaintSurface.LiveMode.FAST;
    }

    /** Fast = native preview, painted on pen-up; No flash = same, posted without pausing raw drawing;
     *  True = software-drawn exact paint only. */
    private void applyLiveMode() {
        canvas.setLiveMode(liveMode);
        updateBrushLabel();
        if (liveButton == null) return;
        switch (liveMode) {
            case NO_FLASH:
                liveButton.setText("Live: fast, no flash (tap for true color)");
                liveButton.setContentDescription("Fast preview; color appears on pen-up without a flash. Tap for true color.");
                break;
            case TRUE_COLOR:
                liveButton.setText("Live: true color (tap for fast)");
                liveButton.setContentDescription("True-color live drawing, slower. Tap for fast preview.");
                break;
            default:
                liveButton.setText("Live: fast preview (tap for no flash)");
                liveButton.setContentDescription("Fast approximate preview. Tap for fast without the pen-up flash.");
        }
    }

    /** Picking a color goes back to the brush, unless painting watercolor. */
    private void setTool(PaintSurface.Tool value) {
        tool = value;
        prefs.edit().putString("tool", tool.name()).apply();
        canvas.setTool(tool);
        applyWidth();
        boolean brush = tool == PaintSurface.Tool.BRUSH, blend = tool == PaintSurface.Tool.BLEND, smudge = tool == PaintSurface.Tool.SMUDGE;
        boolean watercolor = tool == PaintSurface.Tool.WATERCOLOR;
        markActive(brushButton, brush);
        markActive(blendButton, blend);
        markActive(smudgeButton, smudge);
        markActive(watercolorButton, watercolor);
        watercolorButton.setContentDescription(watercolor ? "Watercolor, selected. Tap for width."
            : "Watercolor. Tap to paint a see-through glaze over the canvas.");
        brushButton.setContentDescription(brush ? "Brush, selected. Tap for width and live preview settings."
            : "Brush. Tap to paint with the selected color.");
        blendButton.setContentDescription(blend ? "Blend, selected. Tap for width and strength."
            : "Blend. Tap to blend colors like a blending stump.");
        smudgeButton.setContentDescription(smudge ? "Smudge, selected. Tap for width."
            : "Smudge. Tap to drag paint along with the pen.");
    }

    private void updateGrayButton() {
        if (grayButton == null) return;
        grayButton.setText(grayPreview ? "Fast preview: gray for every color (tap for per-color)"
            : "Fast preview: per-color overrides (tap for all gray)");
    }

    private void updateBrushLabel() {
        if (status == null) return;
        status.setText((tool == PaintSurface.Tool.BLEND ? "Blend · " + (int) width() + " px · strength " + blendStrength
            : tool == PaintSurface.Tool.SMUDGE ? "Smudge · " + (int) width() + " px"
            : tool == PaintSurface.Tool.WATERCOLOR ? "Watercolor · " + (int) width() + " px"
            : "Brush · " + (int) width() + " px · " + (liveMode == PaintSurface.LiveMode.TRUE_COLOR ? "true color"
            : (liveMode == PaintSurface.LiveMode.NO_FLASH ? "no flash" : "fast") + (grayPreview ? " gray" : ""))));
    }

    private void applyInk() {
        Palette.Entry entry = palette.current();
        // Gray mode: every color previews as the visible gray of its lightness, ignoring overrides.
        // A shaded color previews by its dither's overall lightness.
        int shade = shade(entry.color);
        int mixed = Shade.average(Shade.pattern(entry.color, shade));
        int preview = grayPreview || (shade != 0 && entry.preview == 0) ? PreviewColors.gray(this, mixed)
            : PreviewColors.choose(this, entry);
        canvas.setInk(entry.color, shade, preview);
    }

    /** Saves the active palette and writes it through to its saved copy in the library. */
    private void savePalette() {
        prefs.edit().putString("palette", palette.serialize()).putInt("selected", palette.selected)
            .putString("activeName", paletteName).apply();
        PaletteLibrary library = PaletteLibrary.load(this);
        library.put(paletteName, palette);
        library.save(this);
    }

    private void buildSwatches() {
        paletteButton.setText(paletteName);
        paletteButton.setContentDescription("Palette " + paletteName + ". Tap to switch palettes.");
        swatches.setPalette(palette);
        shades.set(palette.current().color, shade(palette.current().color));
        fitPalette();
    }

    /** Each color remembers its own shade, wherever it sits and in every palette; new colors start pure. */
    private static String shadeKey(int color) { return "shade64-" + Palette.hex(color); }
    /** The color's dither with black (< 0) or white (> 0); see {@link Shade}. */
    private int shade(int color) { return Shade.clamp(prefs.getInt(shadeKey(color), 0)); }

    /** The palette rows and the shade bar under them share the top bar, all the same height. */
    private void fitPalette() {
        int w = middle.getWidth(), h = middle.getHeight();
        if (w <= 0 || h <= 0) return;
        int rows = palette.rows(), cell = Math.min(w / Palette.PER_ROW, h / (rows + 1));
        LinearLayout.LayoutParams grid = (LinearLayout.LayoutParams) swatches.getLayoutParams();
        LinearLayout.LayoutParams bar = (LinearLayout.LayoutParams) shades.getLayoutParams();
        if (grid.height != cell * rows || bar.height != cell) {
            grid.height = cell * rows;
            bar.height = cell;
            swatches.setLayoutParams(grid);
            shades.setLayoutParams(bar);
        }
        shades.gridChanged();
    }

    private final PaletteView.Listener swatchActions = new PaletteView.Listener() {
        @Override public void tapped(int spot) {
            if (palette.get(spot) == null) { openBuilder(); return; }
            palette.selected = spot;
            savePalette(); applyInk(); buildSwatches();
            if (tool != PaintSurface.Tool.WATERCOLOR) setTool(PaintSurface.Tool.BRUSH);
        }
        @Override public void longPressed(int spot) { editColor(spot); }
        @Override public void moved() { savePalette(); applyInk(); buildSwatches(); }
        @Override public void dropped(int spot, int color) { }
    };

    /** Quick switcher: saved palettes, plus the builder. */
    private void showPalettes() {
        final List<String> names = PaletteLibrary.load(this).names();
        String[] items = new String[names.size() + 1];
        for (int i = 0; i < names.size(); i++) items[i] = (names.get(i).equals(paletteName) ? "✓ " : "    ") + names.get(i);
        items[names.size()] = "Build and edit palettes…";
        new AlertDialog.Builder(this).setTitle("Palettes")
            .setItems(items, (d, which) -> {
                if (which == names.size()) openBuilder();
                else switchPalette(names.get(which));
            })
            .show();
    }

    private void openBuilder() { startActivity(new Intent(this, PaletteBuilderActivity.class)); }

    private void switchPalette(String name) {
        Palette chosen = PaletteLibrary.load(this).get(name);
        if (chosen == null || name.equals(paletteName)) return;
        int painting = palette.current().color;
        int spot = chosen.indexOf(painting);
        chosen.selected = spot >= 0 ? spot : chosen.first();
        palette = chosen;
        paletteName = name;
        PaletteLibrary.activate(this, name, palette);
        applyInk();
        buildSwatches();
    }

    /** Edits the color in {@code index}, or adds one there if the spot is empty. */
    private void editColor(int index) {
        final Palette.Entry original = palette.get(index);
        final int[] color = {original != null ? original.color : palette.current().color};
        final int[] preview = {original != null ? original.preview : 0};
        final float[] hsv = new float[3];
        Color.colorToHSV(color[0], hsv);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(8));
        View sample = new View(this);
        body.addView(sample, new LinearLayout.LayoutParams(-1, dp(72)));
        EditText hex = new EditText(this);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        hex.setTextSize(18);
        body.addView(hex);
        TextView previewLabel = new TextView(this);
        previewLabel.setTextColor(Color.BLACK);
        SeekBar[] bars = new SeekBar[3];
        String[] names = {"Hue", "Saturation", "Brightness"};
        int[] maxima = {360, 100, 100};
        final boolean[] updating = {false};
        Runnable refresh = () -> {
            sample.setBackgroundColor(color[0]);
            Palette.Entry trial = new Palette.Entry(color[0], preview[0]);
            int chosen = PreviewColors.choose(this, trial);
            previewLabel.setText("Fast-pen preview while drawing: " + PreviewColors.hex(chosen)
                + (preview[0] == 0 ? " (auto: gray of the same lightness)" : " (chosen)"));
            previewLabel.setBackgroundColor(0);
            previewLabel.setCompoundDrawablePadding(dp(8));
            GradientDrawable chip = new GradientDrawable();
            chip.setColor(chosen);
            chip.setSize(dp(28), dp(28));
            chip.setStroke(1, 0xff777777);
            previewLabel.setCompoundDrawablesWithIntrinsicBounds(chip, null, null, null);
        };
        Runnable fromHsv = () -> {
            color[0] = Color.HSVToColor(hsv) | 0xff000000;
            updating[0] = true;
            hex.setText(Palette.hex(color[0]));
            updating[0] = false;
            refresh.run();
        };
        for (int i = 0; i < 3; i++) {
            final int channel = i;
            TextView label = new TextView(this);
            label.setText(names[i]);
            label.setTextColor(Color.BLACK);
            body.addView(label);
            SeekBar bar = new SeekBar(this);
            bar.setMax(maxima[i]);
            bar.setProgress(Math.round(channel == 0 ? hsv[0] : hsv[channel] * 100));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int value, boolean user) {
                    if (!user) return;
                    hsv[channel] = channel == 0 ? value : value / 100f;
                    fromHsv.run();
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
            bars[i] = bar;
            body.addView(bar, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (updating[0]) return;
                String text = s.toString().replace("#", "").trim();
                if (text.length() != 6) return;
                try { color[0] = Integer.parseInt(text, 16) | 0xff000000; }
                catch (NumberFormatException e) { return; }
                Color.colorToHSV(color[0], hsv);
                bars[0].setProgress(Math.round(hsv[0]));
                bars[1].setProgress(Math.round(hsv[1] * 100));
                bars[2].setProgress(Math.round(hsv[2] * 100));
                refresh.run();
            }
        });
        body.addView(previewLabel, new LinearLayout.LayoutParams(-1, -2));
        // Colors that passed the physical calibration sheets come first; they
        // are the ones known to look clean on this panel.
        List<Integer> calibrated = CalibrationColors.sortForReview(CalibrateActivity.load(this));
        TextView libraryLabel = new TextView(this);
        libraryLabel.setTextColor(Color.BLACK);
        libraryLabel.setText(calibrated.isEmpty()
            ? "No calibrated colors yet: More… → Calibrate colors picks the clean-looking ones."
            : "Your calibrated colors (look clean on this screen):");
        body.addView(libraryLabel, 1);
        GridLayout library = new GridLayout(this);
        library.setColumnCount(8);
        for (int choice : calibrated) {
            Button chip = new Button(this);
            chip.setStateListAnimator(null);
            GradientDrawable background = new GradientDrawable();
            background.setColor(choice);
            background.setStroke(dp(1), 0xff777777);
            chip.setBackground(background);
            chip.setOnClickListener(v -> hex.setText(Palette.hex(choice)));
            GridLayout.LayoutParams cell = new GridLayout.LayoutParams();
            cell.width = dp(52); cell.height = dp(44);
            cell.setMargins(dp(2), dp(2), dp(2), dp(2));
            library.addView(chip, cell);
        }
        body.addView(library, 2);
        TextView hint = new TextView(this);
        hint.setText("Optional: tap a preview color the fast pen shows well for this paint. "
            + "Only colors seen during the visibility test are offered.");
        hint.setTextColor(0xff444444);
        body.addView(hint);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(8);
        List<Integer> choices = new ArrayList<>();
        choices.add(0);
        for (int visible : PreviewColors.visible(this)) choices.add(visible);
        for (int choice : choices) {
            Button chip = new Button(this);
            chip.setStateListAnimator(null);
            chip.setPadding(0, 0, 0, 0);
            chip.setAllCaps(false);
            chip.setText(choice == 0 ? "Auto" : "");
            GradientDrawable background = new GradientDrawable();
            background.setColor(choice == 0 ? Color.WHITE : choice);
            background.setStroke(dp(1), 0xff777777);
            chip.setBackground(background);
            chip.setOnClickListener(v -> { preview[0] = choice; refresh.run(); });
            GridLayout.LayoutParams cell = new GridLayout.LayoutParams();
            cell.width = dp(52); cell.height = dp(44);
            cell.setMargins(dp(2), dp(2), dp(2), dp(2));
            grid.addView(chip, cell);
        }
        body.addView(grid);
        hex.setText(Palette.hex(color[0]));
        refresh.run();

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(original != null ? "Edit color" : "Add color")
            .setView(scroll)
            .setPositiveButton("Save", (d, w) -> {
                Palette.Entry entry = new Palette.Entry(color[0], preview[0]);
                palette.set(index, entry);
                palette.selected = index;
                savePalette(); applyInk(); buildSwatches();
            })
            .setNegativeButton("Cancel", null);
        if (original != null && palette.count() > 1) {
            builder.setNeutralButton("Delete", (d, w) -> {
                palette.clear(index);
                savePalette(); applyInk(); buildSwatches();
            });
        }
        builder.show();
    }

    private void fileMenu(View anchor) {
        String[] names = {"New drawing", "Open drawing", "Save drawing", "Test screens"};
        int[] icons = {R.drawable.ic_new, R.drawable.ic_open, R.drawable.ic_save, R.drawable.ic_settings};
        android.widget.ListPopupWindow menu = new android.widget.ListPopupWindow(this);
        menu.setAnchorView(anchor);
        menu.setModal(true);
        menu.setWidth(dp(240));
        menu.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        menu.setAdapter(new android.widget.BaseAdapter() {
            @Override public int getCount() { return names.length; }
            @Override public Object getItem(int position) { return names[position]; }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                TextView row = new TextView(PaintActivity.this);
                row.setText(names[position]);
                row.setTextColor(Color.BLACK);
                GradientDrawable border = new GradientDrawable();
                border.setColor(Color.WHITE);
                border.setStroke(dp(1), Color.BLACK);
                row.setBackground(border);
                row.setTextSize(16);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(12), dp(16), dp(12));
                row.setMinHeight(dp(52));
                row.setCompoundDrawablesRelativeWithIntrinsicBounds(icons[position], 0, 0, 0);
                row.setCompoundDrawablePadding(dp(12));
                return row;
            }
        });
        menu.setOnItemClickListener((parent, view, position, id) -> {
            menu.dismiss();
            if (position == 0) newDrawing();
            else if (position == 1) openDrawing();
            else if (position == 2) saveDrawing();
            else testScreens();
        });
        menu.show();
    }

    private void newDrawing() {
        new AlertDialog.Builder(this).setTitle("New drawing?")
            .setMessage("Start a new drawing with one blank page. Save first to keep all pages of this drawing.")
            .setPositiveButton("New", (d, w) -> {
                canvas.suspend(false);
                project.startNew(error -> runOnUiThread(() -> {
                    canvas.showPage(project.current());
                    updatePages();
                    if (error != null) message("Could not clear the old pages: " + error.getMessage());
                }));
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void saveDrawing() {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Drawing name");
        name.setText(project.name);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Save drawing").setView(name)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = name.getText().toString().trim();
            if (!Project.validName(value)) { name.setError("Use 1–64 letters, numbers, spaces, - or _"); return; }
            Runnable save = () -> {
                dialog.dismiss();
                // The open page's save is queued ahead of the copy.
                canvas.saveNow();
                project.save(value, error -> runOnUiThread(() ->
                    message(error == null ? "Saved " + value : "Save failed: " + error.getMessage())));
                updatePages();
            };
            if (!value.equals(project.name) && project.names().contains(value))
                new AlertDialog.Builder(this).setTitle("Replace “" + value + "”?")
                    .setPositiveButton("Replace", (d, w) -> save.run()).setNegativeButton("Cancel", null).show();
            else save.run();
        }));
        dialog.show();
    }

    private void openDrawing() {
        List<String> names = project.names();
        if (names.isEmpty()) { message("No saved drawings yet"); return; }
        new AlertDialog.Builder(this).setTitle("Open drawing (replaces the current pages)")
            .setItems(names.toArray(new String[0]), (d, which) -> {
                String chosen = names.get(which);
                canvas.suspend(false);
                project.open(chosen, error -> runOnUiThread(() -> {
                    canvas.showPage(project.current());
                    updatePages();
                    message(error == null ? "Opened " + chosen : "Open failed: " + error.getMessage());
                }));
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void clearPage() {
        new AlertDialog.Builder(this).setTitle("Clear this page?")
            .setMessage("Remove every mark on this page. Other pages stay as they are. You can undo this.")
            .setPositiveButton("Clear", (d, w) -> canvas.clear())
            .setNegativeButton("Cancel", null).show();
    }

    private void changePage(int index) {
        if (index < 0 || index >= project.count || index == project.index) return;
        project.select(index);
        canvas.showPage(project.current());
        updatePages();
    }

    private void addPage() {
        if (!project.add()) { message("This drawing has " + Project.MAX_PAGES + " pages. Start a new drawing for more."); return; }
        canvas.showPage(project.current());
        updatePages();
    }

    private void updatePages() {
        int count = project.count, index = project.index;
        pageNumber.setText((index + 1) + " / " + count);
        pageNumber.setContentDescription("Page " + (index + 1) + " of " + count);
        enable(previousPage, index > 0);
        enable(nextPage, index + 1 < count);
        enable(addPage, count < Project.MAX_PAGES);
        menuButton.setContentDescription(project.name.isEmpty() ? "File menu" : "File menu. Drawing " + project.name);
    }

    private void message(String text) { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show(); }

    private void testScreens() {
        final Class<?>[] screens = {GrayPreviewActivity.class, GrayPreviewActivity.class, PaletteBuilderActivity.class, PicksActivity.class, CalibrateActivity.class,
            WheelActivity.class, GradientActivity.class, CmykActivity.class, SweepActivity.class,
            GridTestActivity.class, MainActivity.class};
        new AlertDialog.Builder(this).setTitle("Test screens")
            .setItems(new String[]{"Gray preview test (how dark each preview gray looks)",
                    "Light color preview test (colors that may draw lighter than gray)", "Palette builder", "Picks (review calibrated colors)",
                    "Calibrate colors", "Color wheel test", "Gradient / black-mix test", "CMYK mix test",
                    "Color sweep sheet", "Grid color test sheet", "Pen lab (old test screen)"},
                (d, which) -> startActivity(new Intent(this, screens[which])
                    .putExtra(GrayPreviewActivity.EXTRA_LIGHT, which == 1)))
            .show();
    }

    @Override public void strokeFinished(String summary) { status.setText(summary); }

    @Override public void historyChanged() {
        enable(undoButton, canvas.canUndo());
        enable(redoButton, canvas.canRedo());
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onResume() {
        super.onResume();
        // The palette builder may have changed or switched the active palette.
        String before = paletteName + "|" + palette.serialize() + "|" + palette.selected;
        loadPalette();
        if (!before.equals(paletteName + "|" + palette.serialize() + "|" + palette.selected)) {
            applyInk();
            buildSwatches();
        }
        canvas.resume();
    }
    @Override protected void onPause() { canvas.pause(); super.onPause(); }
    @Override protected void onDestroy() { canvas.release(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (canvas != null) canvas.focus(focused);
    }
}

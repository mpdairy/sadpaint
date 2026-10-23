package dev.tilesmile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The painter's palette: 1–{@link #MAX_ROWS} rows of {@link #PER_ROW} spots,
 * each empty or holding an opaque finished color with an optional
 * native-preview request override (0 = choose automatically). Colors can sit
 * in any spot, so rows can be organized with gaps. Serialized as
 * "rows=3|RRGGBB:PPPPPP,-,RRGGBB:auto,..." ("-" = empty spot; the rows prefix
 * is left out for the default {@link #DEFAULT_ROWS}).
 */
final class Palette {
    static final int PER_ROW = 12, MAX_ROWS = 4, DEFAULT_ROWS = 2, MAX = PER_ROW * MAX_ROWS;

    static final class Entry {
        int color, preview;
        Entry(int color, int preview) { this.color = color | 0xff000000; this.preview = preview == 0 ? 0 : preview | 0xff000000; }
    }

    /** Spots in order; null is an empty spot. Never ends in null. */
    final List<Entry> entries = new ArrayList<>();
    /** The painting color's spot. */
    int selected;
    /** Rows shown; {@link #rows()} also covers any color placed beyond them. */
    int rows = DEFAULT_ROWS;

    /** Rows to show: the chosen count, or more if a color sits further down. */
    int rows() {
        int needed = (entries.size() + PER_ROW - 1) / PER_ROW;
        return Math.max(1, Math.min(MAX_ROWS, Math.max(rows, needed)));
    }

    /** Spots on screen. */
    int spots() { return rows() * PER_ROW; }

    /** True if any color sits in row {@code row} (0-based). */
    boolean rowUsed(int row) {
        for (int i = row * PER_ROW; i < (row + 1) * PER_ROW; i++) if (get(i) != null) return true;
        return false;
    }

    static Palette defaults() {
        Palette palette = new Palette();
        // Grayscale first, as a first-class mode, then the prototype's blues. All
        // preview automatically as the gray of matching lightness.
        for (int gray : new int[]{0x000000, 0x404040, 0x808080, 0xc0c0c0, 0xffffff})
            palette.entries.add(new Entry(gray, 0));
        for (int blue : new int[]{0xc7d8e8, 0x9cbbd6, 0x6f9ec5, 0x315f88, 0x566b78})
            palette.entries.add(new Entry(blue, 0));
        return palette;
    }

    /** The entry in {@code spot}, or null if it is empty or out of range. */
    Entry get(int spot) { return spot >= 0 && spot < entries.size() ? entries.get(spot) : null; }

    void set(int spot, Entry entry) {
        if (spot < 0 || spot >= MAX) return;
        while (entries.size() <= spot) entries.add(null);
        entries.set(spot, entry);
        while (!entries.isEmpty() && entries.get(entries.size() - 1) == null) entries.remove(entries.size() - 1);
    }

    /** Empties {@code spot}; the selection moves to the first color if it was there. */
    void clear(int spot) {
        set(spot, null);
        if (selected == spot) selected = first();
    }

    /** Moves the color in {@code from} to {@code to}, swapping with whatever is there. */
    void move(int from, int to) {
        if (from == to || to < 0 || to >= MAX) return;
        Entry a = get(from), b = get(to);
        set(to, a);
        set(from, b);
        if (selected == from) selected = to;
        else if (selected == to) selected = from;
    }

    int count() {
        int count = 0;
        for (Entry entry : entries) if (entry != null) count++;
        return count;
    }

    /** First empty spot among the rows shown, or -1 if they are full. */
    int firstEmpty() {
        for (int i = 0; i < spots(); i++) if (get(i) == null) return i;
        return -1;
    }

    /** First filled spot, or 0. */
    int first() {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i) != null) return i;
        return 0;
    }

    int indexOf(int color) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry != null && entry.color == (color | 0xff000000)) return i;
        }
        return -1;
    }

    Palette copy() { return parse(serialize(), selected); }

    private static String prefix(int rows) { return "rows=" + rows + "|"; }

    Entry current() {
        Entry entry = get(selected);
        return entry != null ? entry : get(first());
    }

    String serialize() {
        StringBuilder out = new StringBuilder(rows == DEFAULT_ROWS ? "" : prefix(rows));
        int start = out.length();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (out.length() > start) out.append(',');
            if (entry == null) out.append('-');
            else out.append(hex(entry.color)).append(':').append(entry.preview == 0 ? "auto" : hex(entry.preview));
        }
        return out.toString();
    }

    static Palette parse(String text, int selected) {
        Palette palette = new Palette();
        if (text != null && text.startsWith("rows=") && text.indexOf('|') > 0) {
            try { palette.rows = Math.max(1, Math.min(MAX_ROWS, Integer.parseInt(text.substring(5, text.indexOf('|'))))); }
            catch (NumberFormatException ignored) { }
            text = text.substring(text.indexOf('|') + 1);
        }
        if (text != null && !text.isEmpty()) {
            for (String item : text.split(",")) {
                if (palette.entries.size() >= MAX) break;
                if ("-".equals(item.trim())) { palette.entries.add(null); continue; }
                String[] parts = item.trim().split(":");
                try {
                    int color = Integer.parseInt(parts[0], 16);
                    int preview = parts.length > 1 && !"auto".equals(parts[1]) ? Integer.parseInt(parts[1], 16) : 0;
                    palette.entries.add(new Entry(color, preview));
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) { }
            }
        }
        while (!palette.entries.isEmpty() && palette.entries.get(palette.entries.size() - 1) == null)
            palette.entries.remove(palette.entries.size() - 1);
        if (palette.count() == 0) {
            Palette defaults = defaults();
            defaults.rows = palette.rows;
            return defaults;
        }
        palette.selected = palette.get(selected) != null ? selected : palette.first();
        return palette;
    }

    static String hex(int color) { return String.format(Locale.US, "%06X", color & 0xffffff); }
}

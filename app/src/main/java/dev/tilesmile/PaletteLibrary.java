package dev.tilesmile;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Named saved palettes. Stored in prefs {@link #PREFS}: key {@code names} (one
 * name per line, in order) and {@code palette:<name>} → {@link Palette#serialize()}.
 * The active palette lives in {@code paint-v1} ({@code palette}, {@code selected},
 * {@code activeName}); edits to it are written through to its saved copy.
 */
final class PaletteLibrary {
    static final String PREFS = "palettes-v1", ACTIVE_PREFS = "paint-v1";
    static final String DEFAULT_NAME = "Default";
    static final int MAX_NAME = 40;
    private static final String NAMES = "names", PREFIX = "palette:";

    private final List<String> names = new ArrayList<>();
    private final Map<String, String> palettes = new HashMap<>();

    /** Reads what {@link #write()} produced; names without a stored palette are dropped. */
    static PaletteLibrary read(Map<String, ?> stored) {
        PaletteLibrary library = new PaletteLibrary();
        Object list = stored.get(NAMES);
        if (list instanceof String) {
            for (String name : ((String) list).split("\n")) {
                Object palette = stored.get(PREFIX + name);
                if (!name.isEmpty() && palette instanceof String && !library.contains(name)) {
                    library.names.add(name);
                    library.palettes.put(name, (String) palette);
                }
            }
        }
        return library;
    }

    Map<String, String> write() {
        Map<String, String> out = new HashMap<>();
        out.put(NAMES, String.join("\n", names));
        for (String name : names) out.put(PREFIX + name, palettes.get(name));
        return out;
    }

    List<String> names() { return new ArrayList<>(names); }

    boolean contains(String name) { return palettes.containsKey(name); }

    /** The saved palette, or null. */
    Palette get(String name) {
        String text = palettes.get(name);
        return text == null ? null : Palette.parse(text, 0);
    }

    /** Replaces {@code name}'s palette, or adds it at the end. */
    void put(String name, Palette palette) {
        if (!contains(name)) names.add(name);
        palettes.put(name, palette.serialize());
    }

    void rename(String from, String to) {
        if (!contains(from)) throw new IllegalArgumentException("No palette named " + from);
        if (from.equals(to)) return;
        if (contains(to)) throw new IllegalArgumentException("A palette named " + to + " already exists");
        names.set(names.indexOf(from), to);
        palettes.put(to, palettes.remove(from));
    }

    void delete(String name) {
        names.remove(name);
        palettes.remove(name);
    }

    /** {@code base}, or "base 2", "base 3"… if taken. */
    String uniqueName(String base) {
        if (!contains(base)) return base;
        for (int n = 2; ; n++) {
            String candidate = base + " " + n;
            if (candidate.length() > MAX_NAME) candidate = base.substring(0, MAX_NAME - (" " + n).length()) + " " + n;
            if (!contains(candidate)) return candidate;
        }
    }

    /** Trimmed, single-line name, or IllegalArgumentException with a message fit to show. */
    static String validName(String raw) {
        String name = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Enter a name");
        if (name.length() > MAX_NAME) throw new IllegalArgumentException("At most " + MAX_NAME + " characters");
        return name;
    }

    /**
     * First run: saves the active palette (or the defaults) as "Default".
     * Returns the active palette's name, which then exists in the library.
     */
    String migrate(String activeSerialized, String activeName) {
        if (activeName != null && contains(activeName)) return activeName;
        Palette active = Palette.parse(activeSerialized, 0);
        String name = activeName != null ? activeName : names.isEmpty() ? DEFAULT_NAME : uniqueName(DEFAULT_NAME);
        put(name, active);
        return name;
    }

    // Android storage.

    static PaletteLibrary load(Context context) {
        return read(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll());
    }

    void save(Context context) {
        SharedPreferences.Editor edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear();
        for (Map.Entry<String, String> entry : write().entrySet()) edit.putString(entry.getKey(), entry.getValue());
        edit.apply();
    }

    /** Makes {@code name} the painting palette, keeping {@code selected} in range. */
    static void activate(Context context, String name, Palette palette) {
        context.getSharedPreferences(ACTIVE_PREFS, Context.MODE_PRIVATE).edit()
            .putString("palette", palette.serialize())
            .putInt("selected", Math.max(0, Math.min(palette.selected, palette.entries.size() - 1)))
            .putString("activeName", name).apply();
    }

    static String activeName(Context context) {
        return context.getSharedPreferences(ACTIVE_PREFS, Context.MODE_PRIVATE).getString("activeName", null);
    }
}

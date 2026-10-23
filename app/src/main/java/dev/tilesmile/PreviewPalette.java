package dev.tilesmile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Ordered requests for the native pen; these are not measured panel colors. */
final class PreviewPalette {
    static final class Candidate {
        final int color;
        final String name;
        Candidate(int color, String name) { this.color = color; this.name = name; }
        String hex() { return String.format(Locale.US, "#%06X", color & 0xffffff); }
        @Override public String toString() { return name + " " + hex(); }
    }

    static List<Candidate> create() {
        LinkedHashMap<Integer, Candidate> colors = new LinkedHashMap<>();
        add(colors, 0xc7d8e8, "Pale blue");
        add(colors, 0x9cbbd6, "Light blue");
        add(colors, 0x6f9ec5, "Original blue");
        add(colors, 0x315f88, "Deep blue");
        add(colors, 0x566b78, "Blue-gray");
        add(colors, 0x000000, "Black");
        add(colors, 0x0000ff, "Pure blue");
        add(colors, 0x00ffff, "Cyan");
        add(colors, 0xff0000, "Red");
        add(colors, 0x00ff00, "Green");
        add(colors, 0xffff00, "Yellow");
        add(colors, 0xff00ff, "Magenta");
        add(colors, 0xffffff, "White control");
        for (int gray : new int[]{32, 64, 96, 128, 160, 192, 224, 248})
            add(colors, gray * 0x010101, "Gray ramp");
        for (int r : new int[]{32, 80, 128, 176, 224})
            for (int g : new int[]{32, 80, 128, 176, 224})
                add(colors, r << 16 | g << 8 | 255, "Blue sweep");
        for (int r : new int[]{0, 128, 255})
            for (int g : new int[]{0, 128, 255})
                for (int b : new int[]{0, 128, 255})
                    add(colors, r << 16 | g << 8 | b, "RGB cube");
        return new ArrayList<>(colors.values());
    }

    private static void add(LinkedHashMap<Integer, Candidate> colors, int rgb, String name) {
        int argb = rgb | 0xff000000;
        if (!colors.containsKey(argb)) colors.put(argb, new Candidate(argb, name));
    }
}

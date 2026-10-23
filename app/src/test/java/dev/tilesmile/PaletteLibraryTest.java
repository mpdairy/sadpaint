package dev.tilesmile;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class PaletteLibraryTest {
    private static Palette palette(int... colors) {
        Palette palette = new Palette();
        for (int color : colors) palette.entries.add(new Palette.Entry(color, 0));
        return palette;
    }

    @Test public void saveLoadRoundTripKeepsOrderAndPreviewOverrides() {
        PaletteLibrary library = new PaletteLibrary();
        Palette zorn = palette(0xffffff, 0xc0a080);
        zorn.entries.get(1).preview = 0xff808080;
        library.put("Zorn", zorn);
        library.put("Portrait, warm", palette(0xffc4d5));
        Map<String, String> stored = library.write();

        PaletteLibrary copy = PaletteLibrary.read(stored);
        assertEquals(Arrays.asList("Zorn", "Portrait, warm"), copy.names());
        assertEquals(zorn.serialize(), copy.get("Zorn").serialize());
        assertEquals(0xff808080, copy.get("Zorn").entries.get(1).preview);
        assertNull(copy.get("Missing"));
    }

    @Test public void putReplacesInPlace() {
        PaletteLibrary library = new PaletteLibrary();
        library.put("A", palette(0x111111));
        library.put("B", palette(0x222222));
        library.put("A", palette(0x333333));
        assertEquals(Arrays.asList("A", "B"), library.names());
        assertEquals("333333:auto", library.get("A").serialize());
    }

    @Test public void renameAndDelete() {
        PaletteLibrary library = new PaletteLibrary();
        library.put("A", palette(0x111111));
        library.put("B", palette(0x222222));
        library.put("C", palette(0x333333));
        library.rename("B", "Portrait");
        assertEquals(Arrays.asList("A", "Portrait", "C"), library.names());
        assertEquals("222222:auto", library.get("Portrait").serialize());
        assertFalse(library.contains("B"));
        library.rename("A", "A");
        try { library.rename("A", "C"); fail(); } catch (IllegalArgumentException expected) { }
        try { library.rename("Nope", "D"); fail(); } catch (IllegalArgumentException expected) { }
        library.delete("Portrait");
        assertEquals(Arrays.asList("A", "C"), PaletteLibrary.read(library.write()).names());
    }

    @Test public void duplicateNamesGetNumbered() {
        PaletteLibrary library = new PaletteLibrary();
        assertEquals("Zorn", library.uniqueName("Zorn"));
        library.put("Zorn", palette(0));
        assertEquals("Zorn 2", library.uniqueName("Zorn"));
        library.put("Zorn 2", palette(0));
        assertEquals("Zorn 3", library.uniqueName("Zorn"));
        String longName = "x" + new String(new char[PaletteLibrary.MAX_NAME - 1]).replace('\0', 'y');
        library.put(longName, palette(0));
        String unique = library.uniqueName(longName);
        assertTrue(unique.length() <= PaletteLibrary.MAX_NAME);
        assertTrue(unique.endsWith(" 2"));
    }

    @Test public void namesAreValidated() {
        assertEquals("Warm skin", PaletteLibrary.validName("  Warm \n skin "));
        for (String bad : new String[]{null, "", "   ", new String(new char[PaletteLibrary.MAX_NAME + 1]).replace('\0', 'a')}) {
            try { PaletteLibrary.validName(bad); fail(bad); } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void readIgnoresBrokenEntries() {
        Map<String, Object> stored = new HashMap<>();
        stored.put("names", "A\n\nMissing\nA\nB");
        stored.put("palette:A", "111111:auto");
        stored.put("palette:B", "222222:auto");
        stored.put("palette:Orphan", "333333:auto");
        assertEquals(Arrays.asList("A", "B"), PaletteLibrary.read(stored).names());
        assertTrue(PaletteLibrary.read(new HashMap<String, Object>()).names().isEmpty());
    }

    @Test public void firstRunMigratesTheCurrentPaletteAsDefault() {
        PaletteLibrary library = new PaletteLibrary();
        assertEquals("Default", library.migrate("C7D8E8:6F9EC5,000000:auto", null));
        assertEquals("C7D8E8:6F9EC5,000000:auto", library.get("Default").serialize());
        // Already migrated: nothing changes.
        assertEquals("Default", library.migrate("FFFFFF:auto", "Default"));
        assertEquals(1, library.names().size());
        assertEquals("C7D8E8:6F9EC5,000000:auto", library.get("Default").serialize());
    }

    @Test public void migrationWithNoSavedPaletteUsesDefaults() {
        PaletteLibrary library = new PaletteLibrary();
        assertEquals("Default", library.migrate(null, null));
        assertEquals(Palette.defaults().serialize(), library.get("Default").serialize());
    }

    @Test public void migrationRestoresAMissingActiveName() {
        PaletteLibrary library = new PaletteLibrary();
        library.put("Default", palette(0x111111));
        // Active palette refers to a name that is gone (e.g. prefs cleared): re-save it under that name.
        assertEquals("Zorn", library.migrate("222222:auto", "Zorn"));
        assertEquals("222222:auto", library.get("Zorn").serialize());
        // No active name but the library already has a Default: don't overwrite it.
        assertEquals("Default 2", library.migrate("333333:auto", null));
        assertEquals("111111:auto", library.get("Default").serialize());
    }

    @Test public void paletteHoldsUpToMaxAndFindsColors() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Palette.MAX + 5; i++) text.append(i == 0 ? "" : ",").append(String.format("%06X", i));
        Palette palette = Palette.parse(text.toString(), 0);
        assertEquals(Palette.MAX, palette.entries.size());
        assertEquals(3, palette.indexOf(0x000003));
        assertEquals(3, palette.indexOf(0xff000003));
        assertEquals(-1, palette.indexOf(0xabcdef));
        assertEquals(palette.serialize(), palette.copy().serialize());
    }

    @Test public void spotsCanBeEmptyAndSurviveSaving() {
        Palette palette = Palette.parse("111111:auto,-,-,222222:808080", 3);
        assertEquals(4, palette.entries.size());
        assertEquals(2, palette.count());
        assertNull(palette.get(1));
        assertEquals(3, palette.selected);
        assertEquals("111111:auto,-,-,222222:808080", palette.serialize());
        // Trailing empties are dropped; selecting an empty spot falls back to the first color.
        assertEquals("111111:auto", Palette.parse("111111:auto,-,-", 2).serialize());
        assertEquals(0, Palette.parse("111111:auto,-", 1).selected);
        assertEquals(Palette.defaults().serialize(), Palette.parse("-,-", 0).serialize());
        assertEquals(1, palette.firstEmpty());
        // Second row, spot 14.
        palette.set(14, new Palette.Entry(0x333333, 0));
        assertEquals(15, palette.entries.size());
        assertEquals(0xff333333, palette.get(14).color);
        PaletteLibrary library = new PaletteLibrary();
        library.put("Gaps", palette);
        assertEquals(palette.serialize(), PaletteLibrary.read(library.write()).get("Gaps").serialize());
    }

    @Test public void moveSwapsAndSelectionFollows() {
        Palette palette = Palette.parse("111111:auto,222222:auto", 0);
        palette.move(0, 13);                         // to an empty spot on row 2
        assertNull(palette.get(0));
        assertEquals(0xff111111, palette.get(13).color);
        assertEquals(13, palette.selected);
        palette.move(1, 13);                         // onto a color: swap
        assertEquals(0xff222222, palette.get(13).color);
        assertEquals(0xff111111, palette.get(1).color);
        assertEquals(1, palette.selected);
        palette.move(13, 0);
        assertEquals("222222:auto,111111:auto", palette.serialize());
        palette.move(0, Palette.MAX);                // out of range: ignored
        assertEquals("222222:auto,111111:auto", palette.serialize());
    }

    @Test public void clearingTheSelectedSpotSelectsTheFirstColor() {
        Palette palette = Palette.parse("-,111111:auto,222222:auto", 2);
        palette.clear(2);
        assertEquals("-,111111:auto", palette.serialize());
        assertEquals(1, palette.selected);
        assertEquals(0xff111111, palette.current().color);
        assertEquals(0, palette.firstEmpty());
    }

    @Test public void rowsAreSavedAndCoverPlacedColors() {
        Palette palette = Palette.parse("111111:auto", 0);
        assertEquals(Palette.DEFAULT_ROWS, palette.rows());
        assertEquals("111111:auto", palette.serialize());   // default rows: no prefix, old format
        palette.rows = 4;
        assertEquals("rows=4|111111:auto", palette.serialize());
        Palette copy = Palette.parse(palette.serialize(), 0);
        assertEquals(4, copy.rows());
        assertEquals(48, copy.spots());
        assertEquals(0xff111111, copy.get(0).color);
        // A color placed in row 3 keeps 3 rows showing even if fewer were chosen.
        copy.rows = 1;
        copy.set(30, new Palette.Entry(0x222222, 0));
        assertEquals(3, copy.rows());
        assertTrue(copy.rowUsed(2));
        assertFalse(copy.rowUsed(1));
        // Out-of-range or broken prefixes are clamped or ignored.
        assertEquals(4, Palette.parse("rows=9|111111:auto", 0).rows());
        assertEquals(1, Palette.parse("rows=0|111111:auto", 0).rows());
        assertEquals(Palette.DEFAULT_ROWS, Palette.parse("rows=x|111111:auto", 0).rows());
        assertEquals(3, Palette.parse("rows=3|", 0).rows());   // empty: defaults, rows kept
    }

    @Test public void firstEmptyStaysInTheRowsShown() {
        Palette palette = new Palette();
        palette.rows = 1;
        for (int i = 0; i < Palette.PER_ROW; i++) palette.set(i, new Palette.Entry(i, 0));
        assertEquals(-1, palette.firstEmpty());
        palette.rows = 2;
        assertEquals(Palette.PER_ROW, palette.firstEmpty());
    }
}

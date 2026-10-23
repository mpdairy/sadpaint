package dev.tilesmile;

import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class InkStrokeTest {
    @Test public void segmentCoversExactlyPixelsWithinRadius() {
        Random random = new Random(7);
        for (int trial = 0; trial < 300; trial++) {
            OpaqueRaster raster = new OpaqueRaster(64, 64);
            float x0 = random.nextFloat() * 64, y0 = random.nextFloat() * 64;
            float x1 = random.nextFloat() * 64, y1 = random.nextFloat() * 64;
            if (trial % 5 == 0) y1 = y0;
            if (trial % 7 == 0) x1 = x0;
            float diameter = 1.5f + random.nextFloat() * 20;
            raster.segment(x0, y0, x1, y1, diameter, 0xff000000);
            float r = diameter / 2;
            for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
                double d = distance(x + 0.5, y + 0.5, x0, y0, x1, y1);
                boolean painted = raster.pixels[y * 64 + x] == 0xff000000;
                // Skip centers within float rounding of the boundary.
                if (Math.abs(d - r) < 1e-3) continue;
                assertEquals("trial " + trial + " at " + x + "," + y, d < r, painted);
            }
        }
    }

    @Test public void thinDiagonalHasNoGaps() {
        OpaqueRaster raster = new OpaqueRaster(100, 100);
        raster.segment(3.3f, 5.1f, 90.7f, 71.9f, 0.1f, 0xff000000);
        int previousRow = -1;
        for (int x = 5; x < 88; x++) {
            int row = -1;
            for (int y = 0; y < 100; y++) if (raster.pixels[y * 100 + x] == 0xff000000) { row = y; break; }
            assertTrue("column " + x + " empty", row >= 0);
            if (previousRow >= 0) assertTrue(Math.abs(row - previousRow) <= 1);
            previousRow = row;
        }
    }

    @Test public void strokeUsesEndPointWidthPerSegmentAndStaysInBounds() {
        OpaqueRaster raster = new OpaqueRaster(200, 100);
        raster.resetDirty();
        InkStroke stroke = new InkStroke(new float[]{20, 100, 180}, new float[]{50, 50, 50},
            new float[]{99, 4, 30}, 3);
        stroke.draw(raster, 0x00315f88);
        // First segment is 4 wide (its end point), so y=45 is unpainted mid-segment...
        assertEquals(0xffffffff, raster.pixels[45 * 200 + 60]);
        // ...and the second segment is 30 wide.
        assertEquals(0xff315f88, raster.pixels[40 * 200 + 140]);
        int[] b = stroke.bounds(200, 100);
        for (int y = 0; y < 100; y++) for (int x = 0; x < 200; x++)
            if (raster.pixels[y * 200 + x] != 0xffffffff)
                assertTrue(x >= b[0] && x < b[2] && y >= b[1] && y < b[3]);
    }

    @Test public void swapRegionUndoesAndRedoes() {
        OpaqueRaster raster = new OpaqueRaster(50, 50);
        int[] blank = raster.pixels.clone();
        int[] saved = raster.copyRegion(10, 10, 20, 20);
        raster.segment(15, 15, 25, 25, 6, 0xff000000);
        int[] painted = raster.pixels.clone();
        raster.swapRegion(10, 10, 20, 20, saved);
        assertArrayEquals(blank, raster.pixels);
        raster.swapRegion(10, 10, 20, 20, saved);
        assertArrayEquals(painted, raster.pixels);
    }

    @Test public void paletteRoundTripsAndRejectsGarbage() {
        Palette palette = Palette.defaults();
        palette.selected = 3;
        Palette copy = Palette.parse(palette.serialize(), 3);
        assertEquals(palette.serialize(), copy.serialize());
        assertEquals(3, copy.selected);
        assertEquals(0xff6f9ec5, Palette.parse("C7D8E8:6F9EC5", 0).entries.get(0).preview);
        assertEquals(0, copy.entries.get(0).preview);
        assertEquals(Palette.defaults().serialize(), Palette.parse("zz,:", 0).serialize());
        assertEquals(1, Palette.parse("123456:auto", 9).entries.size());
        assertEquals(0, Palette.parse("123456:auto", 9).selected);
    }

    @Test public void previewChoiceIsAlwaysAVisibleRequest() {
        int[] visible = PreviewColors.SURVEYED_VISIBLE;
        assertEquals(0xff000000, PreviewColors.nearest(0xff000000, visible));
        assertEquals(0xff6f9ec5, PreviewColors.nearest(0xff6f9ec5, visible));
        for (int color : new int[]{0xffc7d8e8, 0xffffffff, 0xffff8080, 0xff123456}) {
            int chosen = PreviewColors.nearest(color, visible) & 0xffffff;
            boolean found = false;
            for (int v : visible) found |= v == chosen;
            assertTrue(found);
        }
    }

    @Test public void autoPreviewIsAVisibleGrayOfMatchingLightness() {
        int[] visible = PreviewColors.SURVEYED_VISIBLE;
        assertEquals(0xffe0e0e0, PreviewColors.nearestGray(0xffffc4d5, visible)); // pale pink
        assertEquals(0xff000000, PreviewColors.nearestGray(0xff000000, visible));
        assertEquals(0xff404040, PreviewColors.nearestGray(0xff404040, visible));
        for (int color : new int[]{0xffc7d8e8, 0xff6f9ec5, 0xff00aa95, 0xff4d69ff, 0xff59002b}) {
            int chosen = PreviewColors.nearestGray(color, visible);
            assertEquals((chosen >> 16) & 0xff, chosen & 0xff);
            assertEquals((chosen >> 8) & 0xff, chosen & 0xff);
        }
    }

    @Test public void onlyWhiteCountsAsWhite() {
        assertTrue(PreviewColors.isWhite(0xffffffff));
        assertTrue(PreviewColors.isWhite(0xfffefefe));
        assertFalse(PreviewColors.isWhite(0xfff8f8f8));
        assertFalse(PreviewColors.isWhite(0xfffcead6)); // pale warm pick
    }

    @Test public void measuredApparentGraysDriveTheChoice() {
        java.util.Map<Integer, Integer> apparent = new java.util.HashMap<>();
        // Firmware draws light requests much darker than asked: F8F8F8 looks mid gray.
        apparent.put(0xff000000, 0xff000000);
        apparent.put(0xff808080, 0xff4d4d4d);
        apparent.put(0xffe0e0e0, 0xff808080);
        apparent.put(0xfff8f8f8, 0xffb3b3b3);
        apparent.put(0xfffefefe, 0xffe6e6e6);
        assertEquals(0xfffefefe, PreviewColors.nearestApparentGray(0xffffffff, apparent));
        assertEquals(0xfffefefe, PreviewColors.nearestApparentGray(0xffc7d8e8, apparent)); // pale blue
        assertEquals(0xffe0e0e0, PreviewColors.nearestApparentGray(0xff6f9ec5, apparent));
        assertEquals(0xff000000, PreviewColors.nearestApparentGray(0xff101010, apparent));
        assertEquals(0xff000000, PreviewColors.nearestApparentGray(0xff123456, new java.util.HashMap<>()));
        // Unmeasured grays count at face value, matching nearestGray.
        java.util.Map<Integer, Integer> faceValue = new java.util.HashMap<>();
        for (int gray : new int[]{0xff000000, 0xff404040, 0xff808080, 0xffc0c0c0, 0xffe0e0e0, 0xfff8f8f8})
            faceValue.put(gray, gray);
        assertEquals(PreviewColors.nearestGray(0xffffc4d5, PreviewColors.SURVEYED_VISIBLE),
            PreviewColors.nearestApparentGray(0xffffc4d5, faceValue));
    }

    private static double distance(double px, double py, double x0, double y0, double x1, double y1) {
        double dx = x1 - x0, dy = y1 - y0, len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - x0) * dx + (py - y0) * dy) / len2));
        return Math.hypot(px - (x0 + t * dx), py - (y0 + t * dy));
    }
}

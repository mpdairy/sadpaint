package dev.tilesmile;

import org.junit.Test;
import static org.junit.Assert.*;

public class OpaqueRasterTest {
    @Test public void tenOverlapsAreIdenticalAndOpaque() {
        OpaqueRaster raster = new OpaqueRaster(100, 100);
        stroke(raster, 0x006f9ec5);
        int[] once = raster.pixels.clone();
        for (int i = 0; i < 9; i++) stroke(raster, 0x006f9ec5);
        assertArrayEquals(once, raster.pixels);
        assertEquals(0xff6f9ec5, raster.pixels[50 * 100 + 50]);
    }

    @Test public void shadedInkFollowsTheDitherByCanvasPosition() {
        OpaqueRaster raster = new OpaqueRaster(100, 100);
        int[] pattern = Shade.pattern(0x6f9ec5, -Shade.STEPS / 2);
        raster.pattern = pattern;
        raster.segment(10, 50, 90, 50, 20, 0xff6f9ec5);
        int black = 0;
        for (int y = 44; y < 56; y++) for (int x = 16; x < 84; x++) {
            int pixel = raster.pixels[y * 100 + x];
            assertEquals(pattern[(y % 8) * 8 + x % 8], pixel);
            if (pixel == 0xff000000) black++;
        }
        // Half black, half the color.
        assertEquals(12 * 68 / 2, black, 12);
        for (int c : Shade.pattern(0x6f9ec5, -Shade.STEPS)) assertEquals(0xff000000, c);
        for (int c : Shade.pattern(0x6f9ec5, Shade.STEPS)) assertEquals(0xffffffff, c);
        for (int c : Shade.pattern(0x6f9ec5, 0)) assertEquals(0xff6f9ec5, c);
    }

    @Test public void newColorReplacesOldColor() {
        OpaqueRaster raster = new OpaqueRaster(100, 100);
        stroke(raster, 0xff000000);
        stroke(raster, 0xff6f9ec5);
        assertEquals(0xff6f9ec5, raster.pixels[50 * 100 + 50]);
    }

    @Test public void fastThinStrokeHasNoGaps() {
        OpaqueRaster raster = new OpaqueRaster(100, 100);
        raster.point(2, 50, 0, 0xff000000);
        raster.point(98, 50, 0, 0xff000000);
        for (int x = 2; x < 98; x++) assertEquals(0xff000000, raster.pixels[50 * 100 + x]);
    }

    @Test public void dirtyBoundsContainEveryChangedPixelAndStayLocal() {
        OpaqueRaster raster = new OpaqueRaster(100, 100);
        raster.resetDirty();
        raster.point(30, 40, 0.5f, 0xff000000);
        assertTrue(raster.isDirty());
        assertTrue(raster.right - raster.left < 32);
        for (int y = 0; y < 100; y++) for (int x = 0; x < 100; x++) {
            if (raster.pixels[y * 100 + x] != 0xffffffff) {
                assertTrue(x >= raster.left && x < raster.right && y >= raster.top && y < raster.bottom);
            }
        }
    }

    @Test public void pressureIsClampedAndMonotonic() {
        assertEquals(1.5f, OpaqueRaster.diameter(-1), 0);
        assertEquals(32f, OpaqueRaster.diameter(2), 0);
        assertEquals(1.5f, OpaqueRaster.diameter(Float.NaN), 0);
        for (int i = 0; i < 100; i++)
            assertTrue(OpaqueRaster.diameter(i / 100f) < OpaqueRaster.diameter((i + 1) / 100f));
    }

    @Test public void edgeClippingAndSeparateStrokesAreSafe() {
        OpaqueRaster raster = new OpaqueRaster(100, 100);
        raster.point(0, 0, 1, 0xff000000);
        raster.end();
        raster.point(99, 99, 1, 0xff000000);
        assertEquals(0xffffffff, raster.pixels[50 * 100 + 50]);
        raster.point(Float.NaN, 50, 1, 0xff000000);
        raster.point(Float.MAX_VALUE, 50, 1, 0xff000000);
    }

    private void stroke(OpaqueRaster raster, int color) {
        raster.end();
        raster.point(20, 50, 0.5f, color);
        raster.point(80, 50, 0.5f, color);
        raster.end();
    }
}

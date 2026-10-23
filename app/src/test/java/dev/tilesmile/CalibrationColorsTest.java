package dev.tilesmile;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class CalibrationColorsTest {
    @Test public void pagesAreOpaqueAndOrderedLightToDark() {
        for (int page = 0; page < CalibrationColors.PAGES; page++) {
            int[] colors = CalibrationColors.page(page);
            assertTrue("page " + page, colors.length > 30);
            double previous = 2;
            for (int i = 0; i < colors.length; i += CalibrationColors.COLUMNS) {
                assertEquals(0xff000000, colors[i] & 0xff000000);
                double l = CalibrationColors.oklab(colors[i])[0];
                assertTrue("page " + page + " row lightness", l <= previous + 0.02);
                previous = l;
            }
        }
    }

    @Test public void huePagesHaveTheirHueAndGraysAreNeutral() {
        for (int page = 0; page < 12; page++) {
            int[] colors = CalibrationColors.page(page);
            int vivid = colors[CalibrationColors.COLUMNS * 4 + CalibrationColors.COLUMNS - 1];
            double[] lab = CalibrationColors.oklab(vivid);
            double hue = (Math.toDegrees(Math.atan2(lab[2], lab[1])) + 360) % 360;
            double delta = Math.abs(((hue - page * 30) + 540) % 360 - 180);
            assertTrue("page " + page + " hue " + hue, delta < 6);
        }
        for (int gray : CalibrationColors.page(12))
            assertTrue(Math.abs(((gray >> 16) & 0xff) - (gray & 0xff)) <= 1);
    }

    @Test public void oklchRoundTrips() {
        int color = CalibrationColors.oklch(0.6, 0.1, Math.toRadians(250));
        double[] lab = CalibrationColors.oklab(color);
        assertEquals(0.6, lab[0], 0.01);
        assertEquals(0.1, Math.hypot(lab[1], lab[2]), 0.01);
    }

    @Test public void reviewGroupsByHueThenLightnessWithGraysLast() {
        List<Integer> sorted = CalibrationColors.sortForReview(Arrays.asList(
            0xff808080, 0xff315f88, 0xffc7d8e8, 0xffff0000, 0xff880000));
        assertEquals(0xff808080, (int) sorted.get(4));
        assertEquals(0xffff0000, (int) sorted.get(0));
        assertEquals(0xff880000, (int) sorted.get(1));
        assertEquals(0xffc7d8e8, (int) sorted.get(2));
        assertEquals(0xff315f88, (int) sorted.get(3));
    }
}

package dev.tilesmile;

import static org.junit.Assert.*;

import com.onyx.android.sdk.data.note.TouchPoint;
import org.junit.Test;

public class GlazeTest {
    private static void stroke(OpaqueRaster raster, int color) {
        Glaze glaze = new Glaze(raster, Shade.pattern(color, 0), 16, 4096);
        for (int x = 10; x <= 50; x += 3) glaze.add(x == 10 ? 0 : 1, new TouchPoint(x, 30, 4096, 1, 0));
    }

    @Test public void onWhiteItIsThePaintColor() {
        OpaqueRaster raster = new OpaqueRaster(64, 64);
        stroke(raster, 0xff3366cc);
        assertEquals(0xff3366cc, raster.pixels[30 * 64 + 30]);
        assertEquals(0xffffffff, raster.pixels[5 * 64 + 30]);
    }

    @Test public void linesShowThroughAndOverlappingDabsTintOnce() {
        OpaqueRaster raster = new OpaqueRaster(64, 64);
        for (int y = 0; y < 64; y++) raster.pixels[y * 64 + 30] = 0xff000000;
        stroke(raster, 0xff808080);
        assertEquals(0xff000000, raster.pixels[30 * 64 + 30]);
        assertEquals(0xff808080, raster.pixels[30 * 64 + 20]);
        // A second stroke deepens the glaze.
        stroke(raster, 0xff808080);
        assertEquals(0xff404040, raster.pixels[30 * 64 + 20]);
    }
}

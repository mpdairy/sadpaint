package dev.tilesmile;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

/** Defaults for the prototype's five blue finished inks plus black. */
final class PreviewPreferences {
    private static final int KNOWN_BLUE = 0xff6f9ec5;

    static boolean hasKnownBlue(Context context) {
        return "visible".equals(context.getSharedPreferences("preview-visibility-v1", 0)
            .getString("#6F9EC5", ""));
    }

    static int choose(Context context, int finishedColor) {
        // Visibility alone does not establish color fidelity. The original blue
        // was also physically reported to look blue during native drawing.
        int fallback = finishedColor == 0xff000000 || !hasKnownBlue(context) ? finishedColor : KNOWN_BLUE;
        SharedPreferences favorites = context.getSharedPreferences("preview-matches-v1", 0);
        int chosen = favorites.getInt(String.format(Locale.US, "favorite:#%06X", finishedColor & 0xffffff), fallback);
        String visibility = context.getSharedPreferences("preview-visibility-v1", 0)
            .getString(String.format(Locale.US, "#%06X", chosen & 0xffffff), "");
        return "invisible".equals(visibility) ? fallback : chosen;
    }
}

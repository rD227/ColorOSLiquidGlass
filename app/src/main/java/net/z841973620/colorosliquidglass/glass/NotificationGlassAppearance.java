package net.z841973620.colorosliquidglass.glass;

import android.content.res.Configuration;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrixColorFilter;

/** Material treatment for wallpaper-backed notification plates, independent of capture. */
final class NotificationGlassAppearance {
    // Keep wallpaper detail while giving night-mode white text a dark surface.
    // A white pixel becomes RGB 89 (about 7:1 contrast with opaque white text).
    // Leave alpha unchanged so SystemUI continues to own row fade/expansion animations.
    private static final ColorFilter NIGHT_BACKDROP = new ColorMatrixColorFilter(new float[] {
            0.35f, 0, 0, 0, 0,
            0, 0.35f, 0, 0, 0,
            0, 0, 0.35f, 0, 0,
            0, 0, 0, 1, 0
    });

    private NotificationGlassAppearance() {}

    static ColorFilter backdropFilter(boolean locked, int uiMode) {
        boolean night = (uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        return !locked && night ? NIGHT_BACKDROP : null;
    }
}

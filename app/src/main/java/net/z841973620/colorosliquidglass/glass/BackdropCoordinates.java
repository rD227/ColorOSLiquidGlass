package net.z841973620.colorosliquidglass.glass;

import android.graphics.Matrix;

/** Local glass coordinates → fixed root wallpaper texture, including perspective. */
final class BackdropCoordinates {
    private BackdropCoordinates() {}

    static void map(Matrix targetToRoot, float left, float top,
            float width, float height, float wallpaperZoom, float[] result) {
        Matrix mapping = new Matrix(targetToRoot);
        // The shader starts at Drawable bounds (0,0); the view matrix starts at View (0,0).
        mapping.preTranslate(left, top);
        // Wallpaper zoom is a sampling transform, not a reason to re-render the texture.
        mapping.postScale(1f / wallpaperZoom, 1f / wallpaperZoom, width * .5f, height * .5f);
        mapping.getValues(result);
    }
}

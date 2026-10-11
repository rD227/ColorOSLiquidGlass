package net.z841973620.colorosliquidglass.glass;

import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/** Uses real Android pixels to reproduce bright wallpaper under white night-mode text. */
public final class NotificationAppearanceProbe {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static int render(Bitmap output, Paint paint, boolean locked, int mode, int alpha) {
        output.eraseColor(Color.TRANSPARENT);
        paint.setColor(Color.WHITE);
        paint.setAlpha(alpha);
        paint.setColorFilter(NotificationGlassAppearance.backdropFilter(locked, mode));
        new Canvas(output).drawRect(0, 0, 8, 8, paint);
        return output.getPixel(4, 4);
    }

    public static void main(String[] args) {
        Bitmap output = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        int night = Configuration.UI_MODE_TYPE_NORMAL | Configuration.UI_MODE_NIGHT_YES;
        int day = Configuration.UI_MODE_TYPE_NORMAL | Configuration.UI_MODE_NIGHT_NO;
        int shaded = render(output, paint, false, night, 255);
        double luminance = Math.pow((Color.red(shaded) / 255.0 + 0.055) / 1.055, 2.4);
        double contrast = 1.05 / (luminance + 0.05);
        check(contrast >= 7.0, "bright wallpaper hides white night-mode text: " + contrast);
        check(Color.alpha(shaded) == 255, "night treatment changed plate opacity");
        check(Color.alpha(render(output, paint, false, night, 96)) == 96,
                "night treatment changed SystemUI animation alpha");
        check(Color.alpha(render(output, paint, false, night, 0)) == 0,
                "hidden notification became visible");
        // Reuse the same paint, as SystemUI reuses notification rows during transitions.
        check(render(output, paint, true, night, 255) == Color.WHITE,
                "locking retained unlocked night treatment");
        check(render(output, paint, false, night, 255) == shaded,
                "unlocking did not restore night treatment");
        check(render(output, paint, false, day, 255) == Color.WHITE,
                "switching to day retained night treatment");
        check(render(output, paint, true, day, 255) == Color.WHITE,
                "day lockscreen appearance changed");
        output.recycle();
        System.out.println("PASS: bright wallpaper contrast=" + contrast
                + ", row alpha, lock/unlock and day/night transitions");
    }
}

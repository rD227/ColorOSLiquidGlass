package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

/** Compare fixed wallpaper points, not glass-local points, as a crop moves one pixel/frame. */
public final class TemporalBlurProbe {
    private static final Paint LINEAR = new Paint(Paint.FILTER_BITMAP_FLAG);

    private static void scenario(int kind, int width, int height, int tolerance) {
        Bitmap world = Bitmap.createBitmap(512, 192, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[512 * 192];
        for (int y = 0; y < 192; y++) for (int x = 0; x < 512; x++) {
            int c = kind == 0 ? (x % 8 < 4 ? 0 : 255)
                    : kind == 1 ? (x == 168 ? 0 : 255)
                    : (x >= 156 && x < 181 ? 0 : 255);
            pixels[y * 512 + x] = Color.rgb(c, c, c);
        }
        world.setPixels(pixels, 0, 512, 0, 0, 512, 192);
        int[][] values = new int[16][96];
        for (int offset = 0; offset < 16; offset++) {
            Bitmap crop = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            new Canvas(crop).drawBitmap(world, new Rect(offset, 32, offset + width, 32 + height),
                    new Rect(0, 0, width, height), null);
            Bitmap blurred = BackdropBlur.blurCopy(crop, 32);
            Bitmap expanded = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            new Canvas(expanded).drawBitmap(blurred, null, new Rect(0, 0, width, height), LINEAR);
            for (int x = 0; x < 96; x++) {
                values[offset][x] = Color.red(expanded.getPixel(128 + x - offset, 64));
            }
            expanded.recycle(); blurred.recycle(); crop.recycle();
        }
        int maxRange = 0;
        for (int x = 0; x < 96; x++) {
            int low = 255, high = 0;
            for (int offset = 0; offset < 16; offset++) {
                low = Math.min(low, values[offset][x]);
                high = Math.max(high, values[offset][x]);
            }
            maxRange = Math.max(maxRange, high - low);
        }
        world.recycle();
        System.out.println("pattern=" + kind + " crop=" + width + "x" + height
                + " fixed-point range=" + maxRange);
        if (maxRange > tolerance) throw new AssertionError("moving crop aliases: " + maxRange);
    }

    public static void main(String[] args) {
        // Old single-step reduction produced ranges 255, 26, 33 in these fixtures.
        scenario(0, 256, 128, 1);
        scenario(1, 256, 128, 4);
        scenario(2, 256, 128, 6);
        // Non-power-of-two dimensions must also retain thin features while moving.
        scenario(1, 259, 131, 6);
        scenario(2, 259, 131, 8);
        Bitmap tiny = Bitmap.createBitmap(1, 3, Bitmap.Config.ARGB_8888);
        tiny.eraseColor(Color.WHITE);
        Bitmap result = BackdropBlur.blurCopy(tiny, 32);
        if (result == tiny || result.getPixel(0, 0) != Color.WHITE) {
            throw new AssertionError("tiny source ownership/color");
        }
        result.recycle(); tiny.recycle();
        System.out.println("PASS: temporal antialiasing, odd dimensions and tiny images");
    }
}

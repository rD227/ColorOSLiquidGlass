package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;
import java.util.Arrays;

/** Run with app_process: uses Android's real Bitmap implementation, no mocked pixels. */
public final class GlassPerformanceProbe {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        Bitmap source = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[512 * 384];
        for (int y = 0; y < 384; y++) for (int x = 0; x < 512; x++) {
            pixels[y * 512 + x] = 0xff000000 | ((x * 13 & 255) << 16)
                    | ((y * 11 & 255) << 8) | ((x + y) & 255);
        }
        source.setPixels(pixels, 0, 512, 0, 0, 512, 384);
        int generation = source.getGenerationId();
        Bitmap first = BackdropBlur.blurCopy(source, 32);
        Bitmap second = BackdropBlur.blurCopy(source, 32);
        check(first != source, "blur must not alias producer frame");
        check(source.getGenerationId() == generation, "blur mutated producer frame");
        int[] after = new int[pixels.length];
        source.getPixels(after, 0, 512, 0, 0, 512, 384);
        check(Arrays.equals(pixels, after), "producer pixels changed");
        check(first.sameAs(second), "repeated blur accumulates on original image");
        check(first.getWidth() == 64 && first.getHeight() == 48, "blur was not downsampled");
        Bitmap clear = BackdropBlur.blurCopy(source, 0);
        check(clear.sameAs(source), "zero blur changes pixels");
        source.setPixel(128, 128, 0xffffffff);
        check(source.getGenerationId() != generation, "producer update must change cache revision");

        first.recycle(); second.recycle(); clear.recycle();

        long[] full = new long[12], small = new long[12];
        for (int i = -3; i < 12; i++) {
            Bitmap copy = source.copy(Bitmap.Config.ARGB_8888, true);
            long start = System.nanoTime();
            BackdropBlur.blur(copy, 32);
            long elapsed = System.nanoTime() - start;
            copy.recycle();
            start = System.nanoTime();
            Bitmap reduced = BackdropBlur.blurCopy(source, 32);
            long reducedElapsed = System.nanoTime() - start;
            reduced.recycle();
            if (i >= 0) { full[i] = elapsed; small[i] = reducedElapsed; }
        }
        Arrays.sort(full); Arrays.sort(small);
        System.out.println("PASS: source ownership, repeatability, downsampling, zero blur");
        System.out.println("512x384 radius32 median: full=" + full[6]/1e6
                + "ms reduced=" + small[6]/1e6 + "ms (blur only, not end-to-end FPS)");
        source.recycle();
    }
}

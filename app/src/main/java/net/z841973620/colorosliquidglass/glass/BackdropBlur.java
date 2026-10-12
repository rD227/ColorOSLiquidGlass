package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;

/**
 * CPU stack-blur for target-local content captures.
 * <p>
 * Ordinary folder wallpaper uses a shared GPU texture. Content overlays still feed cropped
 * bitmaps into AGSL and use this independent, area-filtered low-resolution blur.
 */
public final class BackdropBlur {
    private BackdropBlur() {}

    /** Independent low-resolution result: never blur a producer's reusable frame in place. */
    public static Bitmap blurCopy(Bitmap src, float radiusPx) {
        // Average the entire source footprint before decimating. Bilinear 8x resizing
        // only sees four texels; repeated bilinear halving still aliases at odd sizes.
        float reduction = Math.max(1f, Math.min(8f, radiusPx / 2f));
        int width = Math.max(1, Math.round(src.getWidth() / reduction));
        int height = Math.max(1, Math.round(src.getHeight() / reduction));
        Bitmap result = areaResize(src, width, height);
        int radius = Math.round(radiusPx * width / src.getWidth());
        if (radius > 0) stackBlur(result, Math.min(radius, 64));
        return result;
    }

    /** Separable area filter, including fractional edge coverage for non-integer ratios. */
    private static Bitmap areaResize(Bitmap src, int width, int height) {
        int sw = src.getWidth(), sh = src.getHeight();
        if (sw == width && sh == height) return src.copy(Bitmap.Config.ARGB_8888, true);
        int[] row = new int[sw];
        float[] horizontal = new float[width * sh * 4];
        double sx = sw / (double) width, sy = sh / (double) height;
        for (int y = 0; y < sh; y++) {
            src.getPixels(row, 0, sw, 0, y, sw, 1);
            for (int x = 0; x < width; x++) {
                double start = x * sx, end = (x + 1) * sx;
                int out = (y * width + x) * 4;
                for (int ix = (int) start; ix < Math.min(sw, (int) Math.ceil(end)); ix++) {
                    float weight = (float) ((Math.min(end, ix + 1) - Math.max(start, ix)) / sx);
                    int c = row[ix];
                    float a = (c >>> 24) / 255f;
                    horizontal[out] += (c >>> 24) * weight;
                    horizontal[out + 1] += ((c >> 16) & 255) * a * weight;
                    horizontal[out + 2] += ((c >> 8) & 255) * a * weight;
                    horizontal[out + 3] += (c & 255) * a * weight;
                }
            }
        }
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            double start = y * sy, end = (y + 1) * sy;
            for (int x = 0; x < width; x++) {
                float a = 0, r = 0, g = 0, b = 0;
                for (int iy = (int) start; iy < Math.min(sh, (int) Math.ceil(end)); iy++) {
                    float weight = (float) ((Math.min(end, iy + 1) - Math.max(start, iy)) / sy);
                    int in = (iy * width + x) * 4;
                    a += horizontal[in] * weight;
                    r += horizontal[in + 1] * weight;
                    g += horizontal[in + 2] * weight;
                    b += horizontal[in + 3] * weight;
                }
                // Bitmap.setPixels takes straight ARGB; fully transparent footprints have no RGB.
                float unpremultiply = a == 0 ? 0 : 255f / a;
                pixels[y * width + x] = (Math.round(a) << 24)
                        | (Math.round(r * unpremultiply) << 16)
                        | (Math.round(g * unpremultiply) << 8) | Math.round(b * unpremultiply);
            }
        }
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.setPixels(pixels, 0, width, 0, 0, width, height);
        return result;
    }

    /**
     * @param radiusPx blur radius in pixels; {@code <= 0} returns {@code src} unchanged
     * @return blurred ARGB_8888 bitmap (may be {@code src} mutated in-place when mutable)
     */
    public static Bitmap blur(Bitmap src, float radiusPx) {
        if (src == null || src.isRecycled()) return src;
        int radius = Math.round(radiusPx);
        if (radius < 1) return src;
        radius = Math.min(radius, 64);
        Bitmap bitmap;
        if (src.isMutable() && src.getConfig() == Bitmap.Config.ARGB_8888) {
            bitmap = src;
        } else {
            bitmap = src.copy(Bitmap.Config.ARGB_8888, true);
            if (bitmap == null) return src;
        }
        stackBlur(bitmap, radius);
        return bitmap;
    }

    /** Mario Klingemann stack blur (in-place). */
    private static void stackBlur(Bitmap bitmap, int radius) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 0 || h <= 0) return;
        int[] pix = new int[w * h];
        bitmap.getPixels(pix, 0, w, 0, 0, w, h);

        int wm = w - 1;
        int hm = h - 1;
        int wh = w * h;
        int div = radius + radius + 1;

        int[] r = new int[wh];
        int[] g = new int[wh];
        int[] b = new int[wh];
        int[] vmin = new int[Math.max(w, h)];

        int divsum = (div + 1) >> 1;
        divsum *= divsum;
        int[] dv = new int[256 * divsum];
        for (int i = 0; i < dv.length; i++) dv[i] = i / divsum;

        int yi = 0;
        int yw = 0;
        int[][] stack = new int[div][3];

        for (int y = 0; y < h; y++) {
            int rsum = 0, gsum = 0, bsum = 0;
            int rinsum = 0, ginsum = 0, binsum = 0;
            int routsum = 0, goutsum = 0, boutsum = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = pix[yi + Math.min(wm, Math.max(i, 0))];
                int[] sir = stack[i + radius];
                sir[0] = (p >> 16) & 0xff;
                sir[1] = (p >> 8) & 0xff;
                sir[2] = p & 0xff;
                int rbs = radius + 1 - Math.abs(i);
                rsum += sir[0] * rbs;
                gsum += sir[1] * rbs;
                bsum += sir[2] * rbs;
                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }
            }
            int stackpointer = radius;
            for (int x = 0; x < w; x++) {
                r[yi] = dv[rsum];
                g[yi] = dv[gsum];
                b[yi] = dv[bsum];

                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;

                int stackstart = stackpointer - radius + div;
                int[] sir = stack[stackstart % div];

                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];

                if (y == 0) vmin[x] = Math.min(x + radius + 1, wm);
                int p = pix[yw + vmin[x]];

                sir[0] = (p >> 16) & 0xff;
                sir[1] = (p >> 8) & 0xff;
                sir[2] = p & 0xff;

                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];

                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer % div];

                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];

                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];

                yi++;
            }
            yw += w;
        }

        for (int x = 0; x < w; x++) {
            int rsum = 0, gsum = 0, bsum = 0;
            int rinsum = 0, ginsum = 0, binsum = 0;
            int routsum = 0, goutsum = 0, boutsum = 0;
            int yp = -radius * w;
            for (int i = -radius; i <= radius; i++) {
                yi = Math.max(0, yp) + x;
                int[] sir = stack[i + radius];
                sir[0] = r[yi];
                sir[1] = g[yi];
                sir[2] = b[yi];
                int rbs = radius + 1 - Math.abs(i);
                rsum += r[yi] * rbs;
                gsum += g[yi] * rbs;
                bsum += b[yi] * rbs;
                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }
                if (i < hm) yp += w;
            }
            yi = x;
            int stackpointer = radius;
            for (int y = 0; y < h; y++) {
                pix[yi] = (pix[yi] & 0xff000000)
                        | (dv[rsum] << 16)
                        | (dv[gsum] << 8)
                        | dv[bsum];

                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;

                int stackstart = stackpointer - radius + div;
                int[] sir = stack[stackstart % div];

                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];

                if (x == 0) vmin[y] = Math.min(y + radius + 1, hm) * w;
                int p = x + vmin[y];

                sir[0] = r[p];
                sir[1] = g[p];
                sir[2] = b[p];

                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];

                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer];

                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];

                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];

                yi += w;
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h);
    }
}

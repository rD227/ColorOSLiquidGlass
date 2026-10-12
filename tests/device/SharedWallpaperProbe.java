package net.z841973620.colorosliquidglass.glass;

import android.content.res.Resources;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Field;

/** Real GPU/cache/AGSL checks. Run with the built APK on app_process's classpath. */
public final class SharedWallpaperProbe {
    public static final class FolderIcon extends FrameLayout {
        FolderIcon(Context context) { super(context); }
    }
    public static final class DragView extends FrameLayout {
        DragView(Context context) { super(context); }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void coordinates() {
        Matrix transform = new Matrix();
        transform.setValues(new float[] { .8f, -.2f, 41.25f, .3f, 1.1f, 52.75f,
                .001f, -.0003f, 1f });
        float[] matrix = new float[9];
        BackdropCoordinates.map(transform, 7, 13, 512, 192, 1.2f, matrix);
        for (int i = 0; i < 10; i++) {
            float x = i * 7.1f, y = i * 3.5f;
            float[] expected = {x + 7, y + 13};
            transform.mapPoints(expected);
            expected[0] = (expected[0] - 256) / 1.2f + 256;
            expected[1] = (expected[1] - 96) / 1.2f + 96;
            float w = matrix[6] * x + matrix[7] * y + matrix[8];
            float actualX = (matrix[0] * x + matrix[1] * y + matrix[2]) / w;
            float actualY = (matrix[3] * x + matrix[4] * y + matrix[5]) / w;
            check(Math.abs(actualX - expected[0]) < .001f, "projective X / bounds / zoom");
            check(Math.abs(actualY - expected[1]) < .001f, "projective Y / bounds / zoom");
        }
    }

    private static void routing() throws Exception {
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        FrameLayout root = new FrameLayout(context);
        FolderIcon folder = new FolderIcon(context);
        View host = new View(context);
        root.addView(folder); folder.addView(host);
        BackdropCapture capture = BackdropCapture.register(host);
        check(capture.usesSharedWallpaper(), "ordinary folder must use shared wallpaper");
        root.layout(0, 0, 512, 192);
        folder.layout(31, 18, 231, 168); host.layout(13, 7, 113, 107);
        root.scrollTo(9, 6); folder.scrollTo(5, 3);
        folder.setRotation(12); folder.setRotationY(35); folder.setScaleX(.85f);
        host.setTranslationX(.25f);
        float[] actual = new float[9];
        SharedWallpaperBackdrop.coordinates(host, 0, 0, actual);
        Matrix reference = new Matrix(); host.transformMatrixToGlobal(reference);
        Matrix mapping = new Matrix(); mapping.setValues(actual);
        float[] expectedPoint = {25, 40}, actualPoint = {25, 40};
        reference.mapPoints(expectedPoint); mapping.mapPoints(actualPoint);
        check(Math.abs(expectedPoint[0] - actualPoint[0]) < .001f
                && Math.abs(expectedPoint[1] - actualPoint[1]) < .001f,
                "ancestor matrices must match Android's View mapping");
        root.setScaleX(0); root.setScaleY(0);
        SharedWallpaperBackdrop.coordinates(host, 0, 0, actual);
        mapping.setValues(actual); actualPoint = new float[] {25, 40}; mapping.mapPoints(actualPoint);
        check(Math.abs(expectedPoint[0] - actualPoint[0]) < .001f
                && Math.abs(expectedPoint[1] - actualPoint[1]) < .001f,
                "root collapse must not corrupt descendant-to-root mapping");
        root.setScaleX(1); root.setScaleY(1);
        java.lang.reflect.Method update = BackdropCapture.class.getDeclaredMethod(
                "updateSharedCoordinates", View.class);
        update.setAccessible(true);
        check((boolean) update.invoke(capture, host), "first mapping must redraw");
        check(!(boolean) update.invoke(capture, host), "stationary mapping must settle");
        host.setTranslationX(.5f);
        check((boolean) update.invoke(capture, host), "fractional movement must redraw");
        folder.setRotationY(36);
        check((boolean) update.invoke(capture, host), "rotationY must redraw");
        host.setTag("colg_desktop_popup");
        check(!capture.usesSharedWallpaper(), "popup needs its icon backdrop");
        host.setTag(null);
        BackdropCapture.setOverlaySource(host, root);
        check(!capture.usesSharedWallpaper(), "overlay needs local capture");
        BackdropCapture.setOverlaySource(host, null);
        DragView drag = new DragView(context);
        root.removeView(folder); root.addView(drag); drag.addView(folder);
        check(!capture.usesSharedWallpaper(), "drag needs local icon capture");
        drag.removeView(folder); root.addView(folder);
        check(capture.usesSharedWallpaper(), "drop must restore shared wallpaper");
        View card = new View(context);
        root.addView(card); card.layout(0, 0, 128, 96);
        BackdropCapture.markWallpaperOnly(card);
        BackdropCapture.markSharedWallpaper(card);
        BackdropCapture cardCapture = BackdropCapture.register(card);
        check(cardCapture.usesSharedWallpaper(), "notification card must share GPU wallpaper");
        WallpaperScaleTracker.setImmediate(1.2f);
        check(BackdropCapture.sharedWallpaperZoom(card) == 1f, "notification must ignore launcher zoom");
        check(BackdropCapture.sharedWallpaperZoom(host) == 1.2f, "folder must retain launcher zoom");
        WallpaperScaleTracker.setImmediate(1f);
        check(BackdropCapture.wallpaperSource(card) == WallpaperManager.FLAG_SYSTEM, "unlocked source");
        BackdropCapture.markLockWallpaperOnly(card);
        check(cardCapture.usesSharedWallpaper(), "lock notification must still share texture");
        check(BackdropCapture.wallpaperSource(card) == WallpaperManager.FLAG_LOCK, "locked source");
        for (int i = 0; i < 60; i++) {
            card.setTranslationY(i * .5f);
            BackdropCapture.forceCapture(card);
        }
        for (String name : new String[] {"frontBitmap", "backBitmap"}) {
            Field bitmap = BackdropCapture.class.getDeclaredField(name); bitmap.setAccessible(true);
            check(bitmap.get(cardCapture) == null, "shared notification allocated CPU capture " + name);
        }
        BackdropCapture.markLockContent(card);
        check(!cardCapture.usesSharedWallpaper(), "live lock content must retain content capture");
        BackdropCapture.clearWallpaperOnly(card);
        BackdropCapture.markWallpaperOnly(card);
        check(!cardCapture.usesSharedWallpaper(), "ordinary wallpaper-only hosts must not opt in implicitly");
        BackdropCapture.clearWallpaperOnly(card);
        BackdropCapture.unregister(card);
        BackdropCapture.unregister(host);
    }

    private static RuntimeShader glassShader(Bitmap texture) throws Exception {
        Field field = GlassDrawable.class.getDeclaredField("SHADER");
        field.setAccessible(true);
        RuntimeShader shader = new RuntimeShader((String) field.get(null));
        BitmapShader image = new BitmapShader(texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        image.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        shader.setInputShader("image", image);
        shader.setFloatUniform("size", 128, 96);
        shader.setFloatUniform("sampleMatrixX", 1, 0, 100);
        shader.setFloatUniform("sampleMatrixY", 0, 1, 30);
        shader.setFloatUniform("sampleMatrixW", 0, 0, 1);
        shader.setFloatUniform("cornerRadii", 12, 12, 12, 12);
        for (String name : new String[] {"blurRadius", "refractionHeight", "refractionAmount",
                "depthEffect", "chromaticAberration", "glassIntensity", "reflectionIntensity"}) {
            shader.setFloatUniform(name, 0);
        }
        shader.setFloatUniform("surfaceOpacity", 1);
        return shader;
    }

    public static void main(String[] args) throws Exception {
        routing();
        coordinates();
        Bitmap source = Bitmap.createBitmap(512, 192, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.BLACK);
        Paint white = new Paint(); white.setColor(Color.WHITE);
        new android.graphics.Canvas(source).drawRect(256, 0, 512, 192, white);
        BitmapDrawable drawable = new BitmapDrawable(Resources.getSystem(), source);
        drawable.setTargetDensity(source.getDensity());
        SharedWallpaperBackdrop.TextureCache cache = new SharedWallpaperBackdrop.TextureCache();
        int generation = source.getGenerationId();
        Bitmap blurred = cache.get(drawable, 512, 192, 32);
        check(blurred.getConfig() == Bitmap.Config.HARDWARE, "must remain GPU resident");
        check(source.getGenerationId() == generation, "source must not be mutated");
        Bitmap readable = blurred.copy(Bitmap.Config.ARGB_8888, false);
        int boundary = Color.red(readable.getPixel(255, 96));
        check(boundary > 40 && boundary < 220, "GPU blur must mix step edge");
        check(Color.red(readable.getPixel(20, 96)) < 3, "left side must remain dark");
        check(Color.red(readable.getPixel(490, 96)) > 252, "right side must remain white");
        readable.recycle();
        for (int i = 0; i < 120; i++) {
            Matrix translation = new Matrix(); translation.setTranslate(i * .25f, 30);
            BackdropCoordinates.map(translation, 0, 0, 512, 192, 1 + i / 1000f, new float[9]);
            check(cache.get(new BitmapDrawable(Resources.getSystem(), source), 512, 192, 32)
                    == blurred, "movement/wrapper recreation must reuse texture");
        }
        RuntimeShader shader = glassShader(blurred);
        Paint paint = new Paint(); paint.setShader(shader);
        Bitmap plate = GpuBackdropTexture.render(128, 96, 0,
                canvas -> canvas.drawRect(0, 0, 128, 96, paint));
        Bitmap platePixels = plate.copy(Bitmap.Config.ARGB_8888, false);
        check(Color.alpha(platePixels.getPixel(64, 48)) == 255, "production AGSL must draw");
        // A moving plate must reveal different parts of the SAME fixed background.
        shader.setFloatUniform("sampleMatrixX", 1, 0, 300);
        Bitmap moved = GpuBackdropTexture.render(128, 96, 0,
                canvas -> canvas.drawRect(0, 0, 128, 96, paint));
        Bitmap movedPixels = moved.copy(Bitmap.Config.ARGB_8888, false);
        check(Color.red(movedPixels.getPixel(64, 48)) > Color.red(platePixels.getPixel(64, 48)) + 150,
                "coordinates must move continuously over the shared background");
        Bitmap clear = cache.get(drawable, 512, 192, 0);
        check(clear != blurred, "radius change must invalidate texture");
        Bitmap clearPixels = clear.copy(Bitmap.Config.ARGB_8888, false);
        check(Color.red(clearPixels.getPixel(255, 96)) < 3, "zero radius must bypass blur");
        check(Color.red(clearPixels.getPixel(256, 96)) > 252, "zero radius preserves detail");
        source.eraseColor(Color.RED);
        Bitmap updated = cache.get(drawable, 512, 192, 0);
        check(updated != clear, "bitmap revision must invalidate texture");
        Bitmap updatedPixels = updated.copy(Bitmap.Config.ARGB_8888, false);
        check(updatedPixels.getPixel(256, 96) == Color.RED, "updated source must be visible");
        Bitmap resized = cache.get(drawable, 256, 192, 0);
        check(resized != updated && resized.getWidth() == 256, "root resize must invalidate");
        SharedWallpaperBackdrop.RootTextures sources = new SharedWallpaperBackdrop.RootTextures();
        Bitmap lockSource = Bitmap.createBitmap(512, 192, Bitmap.Config.ARGB_8888);
        lockSource.eraseColor(Color.BLUE);
        BitmapDrawable lockDrawable = new BitmapDrawable(Resources.getSystem(), lockSource);
        Bitmap homeTexture = sources.forSource(WallpaperManager.FLAG_SYSTEM).get(drawable, 512, 192, 32);
        Bitmap lockTexture = sources.forSource(WallpaperManager.FLAG_LOCK).get(lockDrawable, 512, 192, 32);
        for (int i = 0; i < 12; i++) {
            check(sources.forSource(WallpaperManager.FLAG_SYSTEM).get(drawable, 512, 192, 32)
                    == homeTexture, "unlock must reuse home texture across cards");
            check(sources.forSource(WallpaperManager.FLAG_LOCK).get(lockDrawable, 512, 192, 32)
                    == lockTexture, "lock must reuse separate lock texture across cards");
        }
        Bitmap lockPixels = lockTexture.copy(Bitmap.Config.ARGB_8888, false);
        check(lockPixels.getPixel(256, 96) == Color.BLUE, "lock wallpaper must not sample home texture");
        // Uniform wallpaper must stay uniform on the flat face even with optics enabled.
        source.eraseColor(Color.rgb(128, 128, 128));
        Bitmap gray = cache.get(drawable, 512, 192, 0);
        RuntimeShader litShader = glassShader(gray);
        litShader.setFloatUniform("refractionHeight", 12);
        litShader.setFloatUniform("refractionAmount", 10);
        litShader.setFloatUniform("glassIntensity", 1);
        litShader.setFloatUniform("depthEffect", 1);
        litShader.setFloatUniform("reflectionIntensity", 1);
        Paint litPaint = new Paint(); litPaint.setShader(litShader);
        Bitmap lit = GpuBackdropTexture.render(128, 96, 0,
                canvas -> canvas.drawRect(0, 0, 128, 96, litPaint));
        Bitmap litPixels = lit.copy(Bitmap.Config.ARGB_8888, false);
        int face = Color.red(litPixels.getPixel(64, 48));
        for (int y = 20; y < 76; y++) for (int x = 20; x < 108; x++) {
            check(Math.abs(Color.red(litPixels.getPixel(x, y)) - face) <= 1,
                    "flat face has a directional lighting seam at " + x + "," + y);
        }
        // Right edge faces the reflected light; gray on the opposite shaded edge can
        // coincidentally cancel the uniform rim lift and is not a highlight reference.
        check(Color.red(litPixels.getPixel(126, 48)) - face > 2,
                "rim light must remain visible at the edge");
        // Previous immutable frames remain valid after replacement and renderer/reader cleanup.
        Bitmap retained = blurred.copy(Bitmap.Config.ARGB_8888, false);
        check(Color.red(retained.getPixel(255, 96)) == boundary, "old frame lifetime");
        for (Bitmap bitmap : new Bitmap[] {platePixels, movedPixels, clearPixels, updatedPixels,
                retained, source, blurred, plate, moved, clear, updated, resized,
                gray, lit, litPixels, lockSource, homeTexture, lockTexture, lockPixels}) bitmap.recycle();
        System.out.println("PASS: GPU blur, zero radius, cache reuse/invalidation, texture lifetime,");
        System.out.println("      production AGSL, moving texture coordinates, perspective/bounds/zoom,");
        System.out.println("      folder / popup / drag / notification routes, home/lock cache isolation");
    }
}

package net.z841973620.colorosliquidglass.glass;

import android.app.WallpaperManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.WeakHashMap;

/** UI-thread owned wallpaper blurs shared by folders and ordinary notification cards. */
final class SharedWallpaperBackdrop {
    private static final WeakHashMap<View, RootTextures> ROOTS = new WeakHashMap<>();

    private SharedWallpaperBackdrop() {}

    static Bitmap texture(View target, float radius) {
        View root = target.getRootView();
        RootTextures textures = ROOTS.get(root);
        if (textures == null) {
            textures = new RootTextures();
            ROOTS.put(root, textures);
        }
        TextureCache cache = textures.forSource(BackdropCapture.wallpaperSource(target));
        Drawable wallpaper = BackdropCapture.resolveWallpaperDrawable(target.getContext(), target);
        if (wallpaper == null) throw new IllegalStateException("Glass wallpaper is unavailable");
        return cache.get(wallpaper, root.getWidth(), root.getHeight(), radius);
    }

    static void coordinates(View target, float left, float top, float[] values) {
        coordinates(target, left, top, BackdropCapture.sharedWallpaperZoom(target), values);
    }

    static void rootCoordinates(View target, float left, float top, float[] values) {
        coordinates(target, left, top, 1f, values);
    }

    private static void coordinates(View target, float left, float top, float zoom, float[] values) {
        View root = target.getRootView();
        Matrix targetToRoot = new Matrix();
        // Walk forward to our ancestor, avoiding an inverse of the root matrix. An OEM
        // may animate the root through scale=0; descendant→root coordinates still exist.
        for (View current = target; current != root; ) {
            View parent = (View) current.getParent();
            targetToRoot.postConcat(current.getMatrix());
            targetToRoot.postTranslate(current.getLeft() - parent.getScrollX(),
                    current.getTop() - parent.getScrollY());
            current = parent;
        }
        BackdropCoordinates.map(targetToRoot, left, top,
                root.getWidth(), root.getHeight(), zoom, values);
    }

    /** Source selection changes on lock/unlock; keep both textures without cache thrashing. */
    static final class RootTextures {
        private final TextureCache home = new TextureCache();
        private final TextureCache lock = new TextureCache();

        TextureCache forSource(int source) {
            return source == WallpaperManager.FLAG_LOCK ? lock : home;
        }
    }

    /** No View reference: a weak root entry must not keep the window alive through its value. */
    static final class TextureCache {
        private Object source;
        private int generation;
        private int width;
        private int height;
        private float radius;
        private Bitmap texture;

        Bitmap get(Drawable wallpaper, int width, int height, float radius) {
            Object identity;
            int revision;
            if (wallpaper instanceof BitmapDrawable) {
                Bitmap bitmap = ((BitmapDrawable) wallpaper).getBitmap();
                identity = bitmap;
                revision = bitmap.getGenerationId();
            } else {
                // OEM drawable wrappers may be recreated around an unchanged ConstantState.
                identity = wallpaper.getConstantState();
                if (identity == null) identity = wallpaper;
                revision = 0;
            }
            if (texture != null && identity == source && revision == generation
                    && this.width == width && this.height == height && this.radius == radius) {
                return texture;
            }
            Bitmap next = GpuBackdropTexture.render(width, height, radius,
                    canvas -> drawWallpaper(wallpaper, canvas, width, height));
            source = identity;
            generation = revision;
            this.width = width;
            this.height = height;
            this.radius = radius;
            // Do not recycle: previously recorded display lists can still reference the old texture.
            texture = next;
            return next;
        }
    }

    private static void drawWallpaper(Drawable wallpaper, Canvas canvas, int width, int height) {
        Rect previous = wallpaper.copyBounds();
        int sourceWidth = Math.max(1, wallpaper.getIntrinsicWidth());
        int sourceHeight = Math.max(1, wallpaper.getIntrinsicHeight());
        float cover = Math.max(width / (float) sourceWidth, height / (float) sourceHeight);
        int drawWidth = Math.round(sourceWidth * cover);
        int drawHeight = Math.round(sourceHeight * cover);
        int left = (width - drawWidth) / 2, top = (height - drawHeight) / 2;
        try {
            wallpaper.setBounds(left, top, left + drawWidth, top + drawHeight);
            wallpaper.draw(canvas);
        } finally {
            wallpaper.setBounds(previous);
        }
    }
}

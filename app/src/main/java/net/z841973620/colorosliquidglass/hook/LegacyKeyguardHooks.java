package net.z841973620.colorosliquidglass.hook;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.widget.ImageView;
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** ColorOS 13/14 锁屏快捷按钮的玻璃底板和系统圆盘处理。 */
final class LegacyKeyguardHooks {
    private final HookContext hooks;

    LegacyKeyguardHooks(HookContext hooks) {
        this.hooks = hooks;
    }


    /**
     * Lock-screen 快捷入口 ({@code start_button}/{@code end_button}) for ColorOS 13/14.
     * OEM disc is baked into the affordance vector (or LayerDrawable on some builds).
     */
    private final Map<ImageView, Drawable> keyguardAffordancePunched =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final ThreadLocal<Boolean> keyguardAffordanceApplying =
            ThreadLocal.withInitial(() -> false);

    void hookKeyguardAffordanceGlass(ClassLoader cl) {
        hooks.after(cl, "com.oplus.systemui.keyguard.view.OplusLaunchableImageView", "onAttachedToWindow",
                this::applyKeyguardAffordanceGlass);
        try {
            Class<?> viewClass = Class.forName(
                    "com.oplus.systemui.keyguard.view.OplusLaunchableImageView", false, cl);
            for (Method m : viewClass.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (name.equals("setImageDrawable")) {
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (Boolean.TRUE.equals(keyguardAffordanceApplying.get())) return result;
                            applyKeyguardAffordanceGlass(chain.getThisObject());
                        } catch (Throwable e) {
                            hooks.log(5, "Os14 OplusLaunchableImageView.setImageDrawable glass failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("setImageAlpha") && m.getParameterTypes().length >= 1) {
                    // OEM fades background with the glyph; keep the glass plate fully opaque.
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            restoreKeyguardGlassDrawableAlpha(chain.getThisObject());
                        } catch (Throwable e) {
                            hooks.log(5, "Os14 setImageAlpha glass alpha restore failed", e);
                        }
                        return result;
                    });
                }
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 OplusLaunchableImageView glass hooks unavailable", e);
        }
        hookKeyguardAffordanceUpdateButton(cl,
                "com.android.systemui.keyguard.ui.binder.KeyguardBottomAreaViewBinder",
                "updateButton", -1);
        hooks.after(cl, "com.oplus.systemui.keyguard.OplusKeyguardBottomAreaController",
                "bindKeyguardBottomAreaView", controller -> {
                    try {
                        if (!hooks.enabled()) return;
                        applyKeyguardAffordanceGlass(HookReflection.field(controller, "startButton"));
                        applyKeyguardAffordanceGlass(HookReflection.field(controller, "endButton"));
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 bindKeyguardBottomAreaView glass failed", e);
                    }
                });
    }

    private void hookKeyguardAffordanceUpdateButton(ClassLoader cl, String className,
            String methodName, int imageViewArgIndex) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            boolean found = false;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName) || m.isBridge() || m.isSynthetic()) continue;
                found = true;
                final int fixedIndex = imageViewArgIndex;
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (!hooks.enabled()) return result;
                        Object button = null;
                        java.util.List<Object> args = chain.getArgs();
                        if (fixedIndex >= 0 && fixedIndex < args.size()
                                && args.get(fixedIndex) instanceof ImageView) {
                            button = args.get(fixedIndex);
                        } else {
                            for (Object arg : args) {
                                if (arg instanceof ImageView && isKeyguardAffordanceButton(arg)) {
                                    button = arg;
                                    break;
                                }
                            }
                            if (button == null) {
                                for (Object arg : args) {
                                    if (arg instanceof ImageView) {
                                        button = arg;
                                        break;
                                    }
                                }
                            }
                        }
                        if (button != null) applyKeyguardAffordanceGlass(button);
                    } catch (Throwable e) {
                        hooks.log(5, className + "." + methodName + " glass failed", e);
                    }
                    return result;
                });
            }
            if (!found) hooks.log(5, "Method not found: " + className + "." + methodName);
        } catch (Throwable e) {
            hooks.log(5, "Class unavailable: " + className, e);
        }
    }

    private static boolean isKeyguardAffordanceButton(Object viewObj) {
        if (!(viewObj instanceof View)) return false;
        View view = (View) viewObj;
        if (HookReflection.isClassOrSubclass(view, "com.oplus.systemui.keyguard.view.OplusLaunchableImageView")) {
            return true;
        }
        try {
            int id = view.getId();
            if (id != View.NO_ID) {
                String name = view.getResources().getResourceEntryName(id);
                if ("start_button".equals(name) || "end_button".equals(name)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private void applyKeyguardAffordanceGlass(Object buttonObj) {
        if (!hooks.enabled() || !(buttonObj instanceof ImageView)) return;
        if (Boolean.TRUE.equals(keyguardAffordanceApplying.get())) return;
        final ImageView button = (ImageView) buttonObj;
        if (!isKeyguardAffordanceButton(button)) return;
        if (button.getVisibility() != View.VISIBLE) return;
        keyguardAffordanceApplying.set(true);
        try {
            suppressKeyguardAffordanceOemChrome(button);
            try { button.setBackgroundTintList(null); } catch (Throwable ignored) { }
            // Lock wallpaper only — never software-draw the keyguard tree (severe lock jank).
            GlassInstaller.markLockWallpaperOnly(button);
            // First install only — reinstall strips InsetDrawable and detectRadii overwrites
            // content-box corners (flashlight on/off flips chromeInSrc → radius jump).
            if (GlassInstaller.get(button) == null) {
                GlassInstaller.installBackground(button, hooks.currentConfig());
            }
            Runnable refresh = () -> {
                if (!hooks.enabled() || button.getVisibility() != View.VISIBLE) return;
                if (Boolean.TRUE.equals(keyguardAffordanceApplying.get())) return;
                keyguardAffordanceApplying.set(true);
                try {
                    suppressKeyguardAffordanceOemChrome(button);
                    try { button.setBackgroundTintList(null); } catch (Throwable ignored) { }
                    GlassInstaller.markLockWallpaperOnly(button);
                    if (GlassInstaller.get(button) == null
                            && !(button.getBackground() instanceof GlassDrawable)
                            && !(button.getBackground() instanceof InsetDrawable)) {
                        GlassInstaller.installBackground(button, hooks.currentConfig());
                    }
                    ensureKeyguardAffordanceGlassPlate(button);
                } finally {
                    keyguardAffordanceApplying.set(false);
                }
            };
            // One layout settle + one post — avoid 120/400 multi forceCapture on lock screen.
            scheduleKeyguardAffordanceGlassRefresh(button, refresh);
        } catch (Throwable e) {
            hooks.log(5, "Os14 applyKeyguardAffordanceGlass failed", e);
        } finally {
            keyguardAffordanceApplying.set(false);
        }
    }

    /**
     * Lock affordance: single plate bake after size is known.
     * Avoids 120/400 multi forceCapture that janked the lock screen.
     */
    private void scheduleKeyguardAffordanceGlassRefresh(View glassHost, Runnable refresh) {
        if (glassHost == null || refresh == null) return;
        if (glassHost.getWidth() > 0 && glassHost.getHeight() > 0) {
            glassHost.post(refresh);
            return;
        }
        glassHost.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (v.getWidth() > 0 && v.getHeight() > 0) {
                    v.removeOnLayoutChangeListener(this);
                    v.post(refresh);
                }
            }
        });
    }

    /**
     * Lock content-box corner radii from padding. Never call {@link GlassInstaller#installBackground}
     * here — that path peels InsetDrawable and runs detectRadii.
     */
    private void ensureKeyguardAffordanceGlassPlate(ImageView button) {
        GlassDrawable live = GlassInstaller.get(button);
        if (live == null) {
            Drawable bg = button.getBackground();
            Drawable inner = unwrapInsetDrawable(bg);
            if (inner instanceof GlassDrawable) live = (GlassDrawable) inner;
        }
        if (live == null) return;
        restoreKeyguardGlassDrawableAlpha(button);
        int pl = button.getPaddingLeft();
        int pt = button.getPaddingTop();
        int pr = button.getPaddingRight();
        int pb = button.getPaddingBottom();
        boolean insetToContent = (pl | pt | pr | pb) != 0;
        int boxW = insetToContent ? button.getWidth() - pl - pr : button.getWidth();
        int boxH = insetToContent ? button.getHeight() - pt - pb : button.getHeight();
        float radius;
        if (boxW > 0 && boxH > 0) {
            radius = Math.min(boxW, boxH) / 2f;
        } else {
            radius = 24f * button.getResources().getDisplayMetrics().density;
        }
        live.setCornerRadii(radius, radius, radius, radius);
        if (insetToContent) {
            Drawable bg = button.getBackground();
            if (!(bg instanceof InsetDrawable) || unwrapInsetDrawable(bg) != live) {
                button.setBackground(new InsetDrawable(live, pl, pt, pr, pb));
            }
        } else if (button.getBackground() != live) {
            button.setBackground(live);
        }
        try { button.setBackgroundTintList(null); } catch (Throwable ignored) { }
        if (button.getWidth() <= 0 || button.getHeight() <= 0) return;
        GlassInstaller.forceCapture(button);
        button.invalidate();
        hooks.log(4, "Os14 keyguard affordance glass ready "
                + button.getClass().getSimpleName()
                + " size=" + button.getWidth() + "x" + button.getHeight()
                + " box=" + boxW + "x" + boxH
                + " inset=" + insetToContent
                + " radius=" + radius);
    }

    private static void restoreKeyguardGlassDrawableAlpha(Object buttonObj) {
        if (!(buttonObj instanceof ImageView)) return;
        ImageView button = (ImageView) buttonObj;
        if (GlassInstaller.get(button) == null
                && !(unwrapInsetDrawable(button.getBackground()) instanceof GlassDrawable)) {
            return;
        }
        Drawable bg = button.getBackground();
        Drawable inner = unwrapInsetDrawable(bg);
        if (inner instanceof GlassDrawable) {
            try { inner.setAlpha(255); } catch (Throwable ignored) { }
        }
        if (bg != null && bg != inner) {
            try { bg.setAlpha(255); } catch (Throwable ignored) { }
        }
    }

    private static Drawable unwrapInsetDrawable(Drawable drawable) {
        if (!(drawable instanceof InsetDrawable)) return drawable;
        try {
            return ((InsetDrawable) drawable).getDrawable();
        } catch (Throwable ignored) {
            return drawable;
        }
    }

    /** @return true when the OEM disc lives in the ImageView src (needs content-box glass). */
    private boolean suppressKeyguardAffordanceOemChrome(ImageView button) {
        Drawable drawable = button.getDrawable();
        if (drawable == null) return false;
        if (drawable instanceof LayerDrawable) {
            return suppressAffordanceBackgroundLayers(
                    (LayerDrawable) drawable, button.getResources());
        }
        if (keyguardAffordancePunched.get(button) == drawable) return true;
        Drawable punched = punchTranslucentCircleFromIcon(button, drawable);
        if (punched != null && punched != drawable) {
            button.setImageDrawable(punched);
            keyguardAffordancePunched.put(button, punched);
            return true;
        }
        return punched == drawable && keyguardAffordancePunched.get(button) == drawable;
    }

    private static boolean suppressAffordanceBackgroundLayers(LayerDrawable layers, Resources res) {
        boolean suppressed = false;
        for (String name : new String[] {
                "affordance_background_circle", "affordance_background_circle_stroke" }) {
            int id = 0;
            try {
                id = res.getIdentifier(name, "id", "com.android.systemui");
            } catch (Throwable ignored) { }
            if (id == 0) {
                try {
                    id = res.getIdentifier(name, "id", res.getResourcePackageName(
                            layers.getId(0)));
                } catch (Throwable ignored) { }
            }
            if (id == 0) continue;
            Drawable layer = layers.findDrawableByLayerId(id);
            if (layer != null) {
                layer.mutate().setAlpha(0);
                suppressed = true;
            }
        }
        if (suppressed) return true;
        try {
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                int id = layers.getId(i);
                if (id == View.NO_ID) continue;
                String entry = res.getResourceEntryName(id);
                if (entry != null && entry.startsWith("affordance_background")) {
                    layers.getDrawable(i).mutate().setAlpha(0);
                    suppressed = true;
                }
            }
        } catch (Throwable ignored) { }
        return suppressed;
    }

    private static Drawable punchTranslucentCircleFromIcon(ImageView host, Drawable source) {
        try {
            int w = source.getIntrinsicWidth();
            int h = source.getIntrinsicHeight();
            if (w <= 0 || h <= 0) {
                w = Math.max(1, host.getWidth() - host.getPaddingLeft() - host.getPaddingRight());
                h = Math.max(1, host.getHeight() - host.getPaddingTop() - host.getPaddingBottom());
            }
            if (w <= 0 || h <= 0) return null;
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            ColorFilter oldFilter = source.getColorFilter();
            Rect oldBounds = source.copyBounds();
            try {
                source.setColorFilter(null);
                source.setBounds(0, 0, w, h);
                source.draw(canvas);
            } finally {
                source.setColorFilter(oldFilter);
                source.setBounds(oldBounds);
            }
            int[] pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            boolean changed = false;
            for (int i = 0; i < pixels.length; i++) {
                int alpha = (pixels[i] >>> 24) & 0xff;
                if (alpha > 0 && alpha < 100) {
                    pixels[i] = 0;
                    changed = true;
                }
            }
            if (!changed) {
                bitmap.recycle();
                return source;
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
            BitmapDrawable out = new BitmapDrawable(host.getResources(), bitmap);
            if (oldFilter != null) out.setColorFilter(oldFilter);
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }
}

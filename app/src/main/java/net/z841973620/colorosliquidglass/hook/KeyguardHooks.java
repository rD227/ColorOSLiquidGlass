package net.z841973620.colorosliquidglass.hook;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.widget.ImageView;
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import static net.z841973620.colorosliquidglass.hook.HookContext.TAG;

/** 锁屏快捷按钮：替换系统圆盘并保持取消手势后的玻璃状态。 */
final class KeyguardHooks {
    private final HookContext hooks;

    KeyguardHooks(HookContext hooks) {
        this.hooks = hooks;
    }


    /**
     * ColorOS 16 lock-screen 快捷入口. Cos14 uses {@link Os14HookBackend}.
     * <p>
     * Cos16 binder only exposes Kotlin synthetic {@code access$updateButton}, paints a material
     * {@code BackgroundTintList} onto whatever background we install, and keeps the resting disc
     * in src LayerDrawable layers. Sample wallpaper-only (keyguard root.draw is empty/scrim).
     */
    private final Map<ImageView, Drawable> keyguardAffordancePunched =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final ThreadLocal<Boolean> keyguardAffordanceApplying =
            ThreadLocal.withInitial(() -> false);
    /** True briefly after KeyguardAffordanceHelper.reset — block OEM default chrome. */
    private final AtomicBoolean keyguardAffordanceCancelGuard = new AtomicBoolean(false);
    private Runnable keyguardAffordanceCancelGuardClear;
    private WeakReference<ImageView> keyguardAffordanceStartBtn = new WeakReference<>(null);
    private WeakReference<ImageView> keyguardAffordanceEndBtn = new WeakReference<>(null);
    private final ThreadLocal<Boolean> keyguardAffordanceSuppressingDiscs =
            ThreadLocal.withInitial(() -> false);

    void hookKeyguardAffordanceGlass(ClassLoader cl) {
        hooks.log(4, TAG, "hookKeyguardAffordanceGlass Cos16");
        hooks.after("com.oplus.systemui.keyguard.view.OplusLaunchableImageView", cl, "onAttachedToWindow",
                button -> applyKeyguardAffordanceGlass(button, false));
        try {
            Class<?> viewClass = Class.forName(
                    "com.oplus.systemui.keyguard.view.OplusLaunchableImageView", false, cl);
            for (Method m : viewClass.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (name.equals("setImageDrawable") || name.equals("setImageResource")) {
                    hooks.hookOnce(m, chain -> {
                        Object self = chain.getThisObject();
                        try {
                            // Our icon-only wrapper must always be allowed through.
                            if (name.equals("setImageDrawable")
                                    && !chain.getArgs().isEmpty()
                                    && chain.getArg(0)
                                    instanceof KeyguardAffordanceIconOnlyDrawable) {
                                return chain.proceed();
                            }
                            // reset() → Flow → updateButton → setImage(OEM LayerDrawable).
                            // Block even if glass was briefly stolen — requiring hasGlass let
                            // OEM discs land and flash after the white circle hit 0.
                            if (shouldBlockKeyguardAffordanceImageRebind(self)) {
                                assertKeyguardAffordanceGlassChrome(self, false);
                                return null;
                            }
                        } catch (Throwable e) {
                            hooks.log(5, TAG, "OplusLaunchableImageView." + name
                                    + " cancel block failed", e);
                        }
                        Object result = chain.proceed();
                        try {
                            if (keyguardAffordanceCancelGuard.get()
                                    && isKeyguardAffordanceButton(self)) {
                                suppressKeyguardAffordanceSrcDiscs(self);
                                assertKeyguardAffordanceGlassChrome(self, false);
                            }
                            if (Boolean.TRUE.equals(keyguardAffordanceApplying.get())
                                    || Boolean.TRUE.equals(
                                            keyguardAffordanceSuppressingDiscs.get())) {
                                return result;
                            }
                            applyKeyguardAffordanceGlass(self, false);
                        } catch (Throwable e) {
                            hooks.log(5, TAG, "OplusLaunchableImageView." + name + " glass failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("setImageAlpha") && m.getParameterTypes().length >= 1) {
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            restoreKeyguardGlassDrawableAlpha(chain.getThisObject());
                        } catch (Throwable e) {
                            hooks.log(5, TAG, "setImageAlpha glass alpha restore failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("setBackground") || name.equals("setBackgroundDrawable")) {
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            restoreKeyguardGlassIfBackgroundStolen(chain.getThisObject());
                        } catch (Throwable e) {
                            hooks.log(5, TAG, name + " glass restore failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("onDraw") && m.getParameterTypes().length == 1) {
                    // Before super.onDraw: src must be icon-only so radius→0 cannot uncover OEM
                    // discs. DeadDisc-via-setDrawable was defeated by LayerDrawable.mutate().
                    hooks.hookOnce(m, chain -> {
                        try {
                            Object self = chain.getThisObject();
                            if (hooks.enabled()
                                    && isKeyguardAffordanceButton(self)
                                    && (hasKeyguardAffordanceGlass(self)
                                            || keyguardAffordanceCancelGuard.get())) {
                                suppressKeyguardAffordanceSrcDiscs(self);
                                if (keyguardAffordanceCancelGuard.get()) {
                                    restoreKeyguardGlassDrawableAlpha(self);
                                }
                            }
                        } catch (Throwable ignored) { }
                        return chain.proceed();
                    });
                } else if (name.equals("updateIconColor") || name.equals("updateIconColor$1")
                        || name.startsWith("updateIconColor")) {
                    hooks.hookOnce(m, chain -> {
                        try {
                            Object self = chain.getThisObject();
                            // Strip discs BEFORE OEM mutate+SRC_ATOP on LayerDrawable children.
                            if (hooks.enabled() && isKeyguardAffordanceButton(self)) {
                                suppressKeyguardAffordanceSrcDiscs(self);
                            }
                        } catch (Throwable ignored) { }
                        Object result = chain.proceed();
                        try {
                            Object self = chain.getThisObject();
                            suppressKeyguardAffordanceSrcDiscs(self);
                            if (keyguardAffordanceCancelGuard.get()
                                    && self instanceof ImageView) {
                                clearKeyguardAffordanceBackgroundTint((ImageView) self);
                                restoreKeyguardGlassDrawableAlpha(self);
                                ensureGlassWhenAffordanceCircleGone(self);
                            }
                        } catch (Throwable e) {
                            hooks.log(5, TAG, name + " disc rehide failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("setCircleRadius")
                        || name.equals("setCircleRadiusWithoutAnimation")) {
                    hooks.hookOnce(m, chain -> {
                        try {
                            Object self = chain.getThisObject();
                            if (hooks.enabled() && isKeyguardAffordanceButton(self)) {
                                suppressKeyguardAffordanceSrcDiscs(self);
                            }
                        } catch (Throwable ignored) { }
                        Object result = chain.proceed();
                        try {
                            ensureGlassWhenAffordanceCircleGone(chain.getThisObject());
                        } catch (Throwable e) {
                            hooks.log(5, TAG, name + " glass after circle failed", e);
                        }
                        return result;
                    });
                }
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "OplusLaunchableImageView glass hooks unavailable", e);
        }
        try {
            Method imageAlpha = ImageView.class.getDeclaredMethod("setImageAlpha", int.class);
            hooks.hookOnce(imageAlpha, chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (isKeyguardAffordanceButton(self) && hasKeyguardAffordanceGlass(self)) {
                        restoreKeyguardGlassDrawableAlpha(self);
                    }
                } catch (Throwable ignored) { }
                return result;
            });
        } catch (Throwable e) {
            hooks.log(5, TAG, "ImageView#setImageAlpha affordance hook unavailable", e);
        }
        // updateButton calls getDrawable().setTint AFTER setImage — re-suppress discs then.
        try {
            Method setTint = Drawable.class.getDeclaredMethod("setTint", int.class);
            hooks.hookOnce(setTint, chain -> {
                Object result = chain.proceed();
                try {
                    if (keyguardAffordanceCancelGuard.get()
                            && !Boolean.TRUE.equals(keyguardAffordanceSuppressingDiscs.get())) {
                        rehideTrackedKeyguardAffordanceDiscs();
                    }
                } catch (Throwable ignored) { }
                return result;
            });
            Method setTintList = Drawable.class.getDeclaredMethod(
                    "setTintList", ColorStateList.class);
            hooks.hookOnce(setTintList, chain -> {
                Object result = chain.proceed();
                try {
                    if (keyguardAffordanceCancelGuard.get()
                            && !Boolean.TRUE.equals(keyguardAffordanceSuppressingDiscs.get())) {
                        rehideTrackedKeyguardAffordanceDiscs();
                    }
                } catch (Throwable ignored) { }
                return result;
            });
        } catch (Throwable e) {
            hooks.log(5, TAG, "Drawable#setTint affordance cancel hooks unavailable", e);
        }
        hookKeyguardAffordanceUpdateButton(cl,
                "com.oplus.systemui.keyguard.ui.binder.OplusKeyguardBottomAreaViewBinder",
                "access$updateButton", 2, true);
        hookKeyguardAffordanceUpdateButton(cl,
                "com.oplus.systemui.keyguard.ui.binder.OplusKeyguardBottomAreaViewBinder",
                "updateButton", -1, false);
        hooks.after("com.oplus.systemui.keyguard.OplusKeyguardBottomAreaController", cl,
                "bindKeyguardBottomAreaView", controller -> {
                    try {
                        if (!hooks.enabled()) return;
                        applyKeyguardAffordanceGlass(
                                HookReflection.invokeNoArgs(controller, "findStartButton"), true);
                        applyKeyguardAffordanceGlass(
                                HookReflection.invokeNoArgs(controller, "findEndButton"), true);
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "bindKeyguardBottomAreaView glass failed", e);
                    }
                });
        // BEFORE reset body: cancelGuard must be on before canLaunch* Flow → updateButton →
        // setImage, which runs synchronously inside reset().
        try {
            Class<?> helperClz = Class.forName(
                    "com.oplus.systemui.keyguard.KeyguardAffordanceHelper", false, cl);
            boolean foundReset = false;
            for (Method m : helperClz.getDeclaredMethods()) {
                if (!m.getName().equals("reset") || m.isBridge() || m.isSynthetic()) continue;
                foundReset = true;
                hooks.hookOnce(m, chain -> {
                    try {
                        if (hooks.enabled()) {
                            Object helper = chain.getThisObject();
                            beginKeyguardAffordanceCancelGuard();
                            Object style = HookReflection.field(helper, "oplusKeyguardStyleBaseUIController");
                            Object start = style != null
                                    ? HookReflection.invokeNoArgs(style, "getStartButton") : null;
                            Object end = style != null
                                    ? HookReflection.invokeNoArgs(style, "getEndButton") : null;
                            rememberKeyguardAffordanceButtons(start, end);
                            suppressKeyguardAffordanceSrcDiscs(start);
                            suppressKeyguardAffordanceSrcDiscs(end);
                            assertKeyguardAffordanceGlassChrome(start, false);
                            assertKeyguardAffordanceGlassChrome(end, false);
                        }
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "KeyguardAffordanceHelper.reset before glass failed", e);
                    }
                    Object result = chain.proceed();
                    try {
                        if (!hooks.enabled()) return result;
                        Object helper = chain.getThisObject();
                        Object style = HookReflection.field(helper, "oplusKeyguardStyleBaseUIController");
                        Object start = style != null
                                ? HookReflection.invokeNoArgs(style, "getStartButton") : null;
                        Object end = style != null
                                ? HookReflection.invokeNoArgs(style, "getEndButton") : null;
                        suppressKeyguardAffordanceSrcDiscs(start);
                        suppressKeyguardAffordanceSrcDiscs(end);
                        assertKeyguardAffordanceGlassChrome(start, false);
                        assertKeyguardAffordanceGlassChrome(end, false);
                        settleKeyguardAffordanceGlassAfterCancel(start);
                        settleKeyguardAffordanceGlassAfterCancel(end);
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "KeyguardAffordanceHelper.reset after glass failed", e);
                    }
                    return result;
                });
            }
            if (!foundReset) {
                hooks.log(5, TAG, "Method not found: KeyguardAffordanceHelper.reset");
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "KeyguardAffordanceHelper.reset glass hook unavailable", e);
        }
        try {
            Method tint = View.class.getDeclaredMethod(
                    "setBackgroundTintList", ColorStateList.class);
            hooks.hookOnce(tint, chain -> {
                Object self = chain.getThisObject();
                Object tintArg = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                if (tintArg != null
                        && hooks.enabled()
                        && isKeyguardAffordanceButton(self)
                        && (hasKeyguardAffordanceGlass(self)
                        || keyguardAffordanceCancelGuard.get())) {
                    if (keyguardAffordanceCancelGuard.get()) {
                        restoreKeyguardGlassIfBackgroundStolen(self);
                    }
                    return null;
                }
                return chain.proceed();
            });
            hooks.log(4, TAG, "Hooked View#setBackgroundTintList for keyguard affordance glass");
        } catch (Throwable e) {
            hooks.log(5, TAG, "setBackgroundTintList affordance hook unavailable", e);
        }
    }

    private boolean shouldBlockKeyguardAffordanceImageRebind(Object buttonObj) {
        if (!keyguardAffordanceCancelGuard.get()) return false;
        if (Boolean.TRUE.equals(keyguardAffordanceApplying.get())) return false;
        if (Boolean.TRUE.equals(keyguardAffordanceSuppressingDiscs.get())) return false;
        if (!hooks.enabled() || !isKeyguardAffordanceButton(buttonObj)) return false;
        // Do not require hasGlass — glass can be briefly missing while OEM still rebinds.
        return true;
    }

    /**
     * When the finger-follow white circle hits 0, keep existing glass + icon-only src.
     * Never forceCapture here — wallpaper-only forced capture promotes a new frame and
     * briefly exposes OEM discs (looks like default → glass reinstall).
     */
    private void ensureGlassWhenAffordanceCircleGone(Object buttonObj) {
        if (!keyguardAffordanceCancelGuard.get()) return;
        if (!hooks.enabled() || !(buttonObj instanceof ImageView)) return;
        if (!isKeyguardAffordanceButton(buttonObj)) return;
        Object radius = HookReflection.field(buttonObj, "mCircleRadius");
        if (!(radius instanceof Number)) return;
        if (((Number) radius).floatValue() > 1f) return;
        ImageView button = (ImageView) buttonObj;
        suppressKeyguardAffordanceSrcDiscs(button);
        clearKeyguardAffordanceBackgroundTint(button);
        restoreKeyguardGlassDrawableAlpha(button);
        if (GlassInstaller.get(button) == null
                && !(unwrapInsetDrawable(button.getBackground()) instanceof GlassDrawable)) {
            applyKeyguardAffordanceGlass(button, true);
        } else {
            assertKeyguardAffordanceGlassChrome(button, false);
        }
    }

    private void beginKeyguardAffordanceCancelGuard() {
        keyguardAffordanceCancelGuard.set(true);
        if (keyguardAffordanceCancelGuardClear != null) {
            hooks.mainHandler.removeCallbacks(keyguardAffordanceCancelGuardClear);
        }
        keyguardAffordanceCancelGuardClear = () -> {
            keyguardAffordanceCancelGuard.set(false);
            try {
                ImageView start = keyguardAffordanceStartBtn.get();
                ImageView end = keyguardAffordanceEndBtn.get();
                if (start != null) {
                    suppressKeyguardAffordanceSrcDiscs(start);
                    assertKeyguardAffordanceGlassChrome(start, false);
                }
                if (end != null) {
                    suppressKeyguardAffordanceSrcDiscs(end);
                    assertKeyguardAffordanceGlassChrome(end, false);
                }
            } catch (Throwable ignored) { }
        };
        // Cover async Flow rebinds after reset's synchronous updateButton.
        hooks.mainHandler.postDelayed(keyguardAffordanceCancelGuardClear, 1200L);
    }

    private void rememberKeyguardAffordanceButtons(Object start, Object end) {
        trackKeyguardAffordanceButton(start);
        trackKeyguardAffordanceButton(end);
    }

    private void trackKeyguardAffordanceButton(Object buttonObj) {
        if (!(buttonObj instanceof ImageView)) return;
        ImageView iv = (ImageView) buttonObj;
        String name = null;
        try {
            int id = iv.getId();
            if (id != View.NO_ID) name = iv.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) { }
        if ("start_button".equals(name)) {
            keyguardAffordanceStartBtn = new WeakReference<>(iv);
        } else if ("end_button".equals(name)) {
            keyguardAffordanceEndBtn = new WeakReference<>(iv);
        } else if (isKeyguardAffordanceButton(iv)) {
            if (keyguardAffordanceStartBtn.get() == null) {
                keyguardAffordanceStartBtn = new WeakReference<>(iv);
            } else if (keyguardAffordanceEndBtn.get() == null
                    && keyguardAffordanceStartBtn.get() != iv) {
                keyguardAffordanceEndBtn = new WeakReference<>(iv);
            }
        }
    }

    private void rehideTrackedKeyguardAffordanceDiscs() {
        suppressKeyguardAffordanceSrcDiscs(keyguardAffordanceStartBtn.get());
        suppressKeyguardAffordanceSrcDiscs(keyguardAffordanceEndBtn.get());
    }

    private static boolean hasKeyguardAffordanceGlass(Object buttonObj) {
        if (!(buttonObj instanceof ImageView)) return false;
        ImageView button = (ImageView) buttonObj;
        return GlassInstaller.get(button) != null
                || unwrapInsetDrawable(button.getBackground()) instanceof GlassDrawable;
    }

    private void hookKeyguardAffordanceUpdateButton(ClassLoader cl, String className,
            String methodName, int imageViewArgIndex, boolean allowSynthetic) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            boolean found = false;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName) || m.isBridge()) continue;
                if (m.isSynthetic() && !allowSynthetic) continue;
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
                        // Trust the binder ImageView (plugin clock may not match id/class filters).
                        if (button != null) {
                            trackKeyguardAffordanceButton(button);
                            applyKeyguardAffordanceGlass(button, true);
                            // setTint runs inside updateButton after setImage — suppress again.
                            if (keyguardAffordanceCancelGuard.get()) {
                                suppressKeyguardAffordanceSrcDiscs(button);
                                assertKeyguardAffordanceGlassChrome(button, false);
                            }
                        }
                    } catch (Throwable e) {
                        hooks.log(5, TAG, className + "." + methodName + " glass failed", e);
                    }
                    return result;
                });
                hooks.log(4, TAG, "Hooked " + className + "#" + methodName
                        + " synthetic=" + m.isSynthetic());
            }
            if (!found) {
                hooks.log(5, TAG, "Method not found: " + className + "." + methodName
                        + " allowSynthetic=" + allowSynthetic);
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "Class unavailable: " + className, e);
        }
    }

    private static boolean isKeyguardAffordanceButton(Object viewObj) {
        if (!(viewObj instanceof View)) return false;
        View view = (View) viewObj;
        if (HookReflection.isClassOrSubclass(view, "com.oplus.systemui.keyguard.view.OplusLaunchableImageView")
                || HookReflection.isClassOrSubclass(view,
                "com.android.systemui.animation.view.LaunchableImageView")) {
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
        applyKeyguardAffordanceGlass(buttonObj, false);
    }

    private void applyKeyguardAffordanceGlass(Object buttonObj, boolean trustBinderView) {
        if (!hooks.enabled() || !(buttonObj instanceof ImageView)) return;
        if (Boolean.TRUE.equals(keyguardAffordanceApplying.get())) return;
        final ImageView button = (ImageView) buttonObj;
        if (!trustBinderView && !isKeyguardAffordanceButton(button)) return;
        if (button.getVisibility() != View.VISIBLE) return;
        trackKeyguardAffordanceButton(button);
        keyguardAffordanceApplying.set(true);
        try {
            suppressKeyguardAffordanceOemChrome(button);
            clearKeyguardAffordanceBackgroundTint(button);
            GlassInstaller.markLockWallpaperOnly(button);
            // First install only — reinstall peels InsetDrawable / detectRadii.
            if (GlassInstaller.get(button) == null) {
                GlassInstaller.installBackground(button, hooks.currentConfig());
            }
            clearKeyguardAffordanceBackgroundTint(button);
            Runnable refresh = () -> {
                if (!hooks.enabled() || button.getVisibility() != View.VISIBLE) return;
                if (Boolean.TRUE.equals(keyguardAffordanceApplying.get())) return;
                keyguardAffordanceApplying.set(true);
                try {
                    suppressKeyguardAffordanceOemChrome(button);
                    clearKeyguardAffordanceBackgroundTint(button);
                    GlassInstaller.markLockWallpaperOnly(button);
                    if (GlassInstaller.get(button) == null
                            && !(button.getBackground() instanceof GlassDrawable)
                            && !(button.getBackground() instanceof InsetDrawable)) {
                        GlassInstaller.installBackground(button, hooks.currentConfig());
                    }
                    ensureKeyguardAffordanceGlassPlate(button,
                            !keyguardAffordanceCancelGuard.get());
                } finally {
                    keyguardAffordanceApplying.set(false);
                }
            };
            // One layout settle + one post — avoid 120/400 multi forceCapture on lock screen.
            scheduleKeyguardAffordanceGlassRefresh(button, refresh);
        } catch (Throwable e) {
            hooks.log(5, TAG, "applyKeyguardAffordanceGlass failed", e);
        } finally {
            keyguardAffordanceApplying.set(false);
        }
    }

    private void settleKeyguardAffordanceGlassAfterCancel(Object buttonObj) {
        if (!(buttonObj instanceof ImageView)) return;
        final ImageView button = (ImageView) buttonObj;
        if (button.getVisibility() != View.VISIBLE) return;
        if (!hasKeyguardAffordanceGlass(button) && GlassInstaller.get(button) == null) return;
        // Keep the already-sampled plate. Capture on this path caused OEM flash at circle-end.
        Runnable light = () -> assertKeyguardAffordanceGlassChrome(button, false);
        light.run();
        button.post(light);
        button.postDelayed(light, 50L);
        button.postDelayed(light, 160L);
        button.postDelayed(light, 320L);
    }

    /**
     * Keep glass chrome without replacing the ImageView src (preserves setImageScale).
     * @param capture whether to force wallpaper sample (skip on hot cancel path).
     */
    private void assertKeyguardAffordanceGlassChrome(Object buttonObj, boolean capture) {
        if (!(buttonObj instanceof ImageView)) return;
        if (!hooks.enabled()) return;
        ImageView button = (ImageView) buttonObj;
        if (button.getVisibility() != View.VISIBLE) return;
        if (GlassInstaller.get(button) == null
                && !(unwrapInsetDrawable(button.getBackground()) instanceof GlassDrawable)) {
            return;
        }
        boolean nested = Boolean.TRUE.equals(keyguardAffordanceApplying.get());
        if (!nested) keyguardAffordanceApplying.set(true);
        try {
            suppressKeyguardAffordanceSrcDiscs(button);
            clearKeyguardAffordanceBackgroundTint(button);
            restoreKeyguardGlassDrawableAlpha(button);
            ensureKeyguardAffordanceGlassPlate(button, capture);
            clearKeyguardAffordanceBackgroundTint(button);
        } catch (Throwable ignored) {
        } finally {
            if (!nested) keyguardAffordanceApplying.set(false);
        }
    }

    private void restoreKeyguardGlassIfBackgroundStolen(Object buttonObj) {
        if (!(buttonObj instanceof ImageView)) return;
        ImageView button = (ImageView) buttonObj;
        GlassDrawable live = GlassInstaller.get(button);
        if (live == null) return;
        Drawable bg = button.getBackground();
        Drawable inner = unwrapInsetDrawable(bg);
        if (inner == live || bg == live) return;
        hooks.log(5, TAG, "keyguard affordance background replaced → restoring glass "
                + (bg == null ? "null" : bg.getClass().getName()));
        // Always put glass back — even when nested under apply/assert (OEM setBackground
        // during cancel must not stick until delayed settle).
        boolean nested = Boolean.TRUE.equals(keyguardAffordanceApplying.get());
        if (!nested) keyguardAffordanceApplying.set(true);
        try {
            int pl = button.getPaddingLeft();
            int pt = button.getPaddingTop();
            int pr = button.getPaddingRight();
            int pb = button.getPaddingBottom();
            if ((pl | pt | pr | pb) != 0) {
                button.setBackground(new InsetDrawable(live, pl, pt, pr, pb));
            } else {
                button.setBackground(live);
            }
            clearKeyguardAffordanceBackgroundTint(button);
            restoreKeyguardGlassDrawableAlpha(button);
        } finally {
            if (!nested) keyguardAffordanceApplying.set(false);
        }
    }

    /** Content-box radii from padding; never calls installBackground. */
    private void ensureKeyguardAffordanceGlassPlate(ImageView button) {
        ensureKeyguardAffordanceGlassPlate(button, true);
    }

    private void ensureKeyguardAffordanceGlassPlate(ImageView button, boolean capture) {
        GlassDrawable live = GlassInstaller.get(button);
        if (live == null) {
            Drawable inner = unwrapInsetDrawable(button.getBackground());
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
        clearKeyguardAffordanceBackgroundTint(button);
        if (button.getWidth() <= 0 || button.getHeight() <= 0) return;
        if (capture) {
            GlassInstaller.forceCapture(button);
        }
        button.invalidate();
        hooks.log(4, TAG, "Keyguard affordance glass ready "
                + button.getClass().getSimpleName()
                + " size=" + button.getWidth() + "x" + button.getHeight()
                + " box=" + boxW + "x" + boxH
                + " inset=" + insetToContent
                + " radius=" + radius
                + " capture=" + capture);
    }

    /** OEM updateButton applies material BackgroundTintList after we install GlassDrawable. */
    private static void clearKeyguardAffordanceBackgroundTint(ImageView button) {
        try { button.setBackgroundTintList(null); } catch (Throwable ignored) { }
        Drawable bg = button.getBackground();
        Drawable inner = unwrapInsetDrawable(bg);
        if (inner != null) {
            try { inner.setTintList(null); } catch (Throwable ignored) { }
            try { inner.clearColorFilter(); } catch (Throwable ignored) { }
        }
        if (bg != null && bg != inner) {
            try { bg.setTintList(null); } catch (Throwable ignored) { }
            try { bg.clearColorFilter(); } catch (Throwable ignored) { }
        }
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

    /**
     * Strip Cos16 OEM disc chrome by replacing src {@link LayerDrawable} with an icon-only
     * drawable. In-place DeadDisc lost to {@code LayerDrawable.mutate()} recreating vectors.
     */
    private boolean suppressKeyguardAffordanceOemChrome(ImageView button) {
        return suppressKeyguardAffordanceSrcDiscs(button);
    }

    private boolean suppressKeyguardAffordanceSrcDiscs(Object buttonObj) {
        if (!(buttonObj instanceof ImageView)) return false;
        if (!hooks.enabled()) return false;
        if (Boolean.TRUE.equals(keyguardAffordanceSuppressingDiscs.get())) return false;
        ImageView button = (ImageView) buttonObj;
        Drawable drawable = button.getDrawable();
        if (drawable == null) return false;
        if (drawable instanceof KeyguardAffordanceIconOnlyDrawable) return true;
        keyguardAffordanceSuppressingDiscs.set(true);
        try {
            if (drawable instanceof LayerDrawable) {
                KeyguardAffordanceIconOnlyDrawable only =
                        KeyguardAffordanceIconOnlyDrawable.from(
                                (LayerDrawable) drawable, button.getResources());
                button.setImageDrawable(only);
                keyguardAffordancePunched.put(button, only);
                return true;
            }
            if (keyguardAffordancePunched.get(button) == drawable) return true;
            if (keyguardAffordanceCancelGuard.get()) return false;
            Drawable punched = punchTranslucentCircleFromIcon(button, drawable);
            if (punched != null && punched != drawable) {
                button.setImageDrawable(punched);
                keyguardAffordancePunched.put(button, punched);
                return true;
            }
            return punched == drawable && keyguardAffordancePunched.get(button) == drawable;
        } finally {
            keyguardAffordanceSuppressingDiscs.set(false);
        }
    }

    private void rehideKeyguardAffordanceDiscLayers(Object buttonObj) {
        suppressKeyguardAffordanceSrcDiscs(buttonObj);
    }

    /**
     * Cos16 affordance_*_new.xml = [circle, stroke, foreground]. After {@code mCircleRadius→0}
     * the discs are the OEM flash. Keep only the foreground at the LayerDrawable intrinsic size
     * so {@code scaleType=center} / {@code mImageScale} stay unchanged.
     */
    private static final class KeyguardAffordanceIconOnlyDrawable extends Drawable {
        private final Drawable foreground;
        private final int intrinsicW;
        private final int intrinsicH;

        static KeyguardAffordanceIconOnlyDrawable from(LayerDrawable layers, Resources res) {
            int n = layers.getNumberOfLayers();
            int fgIndex = Math.max(0, n - 1);
            int fgId = 0;
            try {
                fgId = res.getIdentifier("affordance_foreground_icon", "id", "com.android.systemui");
            } catch (Throwable ignored) { }
            if (fgId != 0) {
                for (int i = 0; i < n; i++) {
                    try {
                        if (layers.getId(i) == fgId) {
                            fgIndex = i;
                            break;
                        }
                    } catch (Throwable ignored) { }
                }
            }
            Drawable fg = layers.getDrawable(fgIndex);
            Drawable copy = fg;
            try {
                Drawable.ConstantState cs = fg != null ? fg.getConstantState() : null;
                if (cs != null) {
                    copy = cs.newDrawable(res).mutate();
                } else if (fg != null) {
                    copy = fg.mutate();
                }
            } catch (Throwable ignored) {
                copy = fg;
            }
            int w = layers.getIntrinsicWidth();
            int h = layers.getIntrinsicHeight();
            if (w <= 0 || h <= 0) {
                w = copy != null ? copy.getIntrinsicWidth() : -1;
                h = copy != null ? copy.getIntrinsicHeight() : -1;
            }
            if (w <= 0 || h <= 0) {
                float d = res != null ? res.getDisplayMetrics().density : 3f;
                w = Math.round(48f * d);
                h = w;
            }
            return new KeyguardAffordanceIconOnlyDrawable(copy, w, h);
        }

        KeyguardAffordanceIconOnlyDrawable(Drawable foreground, int intrinsicW, int intrinsicH) {
            this.foreground = foreground;
            this.intrinsicW = Math.max(1, intrinsicW);
            this.intrinsicH = Math.max(1, intrinsicH);
        }

        @Override public void draw(Canvas canvas) {
            if (foreground == null) return;
            Rect b = getBounds();
            int fw = foreground.getIntrinsicWidth();
            int fh = foreground.getIntrinsicHeight();
            if (fw <= 0) fw = b.width();
            if (fh <= 0) fh = b.height();
            int left = b.left + (b.width() - fw) / 2;
            int top = b.top + (b.height() - fh) / 2;
            foreground.setBounds(left, top, left + fw, top + fh);
            foreground.draw(canvas);
        }

        @Override public void setAlpha(int alpha) {
            if (foreground != null) foreground.setAlpha(alpha);
        }

        @Override public void setColorFilter(ColorFilter colorFilter) {
            if (foreground != null) foreground.setColorFilter(colorFilter);
        }

        @Override public void setTint(int tintColor) {
            if (foreground != null) foreground.setTint(tintColor);
        }

        @Override public void setTintList(ColorStateList tint) {
            if (foreground != null) foreground.setTintList(tint);
        }

        @Override public int getOpacity() {
            return foreground != null ? foreground.getOpacity() : PixelFormat.TRANSLUCENT;
        }

        @Override public int getIntrinsicWidth() {
            return intrinsicW;
        }

        @Override public int getIntrinsicHeight() {
            return intrinsicH;
        }

        @Override protected boolean onStateChange(int[] state) {
            return foreground != null && foreground.setState(state);
        }

        @Override public boolean isStateful() {
            return foreground != null && foreground.isStateful();
        }
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
                // Cos14 vectors bake a ~0.2-alpha disc under the glyph.
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

    /**
     * Lock affordance: single plate bake after size is known.
     * Multi delayed forceCapture (120/400) re-rasterized lock wallpaper and janked Keyguard.
     */
    private void scheduleKeyguardAffordanceGlassRefresh(View glassHost, Runnable refresh) {
        if (glassHost == null || refresh == null) return;
        if (glassHost.getWidth() > 0 && glassHost.getHeight() > 0) {
            // One post so padding/inset settle before wallpaper-only capture.
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
}

package net.z841973620.colorosliquidglass.hook;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import net.z841973620.colorosliquidglass.GlassConfig;
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import net.z841973620.colorosliquidglass.glass.NotificationCardGlass;
import net.z841973620.colorosliquidglass.glass.NotificationPlateDrawable;
import net.z841973620.colorosliquidglass.glass.HeadsUpState;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import static net.z841973620.colorosliquidglass.hook.HookContext.TAG;

/** 通知卡片、分组标题、横屏横幅、锁屏胶囊及通知中心背景。 */
final class NotificationHooks {
    private final HookContext hooks;

    NotificationHooks(HookContext hooks) {
        this.hooks = hooks;
    }


    void hookNotificationCardGlass(ClassLoader cl) {
        try {
            Class<?> background = Class.forName(
                    "com.android.systemui.statusbar.notification.row.NotificationBackgroundView",
                    false, cl);
            NotificationCardGlass renderer = new NotificationCardGlass(background);
            Method draw = background.getDeclaredMethod("onDraw", Canvas.class);
            hooks.hookOnce(draw, chain -> {
                GlassConfig config = hooks.currentConfig();
                View view = (View) chain.getThisObject();
                if (!config.enabled || !config.notificationGlassEnabled || !renderer.shouldDraw(view, config)) {
                    renderer.remove(view);
                    return chain.proceed();
                }
                renderer.draw(view, (Canvas) chain.getArg(0), config);
                return null;
            });
            hooks.log(4, TAG, "Notification card background glass hook installed");
        } catch (ReflectiveOperationException e) {
            // OEM SystemUI is an external API: keep its original rendering if its
            // notification geometry contract differs from the supported layout.
            hooks.log(5, TAG, "Notification card glass unavailable: incompatible background API", e);
        }
    }

    void hookNotificationGroupHeader(ClassLoader cl) {
        try {
            Class<?> extension = Class.forName(
                    "com.oplus.systemui.statusbar.notification.stack.NotificationChildrenContainerExtImp", false, cl);
            Class<?> row = Class.forName(
                    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", false, cl);
            Method factory = extension.getDeclaredMethod("getHeaderBgDrawable", View.class, boolean.class, row);
            Method radius = row.getMethod("getTopCornerRadius");
            HeadsUpState detector = new HeadsUpState(cl);
            net.z841973620.colorosliquidglass.glass.NotificationRefractionScale refractionScale =
                    new net.z841973620.colorosliquidglass.glass.NotificationRefractionScale(cl);
            net.z841973620.colorosliquidglass.glass.NotificationGroupGeometry groupGeometry =
                    new net.z841973620.colorosliquidglass.glass.NotificationGroupGeometry(
                            Class.forName("com.android.systemui.statusbar.notification.row.ActivatableNotificationView", false, cl),
                            Class.forName("com.android.systemui.statusbar.notification.row.NotificationBackgroundView", false, cl));
            hooks.hookOnce(factory, chain -> {
                GlassConfig config = hooks.currentConfig();
                View host = (View) chain.getArg(0);
                View owner = (View) chain.getArg(2);
                if (!config.enabled || !config.notificationGlassEnabled
                        || (!config.headsUpGlassEnabled && detector.isHeadsUp(owner))) return chain.proceed();
                // The OEM factory installs a separate blur drawable for headers. Replace that
                // factory result directly, preserving NotificationChildrenContainer's clipping.
                NotificationPlateDrawable plate = new NotificationPlateDrawable(host, config, () -> detector.isHeadsUp(owner),
                        () -> notificationCornerRadius(radius, owner), true);
                plate.setRefractionReferenceSize(() -> refractionScale.referenceSize(owner));
                plate.setGroupGeometry(material -> groupGeometry.apply(host, (ViewGroup) owner, material));
                net.z841973620.colorosliquidglass.glass.NotificationGlassTrace.headerCreated(
                        host, owner, (Boolean) chain.getArg(1), plate);
                return plate;
            });
            hooks.log(4, TAG, "Notification group header glass hook installed");
        } catch (ReflectiveOperationException e) {
            hooks.log(5, TAG, "Notification group header API unavailable", e);
        }
    }

    private static double notificationCornerRadius(Method method, View owner) {
        try {
            return ((Number) method.invoke(owner)).doubleValue();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Resolved notification corner API failed", e);
        }
    }

    void hookFullscreenBanner(ClassLoader cl) {
        try {
            Class<?> container = Class.forName(
                    "com.oplus.systemui.notification.interruption.fullscreenbanner.view.FullScreenBannerContainer", false, cl);
            Field bannerField = container.getDeclaredField("mFullScreenBanner");
            Field backgroundField = container.getDeclaredField("mFullScreenBannerBg");
            Field usesBlur = container.getDeclaredField("mUseBlurBannerBg");
            bannerField.setAccessible(true);
            backgroundField.setAccessible(true);
            usesBlur.setAccessible(true);
            hooks.hookOnce(container.getDeclaredMethod("onViewAttachedToWindow", View.class), chain -> {
                GlassConfig config = hooks.currentConfig();
                if (!config.enabled || !config.notificationGlassEnabled || !config.headsUpGlassEnabled) return chain.proceed();
                Object self = chain.getThisObject();
                View banner = (View) bannerField.get(self);
                if (banner == null) return chain.proceed(); // OEM may attach before its content is assigned.
                int radiusId = banner.getResources().getIdentifier("fullscreen_banner_corner_radius", "dimen", "com.android.systemui");
                if (radiusId == 0) return chain.proceed(); // Different ROM: retain the original shape/material.
                double radius = banner.getResources().getDimension(radiusId);
                NotificationPlateDrawable drawable = new NotificationPlateDrawable(banner, config,
                        () -> true, () -> radius, false);
                backgroundField.set(self, drawable);
                usesBlur.setBoolean(self, false);
                banner.setBackground(drawable);
                return null;
            });
            hooks.log(4, TAG, "Fullscreen landscape notification glass hook installed");
        } catch (ReflectiveOperationException e) {
            hooks.log(5, TAG, "Fullscreen notification API unavailable", e);
        }
    }

    /**
     * ColorOS 16 lock-screen notification <b>capsule</b> style only
     * ({@code CapsuleBackgroundView}). Do not touch notification-list / stack rows, and never
     * route through {@link Os14HookBackend}.
     * <p>
     * OEM paints material blur in {@code CapsuleBackgroundHelper.drawCapsuleBg}; skip that once
     * LiquidGlass is installed as the view background and sample lock wallpaper only.
     */
    void hookLockScreenCapsuleGlass(ClassLoader cl) {
        final String bgClass =
                "com.oplus.systemui.notification.lockscreen.capsule.CapsuleBackgroundView";
        if (!HookReflection.hasClass(cl, bgClass)) {
            hooks.log(4, TAG, "hookLockScreenCapsuleGlass skipped (no CapsuleBackgroundView)");
            return;
        }
        hooks.log(4, TAG, "hookLockScreenCapsuleGlass Cos16");
        hooks.after(bgClass, cl, "onAttachedToWindow", this::applyLockScreenCapsuleGlass);
        hooks.after(bgClass, cl, "onLayout", this::applyLockScreenCapsuleGlass);
        try {
            Class<?> helper = Class.forName(
                    "com.oplus.systemui.notification.lockscreen.capsule.CapsuleBackgroundHelper",
                    false, cl);
            for (Method m : helper.getDeclaredMethods()) {
                if (!"drawCapsuleBg".equals(m.getName()) || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 2 || !View.class.isAssignableFrom(params[0])) continue;
                hooks.hookOnce(m, chain -> {
                    try {
                        java.util.List<Object> args = chain.getArgs();
                        if (args != null && !args.isEmpty() && args.get(0) instanceof View
                                && GlassInstaller.get((View) args.get(0)) != null) {
                            GlassConfig config = hooks.currentConfig();
                            if (!config.enabled || !config.notificationGlassEnabled) {
                                GlassInstaller.uninstall((View) args.get(0));
                                return chain.proceed();
                            }
                            // Glass is already the background — skip OEM blur / solid fill.
                            return null;
                        }
                    } catch (Throwable ignored) { }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "CapsuleBackgroundHelper.drawCapsuleBg hook unavailable", e);
        }
    }

    private void applyLockScreenCapsuleGlass(Object bgObj) {
        if (!(bgObj instanceof View)) return;
        final View bg = (View) bgObj;
        GlassConfig config = hooks.currentConfig();
        if (!config.enabled || !config.notificationGlassEnabled) {
            if (GlassInstaller.get(bg) != null) GlassInstaller.uninstall(bg);
            return;
        }
        if (!HookReflection.isClassOrSubclass(bg,
                "com.oplus.systemui.notification.lockscreen.capsule.CapsuleBackgroundView")) {
            return;
        }
        // Capsule cards only — never style unrelated SystemUI views that might share helpers.
        if (!isUnderLockScreenCapsuleItem(bg)) return;
        if (bg.getVisibility() == View.GONE) return;
        try {
            GlassInstaller.markLockWallpaperOnly(bg);
            boolean firstInstall = GlassInstaller.get(bg) == null;
            if (firstInstall) {
                GlassInstaller.installBackground(bg, hooks.currentConfig());
            }
            GlassDrawable live = GlassInstaller.get(bg);
            if (live == null) return;
            Rect clip = readLockScreenCapsuleClipRect(bg);
            float radius = readLockScreenCapsuleRadius(bg, clip);
            if (radius > 0f) {
                live.setCornerRadii(radius, radius, radius, radius);
            }
            // GlassDrawable.draw re-applies clipRect bounds each frame (list→capsule shrink).
            int clipW = clip != null ? clip.width() : bg.getWidth();
            int clipH = clip != null ? clip.height() : bg.getHeight();
            int prevW = lastCapsuleClipW.get(bg) == null ? -1 : lastCapsuleClipW.get(bg);
            int prevH = lastCapsuleClipH.get(bg) == null ? -1 : lastCapsuleClipH.get(bg);
            boolean clipChanged = clipW > 0 && clipH > 0
                    && (clipW != prevW || clipH != prevH);
            if (clipW > 0 && clipH > 0) {
                lastCapsuleClipW.put(bg, clipW);
                lastCapsuleClipH.put(bg, clipH);
            }
            if (bg.getWidth() > 0 && bg.getHeight() > 0) {
                if (firstInstall || clipChanged) {
                    GlassInstaller.forceCapture(bg);
                }
                bg.invalidate();
            } else {
                bg.post(() -> applyLockScreenCapsuleGlass(bg));
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "applyLockScreenCapsuleGlass failed", e);
        }
    }

    private final Map<View, Integer> lastCapsuleClipW =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, Integer> lastCapsuleClipH =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static boolean isUnderLockScreenCapsuleItem(View view) {
        for (View current = view; current != null; ) {
            if (HookReflection.isClassOrSubclass(current,
                    "com.oplus.systemui.notification.lockscreen.capsule.CapsuleItemView")) {
                return true;
            }
            if (HookReflection.isClassOrSubclass(current,
                    "com.oplus.systemui.notification.lockscreen.capsule.NotificationCapsuleContainer")) {
                return true;
            }
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return false;
    }

    private static Rect readLockScreenCapsuleClipRect(View bg) {
        try {
            Object helper = HookReflection.invokeNoArgs(bg, "getCapsuleHelper");
            Object pathProvider = HookReflection.field(helper, "pathProvider");
            Object clip = HookReflection.invokeNoArgs(pathProvider, "getClipRect");
            if (clip instanceof Rect && !((Rect) clip).isEmpty()) {
                return new Rect((Rect) clip);
            }
        } catch (Throwable ignored) { }
        try {
            for (View current = bg; current != null; ) {
                if (HookReflection.isClassOrSubclass(current,
                        "com.oplus.systemui.notification.lockscreen.capsule.CapsuleItemView")) {
                    Object location = HookReflection.field(current, "location");
                    Object clip = HookReflection.invokeNoArgs(location, "getClipRect");
                    if (clip instanceof Rect && !((Rect) clip).isEmpty()) {
                        return new Rect((Rect) clip);
                    }
                    break;
                }
                ViewParent parent = current.getParent();
                if (!(parent instanceof View)) break;
                current = (View) parent;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static float readLockScreenCapsuleRadius(View bg, Rect clip) {
        if (clip != null && clip.width() > 0 && clip.height() > 0) {
            return Math.min(clip.width(), clip.height()) / 2f;
        }
        try {
            Object helper = HookReflection.invokeNoArgs(bg, "getCapsuleHelper");
            Object pathProvider = HookReflection.field(helper, "pathProvider");
            if (pathProvider != null) {
                Object meta = HookReflection.field(pathProvider, "pathMetaData");
                Object radius = meta != null ? HookReflection.field(meta, "radius") : null;
                if (radius == null) radius = HookReflection.invokeNoArgs(pathProvider, "getRadius");
                if (radius instanceof Number && ((Number) radius).floatValue() > 0f) {
                    return ((Number) radius).floatValue();
                }
            }
        } catch (Throwable ignored) { }
        try {
            Class<?> cfg = Class.forName(
                    "com.oplus.systemui.notification.lockscreen.config.CapsuleConfig",
                    false, bg.getClass().getClassLoader());
            java.lang.reflect.Field f = cfg.getDeclaredField("defaultRadius");
            f.setAccessible(true);
            float radius = f.getFloat(null);
            if (radius > 0f) return radius;
            Object instance = cfg.getField("INSTANCE").get(null);
            Object height = HookReflection.invokeNoArgs(instance, "getItemHeight");
            if (height instanceof Number && ((Number) height).intValue() > 0) {
                return ((Number) height).intValue() / 2f;
            }
        } catch (Throwable ignored) { }
        int h = bg.getHeight();
        if (h > 0) return h / 2f;
        try {
            int id = bg.getResources().getIdentifier(
                    "capsule_item_height", "dimen", bg.getContext().getPackageName());
            if (id != 0) return bg.getResources().getDimension(id) / 2f;
        } catch (Throwable ignored) { }
        return 24f * bg.getResources().getDisplayMetrics().density;
    }

    private static float readLockScreenCapsuleRadius(View bg) {
        return readLockScreenCapsuleRadius(bg, readLockScreenCapsuleClipRect(bg));
    }

    /**
     * Cancel the frosted blur that appears behind the notification list when sliding a
     * card up into the message list. The blur is applied to the behind scrim by
     * {@code ScrimControllerExImp.showBehindScrim}; keep that call for QS / Bouncer paths
     * but zero the amount on the keyguard notification-center transition.
     */
    void hookNotificationCenterBlur(ClassLoader cl) {
        try {
            Class<?> c = Class.forName(
                    "com.oplus.systemui.statusbar.phone.ScrimControllerExImp", false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!"showBehindScrim".equals(m.getName()) || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 2 || params[0] != float.class
                        || params[1] != boolean.class) {
                    continue;
                }
                hooks.hookOnce(m, chain -> {
                    if (!hooks.enabled()) return chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        java.util.List<Object> args = chain.getArgs();
                        if (args.size() == 2 && Boolean.FALSE.equals(args.get(1))
                                && args.get(0) instanceof Float
                                && ((Float) args.get(0)).floatValue() > 0f
                                && isKgNotificationCenter(self)) {
                            // Card slide-up into the message list: no blur / no scrim scale.
                            return chain.proceed(new Object[] { 0f, args.get(1) });
                        }
                    } catch (Throwable ignored) { }
                    return chain.proceed();
                });
                break;
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "showBehindScrim notification-center blur hook unavailable", e);
        }
    }

    private static boolean isKgNotificationCenter(Object scrimEx) {
        if (scrimEx == null) return false;
        try {
            Object r = scrimEx.getClass().getMethod("isKgNotificationCenter").invoke(scrimEx);
            return Boolean.TRUE.equals(r);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Install glass on the lock-screen message-list "收起" button ({@code OplusCloseAllButton}).
     * The OEM background is a {@code MaskBlurDrawable} from {@code OplusCloseAllController};
     * once glass is installed, ignore further non-glass background sets and re-assert on
     * attach / layout.
     */
    void hookCloseAllButtonGlass(ClassLoader cl) {
        final String btnClass = "com.oplus.systemui.statusbar.notification.view.OplusCloseAllButton";
        if (!HookReflection.hasClass(cl, btnClass)) {
            hooks.log(4, TAG, "hookCloseAllButtonGlass skipped (no OplusCloseAllButton)");
            return;
        }
        hooks.after("com.oplus.systemui.statusbar.notification.OplusCloseAllController", cl, "bindView",
                this::applyCloseAllButtonGlass);
        hooks.after(btnClass, cl, "onAttachedToWindow", this::applyCloseAllButtonGlass);
        hooks.after(btnClass, cl, "onLayout", this::applyCloseAllButtonGlass);
        try {
            Method setBackground = View.class.getMethod("setBackground", Drawable.class);
            hooks.hookOnce(setBackground, chain -> {
                if (!hooks.enabled()) return chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    java.util.List<Object> args = chain.getArgs();
                    if (self instanceof View && isCloseAllButton((View) self)
                            && GlassInstaller.get((View) self) != null
                            && !args.isEmpty()
                            && !(args.get(0) instanceof GlassDrawable)) {
                        return null;
                    }
                } catch (Throwable ignored) { }
                return chain.proceed();
            });
        } catch (Throwable e) {
            hooks.log(5, TAG, "OplusCloseAllButton setBackground hook unavailable", e);
        }
    }

    private static boolean isCloseAllButton(View view) {
        if (view == null) return false;
        String name = view.getClass().getName();
        return name != null && name.contains("OplusCloseAllButton");
    }

    private void applyCloseAllButtonGlass(Object obj) {
        if (!hooks.enabled()) return;
        View btn = null;
        if (obj instanceof View && isCloseAllButton((View) obj)) {
            btn = (View) obj;
        } else {
            Object f = HookReflection.field(obj, "oplusCloseAllButton");
            if (f instanceof View && isCloseAllButton((View) f)) btn = (View) f;
        }
        if (btn == null || !btn.isAttachedToWindow()) return;
        if (btn.getWidth() <= 0 || btn.getHeight() <= 0) return;
        try {
            GlassInstaller.markLockWallpaperOnly(btn);
            boolean firstInstall = GlassInstaller.get(btn) == null;
            if (firstInstall) {
                GlassInstaller.installBackground(btn, hooks.currentConfig());
            }
            GlassDrawable live = GlassInstaller.get(btn);
            if (live == null) return;
            if (firstInstall) {
                GlassInstaller.forceCapture(btn);
            }
            btn.invalidate();
        } catch (Throwable e) {
            hooks.log(5, TAG, "applyCloseAllButtonGlass failed", e);
        }
    }
}

package net.z841973620.colorosliquidglass.hook;

import android.graphics.Canvas;
import android.view.View;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import net.z841973620.colorosliquidglass.glass.Os14VolumePanelGlass;
import net.z841973620.colorosliquidglass.ipc.DesktopBackdropClient;
import java.lang.reflect.Method;

/** ColorOS 13/14 SystemUI 安装入口、音量面板和 COUI 弹窗。 */
final class LegacySystemUiHooks {
    private final HookContext hooks;
    private final LegacyKeyguardHooks keyguardHooks;

    LegacySystemUiHooks(HookContext hooks) {
        this.hooks = hooks;
        keyguardHooks = new LegacyKeyguardHooks(hooks);
    }


    void install(ClassLoader cl) {
        hooks.log(4, "Os14HookBackend: hooking SystemUI (ColorOS 13/14 APIs)");
        // ColorOS 14 SystemUI has no FlexibleMenuManager liquid-glass float menus.
        // COUI popup windows may still appear in some shells — install glass when present.
        hooks.after(cl, "com.coui.appcompat.poplist.COUIIsolatedPopupListWindow", "prepareShowMainMenu",
                this::applyCouiPopupListGlass);
        hooks.after(cl, "com.coui.appcompat.poplist.COUIIsolatedPopupListWindow", "dismiss",
                ignored -> {
                    try {
                        net.z841973620.colorosliquidglass.glass.BehindDisplayCapture.stopAllSessions();
                    } catch (Throwable ignored2) { }
                });
        keyguardHooks.hookKeyguardAffordanceGlass(cl);
        hookSystemUiVolumeGlass(cl);
        DesktopBackdropClient.warmUp();
    }

    /**
     * ColorOS 13/14 volume dialog glass. Lock screen samples lock content in-process
     * (same as ColorOS 16); every other page uses captureScreen via
     * {@link net.z841973620.colorosliquidglass.glass.BehindDisplayCapture#bindApp}.
     */
    private void hookSystemUiVolumeGlass(ClassLoader cl) {
        final String implClass = "com.oplus.systemui.volume.OplusVolumeDialogImpl";
        hooks.after(cl, implClass, "initDialog", this::scheduleOs14VolumeGlass);
        hooks.after(cl, implClass, "showH", this::scheduleOs14VolumeGlass);
        hooks.after(cl, implClass, "expandPanel", this::scheduleOs14VolumeGlass);
        hooks.after(cl, implClass, "updateRowsH", this::scheduleOs14VolumeGlass);
        hooks.after(cl, implClass, "dismissH", this::releaseOs14VolumeGlass);
        try {
            Class<?> sb = Class.forName(
                    "com.oplus.systemui.volume.VolumeSeekBar", false, cl);
            for (Method m : sb.getDeclaredMethods()) {
                if (!"onDraw".equals(m.getName()) || m.getParameterCount() != 1
                        || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                hooks.hookOnce(m, chain -> {
                    try {
                        Object self = chain.getThisObject();
                        Object arg = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                        if (self instanceof View && arg instanceof Canvas
                                && Os14VolumePanelGlass.handleSeekBarOnDraw(
                                        (View) self, (Canvas) arg)) {
                            return null;
                        }
                    } catch (Throwable ignored) { }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 VolumeSeekBar.onDraw hook unavailable", e);
        }
    }

    private void scheduleOs14VolumeGlass(Object dialogImpl) {
        try {
            if (!hooks.enabled() || dialogImpl == null) return;
            Os14VolumePanelGlass.schedule(dialogImpl, hooks.currentConfig());
        } catch (Throwable e) {
            hooks.log(5, "Os14 scheduleOs14VolumeGlass failed", e);
        }
    }

    private void releaseOs14VolumeGlass(Object dialogImpl) {
        try {
            Os14VolumePanelGlass.release(dialogImpl);
        } catch (Throwable t) {
            hooks.log(5, "Os14 volume glass release failed", t);
        }
    }

    private void applyCouiPopupListGlass(Object popup) {
        if (!hooks.menuStyleEnabled() || popup == null) return;
        try {
            View wrapper = HookReflection.asView(HookReflection.field(popup, "mMainMenuWrapper"));
            if (wrapper == null) wrapper = HookReflection.asView(HookReflection.field(popup, "mContentView"));
            if (wrapper == null && popup instanceof View) wrapper = (View) popup;
            if (wrapper == null) return;
            GlassInstaller.installBackground(wrapper, hooks.currentConfig());
            GlassInstaller.forceCapture(wrapper);
        } catch (Throwable e) {
            hooks.log(5, "Os14 COUI popup glass failed", e);
        }
    }
}

package net.z841973620.colorosliquidglass.hook;

import net.z841973620.colorosliquidglass.glass.VolumePanelGlass;
import java.lang.reflect.Method;
import static net.z841973620.colorosliquidglass.hook.HookContext.TAG;

/** 音量面板 Hook 与玻璃渲染器的接入。 */
final class VolumeHooks {
    private final HookContext hooks;

    VolumeHooks(HookContext hooks) {
        this.hooks = hooks;
    }


    /**
     * ColorOS 16 volume indicator + expanded panel: strip OEM blur and install live glass.
     * Capture is the float-menu split (ashmem on launcher pages, captureDisplay on apps/lock).
     */
    void hookSystemUiVolumeGlass(ClassLoader cl) {
        hooks.after("com.oplus.systemui.volume.view.OplusVolumeDialogView", cl, "initDialog",
                this::scheduleVolumePanelGlass);
        hooks.after("com.oplus.systemui.volume.view.OplusVolumeDialogView", cl, "showSliderVolume",
                this::scheduleVolumePanelGlass);
        hooks.after("com.oplus.systemui.volume.view.OplusVolumeDialogView", cl, "expandPanel",
                this::scheduleVolumePanelGlass);
        hooks.after("com.oplus.systemui.volume.view.OplusVolumeDialogView", cl, "updateRowsH",
                this::scheduleVolumePanelGlass);
        hooks.after("com.oplus.systemui.volume.view.OplusVolumeDialogView", cl, "checkSettingButtonIsVisible",
                this::scheduleVolumePanelGlass);
        hooks.after("com.oplus.systemui.volume.view.OplusVolumeDialogView", cl, "dismissH",
                this::releaseVolumePanelGlass);
        hooks.after("com.oplus.systemui.volume.OplusVolumeDialogImpl", cl, "showH", impl -> {
            scheduleVolumePanelGlass(HookReflection.field(impl, "mOplusVolumeDialogView"));
        });
        hooks.after("com.oplus.systemui.volume.OplusVolumeDialogImpl", cl, "expandPanel", impl -> {
            scheduleVolumePanelGlass(HookReflection.field(impl, "mOplusVolumeDialogView"));
        });
        hooks.after("com.oplus.systemui.volume.OplusVolumeDialogImpl", cl, "dismissH", impl -> {
            releaseVolumePanelGlass(HookReflection.field(impl, "mOplusVolumeDialogView"));
        });

        try {
            Class<?> blur = Class.forName(
                    "com.oplus.systemui.volume.utils.VolumeBlurManager", false, cl);
            for (Method m : blur.getDeclaredMethods()) {
                if (m.isBridge()) continue;
                String name = m.getName();
                if ("getVolumeBarBackground".equals(name)
                        || "getVolumePanelBackground".equals(name)
                        || "tryTakeScreenshotForBlur".equals(name)) {
                    hooks.hookOnce(m, chain -> {
                        if (!hooks.enabled()) return chain.proceed();
                        if ("tryTakeScreenshotForBlur".equals(name)) {
                            return null;
                        }
                        if ("getVolumeBarBackground".equals(name)) {
                            return VolumePanelGlass.transparentBarBackground();
                        }
                        return VolumePanelGlass.transparentPanelBackground();
                    });
                }
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "VolumeBlurManager hooks unavailable", e);
        }

        try {
            Class<?> seek = Class.forName(
                    "com.coui.appcompat.seekbar.COUIVerticalSeekBar", false, cl);
            for (Method m : seek.getDeclaredMethods()) {
                if (!"onDraw".equals(m.getName()) || m.getParameterCount() != 1) continue;
                hooks.hookOnce(m, chain -> {
                    try {
                        VolumePanelGlass.clearSeekBarOemFill(chain.getThisObject());
                    } catch (Throwable ignored) { }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "COUIVerticalSeekBar.onDraw volume fill hook unavailable", e);
        }

    }

    private void scheduleVolumePanelGlass(Object dialogView) {
        if (!hooks.enabled() || dialogView == null) return;
        if (!HookReflection.isClassOrSubclass(dialogView, "com.oplus.systemui.volume.view.OplusVolumeDialogView")) {
            Object nested = HookReflection.field(dialogView, "mOplusVolumeDialogView");
            if (nested != null) dialogView = nested;
        }
        VolumePanelGlass.schedule(dialogView, hooks.currentConfig());
    }

    private void releaseVolumePanelGlass(Object dialogView) {
        if (dialogView == null) return;
        if (!HookReflection.isClassOrSubclass(dialogView, "com.oplus.systemui.volume.view.OplusVolumeDialogView")) {
            Object nested = HookReflection.field(dialogView, "mOplusVolumeDialogView");
            if (nested != null) dialogView = nested;
        }
        try {
            VolumePanelGlass.release(dialogView);
        } catch (Throwable t) {
            hooks.log(5, TAG, "volume glass release failed", t);
        }
    }
}

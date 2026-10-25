package net.z841973620.colorosliquidglass.hook;

import android.content.SharedPreferences;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import net.z841973620.colorosliquidglass.GlassConfig;
import net.z841973620.colorosliquidglass.ipc.DesktopBackdropClient;
import static net.z841973620.colorosliquidglass.hook.HookContext.TAG;

/** LSPosed 入口：读取配置，识别系统版本，再分派到桌面或 SystemUI 功能模块。 */
public final class ModuleMain extends XposedModule {

    private SharedPreferences remotePreferences;
    /** ColorOS 13/14 backend; null when using the built-in ColorOS 15+ Hook path. */
    private Os14HookBackend legacyBackend;
    private final HookContext hooks = new HookContext(this, () -> remotePreferences);
    private final LauncherHooks launcherHooks = new LauncherHooks(hooks);
    private final NotificationHooks notificationHooks = new NotificationHooks(hooks);
    private final KeyguardHooks keyguardHooks = new KeyguardHooks(hooks);
    private final FloatMenuHooks floatMenuHooks = new FloatMenuHooks(hooks);
    private final VolumeHooks volumeHooks = new VolumeHooks(hooks);

    @Override public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!pkg.equals("com.android.launcher") && !pkg.equals("com.android.systemui")) return;
        try {
            remotePreferences = getRemotePreferences(GlassConfig.PREFS);
            GlassConfig loaded = GlassConfig.read(remotePreferences);
            hooks.log(4, TAG, "package=" + pkg + " first=" + param.isFirstPackage()
                    + " enabled=" + loaded.enabled
                    + " updatedAt=" + remotePreferences.getLong("updated_at", 0L));
        } catch (Throwable e) {
            remotePreferences = null;
            hooks.log(5, TAG, "Cannot read remote preferences; defaults are used", e);
        }
        // Do not return for !isFirstPackage or enabled=false. ColorOS may expose the useful
        // ClassLoader in a later callback, and hooks must already exist when the next config is read.
        ClassLoader cl = param.getDefaultClassLoader();
        ColorOsVersion.Flavor flavor = ColorOsVersion.detect(cl);
        boolean useLegacy = ColorOsVersion.usesLegacyLauncherApis(cl);
        hooks.log(4, TAG, "ColorOS flavor=" + flavor
                + " major=" + ColorOsVersion.majorVersion()
                + " useLegacyApis=" + useLegacy);
        if (pkg.equals("com.android.systemui")) {
            notificationHooks.hookNotificationCardGlass(cl);
            notificationHooks.hookNotificationGroupHeader(cl);
            notificationHooks.hookFullscreenBanner(cl);
            // e9e3cd0 behavior: always mount the full FlexibleMenu / BehindDisplayCapture chain.
            // Class-not-found only logs; never let OS14 COUI stub replace this path on ColorOS 16.
            floatMenuHooks.hookSystemUiFloatMenus(cl);
            volumeHooks.hookSystemUiVolumeGlass(cl);
            notificationHooks.hookNotificationCenterBlur(cl);
            notificationHooks.hookCloseAllButtonGlass(cl);
            DesktopBackdropClient.warmUp();
            // Cos16 SystemUI CL often lacks FlexibleMenuManager → useLegacy true would wrongly
            // take Os14 AOSP updateButton and never install Cos16 glass.
            boolean hasCos16Keyguard = HookReflection.hasClass(cl,
                    "com.oplus.systemui.keyguard.ui.binder.OplusKeyguardBottomAreaViewBinder");
            boolean cos14SysUi = useLegacy
                    && !HookReflection.hasClass(cl, "com.oplus.flexibletask.menu.FlexibleMenuManager")
                    && !hasCos16Keyguard;
            hooks.log(4, TAG, "SystemUI keyguard route cos14SysUi=" + cos14SysUi
                    + " hasCos16Keyguard=" + hasCos16Keyguard);
            if (cos14SysUi) {
                if (legacyBackend == null) legacyBackend = new Os14HookBackend();
                legacyBackend.hookSystemUi(this, cl, remotePreferences);
            } else {
                keyguardHooks.hookKeyguardAffordanceGlass(cl);
                notificationHooks.hookLockScreenCapsuleGlass(cl);
            }
            return;
        }
        if (useLegacy) {
            if (legacyBackend == null) legacyBackend = new Os14HookBackend();
            legacyBackend.hookLauncher(this, cl, remotePreferences);
            return;
        }
        launcherHooks.hookLauncher(cl);
    }
}

package net.z841973620.colorosliquidglass.hook;

import android.content.SharedPreferences;
import io.github.libxposed.api.XposedModule;

/** ColorOS 13/14 兼容入口；具体功能按桌面、文件夹、菜单和 SystemUI 分文件实现。 */
public final class Os14HookBackend implements HookBackend {
    private SharedPreferences preferences;
    private LegacyLauncherHooks launcherHooks;
    private LegacySystemUiHooks systemUiHooks;

    @Override
    public void hookLauncher(XposedModule module, ClassLoader loader, SharedPreferences preferences) {
        initialize(module, preferences);
        launcherHooks.install(loader);
    }

    @Override
    public void hookSystemUi(XposedModule module, ClassLoader loader, SharedPreferences preferences) {
        initialize(module, preferences);
        systemUiHooks.install(loader);
    }

    private void initialize(XposedModule module, SharedPreferences preferences) {
        this.preferences = preferences;
        if (launcherHooks != null) return;
        HookContext hooks = new HookContext(module, () -> this.preferences);
        launcherHooks = new LegacyLauncherHooks(hooks);
        systemUiHooks = new LegacySystemUiHooks(hooks);
    }
}

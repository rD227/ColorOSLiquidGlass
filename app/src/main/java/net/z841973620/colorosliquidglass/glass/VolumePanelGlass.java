package net.z841973620.colorosliquidglass.glass;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

import net.z841973620.colorosliquidglass.GlassConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * ColorOS 16 volume dialog: strip OEM bar/panel blur and install live glass on the
 * indicator seekbars plus the expanded-panel Settings button.
 * <p>
 * Capture follows the float-menu split, except lock:
 * <ul>
 *   <li>Launcher home / widget page / Overview — ashmem (Launcher process)</li>
 *   <li>Lock screen — lock wallpaper plus in-process clock / notification views</li>
 *   <li>Any visible app including floats — {@code captureDisplay} only</li>
 * </ul>
 */
public final class VolumePanelGlass {
    private static final String TAG = "ColorOSLiquidGlass";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ColorDrawable TRANSPARENT = new ColorDrawable(Color.TRANSPARENT);
    private static final Map<View, WeakReference<Object>> INSTALLED = new WeakHashMap<>();
    /** Main-thread callbacks owned by the dialog that requested them. */
    private static final Map<Object, Runnable> PENDING_APPLIES = new IdentityHashMap<>();
    private static final long[] APPLY_DELAYS_MS = {0L, 32L, 120L, 280L, 520L};

    private VolumePanelGlass() {}

    public static void apply(Object dialogView, GlassConfig config) {
        if (dialogView == null || config == null || !config.enabled) return;
        ViewGroup root = asViewGroup(field(dialogView, "mDialogView"));
        if (root == null) {
            Object dialog = field(dialogView, "volumeDialog");
            Object window = invoke(dialog, "getWindow");
            Object decor = invoke(window, "getDecorView");
            root = asViewGroup(decor);
        }
        if (root == null) return;

        stripPanelBlur(dialogView, root);

        List<View> hosts = new ArrayList<>();
        collectSeekBars(root, hosts);
        View settings = asView(field(dialogView, "mSettingsButton"));
        if (settings != null && settings.getVisibility() == View.VISIBLE
                && settings.getWidth() > 0 && settings.getHeight() > 0) {
            hosts.add(settings);
        }

        Set<View> keep = new HashSet<>(hosts);
        ArrayList<View> stale = new ArrayList<>();
        synchronized (INSTALLED) {
            for (Map.Entry<View, WeakReference<Object>> entry : INSTALLED.entrySet()) {
                View v = entry.getKey();
                if (entry.getValue().get() == dialogView && !keep.contains(v)) stale.add(v);
            }
        }
        for (View v : stale) {
            GlassInstaller.uninstall(v);
            synchronized (INSTALLED) {
                INSTALLED.remove(v);
            }
        }

        for (View host : hosts) {
            installHost(dialogView, host, config);
        }
        bindCapture(hosts);
    }

    public static void release(Object dialogView) {
        cancelPendingApply(dialogView);
        ArrayList<View> hosts;
        synchronized (INSTALLED) {
            hosts = new ArrayList<>();
            for (Map.Entry<View, WeakReference<Object>> entry : INSTALLED.entrySet()) {
                if (entry.getValue().get() == dialogView) hosts.add(entry.getKey());
            }
            for (View host : hosts) INSTALLED.remove(host);
        }
        for (View host : hosts) {
            GlassInstaller.uninstall(host);
        }
        if (dialogView != null) {
            ViewGroup root = asViewGroup(field(dialogView, "mDialogView"));
            if (root != null) {
                try { root.setBackground(null); } catch (Throwable ignored) { }
            }
            setField(dialogView, "mVolumeBackgroundBlurDrawable", TRANSPARENT);
        }
    }

    public static void clearSeekBarOemFill(Object seekBar) {
        if (seekBar == null) return;
        String name = seekBar.getClass().getName();
        if (name == null || !name.contains("OplusVolumeSeekBar")) return;
        setField(seekBar, "mBackgroundColor", 0);
        setField(seekBar, "mInactiveTrackDrawable", null);
        Object paint = field(seekBar, "mBackgroundPaint");
        if (paint instanceof android.graphics.Paint) {
            try { ((android.graphics.Paint) paint).setColor(Color.TRANSPARENT); } catch (Throwable ignored) { }
        }
    }

    public static Drawable transparentBarBackground() {
        return TRANSPARENT;
    }

    public static Drawable transparentPanelBackground() {
        return TRANSPARENT;
    }

    private static void stripPanelBlur(Object dialogView, ViewGroup root) {
        try { root.setBackground(null); } catch (Throwable ignored) { }
        setField(dialogView, "mVolumeBackgroundBlurDrawable", TRANSPARENT);
        Object map = field(dialogView, "mVerticalRowsLayerDrawableMap");
        if (map instanceof Map) {
            try {
                for (Object value : ((Map<?, ?>) map).values()) {
                    if (value instanceof Drawable && !(value instanceof GlassDrawable)) {
                        zeroBlurAmount(value);
                    }
                }
            } catch (Throwable ignored) { }
        }
    }

    private static void zeroBlurAmount(Object drawable) {
        try {
            Object proxy = invoke(drawable, "getViewBlurProxy");
            if (proxy != null) {
                Method set = proxy.getClass().getMethod("setBlurAmount", float.class);
                set.invoke(proxy, 0f);
            }
        } catch (Throwable ignored) { }
    }

    private static void installHost(Object dialogView, View host, GlassConfig config) {
        if (host == null) return;
        BehindDisplayCapture.tagVolumeHost(host);
        if (isKeyguardShowing(host)) {
            BehindDisplayCapture.stopStreaming(host);
            GlassInstaller.markLockContent(host);
        } else {
            GlassInstaller.clearWallpaperOnly(host);
        }
        clearSeekBarOemFill(host);
        try { host.setBackgroundTintList(null); } catch (Throwable ignored) { }
        try { host.setLayerType(View.LAYER_TYPE_NONE, null); } catch (Throwable ignored) { }

        GlassInstaller.installBackground(host, config);
        GlassDrawable glass = GlassInstaller.get(host);
        if (glass == null) return;

        boolean seekBar = host.getClass().getName() != null
                && host.getClass().getName().contains("OplusVolumeSeekBar");
        if (seekBar) {
            try { invoke(host, "setOutline"); } catch (Throwable ignored) { }
            try { host.setClipToOutline(true); } catch (Throwable ignored) { }
            Object radius = field(host, "mCornerRadius");
            if (!(radius instanceof Number) || ((Number) radius).floatValue() <= 0f) {
                radius = field(host, "mOutLineRadius");
            }
            if (!(radius instanceof Number) || ((Number) radius).floatValue() <= 0f) {
                radius = field(host, "mCurBackgroundRadius");
            }
            if (radius instanceof Number) {
                float r = ((Number) radius).floatValue();
                if (r > 0f) glass.setCornerRadii(r, r, r, r);
            }
            rememberSliderBackground(dialogView, host, glass);
        } else {
            float r = Math.min(Math.max(1, host.getWidth()), Math.max(1, host.getHeight())) * 0.5f;
            glass.setCornerRadii(r, r, r, r);
            try {
                host.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        int w = view.getWidth();
                        int h = view.getHeight();
                        float cr = Math.min(w, h) * 0.5f;
                        outline.setRoundRect(0, 0, w, h, cr);
                    }
                });
                host.setClipToOutline(true);
            } catch (Throwable ignored) { }
            if (host instanceof ImageView) {
                try { ((ImageView) host).setBackgroundTintList(null); } catch (Throwable ignored) { }
            }
        }

        synchronized (INSTALLED) {
            INSTALLED.put(host, new WeakReference<>(dialogView));
        }
    }

    @SuppressWarnings("unchecked")
    private static void rememberSliderBackground(Object dialogView, View slider, Drawable glass) {
        Object map = field(dialogView, "mVerticalRowsLayerDrawableMap");
        if (!(map instanceof Map)) return;
        Integer stream = streamOfSlider(dialogView, slider);
        if (stream == null) return;
        try {
            ((Map<Integer, Drawable>) map).put(stream, glass);
        } catch (Throwable ignored) { }
    }

    private static Integer streamOfSlider(Object dialogView, View slider) {
        Object adapter = field(dialogView, "volumeListAdapter");
        if (adapter == null) adapter = invoke(dialogView, "getVolumeListAdapter");
        List<?> rows = volumeRows(adapter);
        if (rows == null) return null;
        for (Object row : rows) {
            Object s = invoke(row, "getSlider");
            if (s == slider) {
                Object stream = invoke(row, "getStream");
                if (stream instanceof Integer) return (Integer) stream;
                if (stream instanceof Number) return ((Number) stream).intValue();
            }
        }
        return null;
    }

    private static List<?> volumeRows(Object adapter) {
        if (adapter == null) return null;
        Object list = invoke(adapter, "getCurrentList");
        if (list instanceof List && !((List<?>) list).isEmpty()) return (List<?>) list;
        list = invoke(adapter, "getVolumeRowList");
        if (list instanceof List) return (List<?>) list;
        list = invoke(adapter, "getPanelShowList");
        if (list instanceof List && !((List<?>) list).isEmpty()) return (List<?>) list;
        list = invoke(adapter, "getSideShowList");
        if (list instanceof List) return (List<?>) list;
        return null;
    }

    private static void collectSeekBars(View view, List<View> out) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        String name = view.getClass().getName();
        if (name != null && name.contains("OplusVolumeSeekBar")
                && view.isShown()
                && view.getWidth() > 0 && view.getHeight() > 0
                && view.getAlpha() > 0.01f) {
            Rect visible = new Rect();
            if (view.getGlobalVisibleRect(visible) && visible.width() >= 4 && visible.height() >= 4) {
                out.add(view);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectSeekBars(g.getChildAt(i), out);
            }
        }
    }

    private static void bindCapture(List<View> hosts) {
        if (hosts == null || hosts.isEmpty()) return;
        View seed = null;
        for (View h : hosts) {
            if (h != null && h.isAttachedToWindow()) {
                seed = h;
                break;
            }
        }
        if (seed == null) return;

        boolean keyguard = isKeyguardShowing(seed);
        boolean appContent = !keyguard && hasVisibleAppContent(seed);
        boolean useAppCapture = !keyguard && (appContent
                || BehindDisplayCapture.anyMenuDesktopSessionOpen());

        Rect union = unionOnScreen(hosts);
        Log.i(TAG, "volume glass capture "
                + (keyguard ? "LOCK_CONTENT" : (useAppCapture ? "APP" : "DESKTOP"))
                + " hosts=" + hosts.size()
                + " region=" + (union == null ? "empty" : union.toShortString()));

        for (View host : hosts) {
            if (host == null || !host.isAttachedToWindow()) continue;
            BehindDisplayCapture.tagVolumeHost(host);
            try { host.setLayerType(View.LAYER_TYPE_NONE, null); } catch (Throwable ignored) { }
            if (keyguard) {
                BehindDisplayCapture.stopStreaming(host);
                GlassInstaller.markLockContent(host);
                GlassInstaller.forceCapture(host);
            } else if (useAppCapture) {
                GlassInstaller.clearWallpaperOnly(host);
                BehindDisplayCapture.bindApp(host, null);
                BehindDisplayCapture.requestRefresh(host, true);
            } else {
                GlassInstaller.clearWallpaperOnly(host);
                BehindDisplayCapture.bindVolumeDesktop(host);
                BehindDisplayCapture.requestRefresh(host, true);
            }
            host.invalidate();
        }
    }

    private static Rect unionOnScreen(List<View> hosts) {
        Rect union = new Rect();
        int[] loc = new int[2];
        for (View host : hosts) {
            if (host == null || !host.isAttachedToWindow()) continue;
            int w = host.getWidth();
            int h = host.getHeight();
            if (w <= 0 || h <= 0) continue;
            host.getLocationOnScreen(loc);
            Rect r = new Rect(loc[0], loc[1], loc[0] + w, loc[1] + h);
            if (union.isEmpty()) union.set(r);
            else union.union(r);
        }
        return union.isEmpty() ? null : union;
    }

    static boolean isKeyguardShowing(View host) {
        if (host == null) return false;
        try {
            KeyguardManager km = (KeyguardManager)
                    host.getContext().getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    /**
     * True when a non-launcher activity is on-screen, including ColorOS floats / freeform.
     * Overview recents cards are launcher-composited and do not count.
     */
    static boolean hasVisibleAppContent(View host) {
        if (host == null) return false;
        try {
            ActivityManager am = (ActivityManager)
                    host.getContext().getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            @SuppressWarnings("deprecation")
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(16);
            if (tasks == null) return false;
            boolean floatApp = false;
            String frontPkg = null;
            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (task == null) continue;
                try {
                    if (!task.isVisible()) continue;
                } catch (Throwable ignored) { }
                android.content.ComponentName top = task.topActivity;
                if (top == null) top = task.baseActivity;
                if (top == null) continue;
                String pkg = top.getPackageName();
                if (isSystemUiPackage(pkg)) continue;
                if (frontPkg == null) frontPkg = pkg;
                if (!isLauncherPackage(pkg) && isFloatLike(task)) floatApp = true;
            }
            if (floatApp) return true;
            return frontPkg != null && !isLauncherPackage(frontPkg);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isFloatLike(ActivityManager.RunningTaskInfo task) {
        try {
            Object config = null;
            try {
                java.lang.reflect.Field cf = task.getClass().getField("configuration");
                config = cf.get(task);
            } catch (Throwable ignored) { }
            if (config == null) {
                try {
                    Method getConfig = task.getClass().getMethod("getConfiguration");
                    config = getConfig.invoke(task);
                } catch (Throwable ignored) { }
            }
            if (config == null) return false;
            Object wc = config.getClass().getField("windowConfiguration").get(config);
            if (wc == null) return false;
            Object modeObj = wc.getClass().getMethod("getWindowingMode").invoke(wc);
            if (!(modeObj instanceof Number)) return false;
            int mode = ((Number) modeObj).intValue();
            // 1 = fullscreen. Anything else (freeform / pinned / split / OEM zoom) is app chrome
            // sitting above launcher, so volume glass must screenshot rather than ashmem-draw home.
            return mode != 0 && mode != 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isLauncherPackage(String pkg) {
        if (pkg == null) return false;
        return pkg.equals("com.android.launcher")
                || pkg.equals("com.android.launcher3")
                || pkg.equals("com.oppo.launcher")
                || pkg.equals("com.oplus.launcher")
                || pkg.equals("com.coloros.launcher");
    }

    private static boolean isSystemUiPackage(String pkg) {
        return pkg != null && (pkg.equals("com.android.systemui")
                || pkg.equals("com.oplus.systemui"));
    }

    public static void schedule(Object dialogView, GlassConfig config) {
        if (dialogView == null || config == null || !config.enabled) return;
        cancelPendingApply(dialogView);
        Runnable run = new Runnable() {
            private int remaining = APPLY_DELAYS_MS.length;

            @Override public void run() {
                if (--remaining == 0) PENDING_APPLIES.remove(dialogView);
                try {
                    apply(dialogView, config);
                } catch (Throwable t) {
                    Log.w(TAG, "volume glass apply failed", t);
                }
            }
        };
        PENDING_APPLIES.put(dialogView, run);
        for (long delay : APPLY_DELAYS_MS) MAIN.postDelayed(run, delay);
    }

    private static void cancelPendingApply(Object dialogView) {
        Runnable pending = PENDING_APPLIES.remove(dialogView);
        if (pending != null) MAIN.removeCallbacks(pending);
    }

    private static ViewGroup asViewGroup(Object o) {
        return o instanceof ViewGroup ? (ViewGroup) o : null;
    }

    private static View asView(Object o) {
        return o instanceof View ? (View) o : null;
    }

    private static Object field(Object object, String name) {
        if (object == null) return null;
        Class<?> c = object.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(object);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static boolean setField(Object object, String name, Object value) {
        if (object == null) return false;
        Class<?> c = object.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(object, value);
                return true;
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return false;
    }

    private static Object invoke(Object object, String name) {
        if (object == null) return null;
        Class<?> c = object.getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(object);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}

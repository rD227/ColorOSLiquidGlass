package net.z841973620.colorosliquidglass.glass;

import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;

import net.z841973620.colorosliquidglass.GlassConfig;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * ColorOS 13/14 volume dialog: strip OEM blur and install live glass on the seekbars.
 * <p>
 * Capture:
 * <ul>
 *   <li>Lock screen — same in-process lock content sample as ColorOS 16 (wallpaper +
 *       clock / notification views via {@link LockscreenContentOverlay})</li>
 *   <li>Every other page — {@code captureScreen} ({@code captureDisplay}) only</li>
 * </ul>
 * Cos14 seekbars ({@code VolumeSeekBar}) draw their pill background + progress in
 * {@code onDraw}; once glass is installed the OEM background draw is replaced by a manual
 * progress stroke so the glass backdrop shows through.
 */
public final class Os14VolumePanelGlass {
    private static final String TAG = "ColorOSLiquidGlass";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ColorDrawable TRANSPARENT = new ColorDrawable(Color.TRANSPARENT);
    private static final Set<View> INSTALLED =
            java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private static final Paint PROGRESS_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Os14VolumePanelGlass() {}

    public static void schedule(Object dialogImpl, GlassConfig config) {
        if (dialogImpl == null || config == null || !config.enabled) return;
        Runnable run = () -> {
            try {
                apply(dialogImpl, config);
            } catch (Throwable t) {
                Log.w(TAG, "os14 volume glass apply failed", t);
            }
        };
        MAIN.post(run);
        MAIN.postDelayed(run, 32L);
        MAIN.postDelayed(run, 120L);
        MAIN.postDelayed(run, 280L);
        MAIN.postDelayed(run, 520L);
    }

    public static void release(Object dialogImpl) {
        ArrayList<View> hosts;
        synchronized (INSTALLED) {
            hosts = new ArrayList<>(INSTALLED);
            INSTALLED.clear();
        }
        for (View host : hosts) {
            GlassInstaller.uninstall(host);
        }
        BehindDisplayCapture.stopVolumeSessions();
        if (dialogImpl != null) {
            stripPanelBlur(dialogImpl);
        }
    }

    public static void apply(Object dialogImpl, GlassConfig config) {
        if (dialogImpl == null || config == null || !config.enabled) return;
        ViewGroup rowsContainer = asViewGroup(field(dialogImpl, "mVerticalRowsContainer"));
        if (rowsContainer == null) {
            ViewGroup root = asViewGroup(field(dialogImpl, "mDialogView"));
            if (root == null) return;
            rowsContainer = root;
        }
        ViewGroup dialogRoot = asViewGroup(field(dialogImpl, "mDialogView"));
        if (dialogRoot == null) dialogRoot = rowsContainer;

        stripPanelBlur(dialogImpl);

        List<View> hosts = new ArrayList<>();
        addContainerIfShown(asViewGroup(field(dialogImpl, "mVerticalRowsContainer")), hosts);
        addContainerIfShown(asViewGroup(field(dialogImpl, "mHorizontalRowsContainer")), hosts);
        addContainerIfShown(asViewGroup(field(dialogImpl, "mHorizontalMultiMediaRowsContainerDetail")), hosts);
        View moreBg = asView(field(dialogImpl, "mBackground"));
        if (moreBg != null && moreBg.getVisibility() == View.VISIBLE
                && moreBg.getWidth() > 0 && moreBg.getHeight() > 0) {
            hosts.add(moreBg);
        }
        collectSeekBars(dialogRoot, hosts);

        Set<View> keep = new HashSet<>(hosts);
        ArrayList<View> stale = new ArrayList<>();
        synchronized (INSTALLED) {
            for (View v : INSTALLED) {
                if (v != null && !keep.contains(v)) stale.add(v);
            }
        }
        for (View v : stale) {
            GlassInstaller.uninstall(v);
            synchronized (INSTALLED) {
                INSTALLED.remove(v);
            }
        }

        for (View host : hosts) {
            installHost(host, config);
        }
        bindCapture(hosts);
    }

    private static void addContainerIfShown(ViewGroup container, List<View> out) {
        if (container != null && container.isShown()
                && container.getWidth() > 0 && container.getHeight() > 0
                && container.getAlpha() > 0.01f) {
            out.add(container);
        }
    }

    private static void stripPanelBlur(Object dialogImpl) {
        try {
            ViewGroup rows = asViewGroup(field(dialogImpl, "mVerticalRowsContainer"));
            if (rows != null) rows.setBackground(null);
        } catch (Throwable ignored) { }
        try {
            View more = asView(field(dialogImpl, "mBackground"));
            if (more != null) more.setBackground(null);
        } catch (Throwable ignored) { }
        try {
            View captions = asView(field(dialogImpl, "mODICaptionsBackground"));
            if (captions != null) captions.setBackground(null);
        } catch (Throwable ignored) { }
        for (String name : new String[] {
                "mVerticalRowsLayerDrawable", "mVerticalRowsBlurDrawable",
                "mVolumeMoreLayerDrawable", "mVolumeMoreBlurDrawable",
                "mVolumeCaptionLayerDrawable", "mVolumeCaptionBlurDrawable" }) {
            setField(dialogImpl, name, TRANSPARENT);
        }
    }

    private static void installHost(View host, GlassConfig config) {
        if (host == null) return;
        BehindDisplayCapture.tagVolumeHost(host);
        if (isKeyguardShowing(host)) {
            BehindDisplayCapture.stopStreaming(host);
            GlassInstaller.markLockContent(host);
        } else {
            GlassInstaller.clearWallpaperOnly(host);
        }
        try { host.setBackgroundTintList(null); } catch (Throwable ignored) { }
        try { host.setLayerType(View.LAYER_TYPE_NONE, null); } catch (Throwable ignored) { }

        GlassInstaller.installBackground(host, config);
        GlassDrawable glass = GlassInstaller.get(host);
        if (glass == null) return;

        if (isSeekBar(host)) {
            Rect pill = volumePillRect(host);
            if (pill != null && !pill.isEmpty()) {
                glass.setBounds(new Rect(pill));
                float r = Math.max(1f, pill.height() / 2f);
                glass.setCornerRadii(r, r, r, r);
            }
        } else {
            float r = containerRadius(host);
            glass.setCornerRadii(r, r, r, r);
        }
        try { host.setClipToOutline(false); } catch (Throwable ignored) { }

        synchronized (INSTALLED) {
            INSTALLED.add(host);
        }
    }

    private static float containerRadius(View container) {
        try {
            int id = container.getResources().getIdentifier(
                    "volume_vertical_row_radius", "dimen", container.getContext().getPackageName());
            if (id != 0) return container.getResources().getDimension(id);
        } catch (Throwable ignored) { }
        return 18f * container.getResources().getDisplayMetrics().density;
    }

    /**
     * On-screen rect of the Cos14 seekbar pill. The vertical layout rotates the seekbar by
     * -90° (a 164x164 square), so the pill (local horizontal strip) becomes a vertical
     * strip on screen. Map the four pill corners through the view matrix to get the exact
     * axis-aligned screen bounding box.
     */
    public static Rect pillScreenRect(View seekBar) {
        if (seekBar == null) return new Rect();
        Rect pill = volumePillRect(seekBar);
        if (pill == null || pill.isEmpty()) return new Rect();
        try {
            android.graphics.Matrix toGlobal = new android.graphics.Matrix();
            seekBar.transformMatrixToGlobal(toGlobal);
            float[] pts = new float[] {
                    pill.left, pill.top,
                    pill.right, pill.top,
                    pill.right, pill.bottom,
                    pill.left, pill.bottom
            };
            toGlobal.mapPoints(pts);
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            for (int i = 0; i < 4; i++) {
                int x = Math.round(pts[i * 2]);
                int y = Math.round(pts[i * 2 + 1]);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
            if (maxX <= minX || maxY <= minY) return new Rect();
            return new Rect(minX, minY, maxX, maxY);
        } catch (Throwable ignored) {
            return new Rect();
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
        Log.i(TAG, "os14 volume glass capture "
                + (keyguard ? "LOCK_CONTENT" : "CAPTURE_SCREEN")
                + " hosts=" + hosts.size());

        for (View host : hosts) {
            if (host == null || !host.isAttachedToWindow()) continue;
            BehindDisplayCapture.tagVolumeHost(host);
            try { host.setLayerType(View.LAYER_TYPE_NONE, null); } catch (Throwable ignored) { }
            if (keyguard) {
                BehindDisplayCapture.stopStreaming(host);
                GlassInstaller.markLockContent(host);
                GlassInstaller.forceCapture(host);
            } else {
                GlassInstaller.clearWallpaperOnly(host);
                BehindDisplayCapture.bindApp(host, null);
                BehindDisplayCapture.requestRefresh(host, true);
            }
            host.invalidate();
        }
    }

    /**
     * Called from the hooked {@code VolumeSeekBar.onDraw}. Returns true when glass is
     * installed and the OEM background/progress draw was replaced by a manual progress
     * stroke (glass backdrop shows through); false keeps the OEM draw untouched.
     */
    public static boolean handleSeekBarOnDraw(View seekBar, Canvas canvas) {
        if (seekBar == null || canvas == null) return false;
        if (!INSTALLED.contains(seekBar)) return false;
        GlassDrawable glass = GlassInstaller.get(seekBar);
        if (glass == null) return false;
        try {
            Rect pill = volumePillRect(seekBar);
            if (pill != null && !pill.isEmpty()) {
                glass.setBounds(new Rect(pill));
                float r = Math.max(1f, pill.height() / 2f);
                glass.setCornerRadii(r, r, r, r);
            }
            drawProgress(seekBar, canvas);
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void drawProgress(View seekBar, Canvas canvas) {
        try {
            int progress = ((SeekBar) seekBar).getProgress();
            int max = ((SeekBar) seekBar).getMax();
            if (max <= 0) return;
            Rect pill = volumePillRect(seekBar);
            if (pill == null || pill.isEmpty()) return;
            float left = pill.left;
            float width = pill.width();
            float px = Math.max(0f, Math.min(width, (float) progress / max * width));
            int color = progressColor(seekBar);
            PROGRESS_PAINT.setColor(color);
            PROGRESS_PAINT.setStyle(Paint.Style.FILL);
            float w = Math.max(1f, pill.height() / 2f);
            canvas.drawRoundRect(left, pill.top, left + px, pill.bottom, w, w, PROGRESS_PAINT);
        } catch (Throwable ignored) { }
    }

    private static int progressColor(View seekBar) {
        try {
            String pkg = seekBar.getContext().getPackageName();
            int id = seekBar.getResources().getIdentifier(
                    "oplus_volume_seekbar_progress", "color", pkg);
            if (id != 0) return seekBar.getContext().getColor(id);
        } catch (Throwable ignored) { }
        return Color.argb(0xd9, 0, 0, 0);
    }

    /**
     * Cos14 seekbar pill: a rounded strip centered vertically in the view, shifted by the
     * OEM left padding on the vertical layout ({@code mLeftPadding}). Thickness =
     * mOffsetExpandWidth (default 3dp, pressed 6dp).
     */
    private static Rect volumePillRect(View seekBar) {
        try {
            int width = seekBar.getWidth();
            int height = seekBar.getHeight();
            if (width <= 0 || height <= 0) return null;
            float w = pillThickness(seekBar);
            float half = height / 2f;
            float leftPad = 0f;
            Object pad = field(seekBar, "mLeftPadding");
            if (pad instanceof Number && ((Number) pad).floatValue() > 0f) {
                leftPad = ((Number) pad).floatValue();
            }
            return new Rect(Math.round(leftPad), Math.round(half - w),
                    width, Math.round(half + w));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float pillThickness(View seekBar) {
        float w = 3f;
        Object off = field(seekBar, "mOffsetExpandWidth");
        if (off instanceof Number && ((Number) off).floatValue() > 0f) {
            w = ((Number) off).floatValue();
        }
        float density = 1f;
        try { density = seekBar.getResources().getDisplayMetrics().density; } catch (Throwable ignored) { }
        return w * density;
    }

    private static boolean isSeekBar(View view) {
        if (view == null) return false;
        String name = view.getClass().getName();
        return name != null && name.contains("VolumeSeekBar");
    }

    private static void collectSeekBars(View view, List<View> out) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        if (isSeekBar(view)
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

    static boolean isKeyguardShowing(View host) {
        if (host == null) return false;
        try {
            KeyguardManager km = (KeyguardManager)
                    host.getContext().getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) return true;
        } catch (Throwable ignored) { }
        return false;
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
}

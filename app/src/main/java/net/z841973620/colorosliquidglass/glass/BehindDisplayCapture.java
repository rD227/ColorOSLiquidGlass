package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.View;

import net.z841973620.colorosliquidglass.ipc.DesktopBackdropClient;

import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live full-resolution backdrop for SystemUI float option menus and the volume panel.
 * <p>
 * DESKTOP: Launcher ashmem region stream (home / widget page / Overview).
 * APP: {@code captureDisplay} (app content including floats).
 * Lock-screen volume is not live here — wallpaper + in-process clock/notifications.
 */
public final class BehindDisplayCapture {
    private static final String TAG = "ColorOSLiquidGlass";
    private static final String TAG_MENU = "colg_sysui_menu";
    private static final String TAG_VOLUME = "colg_sysui_volume";
    private static final Paint BITMAP_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final float CAPTION_EDGE_DP = 12f;

    enum Mode { DESKTOP, APP }

    private static final class HostState {
        Mode mode = Mode.DESKTOP;
        final Rect floatBounds = new Rect();
        /** Fixed max menu region sent to Launcher on OPEN (screen coords). */
        final Rect menuRegion = new Rect();
        volatile Bitmap cache;
        Bitmap cropScratch;
        int generation;
        int publishedGeneration = -1;
        final AtomicBoolean capturing = new AtomicBoolean(false);
        volatile boolean streaming;
        boolean sessionOpen;
        int lastScreenX = Integer.MIN_VALUE;
        int lastScreenY = Integer.MIN_VALUE;
        int lastWidth = -1;
        int lastHeight = -1;
        float lastBlurPx = Float.NaN;
        boolean forceOnce;
        boolean stickyDesktop;
        boolean noCaptureFallback;
        boolean detachHooked;
        /**
         * Keep {@link #menuRegion} as a shared union (volume sliders + settings).
         * Do not shrink it to this host's rect on each refresh.
         */
        boolean pinMenuRegion;
        /** Epoch captured when this host opened LIVE — stale after forceClose. */
        int openEpoch = -1;

        final CaptureCadence cadence = new CaptureCadence();
        volatile int captureEpoch;
        WeakReference<GlassDrawable> headsUpGlass;
        boolean notificationShade;
        int shadeFps;
        float shadeBlurPx;
    }

    private static final class CaptureRequest {
        final View host;
        final HostState state;
        final Mode mode;
        final int width;
        final int height;
        final int screenX;
        final int screenY;
        final Rect floatBounds;
        final float density;
        final SurfaceControl exclude;
        final float blurRadiusPx;
        final int epoch;

        CaptureRequest(View host, HostState state, Mode mode, int width, int height,
                int screenX, int screenY, Rect floatBounds, float density, SurfaceControl exclude,
                float blurRadiusPx) {
            this.host = host;
            this.state = state;
            this.mode = mode;
            this.width = width;
            this.height = height;
            this.screenX = screenX;
            this.screenY = screenY;
            this.floatBounds = floatBounds;
            this.density = density;
            this.exclude = exclude;
            this.blurRadiusPx = blurRadiusPx;
            this.epoch = state.captureEpoch;
        }
    }

    private static final Map<View, HostState> HOSTS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "colg-display-capture");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BehindDisplayCapture() {}

    public static boolean isSysUiMenuGlass(View target) {
        return hasTag(target, TAG_MENU);
    }

    public static boolean isVolumeGlass(View target) {
        return hasTag(target, TAG_VOLUME);
    }

    /** Float menus and volume-panel hosts share the live BehindDisplayCapture path. */
    public static boolean isSysUiLiveGlass(View target) {
        // Lock-screen volume samples wallpaper + in-process clock/notifications.
        if (BackdropCapture.hasWallpaperOnlyMark(target)) return false;
        if (BackdropCapture.hasLockContentMark(target)) return false;
        return isSysUiMenuGlass(target) || isVolumeGlass(target) || isHeadsUpGlass(target);
    }

    static boolean isHeadsUpGlass(View host) {
        HostState state = HOSTS.get(host);
        return state != null && state.headsUpGlass != null && state.headsUpGlass.get() != null;
    }

    static void bindHeadsUp(View host, GlassDrawable glass) {
        stateOf(host).headsUpGlass = new WeakReference<>(glass);
        bindApp(host, null);
    }

    static void unbindHeadsUp(View host) {
        HostState state = HOSTS.get(host);
        if (state != null && state.headsUpGlass != null) {
            stopStreaming(host);
            state.headsUpGlass = null;
        }
    }

    static void requestShade(View root, float blurPx, int fps) {
        HostState state = stateOf(root);
        state.mode = Mode.APP;
        state.notificationShade = true;
        state.shadeFps = fps;
        state.shadeBlurPx = blurPx;
        requestRefresh(root);
    }

    private static boolean hasTag(View target, String expected) {
        Object tag = target == null ? null : target.getTag();
        return tag instanceof String && expected.equals(tag);
    }

    public static void tagHost(View host) {
        if (host == null) return;
        Object existing = host.getTag();
        if (existing instanceof String && ((String) existing).startsWith("colg_rapid_")) return;
        host.setTag(TAG_MENU);
    }

    public static void tagVolumeHost(View host) {
        if (host == null) return;
        Object existing = host.getTag();
        if (existing instanceof String && ((String) existing).startsWith("colg_rapid_")) return;
        if (existing instanceof String && TAG_MENU.equals(existing)) return;
        host.setTag(TAG_VOLUME);
    }

    public static void bindDesktop(View host, Rect floatBoundsOnScreen) {
        bindDesktop(host, floatBoundsOnScreen, null, false);
    }

    public static void bindDesktop(View host, Rect floatBoundsOnScreen, boolean upwardFloatMenu) {
        bindDesktop(host, floatBoundsOnScreen, null, upwardFloatMenu);
    }

    /**
     * @param maxMenuOnScreen fixed max menu rect for Launcher region stream (required for ashmem)
     * @param upwardFloatMenu lock DESKTOP (no captureDisplay fallback) while on launcher
     */
    public static void bindDesktop(View host, Rect floatBoundsOnScreen, Rect maxMenuOnScreen,
            boolean upwardFloatMenu) {
        if (host == null) return;
        HostState s = stateOf(host);
        s.mode = Mode.DESKTOP;
        s.pinMenuRegion = false;
        if (upwardFloatMenu) {
            s.stickyDesktop = true;
            s.noCaptureFallback = true;
        }
        if (floatBoundsOnScreen != null) s.floatBounds.set(floatBoundsOnScreen);
        else s.floatBounds.setEmpty();
        if (maxMenuOnScreen != null && !maxMenuOnScreen.isEmpty()) {
            s.menuRegion.set(maxMenuOnScreen);
        } else {
            s.menuRegion.setEmpty();
        }
        s.streaming = true;
        s.forceOnce = true;
        s.lastScreenX = Integer.MIN_VALUE;
        s.lastWidth = -1;
        DesktopBackdropClient.warmUp();
        ensureDetachStopsSession(host);
        openSessionIfNeeded(host, s);
    }

    /**
     * Volume panel: several glass hosts crop from one Launcher region. {@code regionOnScreen}
     * is the union of those hosts; per-host refresh must not overwrite it.
     */
    public static void bindPinnedDesktop(View host, Rect regionOnScreen) {
        bindDesktop(host, null, regionOnScreen, true);
        HostState s = stateOf(host);
        s.pinMenuRegion = true;
    }

    /** Volume indicator on launcher: LIVE ashmem, crop refreshed every frame to the pill. */
    public static void bindVolumeDesktop(View host) {
        if (host == null) return;
        Rect r = volumeGlassScreenRect(host);
        bindDesktop(host, null, r, true);
    }

    public static void bindApp(View host, Rect floatBoundsOnScreen) {
        if (host == null) return;
        HostState s = stateOf(host);
        // Sticky DESKTOP only while the LIVE session for this epoch is still open.
        // Volume hosts must be able to switch to captureDisplay when an app/float appears.
        if (!isVolumeGlass(host)
                && s.stickyDesktop && s.mode == Mode.DESKTOP && s.sessionOpen
                && s.openEpoch == DesktopBackdropClient.sessionEpoch()
                && DesktopBackdropClient.isDesktopHomeValid()) {
            if (floatBoundsOnScreen != null) s.floatBounds.set(floatBoundsOnScreen);
            s.streaming = true;
            s.forceOnce = true;
            s.lastScreenX = Integer.MIN_VALUE;
            s.lastWidth = -1;
            return;
        }
        closeSessionIfNeeded(s);
        s.mode = Mode.APP;
        s.stickyDesktop = false;
        s.noCaptureFallback = false;
        s.pinMenuRegion = false;
        s.menuRegion.setEmpty();
        if (floatBoundsOnScreen != null) s.floatBounds.set(floatBoundsOnScreen);
        else s.floatBounds.setEmpty();
        s.streaming = true;
        s.forceOnce = true;
        s.lastScreenX = Integer.MIN_VALUE;
        s.lastWidth = -1;
        ensureDetachStopsSession(host);
    }

    private static void openSessionIfNeeded(View host, HostState s) {
        if (s == null || s.sessionOpen) return;
        if (host != null && !host.isAttachedToWindow()) {
            Log.w(TAG, "DESKTOP open skipped — host detached");
            return;
        }
        if (s.menuRegion.isEmpty()) {
            Log.w(TAG, "DESKTOP open skipped — empty menuRegion");
            return;
        }
        int epoch = DesktopBackdropClient.sessionEpoch();
        DesktopBackdropClient.signalOpen(s.menuRegion);
        if (DesktopBackdropClient.sessionEpoch() != epoch) {
            Log.i(TAG, "DESKTOP open aborted — session epoch advanced during signalOpen");
            s.sessionOpen = false;
            s.openEpoch = -1;
            return;
        }
        s.sessionOpen = true;
        s.openEpoch = epoch;
    }

    private static void closeSessionIfNeeded(HostState s) {
        if (s == null || !s.sessionOpen) return;
        DesktopBackdropClient.signalClose();
        s.sessionOpen = false;
        s.openEpoch = -1;
    }

    private static void ensureDetachStopsSession(View host) {
        if (host == null) return;
        HostState s = stateOf(host);
        if (s.detachHooked) return;
        s.detachHooked = true;
        host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) { }

            @Override
            public void onViewDetachedFromWindow(View v) {
                stopStreaming(v);
            }
        });
    }

    private static boolean anyDesktopSessionOpen() {
        int epoch = DesktopBackdropClient.sessionEpoch();
        synchronized (HOSTS) {
            for (HostState s : HOSTS.values()) {
                if (s != null && s.sessionOpen && s.openEpoch == epoch) return true;
            }
        }
        return false;
    }

    private static HostState stateOf(View host) {
        synchronized (HOSTS) {
            HostState s = HOSTS.get(host);
            if (s == null) {
                s = new HostState();
                HOSTS.put(host, s);
            }
            return s;
        }
    }

    public static void requestRefresh(View host) {
        requestRefresh(host, false);
    }

    /**
     * @param force force a sample even if a prior frame exists (bind / install)
     */
    public static void requestRefresh(View host, boolean force) {
        if (host == null || !host.isAttachedToWindow()) return;
        if (host.getWidth() <= 0 || host.getHeight() <= 0) return;
        HostState s = stateOf(host);
        s.streaming = true;
        if (force) s.forceOnce = true;
        enqueueLatest(host, s);
    }

    private static void enqueueLatest(View host, HostState s) {
        if (!s.streaming || !host.isAttachedToWindow()) return;

        int width = host.getWidth();
        int height = host.getHeight();
        if (width <= 0 || height <= 0) return;
        int[] loc = new int[2];
        host.getLocationOnScreen(loc);
        float blurPx = 0f;
        GlassDrawable glass = GlassInstaller.get(host);
        if (glass == null && s.headsUpGlass != null) glass = s.headsUpGlass.get();
        if (glass != null) blurPx = glass.blurRadiusPx();

        boolean volume = isVolumeGlass(host);
        boolean paced = volume || isHeadsUpGlass(host) || s.notificationShade;
        long nowNanos = android.os.SystemClock.elapsedRealtimeNanos();
        int fps = glass == null ? 30 : glass.volumeBackdropFps();
        if (s.notificationShade) {
            fps = s.shadeFps;
            blurPx = s.shadeBlurPx;
        }
        boolean immediate = s.forceOnce || s.cache == null;
        if (paced && !s.cadence.isDue(nowNanos, immediate)) return;
        Rect sampleRect = volume
                ? volumeGlassScreenRect(host)
                : new Rect(loc[0], loc[1], loc[0] + width, loc[1] + height);
        if (sampleRect == null || sampleRect.isEmpty()) {
            sampleRect = new Rect(loc[0], loc[1], loc[0] + width, loc[1] + height);
        }
        int sampleX = sampleRect.left;
        int sampleY = sampleRect.top;
        int sampleW = sampleRect.width();
        int sampleH = sampleRect.height();

        boolean force = s.forceOnce;
        s.forceOnce = false;
        boolean geometryChanged = force
                || sampleX != s.lastScreenX || sampleY != s.lastScreenY
                || sampleW != s.lastWidth || sampleH != s.lastHeight
                || Float.compare(blurPx, s.lastBlurPx) != 0;
        s.lastScreenX = sampleX;
        s.lastScreenY = sampleY;
        s.lastWidth = sampleW;
        s.lastHeight = sampleH;
        s.lastBlurPx = blurPx;

        // DESKTOP: push current glass screen rect to Launcher, poll matching region frame.
        // PAGE_PREVIEW / TOGGLE_BAR clear home-valid — fall through to captureDisplay.
        if (s.mode == Mode.DESKTOP && DesktopBackdropClient.isDesktopHomeValid()) {
            if (!DesktopBackdropClient.isReady()) {
                DesktopBackdropClient.warmUp();
            }
            if (volume) {
                Rect union = unionVolumeDesktopSampleRects();
                if (union == null || union.isEmpty()) union = sampleRect;
                s.menuRegion.set(union);
                syncVolumeDesktopMenuRegion(union);
                if (!s.sessionOpen) openSessionIfNeeded(host, s);
                else DesktopBackdropClient.updateMenuRect(union);
            } else if (s.pinMenuRegion) {
                if (!s.sessionOpen) openSessionIfNeeded(host, s);
            } else if (!sampleRect.isEmpty()) {
                s.menuRegion.set(sampleRect);
                if (!s.sessionOpen) {
                    openSessionIfNeeded(host, s);
                } else {
                    DesktopBackdropClient.updateMenuRect(sampleRect);
                }
            } else {
                openSessionIfNeeded(host, s);
            }
            Bitmap region = DesktopBackdropClient.pollFrame();
            boolean newPlate = region != null;
            if (region == null) region = DesktopBackdropClient.peekFrame();
            // The stream publishes asynchronously. Never wait for its first frame on the UI thread.
            if (region != null && !region.isRecycled()) {
                boolean need = volume || newPlate || force
                        || Float.compare(blurPx, s.lastBlurPx) != 0
                        || s.cache == null || s.cache.isRecycled()
                        || s.cache.getWidth() != sampleW || s.cache.getHeight() != sampleH
                        || geometryChanged;
                if (need) {
                    if (paced && !s.capturing.get()) s.cadence.started(nowNanos, fps, immediate);
                    applyDesktopRegionFrame(host, s, region,
                            sampleX, sampleY, sampleW, sampleH, blurPx);
                }
                return;
            }
            if (s.noCaptureFallback || DesktopBackdropClient.isReady()) return;
        } else if (s.mode == Mode.DESKTOP) {
            // Launcher ashmem no longer valid (e.g. widget tray) — unlock sticky, captureDisplay.
            s.stickyDesktop = false;
            s.noCaptureFallback = false;
            closeSessionIfNeeded(s);
            s.mode = Mode.APP;
        }

        if (!s.capturing.compareAndSet(false, true)) return;
        if (paced) s.cadence.started(nowNanos, fps, immediate);

        float density = host.getResources().getDisplayMetrics().density;
        Object exclude = surfaceControlOf(host);
        // APP mode, or DESKTOP fallback when Ashmem arena is unavailable.
        Mode captureMode = s.mode;
        CaptureRequest req = new CaptureRequest(
                host, s, captureMode, width, height, loc[0], loc[1], new Rect(s.floatBounds), density,
                exclude instanceof SurfaceControl ? (SurfaceControl) exclude : null, blurPx);

        WORKER.execute(() -> {
            if (!s.streaming || s.captureEpoch != req.epoch) {
                s.capturing.set(false);
                return;
            }
            Bitmap shot = null;
            try {
                shot = captureDisplayCrop(req);
            } catch (Throwable t) {
                Log.w(TAG, "live capture failed", t);
            }
            final Bitmap frame = shot;
            MAIN.post(() -> {
                if (!s.streaming || s.captureEpoch != req.epoch || !host.isAttachedToWindow()) {
                    recycleQuiet(frame);
                    s.capturing.set(false);
                    return;
                }
                if (frame != null && !frame.isRecycled()) {
                    synchronized (s) {
                        Bitmap old = s.cache;
                        s.cache = frame;
                        s.generation++;
                        if (old != null && old != frame && old.getConfig() != Bitmap.Config.HARDWARE) {
                            recycleIfDifferent(old, frame);
                        }
                    }
                } else {
                    recycleQuiet(frame);
                }
                s.capturing.set(false);
                if (frame != null && host.isAttachedToWindow()) {
                    host.invalidate();
                }
            });
        });
    }

    /** Latest live frame for {@link GlassDrawable} (may be HARDWARE). */
    public static Bitmap currentFrame(View host) {
        HostState s = HOSTS.get(host);
        Bitmap cache = s == null ? null : s.cache;
        return cache != null && !cache.isRecycled() ? cache : null;
    }

    static boolean hasContent(View host) {
        return currentFrame(host) != null;
    }

    static boolean hasNewFrame(View host) {
        HostState s = HOSTS.get(host);
        return s != null && s.cache != null && !s.cache.isRecycled()
                && s.generation != s.publishedGeneration;
    }

    static void markPublished(View host) {
        HostState s = HOSTS.get(host);
        if (s != null) s.publishedGeneration = s.generation;
    }

    public static void stopStreaming(View host) {
        HostState s = HOSTS.get(host);
        if (s == null) {
            if (!anyDesktopSessionOpen()) {
                DesktopBackdropClient.forceCloseSession();
            }
            return;
        }
        s.streaming = false;
        s.captureEpoch++;
        s.cadence.reset();
        s.forceOnce = false;
        s.stickyDesktop = false;
        s.noCaptureFallback = false;
        s.pinMenuRegion = false;
        closeSessionIfNeeded(s);
        if (!anyDesktopSessionOpen()) {
            DesktopBackdropClient.forceCloseSession();
        }
        synchronized (s) {
            Bitmap cache = s.cache;
            Bitmap crop = s.cropScratch;
            s.cache = null;
            s.cropScratch = null;
            if (cache != null && cache != crop && !DesktopBackdropClient.isSharedFrame(cache)) {
                recycleQuiet(cache);
            }
            if (crop != null && !crop.isRecycled()) {
                try { crop.recycle(); } catch (Throwable ignored) { }
            }
            s.generation++;
            s.publishedGeneration = s.generation;
            s.lastScreenX = Integer.MIN_VALUE;
            s.lastScreenY = Integer.MIN_VALUE;
            s.lastWidth = -1;
            s.lastHeight = -1;
            s.lastBlurPx = Float.NaN;
            s.menuRegion.setEmpty();
            s.openEpoch = -1;
        }
    }

    /** Stop every float-menu ashmem session (dismiss / float closed). Leaves volume hosts. */
    public static void stopAllSessions() {
        stopHostsWithTag(TAG_MENU);
    }

    /** Stop volume-panel ashmem / capture sessions (dialog dismissed). */
    public static void stopVolumeSessions() {
        stopHostsWithTag(TAG_VOLUME);
    }

    private static void stopHostsWithTag(String tag) {
        java.util.ArrayList<View> hosts;
        synchronized (HOSTS) {
            hosts = new java.util.ArrayList<>(HOSTS.keySet());
        }
        for (View host : hosts) {
            if (host != null && hasTag(host, tag)) stopStreaming(host);
        }
        if (!anyDesktopSessionOpen()) {
            DesktopBackdropClient.forceCloseSession();
        }
    }

    static boolean anyMenuDesktopSessionOpen() {
        int epoch = DesktopBackdropClient.sessionEpoch();
        synchronized (HOSTS) {
            for (Map.Entry<View, HostState> e : HOSTS.entrySet()) {
                View host = e.getKey();
                HostState s = e.getValue();
                if (s != null && s.sessionOpen && s.openEpoch == epoch
                        && isSysUiMenuGlass(host)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Region frame is menuRegion-sized at 1:1 screen pixels. Crop the host's current
     * on-screen rect out of that bitmap — never scale (scaling caused vertical stretch).
     */
    private static void applyDesktopRegionFrame(View host, HostState s, Bitmap region,
            int screenX, int screenY, int width, int height, float blurPx) {
        // Keep the last completed blur while the worker is busy. Do not replace it with
        // an unblurred scratch crop or mutate that crop on every redraw.
        boolean blur = blurPx >= 0.5f;
        if (blur && !s.capturing.compareAndSet(false, true)) return;
        Bitmap mapped = mapRegionToHost(s, region, screenX, screenY, width, height);
        if (mapped == null || mapped.isRecycled()) {
            if (blur) s.capturing.set(false);
            return;
        }

        if (blurPx < 0.5f) {
            synchronized (s) {
                if (s.cache != null && s.cache != mapped
                        && !DesktopBackdropClient.isSharedFrame(s.cache)
                        && s.cache != s.cropScratch) {
                    recycleQuiet(s.cache);
                }
                s.cache = mapped;
                s.generation++;
            }
            host.invalidate();
            return;
        }

        final Bitmap toBlur;
        try {
            toBlur = mapped.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable t) {
            s.capturing.set(false);
            synchronized (s) {
                s.cache = mapped;
                s.generation++;
            }
            host.invalidate();
            return;
        }
        final float radius = blurPx;
        final int epoch = s.captureEpoch;
        WORKER.execute(() -> {
            if (!s.streaming || s.captureEpoch != epoch) {
                recycleQuiet(toBlur);
                s.capturing.set(false);
                return;
            }
            Bitmap blurred = null;
            try {
                blurred = HardwareBlur.blur(toBlur, radius);
            } catch (Throwable t) {
                Log.w(TAG, "desktop local blur failed", t);
            }
            final Bitmap frame = (blurred != null && !blurred.isRecycled()) ? blurred : toBlur;
            final Bitmap discard = (frame != toBlur) ? toBlur : null;
            MAIN.post(() -> {
                try {
                    if (!s.streaming || s.captureEpoch != epoch || !host.isAttachedToWindow()) {
                        recycleQuiet(frame);
                        recycleQuiet(discard);
                        return;
                    }
                    synchronized (s) {
                        Bitmap old = s.cache;
                        s.cache = frame;
                        s.generation++;
                        if (old != null && old != frame && old != s.cropScratch
                                && !DesktopBackdropClient.isSharedFrame(old)) {
                            recycleQuiet(old);
                        }
                    }
                    recycleQuiet(discard);
                    host.invalidate();
                } finally {
                    s.capturing.set(false);
                }
            });
        });
    }

    private static Bitmap mapRegionToHost(HostState s, Bitmap region,
            int screenX, int screenY, int width, int height) {
        if (region == null || region.isRecycled() || width <= 0 || height <= 0) return null;
        int rw = region.getWidth();
        int rh = region.getHeight();
        Bitmap out = s.cropScratch;
        if (out == null || out.isRecycled()
                || out.getWidth() != width || out.getHeight() != height) {
            if (out != null && !out.isRecycled()) {
                try { out.recycle(); } catch (Throwable ignored) { }
            }
            out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            s.cropScratch = out;
        }
        Canvas canvas = new Canvas(out);
        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);

        int srcL = 0;
        int srcT = 0;
        if (!s.menuRegion.isEmpty()) {
            srcL = screenX - s.menuRegion.left;
            srcT = screenY - s.menuRegion.top;
        }

        // Exact host-sized plate at origin (float menu): 1:1 blit.
        if (rw == width && rh == height && srcL == 0 && srcT == 0) {
            canvas.drawBitmap(region, 0f, 0f, BITMAP_PAINT);
            return out;
        }

        int srcR = srcL + width;
        int srcB = srcT + height;
        srcL = Math.max(0, srcL);
        srcT = Math.max(0, srcT);
        srcR = Math.min(rw, srcR);
        srcB = Math.min(rh, srcB);
        if (srcR <= srcL || srcB <= srcT) {
            int srcW = Math.min(rw, width);
            int srcH = Math.min(rh, height);
            canvas.drawBitmap(region,
                    new Rect(0, 0, srcW, srcH),
                    new RectF(0f, 0f, srcW, srcH),
                    BITMAP_PAINT);
            return out;
        }
        int dstW = srcR - srcL;
        int dstH = srcB - srcT;
        canvas.drawBitmap(region,
                new Rect(srcL, srcT, srcR, srcB),
                new RectF(0f, 0f, dstW, dstH),
                BITMAP_PAINT);
        return out;
    }

    /** @deprecated GlassDrawable reads {@link #currentFrame} directly for SysUI menus. */
    static boolean paintIntoTargetLocal(View glassHost, Canvas canvas) {
        Bitmap cache = currentFrame(glassHost);
        if (cache == null || canvas == null) return false;
        int w = glassHost.getWidth();
        int h = glassHost.getHeight();
        if (w <= 0 || h <= 0) return false;
        canvas.drawBitmap(cache, null, new RectF(0f, 0f, w, h), BITMAP_PAINT);
        return true;
    }

    /** On-screen rect of the volume glass plate (seekbar pill, or the full settings button). */
    private static Rect volumeGlassScreenRect(View host) {
        if (host == null) return new Rect();
        int[] loc = new int[2];
        host.getLocationOnScreen(loc);
        Rect pill = readOutLineRect(host);
        if (pill != null && pill.width() >= 2 && pill.height() >= 2) {
            return new Rect(loc[0] + pill.left, loc[1] + pill.top,
                    loc[0] + pill.right, loc[1] + pill.bottom);
        }
        // ColorOS 13/14 VolumeSeekBar: no mOutLineRect, and the vertical layout rotates the
        // seekbar -90° so the pill must be mapped through the view matrix to screen space.
        if (isOs14VolumeSeekBar(host)) {
            Rect screenPill = Os14VolumePanelGlass.pillScreenRect(host);
            if (screenPill != null && !screenPill.isEmpty()) return screenPill;
        }
        int w = host.getWidth();
        int h = host.getHeight();
        if (w <= 0 || h <= 0) return new Rect();
        return new Rect(loc[0], loc[1], loc[0] + w, loc[1] + h);
    }

    static boolean isOs14VolumeSeekBar(View host) {
        if (host == null) return false;
        String name = host.getClass().getName();
        return name != null && name.contains("VolumeSeekBar") && !name.contains("Oplus");
    }

    private static Rect readOutLineRect(View host) {
        if (host == null) return null;
        try {
            for (Class<?> c = host.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("mOutLineRect");
                    f.setAccessible(true);
                    Object v = f.get(host);
                    if (v instanceof Rect && !((Rect) v).isEmpty()) return new Rect((Rect) v);
                } catch (NoSuchFieldException ignored) { }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Rect unionVolumeDesktopSampleRects() {
        Rect union = new Rect();
        int epoch = DesktopBackdropClient.sessionEpoch();
        synchronized (HOSTS) {
            for (Map.Entry<View, HostState> e : HOSTS.entrySet()) {
                View host = e.getKey();
                HostState s = e.getValue();
                if (host == null || s == null || !isVolumeGlass(host)) continue;
                if (!s.streaming || s.mode != Mode.DESKTOP) continue;
                if (s.sessionOpen && s.openEpoch != epoch) continue;
                if (!host.isAttachedToWindow() || !host.isShown()) continue;
                Rect r = volumeGlassScreenRect(host);
                if (r.isEmpty()) continue;
                if (union.isEmpty()) union.set(r);
                else union.union(r);
            }
        }
        return union;
    }

    private static void syncVolumeDesktopMenuRegion(Rect union) {
        if (union == null || union.isEmpty()) return;
        synchronized (HOSTS) {
            for (Map.Entry<View, HostState> e : HOSTS.entrySet()) {
                View host = e.getKey();
                HostState s = e.getValue();
                if (host == null || s == null || !isVolumeGlass(host)) continue;
                if (s.mode != Mode.DESKTOP || !s.streaming) continue;
                s.menuRegion.set(union);
            }
        }
    }

    private static Bitmap captureDisplayCrop(CaptureRequest req) {
        int width = req.width;
        int height = req.height;
        if (width <= 0 || height <= 0) return null;

        Rect crop = new Rect(req.screenX, req.screenY, req.screenX + width, req.screenY + height);
        if (crop.isEmpty()) return null;

        int edge = Math.round(CAPTION_EDGE_DP * req.density);
        Rect captureCrop = new Rect(crop);
        if (req.mode == Mode.DESKTOP && !req.floatBounds.isEmpty()) {
            captureCrop.bottom = Math.min(captureCrop.bottom + edge, req.floatBounds.top + edge);
        }
        Bitmap raw = captureDisplay(req.host, captureCrop, req.exclude);
        if (raw == null || raw.isRecycled()) return null;
        Bitmap framed = prepareFullResFrame(raw, width, height, req);
        if (framed == null || framed.isRecycled()) return null;

        if (req.blurRadiusPx >= 0.5f) {
            Bitmap blurred = HardwareBlur.blur(framed, req.blurRadiusPx);
            if (blurred != null && blurred != framed) {
                recycleQuiet(framed);
                framed = blurred;
            }
        }
        return framed;
    }

    /**
     * Keep full resolution. Prefer retaining HARDWARE when no CPU composite is required
     * (APP mode / exact-size crop). DESKTOP caption sliver may require a software composite.
     */
    private static Bitmap prepareFullResFrame(Bitmap raw, int menuW, int menuH, CaptureRequest req) {
        try {
            boolean hardware = raw.getConfig() == Bitmap.Config.HARDWARE;
            int rw = raw.getWidth();
            int rh = raw.getHeight();

            // Fast path: exact menu size — keep HARDWARE (no CPU readback).
            if (rw == menuW && rh == menuH
                    && !(req.mode == Mode.DESKTOP && rh > menuH)) {
                return durableBitmap(raw);
            }

            // DESKTOP caption sliver: composite into menu-sized buffer (needs software draw).
            if (req.mode == Mode.DESKTOP && rh > menuH && rw >= menuW) {
                Bitmap soft = hardware ? raw.copy(Bitmap.Config.ARGB_8888, true) : raw;
                if (soft == null || soft.isRecycled()) return null;
                if (soft != raw) recycleQuiet(raw);
                int extraRows = soft.getHeight() - menuH;
                Bitmap out = Bitmap.createBitmap(menuW, menuH, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(out);
                c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
                int srcW = Math.min(soft.getWidth(), menuW);
                c.drawBitmap(soft, new Rect(0, 0, srcW, menuH),
                        new RectF(0f, 0f, menuW, menuH), BITMAP_PAINT);
                if (extraRows > 0 && extraRows < menuH) {
                    c.drawBitmap(soft, new Rect(0, menuH, srcW, soft.getHeight()),
                            new RectF(0f, menuH - extraRows, menuW, menuH), BITMAP_PAINT);
                }
                if (out != soft) recycleQuiet(soft);
                return out;
            }

            if (rw == menuW && rh == menuH) return durableBitmap(raw);

            // Size mismatch: scale at full intended menu resolution (still 1:1 menu pixels).
            Bitmap soft = hardware ? raw.copy(Bitmap.Config.ARGB_8888, true) : raw;
            if (soft == null) return null;
            if (soft != raw) recycleQuiet(raw);
            if (soft.getWidth() == menuW && soft.getHeight() == menuH) return soft;
            Bitmap scaled = Bitmap.createScaledBitmap(soft, menuW, menuH, true);
            if (scaled != soft) recycleQuiet(soft);
            return scaled;
        } catch (Throwable t) {
            Log.w(TAG, "full-res frame prepare failed", t);
            return null;
        }
    }

    /** Make a bitmap safe to retain after the capture buffer is released. */
    private static Bitmap durableBitmap(Bitmap raw) {
        if (raw == null || raw.isRecycled()) return null;
        try {
            if (raw.getConfig() == Bitmap.Config.HARDWARE) {
                Bitmap copy = raw.copy(Bitmap.Config.HARDWARE, false);
                if (copy == null || copy.isRecycled()) {
                    copy = raw.copy(Bitmap.Config.ARGB_8888, false);
                }
                if (copy != null && copy != raw) recycleQuiet(raw);
                return copy != null ? copy : raw;
            }
            return raw;
        } catch (Throwable t) {
            try {
                return raw.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Throwable ignored) {
                return raw;
            }
        }
    }

    private static void recycleIfDifferent(Bitmap old, Bitmap next) {
        if (old == null || old == next || old.isRecycled()) return;
        if (DesktopBackdropClient.isSharedFrame(old)) return;
        old.recycle();
    }

    private static void recycleQuiet(Bitmap b) {
        if (b == null || b.isRecycled()) return;
        if (DesktopBackdropClient.isSharedFrame(b)) return;
        try { b.recycle(); } catch (Throwable ignored) { }
    }

    private static Object surfaceControlOf(View view) {
        try {
            Object vri = invokeNoArgs(view, "getViewRootImpl");
            if (vri == null) return null;
            Object sc = invokeNoArgs(vri, "getSurfaceControl");
            return sc instanceof SurfaceControl ? sc : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap captureDisplay(View host, Rect crop, Object excludeLayer) {
        try {
            Class<?> screenCapture = Class.forName("android.window.ScreenCapture");
            Class<?> builderClass = Class.forName("android.window.ScreenCapture$CaptureArgs$Builder");
            Object builder = builderClass.getDeclaredConstructor().newInstance();
            builderClass.getMethod("setSourceCrop", Rect.class).invoke(builder, crop);
            if (excludeLayer != null) {
                try {
                    builderClass.getMethod("setExcludeLayers", SurfaceControl[].class)
                            .invoke(builder, (Object) new SurfaceControl[] { (SurfaceControl) excludeLayer });
                } catch (Throwable ignored) { }
            }
            Object captureArgs = builderClass.getMethod("build").invoke(builder);

            Object listener = screenCapture.getMethod("createSyncCaptureListener").invoke(null);
            int displayId = Display.DEFAULT_DISPLAY;
            try {
                Display display = host.getDisplay();
                if (display != null) displayId = display.getDisplayId();
            } catch (Throwable ignored) { }

            Object wm = windowManager();
            if (wm == null) return null;
            Method target = null;
            for (Method m : wm.getClass().getMethods()) {
                if (!m.getName().equals("captureDisplay") || m.getParameterCount() != 3) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params[0] == int.class
                        && params[1].getName().endsWith("CaptureArgs")
                        && params[2].getName().endsWith("ScreenCaptureListener")) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                for (Method m : wm.getClass().getMethods()) {
                    if (!m.getName().equals("captureDisplay") || m.getParameterCount() != 3) continue;
                    target = m;
                    break;
                }
            }
            if (target == null) return null;
            target.invoke(wm, displayId, captureArgs, listener);

            Object buffer = invokeNoArgs(listener, "getBuffer");
            if (buffer == null) return null;
            Object bitmap = invokeNoArgs(buffer, "asBitmap");
            return bitmap instanceof Bitmap ? (Bitmap) bitmap : null;
        } catch (Throwable t) {
            Log.w(TAG, "captureDisplay reflection failed", t);
            return null;
        }
    }

    private static Object windowManager() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "window");
            if (binder == null) return null;
            Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
            return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (Throwable t) {
            Log.w(TAG, "IWindowManager unavailable", t);
            return null;
        }
    }

    private static Object invokeNoArgs(Object target, String name) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable ignored) { }
        }
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}

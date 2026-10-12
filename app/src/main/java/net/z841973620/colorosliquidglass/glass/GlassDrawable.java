package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;
import android.view.View;

import net.z841973620.colorosliquidglass.GlassConfig;

/** Liquid-glass drawable backed by a shared recording of the pixels behind its owning UI item. */
public final class GlassDrawable extends Drawable {
    private static final String TAG = "ColorOSLiquidGlass";
    private static final String SHADER = """
            uniform shader image;
            uniform float2 size;
            uniform float3 sampleMatrixX;
            uniform float3 sampleMatrixY;
            uniform float3 sampleMatrixW;
            uniform float4 cornerRadii;
            uniform float blurRadius;
            uniform float refractionHeight;
            uniform float refractionAmount;
            uniform float depthEffect;
            uniform float chromaticAberration;
            uniform float glassIntensity;
            uniform float surfaceOpacity;
            uniform float reflectionIntensity;

            float radiusAt(float2 coord, float4 radii) {
                if (coord.x >= 0.0) {
                    if (coord.y <= 0.0) return radii.y;
                    return radii.z;
                }
                if (coord.y <= 0.0) return radii.x;
                return radii.w;
            }

            float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
                float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
                float outside = length(max(cornerCoord, 0.0)) - radius;
                float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
                return outside + inside;
            }

            float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
                float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
                if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
                    return sign(coord) * normalize(max(cornerCoord, 0.0001));
                }
                float gradX = step(cornerCoord.y, cornerCoord.x);
                return sign(coord) * float2(gradX, 1.0 - gradX);
            }

            float circleMap(float x) {
                x = clamp(x, 0.0, 1.0);
                return 1.0 - sqrt(max(0.0, 1.0 - x * x));
            }

            half4 sampleBackdrop(float2 coord) {
                float3 point = float3(coord, 1.0);
                float w = dot(sampleMatrixW, point);
                return image.eval(float2(dot(sampleMatrixX, point), dot(sampleMatrixY, point)) / w);
            }

            half4 vibrant(float2 coord) {
                // Blur is applied to the capture bitmap before it reaches this shader
                // (same role as Compose RenderEffect.blur in the console preview).
                half4 c = sampleBackdrop(coord);
                half luminance = dot(c.rgb, half3(0.2126, 0.7152, 0.0722));
                c.rgb = mix(half3(luminance), c.rgb, 1.18);
                c.rgb = (c.rgb - 0.5) * 1.04 + 0.5;
                c.a = 1.0;
                return c;
            }

            half4 dispersed(float2 coord, float2 delta) {
                half4 center = vibrant(coord);
                half4 positive = vibrant(coord + delta);
                half4 negative = vibrant(coord - delta);
                return half4(positive.r, center.g, negative.b, center.a);
            }

            half4 main(float2 coord) {
                float2 halfSize = size * 0.5;
                float2 centered = coord - halfSize;
                float radius = radiusAt(centered, cornerRadii);
                float sd = sdRoundedRect(centered, halfSize, radius);
                if (sd > 0.0) return half4(0.0);

                float rimWidth = max(1.5, min(max(refractionHeight, 6.0), 18.0));
                // SDF is negative inside: the rim fades from 1 at the edge to 0 inward.
                float rim = smoothstep(-rimWidth, 0.0, sd);
                bool refracts = refractionHeight > 0.0 && refractionAmount > 0.0
                    && -sd < refractionHeight;
                half4 color = vibrant(coord);
                // The SDF normal is discontinuous on the medial axes of a flat face.
                // It describes the boundary, so only use it for rim light or edge refraction.
                if (rim > 0.0 || refracts) {
                    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
                    float2 normal = normalize(gradSdRoundedRect(centered, halfSize, gradRadius)
                        + depthEffect * glassIntensity * normalize(centered + 0.0001));
                    if (refracts) {
                        float d = circleMap(1.0 - (-sd / refractionHeight)) * refractionAmount;
                        float2 refractedCoord = coord - d * normal;
                        if (chromaticAberration <= 0.0) {
                            color = vibrant(refractedCoord);
                        } else {
                            float intensity = ((centered.x * centered.y) / max(1.0, halfSize.x * halfSize.y));
                            color = dispersed(refractedCoord, d * normal * intensity * chromaticAberration);
                        }
                    }
                    float2 lightDirection = normalize(float2(-0.72, -0.70));
                    float reflection = pow(max(dot(-normal, lightDirection), 0.0), 3.0);
                    float shade = pow(max(dot(normal, lightDirection), 0.0), 2.0);
                    color.rgb *= half(1.0 - shade * rim * 0.10 * glassIntensity);
                    color.rgb += half3(rim * (0.025 + reflection * 0.14 * reflectionIntensity) * glassIntensity);
                }
                color.a *= half(surfaceOpacity);
                return color;
            }
            """;

    private final View owner;
    private GlassConfig config;
    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final float[] radii = new float[4];
    private final RuntimeShader runtimeShader;
    private final BackdropCapture backdrop;
    private Bitmap cachedSnapshot;
    private Bitmap cachedSnapshotSource;
    private Bitmap cachedBlurredSnapshot;
    private float cachedBlurRadiusPx = -1f;
    private int cachedSnapshotGeneration;
    private BitmapShader cachedImageShader;
    private final float[] sampleMatrix = new float[9];
    private PopupRenderEffect popupEffect;
    private boolean shaderFailureLogged;
    private int alpha = 255;

    public GlassDrawable(View owner, GlassConfig config) {
        this.owner = owner;
        this.config = config;
        this.backdrop = BackdropCapture.register(owner);
        RuntimeShader shader = null;
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                shader = new RuntimeShader(SHADER);
            } catch (Throwable error) {
                shaderFailureLogged = true;
                Log.e(TAG, "AGSL compilation failed for " + owner.getClass().getName(), error);
            }
        }
        runtimeShader = shader;
        fallbackPaint.setColor(Color.argb(54, 238, 245, 255));
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setColor(Color.argb(Math.round(255f * 0.38f), 255, 255, 255));
        if (Build.VERSION.SDK_INT >= 29) highlightPaint.setBlendMode(BlendMode.PLUS);
    }

    public void setCornerRadii(float[] cornerRadii) {
        if (cornerRadii == null || cornerRadii.length < 4) return;
        setCornerRadii(cornerRadii[0], cornerRadii[1], cornerRadii[2], cornerRadii[3]);
    }

    public void setCornerRadii(float topLeft, float topRight, float bottomRight, float bottomLeft) {
        // Installation commonly happens before Drawable bounds exist. Preserve requested radii and
        // only clamp a draw-time copy, otherwise every folder is permanently reduced to square.
        float tl = Math.max(0f, topLeft);
        float tr = Math.max(0f, topRight);
        float br = Math.max(0f, bottomRight);
        float bl = Math.max(0f, bottomLeft);
        if (Float.compare(radii[0], tl) == 0 && Float.compare(radii[1], tr) == 0
                && Float.compare(radii[2], br) == 0 && Float.compare(radii[3], bl) == 0) return;
        radii[0] = tl;
        radii[1] = tr;
        radii[2] = br;
        radii[3] = bl;
        rebuildShape(getBounds());
        invalidateSelf();
    }

    public float[] getCornerRadii() { return radii.clone(); }

    @Override protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        rebuildShape(bounds);
    }

    @Override public void draw(Canvas canvas) {
        if (LockscreenContentOverlay.isSampling()) return;
        // View.drawBackground may reset bounds to the full view after setBackground.
        // COUI submenu open grows RoundFrameLayout.f15656b — re-apply before sampling.
        applyCouiRevealBoundsIfPresent();
        // Lock-screen capsule: OEM blur is clipped to pathProvider clipRect (pill size).
        // CapsuleBackgroundView itself stays notification-row sized during list→capsule.
        applyCapsuleClipBoundsIfPresent();
        applyVolumeSeekBarBoundsIfPresent();
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        int save = canvas.save();
        canvas.clipPath(shape);
        boolean drewBackdrop = false;
        boolean sampleBackdrop = backdropEnabled();
        boolean sharedShade = sampleBackdrop && SharedNotificationBackdrop.contains(owner);
        boolean sysUiLive = sampleBackdrop && BehindDisplayCapture.isSysUiLiveGlass(owner);
        boolean sharedWallpaper = sampleBackdrop && !sysUiLive && backdrop != null && backdrop.usesSharedWallpaper();
        Bitmap snapshot = sharedShade ? SharedNotificationBackdrop.texture(owner)
                : !sampleBackdrop || sharedWallpaper ? null : sysUiLive
                ? BehindDisplayCapture.currentFrame(owner)
                : (backdrop == null ? null : backdrop.bitmap());
        float[] sampleScale = sysUiLive
                ? new float[] { 1f, 1f }
                : (backdrop == null ? new float[] { 1f, 1f } : backdrop.sampleScale());
        // ColorOS 13/14 volume seekbar: the vertical layout rotates the view -90° so the
        // pill (a horizontal strip locally) is a vertical strip on screen. The AGSL
        // sampleOrigin/sampleScale cannot express a 90° rotation, so draw the captured
        // screen pill directly — the rotated canvas maps it back to the correct orientation.
        boolean rotatedVolume = sysUiLive
                && BehindDisplayCapture.isVolumeGlass(owner)
                && Math.abs(owner.getRotation()) >= 0.5f
                && BehindDisplayCapture.isOs14VolumeSeekBar(owner);
        if (rotatedVolume && snapshot != null && !snapshot.isRecycled()) {
            try {
                Bitmap blurred = blurredBackdrop(snapshot, effectiveShaderBlurRadius());
                if (blurred != null && !blurred.isRecycled()) {
                    glassPaint.setShader(null);
                    glassPaint.setAlpha(alpha);
                    canvas.drawBitmap(blurred, null,
                            new RectF(bounds.left, bounds.top, bounds.right, bounds.bottom),
                            glassPaint);
                    drewBackdrop = true;
                }
            } catch (Throwable ignored) { }
        } else if (canvas.isHardwareAccelerated() && runtimeShader != null
                && (sharedWallpaper || (snapshot != null && !snapshot.isRecycled()))) {
            try {
                float blurPx = effectiveShaderBlurRadius();
                boolean gpuPopup = "colg_desktop_popup".equals(owner.getTag()) && snapshot != null;
                Bitmap shaderBitmap = sharedWallpaper
                        ? SharedWallpaperBackdrop.texture(owner, blurPx)
                        : gpuPopup ? snapshot : blurredBackdrop(snapshot, blurPx);
                if (shaderBitmap != cachedSnapshot || cachedImageShader == null) {
                    cachedSnapshot = shaderBitmap;
                    cachedImageShader = new BitmapShader(
                            shaderBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    // RuntimeShader inputs default to nearest sampling, regardless of Paint flags.
                    cachedImageShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                }
                // Identity matrix: mapping, including perspective, is explicit in AGSL.
                cachedImageShader.setLocalMatrix(null);
                runtimeShader.setInputShader("image", cachedImageShader);
                float min = Math.max(1f, Math.min(bounds.width(), bounds.height()));
                float[] corner = clampedRadii(bounds);
                runtimeShader.setFloatUniform("size", bounds.width(), bounds.height());
                if (sharedShade) {
                    SharedWallpaperBackdrop.rootCoordinates(owner, bounds.left, bounds.top, sampleMatrix);
                } else if (sharedWallpaper) {
                    SharedWallpaperBackdrop.coordinates(owner, bounds.left, bounds.top, sampleMatrix);
                } else {
                    captureCoordinates(snapshot, shaderBitmap, bounds, sampleScale);
                }
                runtimeShader.setFloatUniform("sampleMatrixX", sampleMatrix[0], sampleMatrix[1], sampleMatrix[2]);
                runtimeShader.setFloatUniform("sampleMatrixY", sampleMatrix[3], sampleMatrix[4], sampleMatrix[5]);
                runtimeShader.setFloatUniform("sampleMatrixW", sampleMatrix[6], sampleMatrix[7], sampleMatrix[8]);
                // float4 must use the 4-float overload; float[] is for float array uniforms.
                runtimeShader.setFloatUniform("cornerRadii",
                        corner[0], corner[1], corner[2], corner[3]);
                // Blur already baked into shaderBitmap (console uses RenderEffect the same way).
                runtimeShader.setFloatUniform("blurRadius", 0f);
                float intensity = clamp01(config.glassIntensity);
                runtimeShader.setFloatUniform("refractionHeight",
                        Math.max(0f, config.refractionHeight) * intensity * min * 0.5f);
                runtimeShader.setFloatUniform("refractionAmount",
                        Math.max(0f, config.refractionAmount) * intensity * min);
                runtimeShader.setFloatUniform("depthEffect", intensity);
                runtimeShader.setFloatUniform("chromaticAberration",
                        Math.max(0f, Math.min(1f, config.chromaticAberration)));
                runtimeShader.setFloatUniform("glassIntensity", intensity);
                runtimeShader.setFloatUniform("surfaceOpacity", 1f - clamp01(config.transparency) * 0.7f);
                runtimeShader.setFloatUniform("reflectionIntensity", clamp01(config.reflectionIntensity));
                glassPaint.setShader(runtimeShader);
                glassPaint.setAlpha(alpha);
                canvas.translate(bounds.left, bounds.top);
                if (gpuPopup) {
                    if (popupEffect == null) popupEffect = new PopupRenderEffect();
                    popupEffect.draw(canvas, snapshot, runtimeShader, blurPx,
                            bounds.width(), bounds.height(), alpha, glassPaint.getColorFilter());
                } else {
                    canvas.drawRect(0f, 0f, bounds.width(), bounds.height(), glassPaint);
                }
                canvas.translate(-bounds.left, -bounds.top);
                drewBackdrop = true;
            } catch (Throwable error) {
                glassPaint.setShader(null);
                if (!shaderFailureLogged) {
                    shaderFailureLogged = true;
                    Log.e(TAG, "AGSL draw failed for " + owner.getClass().getName(), error);
                }
            }
        }
        if (!drewBackdrop) {
            fallbackPaint.setAlpha(Math.round(alpha * 0.42f));
            canvas.drawPath(shape, fallbackPaint);
        }
        canvas.restoreToCount(save);
        drawHighlight(canvas);
    }

    /** Map existing target-local captures, including reveal bounds and reduced blur textures. */
    private void captureCoordinates(Bitmap snapshot, Bitmap shaderBitmap, Rect bounds,
            float[] sampleScale) {
        float originX = 0f, originY = 0f;
        float scaleX = sampleScale[0], scaleY = sampleScale[1];
        boolean snapshotMatchesBounds = snapshot.getWidth() == bounds.width()
                && snapshot.getHeight() == bounds.height();
        if (!snapshotMatchesBounds) {
            if (snapshot.getWidth() < bounds.width()
                    || snapshot.getHeight() < bounds.height()) {
                // Accept-grow plate: backdrop was sampled at the resting host size
                // while the glass is drawn at the scaled (larger) size. Shrink the
                // sampling scale so the backdrop maps 1:1 onto the plate — otherwise
                // the texture is squeezed to the top-left and the edges CLAMP.
                scaleX = snapshot.getWidth() / (float) bounds.width();
                scaleY = snapshot.getHeight() / (float) bounds.height();
            } else if (owner != null) {
                int ow = owner.getWidth();
                int oh = owner.getHeight();
                if (ow > 0 && oh > 0
                        && (bounds.left != 0 || bounds.top != 0
                        || bounds.width() != ow || bounds.height() != oh)) {
                    originX = bounds.left;
                    originY = bounds.top;
                }
            }
        }
        // The blur texture stays small; scale sampling coordinates instead of upscaling it.
        scaleX *= shaderBitmap.getWidth() / (float) snapshot.getWidth();
        scaleY *= shaderBitmap.getHeight() / (float) snapshot.getHeight();
        sampleMatrix[0] = scaleX; sampleMatrix[1] = 0f;
        sampleMatrix[2] = originX * scaleX;
        sampleMatrix[3] = 0f; sampleMatrix[4] = scaleY;
        sampleMatrix[5] = originY * scaleY;
        sampleMatrix[6] = 0f; sampleMatrix[7] = 0f; sampleMatrix[8] = 1f;
    }

    private void drawHighlight(Canvas canvas) {
        float density = owner.getResources().getDisplayMetrics().density;
        float width = Math.max(1f, 0.5f * density);
        highlightPaint.setStrokeWidth(width);
        highlightPaint.setAlpha(Math.round(alpha * 0.16f
                * clamp01(config.highlightIntensity) * clamp01(config.glassIntensity)));
        canvas.drawPath(shape, highlightPaint);
    }

    /**
     * Re-apply {@code RoundFrameLayout.f15656b} after {@code View.setBackgroundBounds} forces
     * the full view rect, so the glass plate tracks the COUI open reveal.
     */
    private void applyCouiRevealBoundsIfPresent() {
        if (owner == null) return;
        try {
            for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
                String name = c.getName();
                if (name == null || !name.endsWith(".RoundFrameLayout")) continue;
                java.lang.reflect.Field rectField;
                try {
                    rectField = c.getDeclaredField("f15656b");
                } catch (NoSuchFieldException ignored) {
                    try {
                        rectField = c.getDeclaredField("mRevealRect");
                    } catch (NoSuchFieldException ignored2) {
                        return;
                    }
                }
                rectField.setAccessible(true);
                Object value = rectField.get(owner);
                if (!(value instanceof Rect)) return;
                Rect reveal = (Rect) value;
                if (reveal.isEmpty()) return;
                Rect cur = getBounds();
                if (cur.left == reveal.left && cur.top == reveal.top
                        && cur.right == reveal.right && cur.bottom == reveal.bottom) {
                    return;
                }
                setBounds(reveal);
                return;
            }
        } catch (Throwable ignored) { }
    }

    /**
     * ColorOS 16 lock-screen capsule: {@code CapsuleBackgroundHelper} draws OEM blur only
     * inside {@code AutoRoundRectPathProvider.getClipRect()}. During 通知列表→胶囊 the host
     * view stays list-row sized; without this the glass plate fills that large rect.
     */
    private void applyCapsuleClipBoundsIfPresent() {
        if (owner == null) return;
        String ownerName = owner.getClass().getName();
        if (ownerName == null
                || !ownerName.endsWith(".CapsuleBackgroundView")) {
            return;
        }
        Rect clip = readCapsuleClipRect(owner);
        if (clip == null || clip.isEmpty()) return;
        int vw = owner.getWidth();
        int vh = owner.getHeight();
        if (vw > 0 && vh > 0) {
            clip.left = Math.max(0, Math.min(clip.left, vw));
            clip.top = Math.max(0, Math.min(clip.top, vh));
            clip.right = Math.max(clip.left, Math.min(clip.right, vw));
            clip.bottom = Math.max(clip.top, Math.min(clip.bottom, vh));
        }
        if (clip.width() < 2 || clip.height() < 2) return;
        float pill = Math.min(clip.width(), clip.height()) * 0.5f;
        if (pill > 0f) {
            setCornerRadii(pill, pill, pill, pill);
        }
        Rect cur = getBounds();
        if (cur.left == clip.left && cur.top == clip.top
                && cur.right == clip.right && cur.bottom == clip.bottom) {
            return;
        }
        setBounds(clip);
    }

    /**
     * Volume seekbar OEM blur is bound to {@code mOutLineRect} (the pill), not the full view.
     * Keep glass on that pill so the plate matches the indicator.
     */
    private void applyVolumeSeekBarBoundsIfPresent() {
        if (owner == null || !BehindDisplayCapture.isVolumeGlass(owner)) return;
        Object outline = fieldOf(owner, "mOutLineRect");
        if (!(outline instanceof Rect)) return;
        Rect pill = (Rect) outline;
        if (pill.width() < 2 || pill.height() < 2) return;
        Object radiusObj = fieldOf(owner, "mCornerRadius");
        if (!(radiusObj instanceof Number) || ((Number) radiusObj).floatValue() <= 0f) {
            radiusObj = fieldOf(owner, "mOutLineRadius");
        }
        if (!(radiusObj instanceof Number) || ((Number) radiusObj).floatValue() <= 0f) {
            radiusObj = fieldOf(owner, "mCurBackgroundRadius");
        }
        if (radiusObj instanceof Number) {
            float r = ((Number) radiusObj).floatValue();
            if (r > 0f) setCornerRadii(r, r, r, r);
        }
        Rect cur = getBounds();
        if (cur.left == pill.left && cur.top == pill.top
                && cur.right == pill.right && cur.bottom == pill.bottom) {
            return;
        }
        setBounds(new Rect(pill));
    }

    private static Rect readCapsuleClipRect(View bg) {
        try {
            Object helper = invokeNoArg(bg, "getCapsuleHelper");
            Object pathProvider = fieldOf(helper, "pathProvider");
            if (pathProvider != null) {
                Object clip = invokeNoArg(pathProvider, "getClipRect");
                if (clip instanceof Rect && !((Rect) clip).isEmpty()) {
                    return new Rect((Rect) clip);
                }
                Object meta = fieldOf(pathProvider, "pathMetaData");
                Object rectF = meta != null ? invokeNoArg(meta, "getClipRect") : null;
                if (rectF instanceof RectF) {
                    RectF rf = (RectF) rectF;
                    if (rf.width() >= 2f && rf.height() >= 2f) {
                        Rect out = new Rect();
                        rf.roundOut(out);
                        return out;
                    }
                }
            }
        } catch (Throwable ignored) { }
        try {
            for (View current = bg; current != null; ) {
                if (current.getClass().getName() != null
                        && current.getClass().getName().endsWith(".CapsuleItemView")) {
                    Object location = fieldOf(current, "location");
                    Object clip = invokeNoArg(location, "getClipRect");
                    if (clip instanceof Rect && !((Rect) clip).isEmpty()) {
                        return new Rect((Rect) clip);
                    }
                    break;
                }
                Object parent = current.getParent();
                if (!(parent instanceof View)) break;
                current = (View) parent;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Object fieldOf(Object target, String name) {
        if (target == null || name == null) return null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String name) {
        if (target == null || name == null) return null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private float[] clampedRadii(Rect bounds) {
        float limit = Math.max(0f, Math.min(bounds.width(), bounds.height()) * 0.5f);
        return new float[] {
                Math.min(radii[0], limit), Math.min(radii[1], limit),
                Math.min(radii[2], limit), Math.min(radii[3], limit)
        };
    }

    private void rebuildShape(Rect bounds) {
        float[] r = clampedRadii(bounds);
        float[] rr = new float[] { r[0], r[0], r[1], r[1], r[2], r[2], r[3], r[3] };
        shape.reset();
        shape.addRoundRect(new RectF(bounds), rr, Path.Direction.CW);
    }

    private float effectiveShaderBlurRadius() {
        if (SharedNotificationBackdrop.contains(owner)) return 0f;
        // SysUI float menus: blur is applied on the DESKTOP crop worker / APP capture worker.
        // Skip a second CPU blur on the draw thread to keep latency down.
        if (BehindDisplayCapture.isSysUiLiveGlass(owner)) return 0f;
        return blurRadiusPx();
    }

    /** Console {@code blur_radius} in device pixels. */
    float blurRadiusPx() {
        return Math.max(0f, config.blurRadius) * owner.getResources().getDisplayMetrics().density;
    }

    int volumeBackdropFps() { return config.volumeBackdropFps; }

    boolean backdropEnabled() {
        return config.menuBackdropEnabled || !BackdropCapture.isMenuBackdrop(owner);
    }

    /** Hot-update config from the module console without reinstalling the drawable. */
    void applyConfig(GlassConfig next) {
        if (next == null) return;
        this.config = next;
        recycleBlurredCache();
        cachedImageShader = null;
        cachedSnapshot = null;
        invalidateSelf();
    }

    /**
     * Blur the capture the same way the console applies {@code blur()} before lens —
     * never via sparse AGSL taps (those caused ghosting on desktop glass).
     */
    private Bitmap blurredBackdrop(Bitmap snapshot, float blurPx) {
        if (blurPx < 0.5f) {
            recycleBlurredCache();
            return snapshot;
        }
        if (cachedBlurredSnapshot != null && !cachedBlurredSnapshot.isRecycled()
                && cachedSnapshotSource == snapshot
                && cachedSnapshotGeneration == snapshot.getGenerationId()
                && Math.abs(cachedBlurRadiusPx - blurPx) < 0.25f) {
            return cachedBlurredSnapshot;
        }
        recycleBlurredCache();
        // Radius is expressed in view pixels, while captures may already be downsampled.
        float sourceScale = owner.getWidth() > 0
                ? snapshot.getWidth() / (float) owner.getWidth() : 1f;
        Bitmap blurred = BackdropBlur.blurCopy(snapshot, blurPx * sourceScale);
        cachedSnapshotSource = snapshot;
        cachedSnapshotGeneration = snapshot.getGenerationId();
        cachedBlurredSnapshot = blurred;
        cachedBlurRadiusPx = blurPx;
        return blurred;
    }

    private void recycleBlurredCache() {
        if (cachedBlurredSnapshot != null && cachedBlurredSnapshot != cachedSnapshotSource
                && !cachedBlurredSnapshot.isRecycled()) {
            cachedBlurredSnapshot.recycle();
        }
        cachedBlurredSnapshot = null;
        cachedSnapshotSource = null;
        cachedBlurRadiusPx = -1f;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    @Override public void setAlpha(int alpha) {
        int next = Math.max(0, Math.min(255, alpha));
        if (this.alpha == next) return;
        this.alpha = next;
        invalidateSelf();
    }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter colorFilter) {
        if (glassPaint.getColorFilter() == colorFilter) return;
        glassPaint.setColorFilter(colorFilter);
        fallbackPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }
    @SuppressWarnings("deprecation") @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

# Device regression probe

`GlassPerformanceProbe` exercises the real Android bitmap implementation. It checks that
blur does not mutate or progressively degrade a producer frame, verifies reduced texture
dimensions. Its timing output measures only blur,
not UI frame rate.

Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to an Android SDK with API/build-tools 35.
Run from the repository root with one ADB device connected:

```powershell
./gradlew.bat :app:assembleRelease --console=plain
./tests/device/Run-Probes.ps1
```

Without a connected device, use `./tests/device/Run-Probes.ps1 -CompileOnly` to compile
the probes and produce their DEX without uploading or running them. This validates linkage,
not runtime rendering. See [code structure](../../docs/code-structure.md) for module ownership.

`VolumeLifecycleProbe` checks pending volume-apply cancellation/coalescing and LIVE reference
ownership using a private arena in its own process. It does not switch real audio devices.

The script uploads the APK as a classpath dependency under `/data/local/tmp`; it does not
install/enable the module or restart Launcher/SystemUI. `SharedWallpaperProbe` exercises
GPU RenderEffect output, production AGSL, source/radius/size cache invalidation, retained
texture lifetime, perspective/bounds/zoom coordinates, and folder/menu/drag route selection.
It also renders a uniform gray wallpaper with optics enabled: the flat face must remain
uniform across internal diagonals, while the edge must retain rim lighting.
Notification checks cover explicit shared-path opt-in, home/lock selection, ignoring desktop
wallpaper zoom, 60 moving frames without CPU capture buffers, and separate home/lock caches
reused across multiple cards.

`HeadsUpRouteProbe` uses a probe-only notification-row contract fixture to check ancestor
lookup, heads-up and disappearing state, the independent toggle, live bind/unbind, shared
volume FPS, and the disabled menu's forced-capture guard. The fixture is only in the probe DEX,
never the APK. This does not validate the device's actual OEM row API or live surface exclusion.
Kotlin probe compilation requires `kotlinc` on PATH. Test language/API 2.0 keeps metadata
compatible with the existing SDK 35 D8; application build settings are unchanged.


Notification integration still requires checking expanded/grouped cards, swipe dismissal,
lock/unlock with different wallpapers, and the module toggle on the installed build.
The new ordinary-card renderer samples wallpaper, not the foreground app's live surface.
Ordinary folders keep their OEM transform and sample a shared GPU wallpaper texture.
Local content captures remain in use for drag/menu/explicit previews.
The experimental hold-until-settled texture policy was reverted after visible jumps on device.

`NotificationAppearanceProbe` checks a white wallpaper pixel against white text in unlocked
night mode, preserves row fade alpha, and reuses one paint through lock/unlock and day/night
transitions. It tests the material filter, not the full OEM shader, final screenshot contrast,
or semi-transparent notification text. Check those visually after installation.

`TemporalBlurProbe` moves a crop over static stripes and thin lines, then compares the same
wallpaper points across 16 frames. It includes odd crop dimensions and a one-pixel-wide
source. Single-step bilinear downsampling failed with ranges 255/26/33; area filtering
reduces those fixtures to 0/2/4 (0–255 channel values). This limits spatial aliasing;
it does not establish that every OEM animation has correct coordinate mapping.

With no Android device attached, run `python tests/host/test_glass_rim.py`. It evaluates the
actual AGSL rim expression against flat-interior, boundary and medial-axis lighting
invariants. The pre-1.3.4 expression fails all three cases. This host check does not compile
or execute AGSL on a GPU; the uniform-wallpaper device check still needs a connected device.

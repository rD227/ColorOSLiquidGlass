# Device regression probe

`GlassPerformanceProbe` exercises the real Android bitmap implementation. It checks that
blur does not mutate or progressively degrade a producer frame, verifies reduced texture
dimensions. Its timing output measures only blur,
not UI frame rate.

Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to an Android SDK with API/build-tools 35.
Run from the repository root with one ADB device connected:

```powershell
$probe = Join-Path (Get-Location) 'app/build/device-probe'
New-Item -ItemType Directory -Force $probe | Out-Null
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -source 17 -target 17 `
  -cp "$env:ANDROID_HOME/platforms/android-35/android.jar" -d $probe `
  app/src/main/java/net/z841973620/colorosliquidglass/glass/BackdropBlur.java `
  app/src/main/java/net/z841973620/colorosliquidglass/glass/NotificationGlassAppearance.java `
  tests/device/GlassPerformanceProbe.java tests/device/NotificationAppearanceProbe.java `
  tests/device/TemporalBlurProbe.java
if ($LASTEXITCODE) { throw 'Probe compilation failed' }
$classes = Get-ChildItem $probe -Recurse -Filter '*.class' | ForEach-Object FullName
& "$env:JAVA_HOME/bin/java.exe" -cp "$env:ANDROID_HOME/build-tools/35.0.0/lib/d8.jar" `
  com.android.tools.r8.D8 --lib "$env:ANDROID_HOME/platforms/android-35/android.jar" `
  --output $probe $classes
if ($LASTEXITCODE) { throw 'DEX compilation failed' }
& "$env:ANDROID_HOME/platform-tools/adb.exe" push "$probe/classes.dex" /data/local/tmp/colg-performance-probe.dex
if ($LASTEXITCODE) { throw 'Probe upload failed' }
& "$env:ANDROID_HOME/platform-tools/adb.exe" shell 'CLASSPATH=/data/local/tmp/colg-performance-probe.dex app_process /system/bin net.z841973620.colorosliquidglass.glass.GlassPerformanceProbe'
if ($LASTEXITCODE) { throw 'Device regression probe failed' }
& "$env:ANDROID_HOME/platform-tools/adb.exe" shell 'CLASSPATH=/data/local/tmp/colg-performance-probe.dex app_process /system/bin net.z841973620.colorosliquidglass.glass.TemporalBlurProbe'
if ($LASTEXITCODE) { throw 'Temporal blur probe failed' }
& "$env:ANDROID_HOME/platform-tools/adb.exe" shell 'CLASSPATH=/data/local/tmp/colg-performance-probe.dex app_process /system/bin net.z841973620.colorosliquidglass.glass.NotificationAppearanceProbe'
if ($LASTEXITCODE) { throw 'Notification appearance probe failed' }
```

Notification integration still requires checking expanded/grouped cards, swipe dismissal,
lock/unlock with different wallpapers, and the module toggle on the installed build.
The new ordinary-card renderer samples wallpaper, not the foreground app's live surface.
Paging keeps the host's existing OEM transform and the normal backdrop capture schedule.
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

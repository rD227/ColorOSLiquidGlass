# Device regression probe

`GlassPerformanceProbe` exercises the real Android bitmap implementation. It checks that
blur does not mutate or progressively degrade a producer frame, verifies reduced texture
dimensions, and checks paging/settling decisions. Its timing output measures only blur,
not UI frame rate.

Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to an Android SDK with API/build-tools 35.
Run from the repository root with one ADB device connected:

```powershell
$probe = Join-Path (Get-Location) 'app/build/device-probe'
New-Item -ItemType Directory -Force $probe | Out-Null
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -source 17 -target 17 `
  -cp "$env:ANDROID_HOME/platforms/android-35/android.jar" -d $probe `
  app/src/main/java/net/z841973620/colorosliquidglass/glass/BackdropBlur.java `
  app/src/main/java/net/z841973620/colorosliquidglass/glass/PageCapturePolicy.java `
  tests/device/GlassPerformanceProbe.java
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
```

Notification integration still requires checking expanded/grouped cards, swipe dismissal,
lock/unlock with different wallpapers, and the module toggle on the installed build.
The new ordinary-card renderer samples wallpaper, not the foreground app's live surface.
Paging keeps the host's existing OEM transform; it avoids CPU recapture while scrolling
rather than moving a separate glass overlay directly from raw finger coordinates.

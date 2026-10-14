$ErrorActionPreference = 'Stop'
$projectDir = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$probeDir = Join-Path $projectDir 'app/build/shared-wallpaper-probe'
$appClasses = Join-Path $projectDir 'app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes'
$kotlinClasses = Join-Path $projectDir 'app/build/tmp/kotlin-classes/release'
$apk = Join-Path $projectDir 'app/build/outputs/apk/release/app-release.apk'
$androidJar = Join-Path $env:ANDROID_HOME 'platforms/android-35/android.jar'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
New-Item -ItemType Directory -Force $probeDir | Out-Null
$probeNames = @('SharedWallpaperProbe', 'TemporalBlurProbe', 'GlassPerformanceProbe', 'NotificationAppearanceProbe')
$sources = $probeNames | ForEach-Object { Join-Path $PSScriptRoot "${_}.java" }
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -source 17 -target 17 `
    -cp "$androidJar;$appClasses" -d $probeDir $sources
if ($LASTEXITCODE) { throw 'Probe compilation failed; build assembleRelease first' }
$kotlinArguments = @(
    ('"' + (Join-Path $PSScriptRoot 'ExpandableNotificationRow.kt').Replace('\', '/') + '"'),
    ('"' + (Join-Path $PSScriptRoot 'HeadsUpRouteProbe.kt').Replace('\', '/') + '"'),
    ('"' + (Join-Path $PSScriptRoot 'NotificationRefractionProbe.kt').Replace('\', '/') + '"'),
    ('"' + (Join-Path $PSScriptRoot 'HeaderEdgeProbe.kt').Replace('\', '/') + '"'),
    ('"' + (Join-Path $PSScriptRoot 'NotificationAndPopupProbe.kt').Replace('\', '/') + '"'),
    ('"' + (Join-Path $PSScriptRoot 'OemNotificationContractProbe.kt').Replace('\', '/') + '"'),
    ('"' + (Join-Path $PSScriptRoot 'OpenFolderQueryProbe.kt').Replace('\', '/') + '"'),
    ('"' + (Join-Path $PSScriptRoot 'DesktopIconBitmapProbe.kt').Replace('\', '/') + '"'),
    ('"' + (Join-Path $PSScriptRoot 'GitHubReleaseProbe.kt').Replace('\', '/') + '"'),
    ('"' + (Join-Path $PSScriptRoot 'launcher/ActivityContext.kt').Replace('\', '/') + '"'),
    ('"' + (Join-Path $PSScriptRoot 'launcher/AbstractFloatingView.kt').Replace('\', '/') + '"'),
    ('"' + (Join-Path $PSScriptRoot 'launcher/Folder.kt').Replace('\', '/') + '"'),
    '-classpath', ('"' + "$androidJar;$appClasses;$kotlinClasses".Replace('\', '/') + '"'),
    '-language-version', '2.0', '-api-version', '2.0',
    ('"-Xfriend-paths=' + $kotlinClasses.Replace('\', '/') + '"'),
    '-d', ('"' + $probeDir.Replace('\', '/') + '"')
)
$argumentFile = Join-Path $probeDir 'kotlin.args'
Set-Content -LiteralPath $argumentFile -Value $kotlinArguments -Encoding utf8
& kotlinc "@$argumentFile"
if ($LASTEXITCODE) { throw 'Kotlin probe compilation failed' }
$classes = Get-ChildItem $probeDir -Recurse -Filter '*.class' | ForEach-Object FullName
& "$env:JAVA_HOME/bin/java.exe" -cp "$env:ANDROID_HOME/build-tools/35.0.0/lib/d8.jar" `
    com.android.tools.r8.D8 --lib $androidJar --classpath $appClasses --classpath $kotlinClasses --output $probeDir $classes
if ($LASTEXITCODE) { throw 'DEX compilation failed' }
& $adb push $apk /data/local/tmp/colg-probe-app.apk
if ($LASTEXITCODE) { throw 'APK upload failed' }
& $adb push "$probeDir/classes.dex" /data/local/tmp/colg-shared-probe.dex
if ($LASTEXITCODE) { throw 'Probe upload failed' }
foreach ($name in $probeNames) {
    & $adb shell "CLASSPATH=/data/local/tmp/colg-probe-app.apk:/data/local/tmp/colg-shared-probe.dex app_process /system/bin net.z841973620.colorosliquidglass.glass.$name"
    if ($LASTEXITCODE) { throw "$name failed" }
}
& $adb shell 'CLASSPATH=/data/local/tmp/colg-probe-app.apk:/data/local/tmp/colg-shared-probe.dex app_process /system/bin net.z841973620.colorosliquidglass.glass.HeadsUpRouteProbeKt'
if ($LASTEXITCODE) { throw 'Heads-up route probe failed' }
& $adb shell 'CLASSPATH=/data/local/tmp/colg-probe-app.apk:/data/local/tmp/colg-shared-probe.dex app_process /system/bin net.z841973620.colorosliquidglass.glass.NotificationRefractionProbeKt'
if ($LASTEXITCODE) { throw 'Notification refraction probe failed' }
& $adb shell 'CLASSPATH=/data/local/tmp/colg-probe-app.apk:/data/local/tmp/colg-shared-probe.dex app_process /system/bin net.z841973620.colorosliquidglass.glass.HeaderEdgeProbeKt'
if ($LASTEXITCODE) { throw 'Header edge probe failed' }
& $adb shell 'CLASSPATH=/data/local/tmp/colg-probe-app.apk:/data/local/tmp/colg-shared-probe.dex app_process /system/bin net.z841973620.colorosliquidglass.glass.NotificationAndPopupProbeKt'
if ($LASTEXITCODE) { throw 'Notification and popup probe failed' }
& $adb shell 'CLASSPATH=/data/local/tmp/colg-probe-app.apk:/data/local/tmp/colg-shared-probe.dex app_process /system/bin net.z841973620.colorosliquidglass.glass.OpenFolderQueryProbeKt'
if ($LASTEXITCODE) { throw 'Open-folder query probe failed' }
& $adb shell 'CLASSPATH=/data/local/tmp/colg-probe-app.apk:/data/local/tmp/colg-shared-probe.dex app_process /system/bin net.z841973620.colorosliquidglass.glass.DesktopIconBitmapProbeKt'
if ($LASTEXITCODE) { throw 'Desktop icon bitmap probe failed' }
& $adb shell 'CLASSPATH=/data/local/tmp/colg-probe-app.apk:/data/local/tmp/colg-shared-probe.dex app_process /system/bin net.z841973620.colorosliquidglass.update.GitHubReleaseProbeKt'
if ($LASTEXITCODE) { throw 'GitHub release probe failed' }

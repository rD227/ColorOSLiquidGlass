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
    '-classpath', ('"' + "$androidJar;$appClasses;$kotlinClasses".Replace('\', '/') + '"'),
    '-language-version', '2.0', '-api-version', '2.0',
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

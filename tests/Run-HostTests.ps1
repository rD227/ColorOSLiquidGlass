$ErrorActionPreference = 'Stop'
$projectDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path.Replace('\', '/')
$outputDir = "$projectDir/app/build/host-tests"
$appClasses = "$projectDir/app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes"
$androidJar = "$env:ANDROID_HOME/platforms/android-35/android.jar"
$testJar = "$outputDir/host-tests.jar"
New-Item -ItemType Directory -Force $outputDir | Out-Null
$sources = @(
    'app/src/main/java/net/z841973620/colorosliquidglass/glass/CaptureCadence.kt',
    'app/src/main/java/net/z841973620/colorosliquidglass/glass/PopupCaptureCadence.kt',
    'tests/PopupCaptureCadenceTest.kt',
    'tests/MenuBackdropConfigTest.kt'
)
$compilerArguments = @($sources | ForEach-Object { '"' + "$projectDir/$_" + '"' }) + @(
    '-classpath', ('"' + "$androidJar;$appClasses" + '"'),
    '-include-runtime', '-d', ('"' + $testJar + '"')
)
$argumentFile = "$outputDir/compiler.args"
Set-Content -LiteralPath $argumentFile -Value $compilerArguments -Encoding utf8
& kotlinc "@$argumentFile"
if ($LASTEXITCODE) { throw 'Host test compilation failed; assembleRelease first' }
foreach ($entryPoint in @(
    'net.z841973620.colorosliquidglass.glass.PopupCaptureCadenceTestKt',
    'net.z841973620.colorosliquidglass.MenuBackdropConfigTestKt'
)) {
    & "$env:JAVA_HOME/bin/java.exe" -cp "$testJar;$androidJar;$appClasses" $entryPoint
    if ($LASTEXITCODE) { throw "$entryPoint failed" }
}

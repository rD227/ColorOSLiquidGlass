package net.z841973620.colorosliquidglass.update

import org.json.JSONException
import java.io.IOException

fun main(arguments: Array<String>) {
    check(GitHubReleaseChecker.compareVersions("v1.10.0", "1.9.9") == 1)
    check(GitHubReleaseChecker.compareVersions("1.4", "1.4.0") == 0)
    check(GitHubReleaseChecker.compareVersions("1.3.14", "1.4.0") == -1)
    check(GitHubReleaseChecker.compareVersions("nightly", "1.4.0") == null)
    check(GitHubReleaseChecker.compareVersions("1.4.0", "1.4.0-beta") == null)
    fun release(tag: String, extra: String = "") = GitHubReleaseChecker.parseRelease(
        "1.4.0", """{"tag_name":"$tag"$extra}"""
    )
    check(release("v1.4.1").comparison == 1)
    check(release("v1.4.0").comparison == 0)
    check(release("v1.3.14").comparison == -1)
    check(release("nightly").comparison == null)
    check(GitHubReleaseChecker.parseRelease("1.4.0", """{"tag_name":"v1.4.1"}""").comparison == 1)
    check(GitHubReleaseChecker.parseRelease("1.4.0", """{"tag_name":"v1.4.0"}""").comparison == 0)
    try {
        release("v2.0.0", ",\"prerelease\":true")
        error("A prerelease must not be reported as the latest stable version")
    } catch (_: IOException) { }
    try {
        GitHubReleaseChecker.parseRelease("1.4.0", "{}")
        error("A missing tag must not be reported as up to date")
    } catch (_: JSONException) { }
    println("PASS: numeric versions, v-prefix, equal versions, local-newer version, unsupported tags, invalid response")
    if (arguments.contains("--resources")) {
        val assets = android.content.res.AssetManager::class.java.getDeclaredConstructor().newInstance()
        android.content.res.AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            .invoke(assets, "/data/local/tmp/colg-probe-app.apk")
        for ((locale, expected) in listOf("zh-CN" to "发现新版本", "en-US" to "is available", "de-DE" to "is available")) {
            val config = android.content.res.Configuration(android.content.res.Resources.getSystem().configuration)
            config.setLocale(java.util.Locale.forLanguageTag(locale))
            val resources = android.content.res.Resources(assets, android.content.res.Resources.getSystem().displayMetrics, config)
            check(release("v1.4.1").message(resources).contains(expected))
            for (tag in listOf("v1.4.1", "v1.4.0", "v1.3.14", "nightly")) {
                val message = release(tag).message(resources)
                check(message.contains(tag) && !message.contains("%1"))
            }
            val error = GitHubReleaseChecker.failureMessage(resources, IOException())
            check(error.contains(if (locale.startsWith("zh")) "检查失败" else "Could not check"))
        }
        println("PASS: Chinese, English, unsupported-language fallback and localized network errors")
    }
    if (arguments.contains("--browser")) {
        if (android.os.Looper.myLooper() == null) android.os.Looper.prepareMainLooper()
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        val context = activityThread.getMethod("getSystemContext").invoke(thread) as android.content.Context
        val url = "https://github.com/rD227/ColorOSLiquidGlass/releases/latest/download/app-release.apk"
        val intent = defaultBrowserIntent(context.packageManager, url)
        check(intent != null) { "No selected browser was resolved on this device" }
        check(intent.selector == null && intent.dataString == url && intent.`package` != null)
        check(context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName == intent.`package`)
        println("PASS: default browser download intent resolves to ${intent.`package`} without launching it")
    }
    if (arguments.contains("--live")) {
        val task = java.util.concurrent.FutureTask { GitHubReleaseChecker.checkRelease("1.4.0") }
        Thread(task, "ReleaseProbeNetwork").start()
        println("LIVE: " + task.get())
    }
}

package net.z841973620.colorosliquidglass.update

import org.json.JSONException
import java.io.IOException

fun main(arguments: Array<String>) {
    check(GitHubReleaseChecker.compareVersions("v1.10.0", "1.9.9") == 1)
    check(GitHubReleaseChecker.compareVersions("1.4", "1.4.0") == 0)
    check(GitHubReleaseChecker.compareVersions("1.3.14", "1.4.0") == -1)
    check(GitHubReleaseChecker.compareVersions("nightly", "1.4.0") == null)
    check(GitHubReleaseChecker.compareVersions("1.4.0", "1.4.0-beta") == null)
    fun describe(tag: String, extra: String = ""): String = GitHubReleaseChecker.describe(
        "1.4.0", """{"tag_name":"$tag"$extra}"""
    )
    check(describe("v1.4.1").contains("发现新版本"))
    check(describe("v1.4.0").contains("已是最新"))
    check(describe("v1.3.14").contains("当前版本 1.4.0 更新"))
    check(describe("nightly").contains("无法自动比较"))
    check(GitHubReleaseChecker.parseRelease("1.4.0", """{"tag_name":"v1.4.1"}""").comparison == 1)
    check(GitHubReleaseChecker.parseRelease("1.4.0", """{"tag_name":"v1.4.0"}""").comparison == 0)
    try {
        describe("v2.0.0", ",\"prerelease\":true")
        error("A prerelease must not be reported as the latest stable version")
    } catch (_: IOException) { }
    try {
        GitHubReleaseChecker.describe("1.4.0", "{}")
        error("A missing tag must not be reported as up to date")
    } catch (_: JSONException) { }
    println("PASS: numeric versions, v-prefix, equal versions, local-newer version, unsupported tags, invalid response")
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
        val task = java.util.concurrent.FutureTask { GitHubReleaseChecker.check("1.4.0") }
        Thread(task, "ReleaseProbeNetwork").start()
        println("LIVE: " + task.get())
    }
}

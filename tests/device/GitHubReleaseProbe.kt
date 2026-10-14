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
    try {
        describe("v2.0.0", ",\"prerelease\":true")
        error("A prerelease must not be reported as the latest stable version")
    } catch (_: IOException) { }
    try {
        GitHubReleaseChecker.describe("1.4.0", "{}")
        error("A missing tag must not be reported as up to date")
    } catch (_: JSONException) { }
    println("PASS: numeric versions, v-prefix, equal versions, local-newer version, unsupported tags, invalid response")
    if (arguments.contains("--live")) println("LIVE: " + GitHubReleaseChecker.check("1.4.0"))
}

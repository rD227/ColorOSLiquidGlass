package net.z841973620.colorosliquidglass.update

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Explicit, user-triggered request. No background schedule or stored check timestamp. */
internal object GitHubReleaseChecker {
    private const val ENDPOINT = "https://api.github.com/repos/rD227/ColorOSLiquidGlass/releases/latest"

    data class ReleaseStatus(val latestVersion: String, val comparison: Int?, val message: String)

    fun check(currentVersion: String): String = checkRelease(currentVersion).message

    fun checkRelease(currentVersion: String): ReleaseStatus {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "ColorOSLiquidGlass/$currentVersion")
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> return parseRelease(
                    currentVersion, connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                )
                HttpURLConnection.HTTP_NOT_FOUND -> throw IOException("未找到公开的正式版，请前往 GitHub 查看。")
                HttpURLConnection.HTTP_FORBIDDEN, 429 -> throw IOException("GitHub 暂时限制了请求，请稍后再试。")
                else -> throw IOException("更新服务返回 HTTP $status，请稍后再试。")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun describe(currentVersion: String, response: String): String = parseRelease(currentVersion, response).message

    fun parseRelease(currentVersion: String, response: String): ReleaseStatus {
        val release = JSONObject(response)
        val latest = release.getString("tag_name").trim()
        if (latest.isEmpty() || release.optBoolean("draft") || release.optBoolean("prerelease")) {
            throw IOException("更新服务没有返回有效的正式版信息。")
        }
        val comparison = compareVersions(latest, currentVersion)
        val message = when (comparison) {
            1 -> "发现新版本 $latest（当前 $currentVersion），可点击下方按钮下载。"
            0 -> "当前已是最新正式版：$latest。"
            -1 -> "最新正式版为 $latest；当前版本 $currentVersion 更新。"
            else -> "最新正式版为 $latest，当前版本为 $currentVersion；版本格式无法自动比较，请前往 GitHub 核对。"
        }
        return ReleaseStatus(latest, comparison, message)
    }

    /** Unknown tag formats are shown to the user instead of claiming they are up to date. */
    fun compareVersions(left: String, right: String): Int? {
        fun parts(version: String): List<java.math.BigInteger>? {
            val trimmed = version.trim()
            val normalized = if (trimmed.startsWith("v", ignoreCase = true)) trimmed.drop(1) else trimmed
            if (!normalized.matches(Regex("[0-9]+(?:\\.[0-9]+)*"))) return null
            return normalized.split('.').map { it.toBigInteger() }
        }
        val leftParts = parts(left) ?: return null
        val rightParts = parts(right) ?: return null
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val comparison = (leftParts.getOrNull(index) ?: java.math.BigInteger.ZERO)
                .compareTo(rightParts.getOrNull(index) ?: java.math.BigInteger.ZERO)
            if (comparison != 0) return comparison
        }
        return 0
    }
}

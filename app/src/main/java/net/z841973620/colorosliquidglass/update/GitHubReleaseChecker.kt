package net.z841973620.colorosliquidglass.update

import android.content.res.Resources
import net.z841973620.colorosliquidglass.R
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Explicit, user-triggered request. No background schedule or stored check timestamp. */
internal object GitHubReleaseChecker {
    private const val ENDPOINT = "https://api.github.com/repos/rD227/ColorOSLiquidGlass/releases/latest"

    data class ReleaseStatus(val latestVersion: String, val comparison: Int?, val currentVersion: String) {
        fun message(resources: Resources): String = when (comparison) {
            1 -> resources.getString(R.string.update_available_message, latestVersion, currentVersion)
            0 -> resources.getString(R.string.update_current_message, latestVersion)
            -1 -> resources.getString(R.string.update_local_newer_message, latestVersion, currentVersion)
            else -> resources.getString(R.string.update_unknown_version_message, latestVersion, currentVersion)
        }
    }

    private class ServiceException(val messageId: Int, val status: Int = 0) : IOException()

    fun failureMessage(resources: Resources, error: IOException): String =
        if (error is ServiceException) resources.getString(error.messageId, error.status)
        else resources.getString(R.string.update_network_error)

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
                HttpURLConnection.HTTP_NOT_FOUND -> throw ServiceException(R.string.update_not_found)
                HttpURLConnection.HTTP_FORBIDDEN, 429 -> throw ServiceException(R.string.update_rate_limited)
                else -> throw ServiceException(R.string.update_http_error, status)
            }
        } finally {
            connection.disconnect()
        }
    }

    fun parseRelease(currentVersion: String, response: String): ReleaseStatus {
        val release = JSONObject(response)
        val latest = release.getString("tag_name").trim()
        if (latest.isEmpty() || release.optBoolean("draft") || release.optBoolean("prerelease")) {
            throw ServiceException(R.string.update_invalid_release)
        }
        val comparison = compareVersions(latest, currentVersion)
        return ReleaseStatus(latest, comparison, currentVersion)
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

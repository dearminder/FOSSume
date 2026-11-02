package com.dearminder.fossume

import org.json.JSONObject

// ---------- Decisions behind Updates, free of Android types so they're unit-tested on the JVM ----------

internal const val REPOSITORY = "dearminder/FOSSume"
const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases/latest"

/** A published release; [apkUrl] is null if it has no APK attached (yet). */
data class Release(val version: String, val pageUrl: String, val apkUrl: String?)

/** GitHub's "latest release" answer, or null if it isn't one. */
internal fun parseRelease(json: String): Release? {
    val o = try { JSONObject(json) } catch (e: Exception) { return null }
    val tag = o.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
    val assets = o.optJSONArray("assets")
    val apk = assets?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?.optString("browser_download_url")?.takeIf { it.isNotBlank() }
    }
    return Release(
        version = tag.removePrefix("v"),
        pageUrl = o.optString("html_url").takeIf { it.isNotBlank() } ?: RELEASES_PAGE,
        apkUrl = apk
    )
}

private val VERSION_NUMBERS = Regex("\\d+(\\.\\d+)*")

/**
 * Whether [latest] is a higher version than [current], comparing their numbers part by part
 * (1.0.10 > 1.0.9, 1.1 == 1.1.0). A build without a version number, like "dev", counts as older
 * than any release.
 */
internal fun isNewerVersion(latest: String, current: String): Boolean {
    val l = VERSION_NUMBERS.find(latest)?.value?.split('.')?.map { it.toLongOrNull() ?: 0L } ?: return false
    val c = VERSION_NUMBERS.find(current)?.value?.split('.')?.map { it.toLongOrNull() ?: 0L } ?: return true
    for (i in 0 until maxOf(l.size, c.size)) {
        val a = l.getOrElse(i) { 0L }
        val b = c.getOrElse(i) { 0L }
        if (a != b) return a > b
    }
    return false
}

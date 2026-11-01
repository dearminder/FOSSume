package com.dearminder.fossume

import java.util.Locale

// ---------- Decisions behind MediaControl, free of Android types so they're unit-tested on the JVM ----------

internal enum class ButtonKind { LIKE, UNLIKE, HIDE }

// Matched against action ids and their (localized) labels; "unlike" is checked before "like".
private val HIDE_WORDS = listOf("hide", "ukryj")
// Too short to match inside other words ("urban", "banner").
private val HIDE_WHOLE_WORDS = Regex("(^|[^\\p{L}])ban([^\\p{L}]|$)")
private val UNLIKE_WORDS = listOf("unlike", "un_like", "unfavo", "unheart", "unsave", "remove from", "remove_from", "usuń z", "usun z")
private val LIKE_WORDS = listOf("like", "favo", "heart", "collection", "save", "polub", "ulubion", "zapisz", "thumbs_up", "thumb_up")

/** What a player's button does, judged by its action id or label; null if it's none of ours. */
internal fun classifyButton(text: String): ButtonKind? {
    val t = text.lowercase(Locale.ROOT)
    return when {
        HIDE_WORDS.any { it in t } || HIDE_WHOLE_WORDS.containsMatchIn(t) -> ButtonKind.HIDE
        UNLIKE_WORDS.any { it in t } -> ButtonKind.UNLIKE
        LIKE_WORDS.any { it in t } -> ButtonKind.LIKE
        else -> null
    }
}

/**
 * What a session custom action is judged by. Action ids are often prefixed with the player's
 * package ("com.spotify.music.ADD_TO_COLLECTION"), which must not count - a player called
 * "com.savemusic" would otherwise have every button taken for "like".
 */
internal fun customActionText(action: String, name: CharSequence?): String = "${action.substringAfterLast('.')} ${name ?: ""}"

/**
 * Where a seek by [deltaMs] lands. The reported [positionMs] is a snapshot taken at
 * [lastUpdateMs]; while playing it has moved on since then by the elapsed time times [speed].
 * The result stays within the track ([durationMs] of 0 or less means unknown).
 */
internal fun seekTarget(
    positionMs: Long,
    lastUpdateMs: Long,
    nowMs: Long,
    speed: Float,
    playing: Boolean,
    deltaMs: Long,
    durationMs: Long
): Long {
    var position = positionMs
    if (playing && lastUpdateMs > 0) position += ((nowMs - lastUpdateMs) * speed).toLong()
    val target = (position + deltaMs).coerceAtLeast(0L)
    return if (durationMs > 0) target.coerceAtMost(durationMs) else target
}

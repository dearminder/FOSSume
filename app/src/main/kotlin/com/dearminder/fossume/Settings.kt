package com.dearminder.fossume

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

// ---------- Persisted settings (shared by the UI and the services) ----------

object Prefs {
    const val NAME = "fossume_prefs"
    const val ACTIVATION = "activation"
    const val VIBRATION = "vibration"
    const val LONG_PRESS_MS = "long_press_ms"
    const val CLICK_GAP_MS = "click_gap_ms"
    const val LONG_ACTIONS = "long_actions"
    const val SHORT_COMBOS = "short_combos"
    const val DISABLED_PLAYERS = "disabled_players"
    /** The players saved in Settings (the key predates the split into saved and seen-this-session). */
    const val DETECTED_PLAYERS = "detected_players"
    const val STATUS_NOTIFICATION = "status_notification"
    const val AUTO_UPDATE_CHECK = "auto_update_check"
    /** When GitHub was last asked for a new release (ms since the epoch). Not a setting, like [DETECTED_PLAYERS]. */
    const val LAST_UPDATE_CHECK = "last_update_check"

    const val DEFAULT_VIBRATION = 50
    const val DEFAULT_LONG_PRESS_MS = 400
    const val DEFAULT_CLICK_GAP_MS = 350
    const val DEFAULT_SEEK_SECONDS = 10
}

fun Context.appPrefs(): SharedPreferences = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

/**
 * When the volume keys are taken over: with the screen off or locked (the lock screen showing),
 * or also while the phone is unlocked. Saved by ordinal - keep the order.
 */
enum class Activation { LOCKED, ALWAYS }

enum class VolumeKey { UP, DOWN }

/** What a long press is made of: one of the keys, or both held together. */
enum class LongTrigger { UP, DOWN, BOTH }

enum class MediaAction(val usesSeconds: Boolean = false, val spotifyOnly: Boolean = false) {
    NEXT, PREVIOUS, FORWARD(usesSeconds = true), REWIND(usesSeconds = true), PLAY_PAUSE, LIKE, UNLIKE, HIDE(spotifyOnly = true)
}

data class ActionBinding(
    val enabled: Boolean,
    val action: MediaAction,
    val seconds: Int = Prefs.DEFAULT_SEEK_SECONDS
) {
    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("action", action.name).put("seconds", seconds)

    companion object {
        const val MIN_SECONDS = 1
        const val MAX_SECONDS = 600

        fun fromJson(o: JSONObject, fallback: ActionBinding): ActionBinding = ActionBinding(
            enabled = o.optBoolean("enabled", fallback.enabled),
            action = MediaAction.entries.firstOrNull { it.name == o.optString("action") } ?: fallback.action,
            seconds = o.optInt("seconds", fallback.seconds).coerceIn(MIN_SECONDS, MAX_SECONDS)
        )
    }
}

/** A sequence of quick presses, e.g. up-up, and what it does. */
data class ShortCombo(val keys: List<VolumeKey>, val binding: ActionBinding) {
    /** Stable and unique within the list (duplicates aren't allowed), so it doubles as a list key. */
    val id: String get() = keys.joinToString("-")

    companion object {
        const val MAX_KEYS = 6
    }
}

val DEFAULT_LONG_ACTIONS = mapOf(
    LongTrigger.UP to ActionBinding(true, MediaAction.NEXT),
    LongTrigger.DOWN to ActionBinding(true, MediaAction.PREVIOUS),
    LongTrigger.BOTH to ActionBinding(true, MediaAction.PLAY_PAUSE)
)

data class AppSettings(
    val activation: Activation,
    /** 0 (off) to 100 (strongest). */
    val vibration: Int,
    val longPressMs: Int,
    /** How long to wait for the next press of a short-click combination. */
    val clickGapMs: Int,
    val longActions: Map<LongTrigger, ActionBinding>,
    val shortCombos: List<ShortCombo>,
    /** Players are opted out rather than in, so a newly detected one works straight away. */
    val disabledPlayers: Set<String>,
    val statusNotification: Boolean,
    /** Ask GitHub for a new release once a day when the app is opened. Off by default - no network unless asked. */
    val autoUpdateCheck: Boolean = false
) {
    fun longAction(trigger: LongTrigger): ActionBinding = longActions[trigger] ?: DEFAULT_LONG_ACTIONS.getValue(trigger)

    /** Whether a key has anything to do at all - if not, it's left entirely to the system. */
    fun uses(key: VolumeKey): Boolean {
        val single = if (key == VolumeKey.UP) LongTrigger.UP else LongTrigger.DOWN
        return longAction(single).enabled || longAction(LongTrigger.BOTH).enabled ||
            shortCombos.any { it.binding.enabled && key in it.keys }
    }

    fun save(context: Context) {
        context.appPrefs().edit()
            .putInt(Prefs.ACTIVATION, activation.ordinal)
            .putInt(Prefs.VIBRATION, vibration)
            .putInt(Prefs.LONG_PRESS_MS, longPressMs)
            .putInt(Prefs.CLICK_GAP_MS, clickGapMs)
            .putString(Prefs.LONG_ACTIONS, encodeLongActions(longActions))
            .putString(Prefs.SHORT_COMBOS, encodeCombos(shortCombos))
            .putStringSet(Prefs.DISABLED_PLAYERS, disabledPlayers)
            .putBoolean(Prefs.STATUS_NOTIFICATION, statusNotification)
            .putBoolean(Prefs.AUTO_UPDATE_CHECK, autoUpdateCheck)
            .apply()
    }

    companion object {
        const val MIN_VIBRATION = 0
        const val MAX_VIBRATION = 100
        const val MIN_LONG_PRESS_MS = 10
        const val MAX_LONG_PRESS_MS = 2000
        const val MIN_CLICK_GAP_MS = 100
        const val MAX_CLICK_GAP_MS = 1000

        fun load(context: Context): AppSettings {
            val p = context.appPrefs()
            return AppSettings(
                activation = Activation.entries.getOrElse(p.getInt(Prefs.ACTIVATION, 0)) { Activation.LOCKED },
                vibration = p.getInt(Prefs.VIBRATION, Prefs.DEFAULT_VIBRATION).coerceIn(MIN_VIBRATION, MAX_VIBRATION),
                longPressMs = p.getInt(Prefs.LONG_PRESS_MS, Prefs.DEFAULT_LONG_PRESS_MS).coerceIn(MIN_LONG_PRESS_MS, MAX_LONG_PRESS_MS),
                clickGapMs = p.getInt(Prefs.CLICK_GAP_MS, Prefs.DEFAULT_CLICK_GAP_MS).coerceIn(MIN_CLICK_GAP_MS, MAX_CLICK_GAP_MS),
                longActions = parseLongActions(p.getString(Prefs.LONG_ACTIONS, null)),
                shortCombos = parseCombos(p.getString(Prefs.SHORT_COMBOS, null)),
                disabledPlayers = p.getStringSet(Prefs.DISABLED_PLAYERS, null)?.toSet() ?: emptySet(),
                statusNotification = p.getBoolean(Prefs.STATUS_NOTIFICATION, true),
                autoUpdateCheck = p.getBoolean(Prefs.AUTO_UPDATE_CHECK, false)
            )
        }

        internal fun encodeLongActions(actions: Map<LongTrigger, ActionBinding>): String {
            val long = JSONObject()
            LongTrigger.entries.forEach { long.put(it.name, (actions[it] ?: DEFAULT_LONG_ACTIONS.getValue(it)).toJson()) }
            return long.toString()
        }

        internal fun encodeCombos(combos: List<ShortCombo>): String {
            val a = JSONArray()
            combos.forEach { c ->
                a.put(JSONObject().put("keys", JSONArray(c.keys.map { it.name })).put("binding", c.binding.toJson()))
            }
            return a.toString()
        }

        internal fun parseLongActions(json: String?): Map<LongTrigger, ActionBinding> {
            val o = try { json?.let { JSONObject(it) } } catch (e: Exception) { null } ?: return DEFAULT_LONG_ACTIONS
            return LongTrigger.entries.associateWith { t ->
                val fallback = DEFAULT_LONG_ACTIONS.getValue(t)
                o.optJSONObject(t.name)?.let { ActionBinding.fromJson(it, fallback) } ?: fallback
            }
        }

        internal fun parseCombos(json: String?): List<ShortCombo> {
            val a = try { json?.let { JSONArray(it) } } catch (e: Exception) { null } ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val keysJson = o.optJSONArray("keys") ?: return@mapNotNull null
                val keys = (0 until keysJson.length()).mapNotNull { k -> VolumeKey.entries.firstOrNull { it.name == keysJson.optString(k) } }
                if (keys.isEmpty() || keys.size > ShortCombo.MAX_KEYS) return@mapNotNull null
                val binding = o.optJSONObject("binding")?.let { ActionBinding.fromJson(it, ActionBinding(true, MediaAction.NEXT)) }
                    ?: return@mapNotNull null
                ShortCombo(keys, binding)
            }.distinctBy { it.id }
        }
    }
}

/**
 * The players listed in Settings, kept across restarts so one can be switched off before it next
 * plays. The listener only notes players for the current session ([Players.detected]); they're
 * added here when Settings is opened.
 */
object SavedPlayers {
    fun load(context: Context): List<String> = decode(context.appPrefs().getString(Prefs.DETECTED_PLAYERS, null))

    fun save(context: Context, players: List<String>) {
        context.appPrefs().edit().putString(Prefs.DETECTED_PLAYERS, encode(players)).apply()
    }

    // One package name per line.
    internal fun decode(text: String?): List<String> =
        text?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()

    internal fun encode(players: List<String>): String = players.joinToString("\n")

    /** The saved players followed by the newly seen ones, each once, in the order they were first seen. */
    internal fun merge(saved: List<String>, seen: List<String>): List<String> = (saved + seen).distinct()
}

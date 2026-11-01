package com.dearminder.fossume

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

const val SPOTIFY_PACKAGE = "com.spotify.music"

/**
 * Media players seen in this session (since the process started) - every app that had a media
 * session or posted a media notification while notification access was granted. Fed by
 * [MediaNotificationListener]. Nothing here is saved: Settings adds these players to [SavedPlayers].
 */
object Players {
    private val _detected = MutableStateFlow<List<String>>(emptyList())
    /** Package names in the order they were first seen this session. */
    val detected: StateFlow<List<String>> = _detected

    private val _playing = MutableStateFlow<Set<String>>(emptySet())
    /** Packages whose session is playing right now. */
    val playing: StateFlow<Set<String>> = _playing

    /**
     * Each player's media notification buttons - a fallback for like / hide. Only the buttons are
     * kept: the notification itself carries the album art, which can be megabytes per player.
     */
    val buttons = ConcurrentHashMap<String, List<Notification.Action>>()

    // Main thread only: the session listener, the controller callbacks and the key events all
    // arrive there.
    private class Watched(val controller: MediaController, val callback: MediaController.Callback, var state: Int)

    /** Keyed by token; a callback must be unregistered on the same controller instance it was registered on. */
    private val watched = HashMap<MediaSession.Token, Watched>()
    /** Sessions in the system's priority order (the most recently active first). */
    private var order: List<MediaSession.Token> = emptyList()

    /** True while [MediaNotificationListener] is connected - the session cache is current then. */
    var tracking = false
        private set

    fun record(context: Context, pkg: String) {
        if (pkg == context.packageName || pkg in _detected.value) return
        _detected.value = _detected.value + pkg
    }

    fun onSessions(context: Context, controllers: List<MediaController>) {
        tracking = true
        val tokens = controllers.mapTo(HashSet()) { it.sessionToken }
        watched.keys.filter { it !in tokens }.forEach { token ->
            watched.remove(token)?.let { it.controller.unregisterCallback(it.callback) }
        }
        controllers.forEach { c ->
            record(context, c.packageName)
            val token = c.sessionToken
            if (token !in watched) {
                // States are cached from the callbacks, so picking a player needs no IPC per session.
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        watched[token]?.state = state?.state ?: PlaybackState.STATE_NONE
                        refreshPlaying()
                    }
                }
                c.registerCallback(cb)
                watched[token] = Watched(c, cb, c.playbackState?.state ?: PlaybackState.STATE_NONE)
            }
        }
        order = controllers.map { it.sessionToken }
        refreshPlaying()
    }

    fun clearSessions() {
        tracking = false
        watched.values.forEach { it.controller.unregisterCallback(it.callback) }
        watched.clear()
        order = emptyList()
        refreshPlaying()
    }

    /** The player the keys should control: a playing one first, else the most recently active one. */
    fun current(disabled: Set<String>): MediaController? {
        val allowed = order.mapNotNull { watched[it] }.filter { it.controller.packageName !in disabled }
        return (allowed.firstOrNull { it.state == PlaybackState.STATE_PLAYING } ?: allowed.firstOrNull())?.controller
    }

    private fun refreshPlaying() {
        _playing.value = watched.values.filter { it.state == PlaybackState.STATE_PLAYING }
            .mapTo(HashSet()) { it.controller.packageName }
    }
}

// ---------- Acting on the current player ----------

object MediaControl {

    /** The player to act on, or null if there's none (or no notification access). */
    fun controller(context: Context, settings: AppSettings): MediaController? {
        if (Players.tracking) return Players.current(settings.disabledPlayers)
        // The listener isn't connected (yet) - ask the system directly. This throws without
        // notification access, which is cheaper than checking for it on every key press.
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
        val sessions = try {
            manager.getActiveSessions(ComponentName(context, MediaNotificationListener::class.java))
        } catch (e: SecurityException) {
            return null
        }
        val allowed = sessions.filter { it.packageName !in settings.disabledPlayers }
        return allowed.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: allowed.firstOrNull()
    }

    /** Returns whether the player was asked to do something (it may still ignore it). */
    fun perform(controller: MediaController, binding: ActionBinding): Boolean {
        val controls = controller.transportControls
        val state = controller.playbackState
        return when (binding.action) {
            MediaAction.NEXT -> { controls.skipToNext(); true }
            MediaAction.PREVIOUS -> { controls.skipToPrevious(); true }
            MediaAction.FORWARD -> seekBy(controller, binding.seconds * 1000L)
            MediaAction.REWIND -> seekBy(controller, -binding.seconds * 1000L)
            MediaAction.PLAY_PAUSE -> {
                if (state?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
                true
            }
            MediaAction.LIKE -> rate(controller, liked = true)
            MediaAction.UNLIKE -> rate(controller, liked = false)
            MediaAction.HIDE -> controller.packageName == SPOTIFY_PACKAGE && triggerButton(controller, ButtonKind.HIDE)
        }
    }

    private fun seekBy(controller: MediaController, deltaMs: Long): Boolean {
        val state = controller.playbackState ?: return false
        if (state.actions and PlaybackState.ACTION_SEEK_TO == 0L) {
            // No precise seeking - the player's own skip step is the next best thing.
            if (deltaMs > 0) controller.transportControls.fastForward() else controller.transportControls.rewind()
            return true
        }
        val target = seekTarget(
            positionMs = state.position,
            lastUpdateMs = state.lastPositionUpdateTime,
            nowMs = SystemClock.elapsedRealtime(),
            speed = state.playbackSpeed,
            playing = state.state == PlaybackState.STATE_PLAYING,
            deltaMs = deltaMs,
            durationMs = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        )
        controller.transportControls.seekTo(target)
        return true
    }

    /**
     * Players differ a lot here: Spotify and many others expose a custom "like" button, some only
     * have it in their notification, and a few take a standard rating.
     */
    private fun rate(controller: MediaController, liked: Boolean): Boolean {
        if (triggerButton(controller, if (liked) ButtonKind.LIKE else ButtonKind.UNLIKE)) return true
        val rating = when (controller.ratingType) {
            Rating.RATING_HEART -> Rating.newHeartRating(liked)
            Rating.RATING_THUMB_UP_DOWN -> Rating.newThumbRating(liked)
            else -> return false
        }
        controller.transportControls.setRating(rating)
        return true
    }

    /**
     * Presses the player's own button of that kind - a session custom action, else a notification
     * button. A like button that currently shows "unlike" means the track is already liked, which
     * also counts as done.
     */
    private fun triggerButton(controller: MediaController, kind: ButtonKind): Boolean {
        val opposite = when (kind) {
            ButtonKind.LIKE -> ButtonKind.UNLIKE
            ButtonKind.UNLIKE -> ButtonKind.LIKE
            ButtonKind.HIDE -> null
        }
        val custom = controller.playbackState?.customActions.orEmpty()
        custom.firstOrNull { classifyButton(customActionText(it.action, it.name)) == kind }?.let {
            controller.transportControls.sendCustomAction(it, null)
            return true
        }
        val buttons = Players.buttons[controller.packageName].orEmpty()
        buttons.firstOrNull { classifyButton(it.title?.toString().orEmpty()) == kind }?.let {
            return try { it.actionIntent?.send(); true } catch (e: Exception) { false }
        }
        if (opposite == null) return false
        return custom.any { classifyButton(customActionText(it.action, it.name)) == opposite } ||
            buttons.any { classifyButton(it.title?.toString().orEmpty()) == opposite }
    }
}

// ---------- Vibration ----------

private const val VIBRATION_MS = 40L

/** A short buzz confirming an action, [strength] from 0 (none) to 100. */
fun vibrate(context: Context, strength: Int) {
    if (strength <= 0) return
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    } ?: return
    if (!vibrator.hasVibrator()) return
    val effect = if (vibrator.hasAmplitudeControl()) {
        VibrationEffect.createOneShot(VIBRATION_MS, (strength * 255 / 100).coerceIn(1, 255))
    } else {
        // Without amplitude control the length is all there is to play with.
        VibrationEffect.createOneShot(10L + strength * VIBRATION_MS * 2 / 100, VibrationEffect.DEFAULT_AMPLITUDE)
    }
    // Media usage: it follows the "media vibration" setting, not touch feedback (often off), and
    // still plays with the screen off.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
    }
}

package com.dearminder.fossume

import android.content.Context
import android.content.Intent
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.view.KeyEvent

/**
 * The volume keys with the display fully off. Android gives them to no app then, not even to an
 * accessibility service (Always On Display is different - there the keys still reach
 * [VolumeKeyService]). They only go to the media session in charge of the volume: the active one
 * that most recently started playing. So while the screen is off, this session of ours stands in
 * front of the players with a remote volume - each press arrives at [onAdjust] as a direction
 * (+1 / -1), each release as 0.
 *
 * It plays nothing and posts no notification, so it doesn't show up anywhere. Main thread only.
 */
class ScreenOffSession(
    context: Context,
    onAdjust: (direction: Int) -> Unit,
    onMediaButton: (KeyEvent) -> Unit
) {
    private val session = MediaSession(context, "FOSSume").apply {
        setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) = onAdjust(direction)
        })
        // Also what delivers onAdjustVolume - without a callback the session drops it.
        setCallback(object : MediaSession.Callback() {
            // Older Android versions may route headset buttons to the session in front - they belong to the player.
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                event?.let(onMediaButton)
                return true
            }
        })
    }

    var active = false
        private set

    fun start() {
        if (active) return
        active = true
        session.isActive = true
        claim()
    }

    fun stop() {
        if (!active) return
        active = false
        session.setPlaybackState(state(PlaybackState.STATE_STOPPED))
        session.isActive = false
    }

    /** Steps in front of the players again - each one that starts playing (or skips) takes the keys back. */
    fun claim() {
        if (!active) return
        // Only a change into playing counts as starting.
        session.setPlaybackState(state(PlaybackState.STATE_PAUSED))
        session.setPlaybackState(state(PlaybackState.STATE_PLAYING))
    }

    fun release() {
        active = false
        session.release()
    }

    private fun state(state: Int) = PlaybackState.Builder().setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f).build()
}

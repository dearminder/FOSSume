package com.dearminder.fossume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaRulesTest {

    @Test
    fun likeButtonsAreRecognized() {
        listOf(
            "com.spotify.music.ADD_TO_COLLECTION Add to Liked Songs",
            "ACTION_LIKE Like",
            "favorite",
            "THUMBS_UP",
            "Polub",
            "Dodaj do ulubionych",
            "Save"
        ).forEach { assertEquals(it, ButtonKind.LIKE, classifyButton(it)) }
    }

    @Test
    fun unlikeIsCheckedBeforeLike() {
        listOf(
            "com.spotify.music.REMOVE_FROM_COLLECTION Remove from Liked Songs",
            "ACTION_UNLIKE Unlike",
            "Unfavorite",
            "Usuń z ulubionych",
            "usun z polubionych"
        ).forEach { assertEquals(it, ButtonKind.UNLIKE, classifyButton(it)) }
    }

    @Test
    fun hideButtonsAreRecognized() {
        assertEquals(ButtonKind.HIDE, classifyButton("Hide song"))
        assertEquals(ButtonKind.HIDE, classifyButton("Ukryj utwór"))
        assertEquals(ButtonKind.HIDE, classifyButton("BAN"))
        assertEquals(ButtonKind.HIDE, classifyButton("ban_track"))
    }

    @Test
    fun banOnlyCountsAsAWholeWord() {
        assertNull(classifyButton("Urban radio"))
        assertNull(classifyButton("banner"))
    }

    @Test
    fun theActionIdsPackagePrefixIsIgnored() {
        assertEquals("ADD_TO_COLLECTION Like", customActionText("com.spotify.music.ADD_TO_COLLECTION", "Like"))
        assertEquals("SHUFFLE ", customActionText("SHUFFLE", null))
        assertNull(classifyButton(customActionText("com.savemusic.player.SHUFFLE", "Shuffle")))
        assertNull(classifyButton(customActionText("com.urbanradio.REPEAT", "Repeat")))
        assertEquals(ButtonKind.LIKE, classifyButton(customActionText("com.savemusic.player.THUMB_UP", "Thumb up")))
    }

    @Test
    fun otherButtonsAreLeftAlone() {
        listOf("Play", "Pause", "Next", "Previous", "Shuffle", "Repeat", "").forEach { assertNull(it, classifyButton(it)) }
    }

    @Test
    fun matchingIgnoresCase() {
        assertEquals(ButtonKind.LIKE, classifyButton("LIKE"))
        assertEquals(ButtonKind.UNLIKE, classifyButton("UNLIKE"))
    }

    // ---------- Seeking ----------

    private fun seek(
        position: Long = 60_000,
        lastUpdate: Long = 1_000,
        now: Long = 1_000,
        speed: Float = 1f,
        playing: Boolean = true,
        delta: Long = 10_000,
        duration: Long = 180_000
    ) = seekTarget(position, lastUpdate, now, speed, playing, delta, duration)

    @Test
    fun seekMovesByTheDelta() {
        assertEquals(70_000L, seek())
        assertEquals(50_000L, seek(delta = -10_000))
    }

    @Test
    fun seekCountsTheTimePlayedSinceTheSnapshot() {
        assertEquals(75_000L, seek(lastUpdate = 1_000, now = 6_000))
        // At double speed twice as much has played.
        assertEquals(80_000L, seek(lastUpdate = 1_000, now = 6_000, speed = 2f))
    }

    @Test
    fun aPausedPositionDoesNotMove() {
        assertEquals(70_000L, seek(lastUpdate = 1_000, now = 60_000, playing = false))
    }

    @Test
    fun anUnknownSnapshotTimeIsTakenAsIs() {
        assertEquals(70_000L, seek(lastUpdate = 0, now = 60_000))
    }

    @Test
    fun seekStaysWithinTheTrack() {
        assertEquals(0L, seek(position = 3_000, delta = -10_000))
        assertEquals(180_000L, seek(position = 175_000, delta = 10_000))
    }

    @Test
    fun anUnknownDurationOnlyLimitsTheStart() {
        assertEquals(1_010_000L, seek(position = 1_000_000, duration = 0))
        assertEquals(0L, seek(position = 0, delta = -5_000, duration = -1))
    }
}

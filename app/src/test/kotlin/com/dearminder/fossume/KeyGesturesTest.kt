package com.dearminder.fossume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyGesturesTest {

    private val actions = mutableListOf<MediaAction>()
    private val volume = mutableListOf<VolumeKey>()
    private var handle = true
    private var settings = AppSettings(
        activation = Activation.ALWAYS,
        vibration = 0,
        longPressMs = 400,
        clickGapMs = 300,
        longActions = DEFAULT_LONG_ACTIONS,
        shortCombos = emptyList(),
        disabledPlayers = emptySet(),
        statusNotification = false
    )

    // A fake clock: timers only run when the test moves time forward.
    private class Timer(val at: Long, val block: () -> Unit) { var cancelled = false }
    private var now = 0L
    private val timers = mutableListOf<Timer>()

    private val gestures = KeyGestures(
        settings = { settings },
        shouldHandle = { handle },
        scheduler = { delay, block ->
            val t = Timer(now + delay, block)
            timers += t
            ({ t.cancelled = true })
        },
        onAction = { actions += it.action },
        onVolume = { volume += it }
    )

    private fun advance(ms: Long) {
        val until = now + ms
        while (true) {
            val next = timers.filter { !it.cancelled && it.at <= until }.minByOrNull { it.at } ?: break
            timers.remove(next)
            now = next.at
            next.block()
        }
        now = until
    }

    private fun press(key: VolumeKey, holdMs: Long = 50, gapMs: Long = 50) {
        assertTrue(gestures.onKeyDown(key, 0))
        advance(holdMs)
        assertTrue(gestures.onKeyUp(key))
        advance(gapMs)
    }

    private fun combo(vararg keys: VolumeKey, action: MediaAction, enabled: Boolean = true) =
        ShortCombo(keys.toList(), ActionBinding(enabled, action))

    @Test
    fun shortPressGivesTheVolumeStepBack() {
        press(VolumeKey.UP)
        assertEquals(listOf(VolumeKey.UP), volume)
        assertEquals(emptyList<MediaAction>(), actions)
    }

    @Test
    fun longPressFiresWhileStillHeld() {
        gestures.onKeyDown(VolumeKey.UP, 0)
        advance(399)
        assertEquals(emptyList<MediaAction>(), actions)
        advance(1)
        assertEquals(listOf(MediaAction.NEXT), actions)
        // Auto-repeat and the release belong to the same hold.
        assertTrue(gestures.onKeyDown(VolumeKey.UP, 5))
        assertTrue(gestures.onKeyUp(VolumeKey.UP))
        assertEquals(emptyList<VolumeKey>(), volume)
    }

    @Test
    fun holdingBothFiresTheBothAction() {
        gestures.onKeyDown(VolumeKey.UP, 0)
        advance(150)
        gestures.onKeyDown(VolumeKey.DOWN, 0)
        advance(400)
        gestures.onKeyUp(VolumeKey.UP)
        gestures.onKeyUp(VolumeKey.DOWN)
        advance(1000)
        assertEquals(listOf(MediaAction.PLAY_PAUSE), actions)
        assertEquals(emptyList<VolumeKey>(), volume)
    }

    @Test
    fun comboFiresAtOnceWhenNothingLongerStartsWithIt() {
        settings = settings.copy(shortCombos = listOf(combo(VolumeKey.UP, VolumeKey.UP, action = MediaAction.NEXT)))
        press(VolumeKey.UP, gapMs = 0)
        press(VolumeKey.UP, gapMs = 0)
        assertEquals(listOf(MediaAction.NEXT), actions)
        assertEquals(emptyList<VolumeKey>(), volume)
    }

    @Test
    fun shorterComboWaitsForTheGap() {
        settings = settings.copy(
            shortCombos = listOf(
                combo(VolumeKey.UP, action = MediaAction.PLAY_PAUSE),
                combo(VolumeKey.UP, VolumeKey.UP, action = MediaAction.NEXT)
            )
        )
        press(VolumeKey.UP, gapMs = 0)
        assertEquals(emptyList<MediaAction>(), actions)
        advance(300)
        assertEquals(listOf(MediaAction.PLAY_PAUSE), actions)
    }

    @Test
    fun clicksThatMatchNothingBecomeVolumeSteps() {
        settings = settings.copy(shortCombos = listOf(combo(VolumeKey.UP, VolumeKey.DOWN, action = MediaAction.NEXT)))
        press(VolumeKey.UP)
        press(VolumeKey.UP)
        assertEquals(listOf(VolumeKey.UP, VolumeKey.UP), volume)
        assertEquals(emptyList<MediaAction>(), actions)
    }

    @Test
    fun unfinishedComboTimesOutAsVolume() {
        settings = settings.copy(shortCombos = listOf(combo(VolumeKey.UP, VolumeKey.DOWN, action = MediaAction.NEXT)))
        press(VolumeKey.UP, gapMs = 0)
        assertEquals(emptyList<VolumeKey>(), volume)
        advance(300)
        assertEquals(listOf(VolumeKey.UP), volume)
    }

    @Test
    fun disabledComboIsIgnored() {
        settings = settings.copy(shortCombos = listOf(combo(VolumeKey.UP, VolumeKey.UP, action = MediaAction.NEXT, enabled = false)))
        press(VolumeKey.UP)
        press(VolumeKey.UP)
        assertEquals(listOf(VolumeKey.UP, VolumeKey.UP), volume)
        assertEquals(emptyList<MediaAction>(), actions)
    }

    @Test
    fun keysPassThroughWhenNotHandled() {
        handle = false
        assertFalse(gestures.onKeyDown(VolumeKey.UP, 0))
        advance(1000)
        assertFalse(gestures.onKeyDown(VolumeKey.UP, 3))
        assertFalse(gestures.onKeyUp(VolumeKey.UP))
        assertEquals(emptyList<MediaAction>(), actions)
        assertEquals(emptyList<VolumeKey>(), volume)
    }

    @Test
    fun keyWithNothingToDoIsLeftToTheSystem() {
        settings = settings.copy(
            longActions = LongTrigger.entries.associateWith { ActionBinding(false, MediaAction.NEXT) },
            shortCombos = listOf(combo(VolumeKey.UP, VolumeKey.UP, action = MediaAction.NEXT))
        )
        assertFalse(gestures.onKeyDown(VolumeKey.DOWN, 0))
        assertFalse(gestures.onKeyUp(VolumeKey.DOWN))
        press(VolumeKey.UP, gapMs = 0)
        press(VolumeKey.UP)
        assertEquals(listOf(MediaAction.NEXT), actions)
    }

    @Test
    fun clicksBeforeALongPressAreSettledFirst() {
        settings = settings.copy(shortCombos = listOf(combo(VolumeKey.UP, VolumeKey.DOWN, action = MediaAction.PLAY_PAUSE)))
        press(VolumeKey.UP, gapMs = 0)
        gestures.onKeyDown(VolumeKey.UP, 0)
        advance(400)
        gestures.onKeyUp(VolumeKey.UP)
        assertEquals(listOf(VolumeKey.UP), volume)
        assertEquals(listOf(MediaAction.NEXT), actions)
    }

    @Test
    fun longPressDownFiresItsOwnAction() {
        gestures.onKeyDown(VolumeKey.DOWN, 0)
        advance(400)
        gestures.onKeyUp(VolumeKey.DOWN)
        assertEquals(listOf(MediaAction.PREVIOUS), actions)
        assertEquals(emptyList<VolumeKey>(), volume)
    }

    @Test
    fun holdingBothWithBothOffIsJustTwoClicks() {
        settings = settings.copy(longActions = DEFAULT_LONG_ACTIONS + (LongTrigger.BOTH to ActionBinding(false, MediaAction.PLAY_PAUSE)))
        gestures.onKeyDown(VolumeKey.UP, 0)
        advance(100)
        gestures.onKeyDown(VolumeKey.DOWN, 0)
        advance(1000)
        gestures.onKeyUp(VolumeKey.DOWN)
        gestures.onKeyUp(VolumeKey.UP)
        advance(1000)
        // The second key cancelled the first one's long press, and nothing replaced it.
        assertEquals(emptyList<MediaAction>(), actions)
        assertEquals(listOf(VolumeKey.DOWN, VolumeKey.UP), volume)
    }

    @Test
    fun aStartedComboIsFinishedEvenIfTheKeysStopBeingHandled() {
        settings = settings.copy(shortCombos = listOf(combo(VolumeKey.UP, VolumeKey.UP, action = MediaAction.NEXT)))
        press(VolumeKey.UP, gapMs = 0)
        // E.g. the screen came on halfway through.
        handle = false
        press(VolumeKey.UP)
        assertEquals(listOf(MediaAction.NEXT), actions)
    }

    @Test
    fun resetDropsWhatIsInProgress() {
        settings = settings.copy(shortCombos = listOf(combo(VolumeKey.UP, VolumeKey.UP, action = MediaAction.NEXT)))
        press(VolumeKey.UP, gapMs = 0)
        gestures.onKeyDown(VolumeKey.DOWN, 0)
        gestures.reset()
        advance(1000)
        assertEquals(emptyList<MediaAction>(), actions)
        assertEquals(emptyList<VolumeKey>(), volume)
        // The release of a key held across the reset isn't ours any more.
        assertFalse(gestures.onKeyUp(VolumeKey.DOWN))
        press(VolumeKey.UP, gapMs = 0)
        press(VolumeKey.UP)
        assertEquals(listOf(MediaAction.NEXT), actions)
    }

    @Test
    fun aReleaseWithoutAPressIsLeftToTheSystem() {
        assertFalse(gestures.onKeyUp(VolumeKey.UP))
        assertEquals(emptyList<VolumeKey>(), volume)
    }

    @Test
    fun aPassedKeyStaysPassedUntilReleased() {
        handle = false
        assertFalse(gestures.onKeyDown(VolumeKey.UP, 0))
        // Being handled again mid-press doesn't take over a press the system already has.
        handle = true
        assertFalse(gestures.onKeyDown(VolumeKey.UP, 1))
        assertFalse(gestures.onKeyUp(VolumeKey.UP))
        press(VolumeKey.UP)
        assertEquals(listOf(VolumeKey.UP), volume)
    }
}

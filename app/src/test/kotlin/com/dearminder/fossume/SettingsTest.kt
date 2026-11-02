package com.dearminder.fossume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    private val base = AppSettings(
        activation = Activation.LOCKED,
        vibration = 50,
        longPressMs = 400,
        clickGapMs = 350,
        longActions = DEFAULT_LONG_ACTIONS,
        shortCombos = emptyList(),
        disabledPlayers = emptySet(),
        statusNotification = true
    )

    @Test
    fun longActionsSurviveARoundTrip() {
        val actions = mapOf(
            LongTrigger.UP to ActionBinding(true, MediaAction.FORWARD, 30),
            LongTrigger.DOWN to ActionBinding(false, MediaAction.REWIND, 5),
            LongTrigger.BOTH to ActionBinding(true, MediaAction.LIKE)
        )
        assertEquals(actions, AppSettings.parseLongActions(AppSettings.encodeLongActions(actions)))
    }

    @Test
    fun missingLongActionsAreSavedAsTheDefaults() {
        val parsed = AppSettings.parseLongActions(AppSettings.encodeLongActions(emptyMap()))
        assertEquals(DEFAULT_LONG_ACTIONS, parsed)
    }

    @Test
    fun brokenLongActionsFallBackToTheDefaults() {
        assertEquals(DEFAULT_LONG_ACTIONS, AppSettings.parseLongActions(null))
        assertEquals(DEFAULT_LONG_ACTIONS, AppSettings.parseLongActions("not json"))
        assertEquals(DEFAULT_LONG_ACTIONS, AppSettings.parseLongActions("{}"))
    }

    @Test
    fun unknownActionAndOutOfRangeSecondsAreRepaired() {
        val parsed = AppSettings.parseLongActions(
            """{"UP":{"enabled":false,"action":"TELEPORT","seconds":99999},"DOWN":{"action":"REWIND","seconds":0}}"""
        )
        val up = parsed.getValue(LongTrigger.UP)
        assertFalse(up.enabled)
        assertEquals(DEFAULT_LONG_ACTIONS.getValue(LongTrigger.UP).action, up.action)
        assertEquals(ActionBinding.MAX_SECONDS, up.seconds)
        val down = parsed.getValue(LongTrigger.DOWN)
        assertEquals(MediaAction.REWIND, down.action)
        assertEquals(ActionBinding.MIN_SECONDS, down.seconds)
        // Fields that are missing keep the default's values.
        assertTrue(down.enabled)
        assertEquals(DEFAULT_LONG_ACTIONS.getValue(LongTrigger.BOTH), parsed.getValue(LongTrigger.BOTH))
    }

    @Test
    fun combosSurviveARoundTrip() {
        val combos = listOf(
            ShortCombo(listOf(VolumeKey.UP, VolumeKey.UP), ActionBinding(true, MediaAction.NEXT)),
            ShortCombo(listOf(VolumeKey.DOWN, VolumeKey.UP, VolumeKey.DOWN), ActionBinding(false, MediaAction.FORWARD, 15))
        )
        assertEquals(combos, AppSettings.parseCombos(AppSettings.encodeCombos(combos)))
    }

    @Test
    fun invalidCombosAreDropped() {
        val json = """[
            {"keys":["UP","UP"],"binding":{"enabled":true,"action":"NEXT"}},
            {"keys":["UP","UP"],"binding":{"enabled":true,"action":"PREVIOUS"}},
            {"keys":[],"binding":{"enabled":true,"action":"NEXT"}},
            {"keys":["UP","UP","UP","UP","UP","UP","UP"],"binding":{"enabled":true,"action":"NEXT"}},
            {"keys":["DOWN"]},
            {"keys":["SIDEWAYS","DOWN"],"binding":{"enabled":true,"action":"PLAY_PAUSE"}},
            "garbage"
        ]"""
        val parsed = AppSettings.parseCombos(json)
        assertEquals(listOf("UP-UP", "DOWN"), parsed.map { it.id })
        // The first of two duplicates wins.
        assertEquals(MediaAction.NEXT, parsed[0].binding.action)
        assertEquals(MediaAction.PLAY_PAUSE, parsed[1].binding.action)
    }

    @Test
    fun brokenCombosGiveAnEmptyList() {
        assertEquals(emptyList<ShortCombo>(), AppSettings.parseCombos(null))
        assertEquals(emptyList<ShortCombo>(), AppSettings.parseCombos("{"))
    }

    @Test
    fun aKeyIsUsedByItsLongPressBothOrACombo() {
        val off = LongTrigger.entries.associateWith { ActionBinding(false, MediaAction.NEXT) }
        val none = base.copy(longActions = off)
        assertFalse(none.uses(VolumeKey.UP))
        assertFalse(none.uses(VolumeKey.DOWN))

        val upOnly = base.copy(longActions = off + (LongTrigger.UP to ActionBinding(true, MediaAction.NEXT)))
        assertTrue(upOnly.uses(VolumeKey.UP))
        assertFalse(upOnly.uses(VolumeKey.DOWN))

        val both = base.copy(longActions = off + (LongTrigger.BOTH to ActionBinding(true, MediaAction.PLAY_PAUSE)))
        assertTrue(both.uses(VolumeKey.UP))
        assertTrue(both.uses(VolumeKey.DOWN))

        val combo = base.copy(
            longActions = off,
            shortCombos = listOf(
                ShortCombo(listOf(VolumeKey.DOWN, VolumeKey.DOWN), ActionBinding(true, MediaAction.NEXT)),
                ShortCombo(listOf(VolumeKey.UP), ActionBinding(false, MediaAction.NEXT))
            )
        )
        assertTrue(combo.uses(VolumeKey.DOWN))
        // A switched-off combination doesn't count.
        assertFalse(combo.uses(VolumeKey.UP))
    }

    @Test
    fun aMissingLongActionFallsBackToTheDefault() {
        val partial = base.copy(longActions = mapOf(LongTrigger.UP to ActionBinding(false, MediaAction.LIKE)))
        assertEquals(DEFAULT_LONG_ACTIONS.getValue(LongTrigger.DOWN), partial.longAction(LongTrigger.DOWN))
        assertEquals(ActionBinding(false, MediaAction.LIKE), partial.longAction(LongTrigger.UP))
    }

    // ---------- Saved players ----------

    @Test
    fun savedPlayersSurviveARoundTrip() {
        val players = listOf("com.spotify.music", "com.maxmpz.audioplayer")
        assertEquals(players, SavedPlayers.decode(SavedPlayers.encode(players)))
    }

    @Test
    fun savedPlayersSkipBlankLinesAndRepeats() {
        assertEquals(emptyList<String>(), SavedPlayers.decode(null))
        assertEquals(emptyList<String>(), SavedPlayers.decode(""))
        assertEquals(listOf("a.b", "c.d"), SavedPlayers.decode("a.b\n\n  \nc.d\na.b\n"))
    }

    @Test
    fun sessionPlayersAreAddedAfterTheSavedOnes() {
        val saved = listOf("com.spotify.music", "org.videolan.vlc")
        val seen = listOf("org.videolan.vlc", "com.maxmpz.audioplayer")
        assertEquals(
            listOf("com.spotify.music", "org.videolan.vlc", "com.maxmpz.audioplayer"),
            SavedPlayers.merge(saved, seen)
        )
        // Nothing new: the list stays the same, so Settings doesn't write it again.
        assertEquals(saved, SavedPlayers.merge(saved, listOf("com.spotify.music")))
        assertEquals(seen, SavedPlayers.merge(emptyList(), seen))
    }
}

package com.dearminder.fossume

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.LocaleManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Receives the volume keys before the system does (the accessibility key filter) and hands them
 * to [KeyGestures]. Keys it doesn't need right now - wrong screen state, a call, no player - pass
 * straight through untouched.
 */
class VolumeKeyService : AccessibilityService() {

    companion object {
        const val STATUS_CHANNEL = "status"
        private const val STATUS_ID = 1
        /** How soon after [onKeyEvent] let a press through it may reach [ScreenOffSession] as well. */
        private const val PASSED_WINDOW_MS = 500L

        private val _connected = MutableStateFlow(false)
        /** Whether the system has actually bound the service (it's enabled and running). */
        val connected: StateFlow<Boolean> = _connected
    }

    private val handler = Handler(Looper.getMainLooper())
    // Looked up once - they're needed on every key press.
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private lateinit var settings: AppSettings
    private lateinit var gestures: KeyGestures
    private var foreground = false
    private var screenOff: ScreenOffSession? = null
    /** Keys pressed with the display fully off, in the order they went down - a release doesn't say which key it was. */
    private val screenOffHeld = mutableListOf<VolumeKey>()
    /** When [onKeyEvent] last let each key's press through to the system. */
    private val passedAt = mutableMapOf<VolumeKey, Long>()
    // With the screen off the CPU may fall asleep mid-gesture, stalling the long press and click timers.
    private val wakeLock by lazy {
        power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FOSSume:keys")?.apply { setReferenceCounted(false) }
    }

    // Kept in a field: SharedPreferences only holds its listeners weakly.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // The player list and the update check time share the file but aren't settings - no need
        // to re-parse everything.
        if (key == Prefs.DETECTED_PLAYERS || key == Prefs.LAST_UPDATE_CHECK) return@OnSharedPreferenceChangeListener
        settings = AppSettings.load(this)
        if (key == Prefs.STATUS_NOTIFICATION) updateStatusNotification()
        if (key == Prefs.SCREEN_OFF_KEYS) updateScreenOffSession()
    }

    // Sent to the app when its language changes in system Settings → Apps → FOSSume → Language,
    // and when the phone's language changes.
    private val localeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateStatusNotification()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateScreenOffSession()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = AppSettings.load(this)
        appPrefs().registerOnSharedPreferenceChangeListener(prefsListener)
        ContextCompat.registerReceiver(this, localeReceiver, IntentFilter(Intent.ACTION_LOCALE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screenReceiver, screenFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        Players.onSessionToFront = { screenOff?.claim() }
        gestures = KeyGestures(
            settings = { settings },
            shouldHandle = ::shouldHandle,
            scheduler = { delayMs, block ->
                val r = Runnable { block() }
                handler.postDelayed(r, delayMs)
                ({ handler.removeCallbacks(r) })
            },
            onAction = ::runAction,
            onVolume = ::stepVolume
        )
        _connected.value = true
        updateStatusNotification()
        updateScreenOffSession()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!::gestures.isInitialized) return false
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> VolumeKey.UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeKey.DOWN
            else -> return false
        }
        keepAwake()
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> gestures.onKeyDown(key, event.repeatCount).also {
                if (!it && event.repeatCount == 0) passedAt[key] = SystemClock.uptimeMillis()
            }
            KeyEvent.ACTION_UP -> gestures.onKeyUp(key)
            else -> false
        }
    }

    /** A volume key with the display fully off, through [ScreenOffSession]: +1 / -1 pressed, 0 released. */
    private fun onScreenOffKey(direction: Int) {
        if (!::gestures.isInitialized) return
        keepAwake()
        if (direction == 0) {
            screenOffHeld.removeFirstOrNull()?.let { gestures.onKeyUp(it) }
            return
        }
        val key = if (direction > 0) VolumeKey.UP else VolumeKey.DOWN
        // onKeyEvent already had this press (Always On Display) and let it through - just a volume step.
        val passed = passedAt.remove(key)
        if (passed != null && SystemClock.uptimeMillis() - passed < PASSED_WINDOW_MS) {
            directVolume(key)
            return
        }
        screenOffHeld += key
        if (!gestures.onKeyDown(key, 0)) directVolume(key)
    }

    /** Takes [ScreenOffSession] in front of the players while the screen is off, and away when it comes on. */
    private fun updateScreenOffSession() {
        val wanted = _connected.value && settings.screenOffKeys && power?.isInteractive == false
        if (wanted) {
            val session = screenOff ?: ScreenOffSession(this, ::onScreenOffKey) { event ->
                MediaControl.controller(this, settings)?.dispatchMediaButtonEvent(event)
            }.also { screenOff = it }
            session.start()
        } else {
            screenOff?.stop()
            // A release that never came must not hold a key forever.
            screenOffHeld.clear()
        }
    }

    private fun keepAwake() {
        if (power?.isInteractive == false) {
            wakeLock?.acquire(maxOf(settings.longPressMs, settings.clickGapMs) + 1000L)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The notification was built in the old language.
        if (_connected.value) updateStatusNotification()
    }

    override fun onInterrupt() {
        if (::gestures.isInitialized) gestures.reset()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    private fun shutDown() {
        if (!_connected.value) return
        _connected.value = false
        if (::gestures.isInitialized) gestures.reset()
        appPrefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
        try {
            unregisterReceiver(localeReceiver)
            unregisterReceiver(screenReceiver)
        } catch (e: IllegalArgumentException) {
            // Never registered - the service was torn down before it connected.
        }
        Players.onSessionToFront = null
        screenOff?.release()
        screenOff = null
        screenOffHeld.clear()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        removeStatusNotification()
    }

    private fun shouldHandle(): Boolean {
        if (settings.activation == Activation.LOCKED && !isLocked()) return false
        // During a call (or while it rings) the keys belong to the call.
        if (audio?.mode != AudioManager.MODE_NORMAL) return false
        // A ringing alarm is snoozed or silenced with the volume keys.
        if (isAlarmPlaying()) return false
        return MediaControl.controller(this, settings) != null
    }

    /**
     * Screen off, or the lock screen showing. The screen alone isn't enough: a notification, the
     * pocket sensor or - on many ROMs - the volume key press itself turns it on before the key
     * reaches us, while the phone is still locked.
     */
    private fun isLocked(): Boolean = power?.isInteractive == false || keyguard?.isKeyguardLocked == true

    private fun isAlarmPlaying(): Boolean = try {
        audio?.activePlaybackConfigurations.orEmpty().any { it.audioAttributes.usage == AudioAttributes.USAGE_ALARM }
    } catch (e: Exception) {
        false
    }

    private fun runAction(binding: ActionBinding) {
        // Looked up again: the gesture may have taken a while, and players come and go.
        val controller = MediaControl.controller(this, settings) ?: return
        if (MediaControl.perform(controller, binding)) vibrate(this, settings.vibration)
    }

    /** What the press would have done without us. */
    private fun stepVolume(key: VolumeKey) {
        // The usual way goes through the media sessions - with ours in front it would come straight back.
        if (screenOff?.active == true) {
            directVolume(key)
            return
        }
        val audio = audio ?: return
        val screenOn = power?.isInteractive == true
        audio.adjustSuggestedStreamVolume(
            if (key == VolumeKey.UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.USE_DEFAULT_STREAM_TYPE,
            if (screenOn) AudioManager.FLAG_SHOW_UI else 0
        )
    }

    /** A volume step that bypasses [ScreenOffSession]: the call's volume during a call, else the player's. */
    private fun directVolume(key: VolumeKey) {
        val audio = audio ?: return
        val direction = if (key == VolumeKey.UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        if (audio.mode != AudioManager.MODE_NORMAL) {
            audio.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, direction, 0)
            return
        }
        // The player's own volume - it may be playing on a remote device (Cast, Spotify Connect).
        val player = MediaControl.controller(this, settings)
        if (player != null) player.adjustVolume(direction, 0) else audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
    }

    // ---------- Status notification ----------

    /**
     * Shown as a foreground service, which keeps the process from being frozen while the screen
     * is off on aggressive vendor ROMs. Hiding its channel keeps that benefit.
     */
    private fun updateStatusNotification() {
        if (!settings.statusNotification) {
            removeStatusNotification()
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val text = localized()
        // Re-creating an existing channel only updates its name and description, e.g. to a new language.
        manager.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL, text.getString(R.string.status_channel_name), NotificationManager.IMPORTANCE_MIN).apply {
                description = text.getString(R.string.status_channel_description)
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(text.getString(R.string.status_notification_title))
            .setContentText(text.getString(R.string.status_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(STATUS_ID, notification)
            }
            foreground = true
        } catch (e: Exception) {
            // Not allowed right now (background start limits) - a plain notification still informs.
            if (areNotificationsAllowed(this)) manager.notify(STATUS_ID, notification)
        }
    }

    /**
     * Strings in the app's language. Android 13+ lets it differ from the phone's (system Settings →
     * Apps → FOSSume → Language), but only recreates activities for it - a service already running,
     * like this one, can keep the old language until the process restarts. So the language is
     * asked for and applied here explicitly.
     */
    private fun localized(): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
        val manager = getSystemService(LocaleManager::class.java) ?: return this
        val locales = manager.applicationLocales.takeUnless { it.isEmpty } ?: manager.systemLocales
        return createConfigurationContext(Configuration(resources.configuration).apply { setLocales(locales) })
    }

    private fun removeStatusNotification() {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        getSystemService(NotificationManager::class.java)?.cancel(STATUS_ID)
    }
}

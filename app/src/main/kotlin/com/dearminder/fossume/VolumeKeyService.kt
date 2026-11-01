package com.dearminder.fossume

import android.accessibilityservice.AccessibilityService
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
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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

        private val _connected = MutableStateFlow(false)
        /** Whether the system has actually bound the service (it's enabled and running). */
        val connected: StateFlow<Boolean> = _connected
    }

    private val handler = Handler(Looper.getMainLooper())
    // Looked up once - they're needed on every key press.
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private lateinit var settings: AppSettings
    private lateinit var gestures: KeyGestures
    private var foreground = false

    // Kept in a field: SharedPreferences only holds its listeners weakly.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // The player list shares the file but isn't a setting - no need to re-parse everything.
        if (key == Prefs.DETECTED_PLAYERS) return@OnSharedPreferenceChangeListener
        settings = AppSettings.load(this)
        if (key == Prefs.STATUS_NOTIFICATION) updateStatusNotification()
    }

    // Sent to the app when its language changes in system Settings → Apps → FOSSume → Language,
    // and when the phone's language changes.
    private val localeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateStatusNotification()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = AppSettings.load(this)
        appPrefs().registerOnSharedPreferenceChangeListener(prefsListener)
        ContextCompat.registerReceiver(this, localeReceiver, IntentFilter(Intent.ACTION_LOCALE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
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
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!::gestures.isInitialized) return false
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> VolumeKey.UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeKey.DOWN
            else -> return false
        }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> gestures.onKeyDown(key, event.repeatCount)
            KeyEvent.ACTION_UP -> gestures.onKeyUp(key)
            else -> false
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
        } catch (e: IllegalArgumentException) {
            // Never registered - the service was torn down before it connected.
        }
        removeStatusNotification()
    }

    private fun shouldHandle(): Boolean {
        if (settings.activation == Activation.SCREEN_OFF && power?.isInteractive != false) return false
        // During a call (or while it rings) the keys belong to the call.
        if (audio?.mode != AudioManager.MODE_NORMAL) return false
        return MediaControl.controller(this, settings) != null
    }

    private fun runAction(binding: ActionBinding) {
        // Looked up again: the gesture may have taken a while, and players come and go.
        val controller = MediaControl.controller(this, settings) ?: return
        if (MediaControl.perform(controller, binding)) vibrate(this, settings.vibration)
    }

    /** What the press would have done without us. */
    private fun stepVolume(key: VolumeKey) {
        val audio = audio ?: return
        val screenOn = power?.isInteractive == true
        audio.adjustSuggestedStreamVolume(
            if (key == VolumeKey.UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.USE_DEFAULT_STREAM_TYPE,
            if (screenOn) AudioManager.FLAG_SHOW_UI else 0
        )
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

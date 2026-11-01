package com.dearminder.fossume

import android.app.Notification
import android.content.ComponentName
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Notification access is what lets the app see other apps' media sessions at all
 * ([MediaSessionManager.getActiveSessions] requires it). While connected it also keeps
 * [Players] up to date - which players exist, which one is playing, and their notification buttons.
 */
class MediaNotificationListener : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        Players.onSessions(this, controllers.orEmpty())
    }

    override fun onListenerConnected() {
        val manager = getSystemService(MediaSessionManager::class.java) ?: return
        val component = ComponentName(this, MediaNotificationListener::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, Handler(Looper.getMainLooper()))
            Players.onSessions(this, manager.getActiveSessions(component))
            sessionManager = manager
        } catch (e: SecurityException) {
            // Access was revoked between connecting and now.
        }
        try {
            activeNotifications?.forEach { onNotificationPosted(it) }
        } catch (e: Exception) {
            // Not available on every connection state - the next notification fills it in anyway.
        }
    }

    override fun onListenerDisconnected() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        sessionManager = null
        Players.clearSessions()
        Players.buttons.clear()
        // Some ROMs drop the binding (e.g. after an app update) without revoking access - ask to
        // be bound again. Does nothing if the user really turned access off.
        try {
            requestRebind(ComponentName(this, MediaNotificationListener::class.java))
        } catch (e: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || !isMedia(sbn.notification)) return
        Players.record(this, sbn.packageName)
        Players.buttons[sbn.packageName] = sbn.notification.actions?.toList().orEmpty()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isMedia(sbn.notification)) Players.buttons.remove(sbn.packageName)
    }

    private fun isMedia(n: Notification): Boolean =
        n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true || n.category == Notification.CATEGORY_TRANSPORT
}

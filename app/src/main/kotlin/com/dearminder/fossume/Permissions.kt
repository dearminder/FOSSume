package com.dearminder.fossume

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

// ---------- What the app needs to work, and the system screens that grant it ----------

/** Read from system settings rather than the service itself - that also covers "on, but not bound yet". */
fun isAccessibilityEnabled(context: Context): Boolean {
    val ours = ComponentName(context, VolumeKeyService::class.java)
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
}

fun isNotificationAccessGranted(context: Context): Boolean =
    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

fun isBatteryOptimizationIgnored(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

/** The runtime permission on Android 13+; also catches notifications switched off in system settings on any version. */
fun areNotificationsAllowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

fun isPostNotificationsPermissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * Access granted but the listener isn't connected: some ROMs lose the binding after an app update
 * or a crash and never restore it on their own.
 */
fun rebindNotificationListenerIfNeeded(context: Context) {
    if (Players.tracking || !isNotificationAccessGranted(context)) return
    try {
        NotificationListenerService.requestRebind(ComponentName(context, MediaNotificationListener::class.java))
    } catch (e: Exception) {
    }
}

/** Both halves are needed: the keys come from accessibility, the players from notification access. */
fun isWorking(context: Context): Boolean = isAccessibilityEnabled(context) && isNotificationAccessGranted(context)

private fun Context.startFirst(vararg intents: Intent) {
    for (intent in intents) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // Some vendors drop the more specific screens - try the next one.
        }
    }
}

private fun appDetails(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

private const val ACCESSIBILITY_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

fun openAccessibilitySettings(context: Context) {
    val component = ComponentName(context, VolumeKeyService::class.java).flattenToString()
    // Android 13+ can jump straight to our entry (the action isn't in the public SDK, hence the
    // literal). Older versions have the screen too, but only for the system - some ROMs open it
    // anyway and show an empty page, so they get the whole list.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.startFirst(
            Intent(ACCESSIBILITY_DETAILS_SETTINGS).putExtra(Intent.EXTRA_COMPONENT_NAME, component),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        )
    } else {
        context.startFirst(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

fun openNotificationAccessSettings(context: Context) {
    val component = ComponentName(context, MediaNotificationListener::class.java).flattenToString()
    val intents = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component))
        }
        add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    context.startFirst(*intents.toTypedArray())
}

/** Asks directly (one-tap dialog); once allowed, only the system's list can undo it. */
fun openBatteryOptimizationSettings(context: Context) {
    if (isBatteryOptimizationIgnored(context)) {
        context.startFirst(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails(context))
    } else {
        context.startFirst(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            appDetails(context)
        )
    }
}

/** For notifications switched off below Android 13, or the permission denied for good on 13+. */
fun openAppNotificationSettings(context: Context) {
    context.startFirst(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        appDetails(context)
    )
}

/** The status notification's own channel, where it can be hidden without stopping anything. */
fun openStatusNotificationSettings(context: Context) {
    context.startFirst(
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, VolumeKeyService.STATUS_CHANNEL),
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        appDetails(context)
    )
}

/** Where Android 13+ hides "Allow restricted settings" for apps installed outside an app store. */
fun openAppDetails(context: Context) = context.startFirst(appDetails(context))

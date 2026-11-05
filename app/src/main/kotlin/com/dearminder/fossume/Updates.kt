package com.dearminder.fossume

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ---------- Checking GitHub for a newer release ----------

private const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"

private const val TIMEOUT_MS = 10_000
private const val APK_FILE = "update.apk"
/** How long an update may wait for the user's confirmation and still reopen the app once installed. */
private const val RELAUNCH_WINDOW_MS = 60 * 60 * 1000L
private const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data object Failed : UpdateState
    /** [progress] from 0 to 1, or null while the size is unknown. */
    data class Downloading(val release: Release, val progress: Float?) : UpdateState
    /** Handed to the system installer, which asks the user to confirm. */
    data class Installing(val release: Release) : UpdateState
    data class InstallFailed(val release: Release) : UpdateState
}

/** The newer release the state is about, if any. */
val UpdateState.foundRelease: Release?
    get() = when (this) {
        is UpdateState.Available -> release
        is UpdateState.Downloading -> release
        is UpdateState.Installing -> release
        is UpdateState.InstallFailed -> release
        else -> null
    }

/**
 * The only network access in the app: one request for the latest release, made when the user
 * taps the button or, with the automatic check on, at most once a day when the app is opened -
 * and the APK itself, downloaded only when the user taps "Download and install".
 */
object Updates {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    // Not tied to a screen: switching tabs mid-check must not cancel it and leave it "checking".
    private val scope = MainScope()

    fun check(context: Context) {
        val current = _state.value
        if (current == UpdateState.Checking || current is UpdateState.Downloading || current is UpdateState.Installing) return
        _state.value = UpdateState.Checking
        val app = context.applicationContext
        app.appPrefs().edit().putLong(Prefs.LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
        scope.launch {
            val release = withContext(Dispatchers.IO) {
                // Left over if the app was stopped mid-download.
                File(app.cacheDir, APK_FILE).delete()
                try { fetchLatest(app) } catch (e: Exception) { null }
            }
            _state.value = when {
                release == null -> UpdateState.Failed
                isNewerVersion(release.version, installedVersion(app).orEmpty()) -> UpdateState.Available(release)
                else -> UpdateState.UpToDate
            }
        }
    }

    /** The automatic check: skipped if it's off or GitHub was already asked in the last day. */
    fun checkIfDue(context: Context, settings: AppSettings) {
        if (!settings.autoUpdateCheck || _state.value != UpdateState.Idle) return
        val last = context.appPrefs().getLong(Prefs.LAST_UPDATE_CHECK, 0L)
        if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return
        check(context)
    }

    /**
     * Downloads the APK into the cache and hands it to the system installer, which keeps its own
     * copy - so the file is deleted straight away, whether that worked or not. A release without
     * an APK opens its page in the browser instead.
     */
    fun install(context: Context, release: Release) {
        val apkUrl = release.apkUrl ?: return openUrl(context, release.pageUrl)
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        val app = context.applicationContext
        _state.value = UpdateState.Downloading(release, null)
        scope.launch {
            val started = withContext(Dispatchers.IO) {
                val apk = File(app.cacheDir, APK_FILE)
                try {
                    downloadApk(app, apkUrl, apk) { _state.value = UpdateState.Downloading(release, it) }
                    startInstall(app, apk)
                    true
                } catch (e: Exception) {
                    false
                } finally {
                    apk.delete()
                }
            }
            _state.value = if (started) UpdateState.Installing(release) else UpdateState.InstallFailed(release)
        }
    }

    /** The installer's answer, from [UpdateInstallReceiver]; on success there's none - the app is replaced. */
    internal fun onInstallResult(context: Context, cancelled: Boolean) {
        forgetRelaunch(context)
        val release = when (val s = _state.value) {
            is UpdateState.Installing -> s.release
            // The process was restarted in between - there's nothing to show the result on.
            else -> return
        }
        _state.value = if (cancelled) UpdateState.Available(release) else UpdateState.InstallFailed(release)
    }

    /**
     * Opens FOSSume again once the update it started has replaced it - Android stops the old
     * version and doesn't start the new one. Called from [UpdatedReceiver] and when
     * [VolumeKeyService] connects: from Android 10 an app in the background may only open its
     * screen while its accessibility service is bound, which can happen before or after the
     * broadcast. [retryLater] keeps the request for that second chance (the screen is single
     * task, so opening it twice just brings it to the front).
     */
    fun relaunchIfUpdated(context: Context, retryLater: Boolean) {
        val prefs = context.appPrefs()
        val before = prefs.getString(Prefs.UPDATING_FROM, null) ?: return
        val updated = installedVersion(context).orEmpty() != before
        val expired = System.currentTimeMillis() - prefs.getLong(Prefs.UPDATING_SINCE, 0L) > RELAUNCH_WINDOW_MS
        // Not installed yet - still waiting for the user to confirm.
        if (!updated && !expired) return
        if (!updated || !retryLater) forgetRelaunch(context)
        if (!updated) return
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Not allowed from the background here - the app just stays closed, as before.
        }
    }

    private fun forgetRelaunch(context: Context) {
        context.appPrefs().edit().remove(Prefs.UPDATING_FROM).remove(Prefs.UPDATING_SINCE).apply()
    }

    private fun downloadApk(context: Context, url: String, target: File, onProgress: (Float?) -> Unit) {
        // GitHub answers with a redirect to its file host; both are https, so it's followed.
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "FOSSume/${installedVersion(context).orEmpty()}")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            var done = 0L
            var shown = -1
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        // Once per percent, not per buffer.
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != shown) {
                            shown = percent
                            onProgress(if (total > 0) done.toFloat() / total else null)
                        }
                    }
                }
            }
            if (total > 0 && done != total) error("Download cut short")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Copies the APK into an install session and commits it; the result comes to
     * [UpdateInstallReceiver]. Usually that's first a request to show the confirmation (and, the
     * first time, to allow FOSSume to install apps) - Android 12+ skips it when FOSSume installed
     * the version being updated itself.
     */
    private fun startInstall(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            try {
                session.openWrite(APK_FILE, 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
            // Mutable: the installer adds the status to it.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, UpdateInstallReceiver::class.java), flags)
            // Written synchronously: a silent install can stop the process moments after the commit.
            context.appPrefs().edit()
                .putString(Prefs.UPDATING_FROM, installedVersion(context).orEmpty())
                .putLong(Prefs.UPDATING_SINCE, System.currentTimeMillis())
                .commit()
            session.commit(callback.intentSender)
        }
    }

    private fun fetchLatest(context: Context): Release? {
        val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub rejects requests without one.
            connection.setRequestProperty("User-Agent", "FOSSume/${installedVersion(context).orEmpty()}")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            parseRelease(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}

/** Gets the install session's status: shows the system's confirmation, or reports how it ended. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                val shown = confirm != null && try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (e: Exception) {
                    false
                }
                if (!shown) Updates.onInstallResult(context, cancelled = false)
            }
            // The new version has replaced this one, which is about to be stopped.
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updates.onInstallResult(context, cancelled = true)
            else -> Updates.onInstallResult(context, cancelled = false)
        }
    }
}

/** Sent to the new version right after an update; reopens the app if it was FOSSume's own. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Before Android 10, or with the accessibility service already bound, opening the screen
        // surely works; otherwise VolumeKeyService tries again once it connects.
        val sure = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || VolumeKeyService.connected.value
        Updates.relaunchIfUpdated(context, retryLater = !sure)
    }
}

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // No browser at all - nothing else could open it either.
    }
}

fun installedVersion(context: Context): String? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
    } else {
        @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }
} catch (e: Exception) {
    null
}

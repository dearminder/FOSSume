package com.dearminder.fossume

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// ---------- Checking GitHub for a newer release ----------

private const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"

private const val TIMEOUT_MS = 10_000
private const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data object Failed : UpdateState
}

/**
 * The only network access in the app: one request for the latest release, made when the user
 * taps the button or, with the automatic check on, at most once a day when the app is opened.
 * The APK itself is downloaded by the browser, so no install permission is needed.
 */
object Updates {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    // Not tied to a screen: switching tabs mid-check must not cancel it and leave it "checking".
    private val scope = MainScope()

    fun check(context: Context) {
        if (_state.value == UpdateState.Checking) return
        _state.value = UpdateState.Checking
        val app = context.applicationContext
        app.appPrefs().edit().putLong(Prefs.LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
        scope.launch {
            val release = withContext(Dispatchers.IO) {
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

    /** Opens the APK in the browser, which downloads it; the release page if there's no APK. */
    fun download(context: Context, release: Release) {
        val url = release.apkUrl ?: release.pageUrl
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // No browser at all - nothing else could open it either.
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

fun installedVersion(context: Context): String? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
    } else {
        @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }
} catch (e: Exception) {
    null
}

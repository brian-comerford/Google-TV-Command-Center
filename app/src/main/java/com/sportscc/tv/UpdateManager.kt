package com.sportscc.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.sportscc.core.Alert
import com.sportscc.core.HttpText
import com.sportscc.core.League
import com.sportscc.core.ReleaseInfo
import com.sportscc.core.Updates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Looks for a newer build on GitHub (the repo's latest release) when the app opens and every few
 * hours, says so once per new version, and can download it and hand it to Android's installer.
 * Every build is signed with the same key, so it installs over this one and keeps your list.
 */
class UpdateManager(
    private val context: Context,
    private val notify: (Alert) -> Unit,
) {
    private val prefs = context.getSharedPreferences("scc_updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val currentVersion: String = BuildConfig.VERSION_NAME

    private val _available = MutableStateFlow<ReleaseInfo?>(null)
    /** A newer version than this one, once a check has found it. */
    val available: StateFlow<ReleaseInfo?> = _available.asStateFlow()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** "Later" on the home-screen banner hides it until the app is next opened. */
    val dismissed = MutableStateFlow(false)

    private var lastCheck = 0L
    private var loop: Job? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                check()
                delay(CHECK_EVERY_MS)
            }
        }
    }

    /** Checks GitHub unless it was checked in the last hour (or [force]). */
    fun checkSoon(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastCheck < 60 * 60_000L) return
        scope.launch { check() }
    }

    private suspend fun check() {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        lastCheck = System.currentTimeMillis()
        try {
            val text = withContext(Dispatchers.IO) {
                HttpText.get(BuildConfig.RELEASES_API, mapOf("Accept" to "application/vnd.github+json"), maxBytes = 2_000_000, timeoutMs = 15_000)
            }
            val latest = Updates.parseLatest(text) ?: throw IOException("GitHub didn't list a release")
            if (Updates.isNewer(latest.version, currentVersion)) {
                _available.value = latest
                _state.value = UpdateState.Idle
                if (prefs.getString(KEY_NOTIFIED, null) != latest.version) {
                    prefs.edit().putString(KEY_NOTIFIED, latest.version).apply()
                    val now = System.currentTimeMillis()
                    notify(
                        Alert(
                            id = "update:${latest.version}", time = now, league = League.NFL, watchKey = "update",
                            title = "Update available: version ${latest.version}",
                            detail = (latest.whatsNew?.let { "$it. " } ?: "") + "Open Sports Command Center and choose Update now.",
                            big = false,
                        ),
                    )
                }
            } else {
                _available.value = null
                _state.value = UpdateState.UpToDate(System.currentTimeMillis())
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "update check failed", e)
            _state.value = UpdateState.Failed("Couldn't check for updates: ${e.message ?: e::class.simpleName}")
        }
    }

    /** Downloads the new APK and opens Android's installer for it. */
    fun install() {
        if (_state.value is UpdateState.Downloading) return
        val url = _available.value?.apkUrl ?: BuildConfig.APK_URL
        scope.launch {
            try {
                _state.value = UpdateState.Downloading(0)
                val file = withContext(Dispatchers.IO) { download(url) }
                _state.value = UpdateState.Idle
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                withContext(Dispatchers.Main) {
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        _state.value = UpdateState.Failed("This TV has no app installer to open. Use Downloader with the link below.")
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "update download failed", e)
                _state.value = UpdateState.Failed("Couldn't download the update: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    private fun download(url: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "SportsCommandCenter.apk")
        var current = url
        repeat(6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                val code = conn.responseCode
                if (code in 300..399) {
                    current = URL(URL(current), conn.getHeaderField("Location") ?: throw IOException("HTTP $code")).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                val total = conn.contentLengthLong
                var done = 0L
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) _state.value = UpdateState.Downloading((done * 100 / total).toInt())
                        }
                    }
                }
                if (total > 0 && done < total) throw IOException("the download was cut off")
                return out
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    private companion object {
        const val TAG = "Updates"
        const val KEY_NOTIFIED = "notifiedVersion"
        const val CHECK_EVERY_MS = 6 * 60 * 60_000L
    }
}

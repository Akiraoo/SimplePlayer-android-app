package com.akira.simpleplayer.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.akira.simpleplayer.AppExecutors
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater backed by GitHub Releases.
 * The release workflow publishes one signed APK per tag (v1.0.5 -> versionName 1.0.5).
 */
object Updater {
    /** Public repo that publishes the APK. Must have Releases with .apk assets. */
    const val REPO = "Akiraoo/SimplePlayer-android-app"

    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val size: Long
    )

    fun installedVersion(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0"

    /** Latest published release, or null if the repo has no release with an .apk asset. */
    suspend fun latest(): Release? = withContext(AppExecutors.io) {
        val c = (URL("https://api.github.com/repos/$REPO/releases/latest")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SimplePlayer-Android")
        }
        try {
            val code = c.responseCode
            if (code == 404) return@withContext null
            if (code !in 200..299) error("GitHub HTTP $code")

            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = o.optJSONArray("assets") ?: return@withContext null

            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return@withContext Release(
                        version = o.optString("tag_name").removePrefix("v"),
                        notes = o.optString("body").trim(),
                        apkUrl = a.getString("browser_download_url"),
                        size = a.optLong("size")
                    )
                }
            }
            null
        } finally {
            c.disconnect()
        }
    }

    /** "1.0.10" is newer than "1.0.9". Non-numeric parts are ignored. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) =
            v.removePrefix("v").split('.', '-', '+').map { it.toIntOrNull() ?: 0 }

        val r = parts(remote)
        val l = parts(local)

        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Float) -> Unit
    ): File = withContext(AppExecutors.io) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "update.apk")

        val c = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 30000
            setRequestProperty("User-Agent", "SimplePlayer-Android")
        }
        try {
            if (c.responseCode !in 200..299) error("下載失敗 HTTP ${c.responseCode}")

            val total = c.contentLengthLong.takeIf { it > 0 } ?: release.size
            var read = 0L
            val buf = ByteArray(64 * 1024)

            c.inputStream.use { input ->
                target.outputStream().use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (total > 0 && read != total) error("檔案不完整")
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            c.disconnect()
        }
        target
    }

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Opens "Install unknown apps" for this app. */
    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Hands the APK to the system installer (Android always shows its own confirm screen). */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
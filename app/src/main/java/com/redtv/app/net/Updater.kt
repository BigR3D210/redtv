package com.redtv.app.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import com.redtv.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** Checks the public GitHub Releases for a newer build and installs it in place. */
object Updater {

    data class Info(val buildNumber: Int, val label: String, val apkUrl: String)

    private fun api() =
        "https://api.github.com/repos/${BuildConfig.GH_OWNER}/${BuildConfig.GH_REPO}/releases/latest"

    /** Returns update info if a newer build is available, else null. */
    suspend fun check(): Info? = withContext(Dispatchers.IO) {
        runCatching {
            val json = Http.getString(api())
            val root = JsonParser.parseString(json).asJsonObject
            val tag = root.get("tag_name")?.asString ?: return@runCatching null
            val num = Regex("\\d+").find(tag)?.value?.toIntOrNull() ?: 0
            if (num <= BuildConfig.BUILD_NUMBER) return@runCatching null
            val assets = root.getAsJsonArray("assets") ?: return@runCatching null
            val apk = assets.firstOrNull {
                it.asJsonObject.get("name")?.asString?.endsWith(".apk", true) == true
            }?.asJsonObject?.get("browser_download_url")?.asString ?: return@runCatching null
            val label = root.get("name")?.asString?.takeIf { it.isNotBlank() } ?: tag
            Info(num, label, apk)
        }.getOrNull()
    }

    /** Download the APK and launch the system installer. */
    suspend fun downloadAndInstall(context: Context, apkUrl: String) = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "RedTV-update.apk")
        val req = Request.Builder().url(apkUrl).header("User-Agent", Http.userAgent).build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed: HTTP ${resp.code}")
            resp.body?.byteStream()?.use { input ->
                file.outputStream().use { out -> input.copyTo(out) }
            } ?: error("Empty download")
        }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

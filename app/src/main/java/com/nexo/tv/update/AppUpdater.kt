package com.nexo.tv.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.nexo.tv.BuildConfig
import com.nexo.tv.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

data class UpdateInfo(
    @SerializedName("versionCode") val versionCode: Int = 0,
    @SerializedName("versionName") val versionName: String = "",
    @SerializedName("apkUrl") val apkUrl: String = "",
    @SerializedName("changelog") val changelog: String = "",
    @SerializedName("mandatory") val mandatory: Boolean = false
)

object AppUpdater {
    private const val TAG = "AppUpdater"
    private val gson = Gson()

    /** Lectura pública del último release (el repo debe ser público para OTA). */
    private val VERSION_URLS = listOf(
        "https://github.com/pjaraf/nexo-plus/releases/latest/download/version.json",
        "https://raw.githubusercontent.com/pjaraf/nexo-plus/main/version.json"
    )

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        for (url in VERSION_URLS) {
            val info = fetchVersion(url) ?: continue
            if (info.versionCode > BuildConfig.VERSION_CODE) {
                Log.i(TAG, "update available ${info.versionName} (${info.versionCode})")
                return@withContext info
            }
            Log.i(TAG, "up to date local=${BuildConfig.VERSION_CODE} remote=${info.versionCode}")
            return@withContext null
        }
        null
    }

    private fun fetchVersion(url: String): UpdateInfo? {
        return try {
            val req = Request.Builder().url(url).header("Accept", "application/json").build()
            Http.client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    Log.w(TAG, "version $url -> ${res.code}")
                    return null
                }
                val body = res.body?.string().orEmpty()
                gson.fromJson(body, UpdateInfo::class.java)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "fetch $url: ${e.message}")
            null
        }
    }

    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val url = info.apkUrl.ifBlank {
                "https://github.com/pjaraf/nexo-plus/releases/latest/download/app-release.apk"
            }
            try {
                val req = Request.Builder().url(url).build()
                Http.client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) {
                        Log.e(TAG, "download failed ${res.code}")
                        return@withContext null
                    }
                    val body = res.body ?: return@withContext null
                    val total = body.contentLength()
                    val out = File(context.cacheDir, "nexo-update.apk")
                    body.byteStream().use { input ->
                        FileOutputStream(out).use { output ->
                            val buf = ByteArray(16 * 1024)
                            var read = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                read += n
                                if (total > 0) onProgress(((read * 100) / total).toInt())
                            }
                        }
                    }
                    out
                }
            } catch (e: Throwable) {
                Log.e(TAG, "download error", e)
                null
            }
        }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    fun openInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
        }
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

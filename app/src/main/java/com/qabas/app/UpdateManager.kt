package com.qabas.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * مدير التحديثات الذكية.
 *
 * الأولوية:
 * 1) تحميل .delta + تحميل xdelta3 تلقائياً (حسب معمارية الجهاز) من نفس الـ Release
 * 2) تطبيق الـ patch على APK المثبت
 * 3) عند أي فشل → تحميل APK الكامل
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val REPO = "aliwalead2007-ship-it/QabasStudio-"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val deltaUrl: String?,
        val apkUrl: String,
        val releaseNotes: String,
        val deltaSize: Long,
        val apkSize: Long,
        /** روابط ثنائيات xdelta3 حسب المعمارية (اسم الملف → رابط) */
        val xdeltaUrls: Map<String, String> = emptyMap()
    )

    suspend fun checkForUpdate(context: Context, force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
            val lastCheck = prefs.getLong("update_last_check", 0)
            if (!force && System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
                Log.d(TAG, "Skipping check")
                return@withContext null
            }

            val currentVersionCode = getCurrentVersionCode(context)
            Log.d(TAG, "Checking updates… current=$currentVersionCode")

            val request = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Qabas-Studio")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "GitHub API ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val json = org.json.JSONObject(body)
            val tagName = json.optString("tag_name", "")
            val releaseNotes = json.optString("body", "")
            val assets = json.getJSONArray("assets")

            val releaseVersionCode = parseVersionCode(tagName)
            if (releaseVersionCode <= currentVersionCode) {
                prefs.edit().putLong("update_last_check", System.currentTimeMillis()).apply()
                return@withContext null
            }

            var deltaUrl: String? = null
            var deltaSize = 0L
            var apkUrl: String? = null
            var apkSize = 0L
            val xdeltaUrls = mutableMapOf<String, String>()

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                val url = asset.getString("browser_download_url")
                val size = asset.getLong("size")
                when {
                    name.endsWith(".delta") -> {
                        deltaUrl = url; deltaSize = size
                    }
                    name.endsWith(".apk") -> {
                        apkUrl = url; apkSize = size
                    }
                    name.startsWith("xdelta3-") -> {
                        xdeltaUrls[name] = url
                    }
                }
            }

            if (apkUrl == null) {
                Log.w(TAG, "No APK in release")
                return@withContext null
            }

            prefs.edit()
                .putLong("update_last_check", System.currentTimeMillis())
                .putInt("update_last_version_code", releaseVersionCode)
                .apply()

            UpdateInfo(
                versionName = tagName.removePrefix("v"),
                versionCode = releaseVersionCode,
                deltaUrl = deltaUrl,
                apkUrl = apkUrl!!,
                releaseNotes = releaseNotes,
                deltaSize = deltaSize,
                apkSize = apkSize,
                xdeltaUrls = xdeltaUrls
            )
        } catch (e: Exception) {
            Log.e(TAG, "check failed: ${e.message}")
            null
        }
    }

    fun downloadAndInstall(
        context: Context,
        update: UpdateInfo,
        onProgress: (Int) -> Unit = {},
        onDone: (Boolean, String) -> Unit = { _, _ -> },
        authToken: String? = null
    ) {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
                onProgress(0)

                if (!update.deltaUrl.isNullOrBlank() && update.deltaSize > 0) {
                    Log.d(TAG, "Trying delta (${formatSize(update.deltaSize)})…")
                    val patched = tryDeltaUpdate(context, update, updatesDir, authToken) { pct ->
                        onProgress((pct * 0.85).toInt().coerceIn(0, 85))
                    }
                    if (patched != null && patched.exists() && patched.length() > 1_000_000) {
                        onProgress(100)
                        withContext(Dispatchers.Main) {
                            launchInstaller(context, patched)
                            onDone(
                                true,
                                "تحديث صغير ${update.versionName} (${formatSize(update.deltaSize)} بدل ${formatSize(update.apkSize)})"
                            )
                        }
                        return@launch
                    }
                    Log.w(TAG, "Delta failed — full APK fallback")
                }

                Log.d(TAG, "Downloading full APK (${formatSize(update.apkSize)})…")
                val apkFile = downloadFile(
                    update.apkUrl, updatesDir,
                    "qabas-${update.versionName}.apk", authToken
                ) { onProgress(it) }

                if (apkFile != null && apkFile.exists() && apkFile.length() > 1_000_000) {
                    onProgress(100)
                    withContext(Dispatchers.Main) {
                        launchInstaller(context, apkFile)
                        onDone(true, "تم تحميل ${update.versionName} (${formatSize(update.apkSize)})")
                    }
                } else {
                    withContext(Dispatchers.Main) { onDone(false, "فشل تحميل التحديث") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "downloadAndInstall: ${e.message}", e)
                withContext(Dispatchers.Main) { onDone(false, "خطأ: ${e.message}") }
            }
        }
    }

    private fun tryDeltaUpdate(
        context: Context,
        update: UpdateInfo,
        updatesDir: File,
        authToken: String?,
        onProgress: (Int) -> Unit
    ): File? {
        return try {
            // 1) تأكد من وجود xdelta3 (تحميل تلقائي إن لزم)
            val binary = ensureXdeltaBinary(context, update, authToken)
                ?: run {
                    Log.w(TAG, "No xdelta3 binary available")
                    return null
                }
            onProgress(10)

            // 2) تحميل الـ delta
            val deltaFile = downloadFile(
                update.deltaUrl!!, updatesDir,
                "qabas-${update.versionName}.delta", authToken
            ) { pct -> onProgress(10 + (pct * 0.4).toInt()) } ?: return null

            if (deltaFile.length() < 100) return null

            // 3) APK المثبت كمصدر
            val sourceApk = File(context.applicationInfo.sourceDir)
            if (!sourceApk.exists()) {
                Log.w(TAG, "source APK missing")
                return null
            }

            val outApk = File(updatesDir, "qabas-${update.versionName}-patched.apk")
            if (outApk.exists()) outApk.delete()

            onProgress(55)
            val ok = applyXdelta(binary, sourceApk, deltaFile, outApk)
            onProgress(90)

            if (ok && outApk.exists() && outApk.length() > 1_000_000) {
                deltaFile.delete()
                outApk
            } else {
                outApk.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryDeltaUpdate: ${e.message}", e)
            null
        }
    }

    /**
     * يوفّر ثنائي xdelta3:
     * 1) filesDir إن وُجد مسبقاً
     * 2) تحميل من أصول الـ Release حسب ABI الجهاز
     */
    private fun ensureXdeltaBinary(
        context: Context,
        update: UpdateInfo,
        authToken: String?
    ): File? {
        val dest = File(context.filesDir, "xdelta3")
        if (dest.exists() && dest.canExecute() && dest.length() > 10_000) {
            return dest
        }

        val abi = preferredAbi()
        val assetName = "xdelta3-$abi"
        val url = update.xdeltaUrls[assetName]
            ?: update.xdeltaUrls.entries.firstOrNull {
                it.key.contains(abi) || (abi == "arm64-v8a" && it.key.contains("arm64"))
            }?.value

        if (url.isNullOrBlank()) {
            Log.w(TAG, "No xdelta3 asset for ABI=$abi in release (have: ${update.xdeltaUrls.keys})")
            return null
        }

        Log.d(TAG, "Downloading $assetName …")
        val tmp = downloadFile(url, context.filesDir, "xdelta3.download", authToken) { }
            ?: return null

        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        dest.setExecutable(true, false)
        dest.setReadable(true, false)

        return if (dest.exists() && dest.length() > 10_000) dest else null
    }

    private fun preferredAbi(): String {
        val abis = Build.SUPPORTED_ABIS
        return when {
            abis.any { it == "arm64-v8a" } -> "arm64-v8a"
            abis.any { it == "armeabi-v7a" } -> "armeabi-v7a"
            abis.any { it == "x86_64" } -> "x86_64"
            abis.any { it == "x86" } -> "x86"
            else -> abis.firstOrNull() ?: "arm64-v8a"
        }
    }

    private fun applyXdelta(binary: File, source: File, delta: File, output: File): Boolean {
        return try {
            val cmd = listOf(
                binary.absolutePath,
                "-d",
                "-s", source.absolutePath,
                delta.absolutePath,
                output.absolutePath
            )
            Log.d(TAG, "exec: ${cmd.joinToString(" ")}")
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val log = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0) {
                Log.e(TAG, "xdelta3 exit=$code log=$log")
                return false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "applyXdelta: ${e.message}", e)
            false
        }
    }

    private fun downloadFile(
        url: String,
        destDir: File,
        fileName: String,
        authToken: String? = null,
        onProgress: (Int) -> Unit
    ): File? {
        return try {
            val builder = Request.Builder().url(url)
            if (!authToken.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $authToken")
                if (url.contains("/releases/assets/")) {
                    builder.header("Accept", "application/octet-stream")
                }
            }
            val response = client.newCall(builder.build()).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} $url")
                return null
            }
            val body = response.body ?: return null
            val total = body.contentLength()
            val file = File(destDir, fileName)
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var n: Int
                    var read = 0L
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read * 100 / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "download: ${e.message}")
            null
        }
    }

    private fun launchInstaller(context: Context, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                try {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    android.widget.Toast.makeText(
                        context,
                        "فعّل «السماح من هذا المصدر» ثم أعد المحاولة",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) {
                }
                return
            }
        }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apkFile
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun getCurrentVersionCode(context: Context): Int = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
    } catch (_: PackageManager.NameNotFoundException) {
        0
    }

    private fun parseVersionCode(tag: String): Int {
        val buildPart = tag.substringAfter("+", "")
        if (buildPart.isNotEmpty()) {
            return buildPart.filter { it.isDigit() }.toIntOrNull() ?: 0
        }
        val parts = tag.removePrefix("v").trim().split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return major * 10000 + minor * 100 + patch
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "${"%.1f".format(bytes / 1024.0 / 1024.0)}MB"
    }
}

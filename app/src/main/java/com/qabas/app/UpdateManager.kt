package com.qabas.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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

    /** بثّ حيّ للتقدم — تلتقطه الواجهة وخدمة الخلفية معاً. */
    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    /** بثّ نتيجة التحميل: Triple(مفتاح النسخة، نجح؟، رسالة). */
    private val _downloadResult = MutableStateFlow<Triple<String, Boolean, String>?>(null)
    val downloadResult: StateFlow<Triple<String, Boolean, String>?> = _downloadResult.asStateFlow()

    /** المقابض النشطة حسب مفتاح النسخة — للإلغاء الموحّد من أي مكان. */
    private val activeHandles = ConcurrentHashMap<String, DownloadHandle>()

    fun cancelDownload(key: String) {
        activeHandles[key]?.cancel()
    }

    @Serializable
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

    /** مراحل عملية التحديث — تُستخدم لعرض تسمية دقيقة في الواجهة. */
    enum class Phase { DOWNLOADING_DELTA, APPLYING_PATCH, DOWNLOADING_FULL }

    /** تقدّم لحظي غني بالتفاصيل: نسبة + بايتات + سرعة + المرحلة الحالية. */
    data class DownloadProgress(
        val percent: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val phase: Phase,
        val speedBytesPerSec: Long
    )

    /** مقبض تحكّم يُعاد للواجهة فور بدء التحميل — يسمح بالإلغاء الفعلي في أي لحظة. */
    class DownloadHandle {
        @Volatile var cancelled: Boolean = false
            private set
        fun cancel() { cancelled = true }
    }

    /** يُرمى داخلياً لقطع سلسلة التنفيذ فور طلب الإلغاء. */
    private class DownloadCancelledException : Exception("أُلغي التحديث بواسطة المستخدم")

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
            val tagHasBuildNumber = tagName.contains("+")
            val currentVersionName = getCurrentVersionName(context)
            val releaseVersionName = tagName.removePrefix("v").trim().substringBefore("+")
            // المقارنة الأساسية برقم البناء (يتصاعد مع كل CI run) — اسم الإصدار
            // ثابت (1.2.1) فلا يصلح وحده لكشف التحديث.
            if (tagHasBuildNumber && releaseVersionCode <= currentVersionCode) {
                prefs.edit().putLong("update_last_check", System.currentTimeMillis()).apply()
                return@withContext null
            }
            if (!tagHasBuildNumber && compareVersionNames(releaseVersionName, currentVersionName) <= 0) {
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
                versionName = tagName.removePrefix("v").trim().substringBefore("+"),
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

    /**
     * يبدأ التحميل والتثبيت في الخلفية ويعيد فوراً [DownloadHandle] يمكن استدعاء
     * cancel() عليه من الواجهة لإيقاف العملية بشكل فعلي (لا مجرد إخفاء الشريط).
     */
    fun downloadAndInstall(
        context: Context,
        update: UpdateInfo,
        onProgress: (DownloadProgress) -> Unit = {},
        onDone: (Boolean, String) -> Unit = { _, _ -> },
        authToken: String? = null,
        forceFull: Boolean = false
    ): DownloadHandle {
        val handle = DownloadHandle()
        val key = "${update.versionName}|${update.versionCode}"
        activeHandles[key] = handle
        val emitProgress: (DownloadProgress) -> Unit = { p ->
            _downloadProgress.value = p
            onProgress(p)
        }
        val emitDone: (Boolean, String) -> Unit = { ok, msg ->
            activeHandles.remove(key)
            _downloadResult.value = Triple(key, ok, msg)
            onDone(ok, msg)
        }
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }

                if (!forceFull && !update.deltaUrl.isNullOrBlank() && update.deltaSize > 0) {
                    Log.d(TAG, "Trying delta (${formatSize(update.deltaSize)})…")
                    val patched = tryDeltaUpdate(context, update, updatesDir, authToken, handle, emitProgress)
                    if (patched != null && patched.exists() && patched.length() > 1_000_000) {
                        withContext(Dispatchers.Main) {
                            launchInstaller(context, patched)
                            emitDone(
                                true,
                                "تحديث صغير ${update.versionName} (${formatSize(update.deltaSize)} بدل ${formatSize(update.apkSize)})"
                            )
                        }
                        return@launch
                    }
                    if (handle.cancelled) throw DownloadCancelledException()
                    Log.w(TAG, "Delta failed — full APK fallback")
                }

                if (handle.cancelled) throw DownloadCancelledException()

                Log.d(TAG, "Downloading full APK (${formatSize(update.apkSize)})…")
                val apkFile = downloadFile(
                    update.apkUrl, updatesDir,
                    "qabas-${update.versionName}.apk", authToken, handle
                ) { bytes, total, speed ->
                    val pct = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0
                    emitProgress(DownloadProgress(pct, bytes, total, Phase.DOWNLOADING_FULL, speed))
                }

                if (handle.cancelled) throw DownloadCancelledException()

                if (apkFile != null && apkFile.exists() && apkFile.length() > 1_000_000) {
                    withContext(Dispatchers.Main) {
                        launchInstaller(context, apkFile)
                        emitDone(true, "تم تحميل ${update.versionName} (${formatSize(update.apkSize)})")
                    }
                } else {
                    withContext(Dispatchers.Main) { emitDone(false, "فشل تحميل التحديث") }
                }
            } catch (e: DownloadCancelledException) {
                withContext(Dispatchers.Main) { emitDone(false, "أُلغي التحديث") }
            } catch (e: Exception) {
                Log.e(TAG, "downloadAndInstall: ${e.message}", e)
                withContext(Dispatchers.Main) { emitDone(false, "خطأ: ${e.message}") }
            }
        }
        return handle
    }

    private fun tryDeltaUpdate(
        context: Context,
        update: UpdateInfo,
        updatesDir: File,
        authToken: String?,
        handle: DownloadHandle,
        onProgress: (DownloadProgress) -> Unit
    ): File? {
        return try {
            // 1) تأكد من وجود xdelta3 (تحميل تلقائي إن لزم)
            val binary = ensureXdeltaBinary(context, update, authToken, handle)
                ?: run {
                    Log.w(TAG, "No xdelta3 binary available")
                    return null
                }
            if (handle.cancelled) return null

            // 2) تحميل الـ delta (مع تقدّم لحظي حقيقي بالبايت والسرعة)
            val deltaFile = downloadFile(
                update.deltaUrl!!, updatesDir,
                "qabas-${update.versionName}.delta", authToken, handle
            ) { bytes, total, speed ->
                val pct = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0
                onProgress(DownloadProgress(pct, bytes, total, Phase.DOWNLOADING_DELTA, speed))
            } ?: return null

            if (handle.cancelled) { deltaFile.delete(); return null }
            if (deltaFile.length() < 100) return null

            // 3) APK المثبت كمصدر
            val sourceApk = File(context.applicationInfo.sourceDir)
            if (!sourceApk.exists()) {
                Log.w(TAG, "source APK missing")
                return null
            }

            val outApk = File(updatesDir, "qabas-${update.versionName}-patched.apk")
            if (outApk.exists()) outApk.delete()

            onProgress(DownloadProgress(0, 0, update.deltaSize, Phase.APPLYING_PATCH, 0))
            val ok = applyXdelta(binary, sourceApk, deltaFile, outApk)
            if (handle.cancelled) { outApk.delete(); return null }
            onProgress(DownloadProgress(100, update.deltaSize, update.deltaSize, Phase.APPLYING_PATCH, 0))

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
        authToken: String?,
        handle: DownloadHandle
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
        val tmp = downloadFile(url, context.filesDir, "xdelta3.download", authToken, handle) { _, _, _ -> }
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

    /**
     * تحميل ملف مع تقدّم لحظي حقيقي (بايتات + سرعة) وقابلية إلغاء فعلية:
     * يفحص handle.cancelled كل دفعة قراءة، ويقطع الاتصال ويحذف الملف الجزئي فوراً عند الإلغاء.
     */
    private fun downloadFile(
        url: String,
        destDir: File,
        fileName: String,
        authToken: String? = null,
        handle: DownloadHandle,
        onProgress: (bytesRead: Long, total: Long, speedBps: Long) -> Unit
    ): File? {
        val builder = Request.Builder().url(url)
        if (!authToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $authToken")
            if (url.contains("/releases/assets/")) {
                builder.header("Accept", "application/octet-stream")
            }
        }
        val call = client.newCall(builder.build())
        val file = File(destDir, fileName)
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} $url")
                return null
            }
            val body = response.body ?: return null
            val total = body.contentLength()

            var windowStart = System.currentTimeMillis()
            var windowBytes = 0L
            var lastSpeed = 0L

            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buf = ByteArray(65536)
                    var n: Int
                    var read = 0L
                    while (true) {
                        if (handle.cancelled) {
                            call.cancel()
                            throw DownloadCancelledException()
                        }
                        n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                        read += n
                        windowBytes += n

                        val now = System.currentTimeMillis()
                        val elapsed = now - windowStart
                        if (elapsed >= 200) {
                            lastSpeed = (windowBytes * 1000L / elapsed)
                            windowStart = now
                            windowBytes = 0
                        }
                        onProgress(read, total, lastSpeed)
                    }
                    onProgress(read, total, lastSpeed)
                }
            }
            return file
        } catch (e: DownloadCancelledException) {
            try { file.delete() } catch (_: Exception) {}
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "download: ${e.message}")
            try { file.delete() } catch (_: Exception) {}
            return null
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

    private fun getCurrentVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    }

    /** مقارنة دلالية لأسماء الإصدارات: 1.2.10 > 1.2.1. يُرجع موجباً إن كان a أحدث. */
    private fun compareVersionNames(a: String, b: String): Int {
        val pa = a.removePrefix("v").trim().split(".")
        val pb = b.removePrefix("v").trim().split(".")
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            if (x != y) return x - y
        }
        return 0
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

    fun formatSpeed(bytesPerSec: Long): String = "${formatSize(bytesPerSec)}/ث"
}

package com.qabas.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * مدير التحديثات الذكية — يتحقق من GitHub Releases.
 *
 * الأولوية:
 * 1) تحميل ملف .delta الصغير وتطبيقه على APK المثبت (إن أمكن)
 * 2) الرجوع تلقائياً لتحميل APK الكامل عند غياب الـ Delta أو فشل تطبيقه
 *
 * ملاحظة: تطبيق الـ Delta يحتاج أداة xdelta3 على الجهاز.
 * إن لم تتوفر، يتم التنزيل الكامل بأمان دون فشل صامت.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val REPO = "aliwalead2007-ship-it/QabasStudio-"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 ساعات

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val deltaUrl: String?,   // رابط ملف .delta الصغير
        val apkUrl: String,      // رابط APK الكامل (احتياطي)
        val releaseNotes: String,
        val deltaSize: Long,
        val apkSize: Long
    )

    /**
     * يتحقق من وجود تحديث. يُرجع null إذا لم يكن هناك جديد.
     */
    suspend fun checkForUpdate(context: Context, force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
            val lastCheck = prefs.getLong("update_last_check", 0)

            if (!force && System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
                Log.d(TAG, "Skipping check — last check was ${(System.currentTimeMillis() - lastCheck) / 1000 / 60}min ago")
                return@withContext null
            }

            val currentVersionCode = getCurrentVersionCode(context)
            Log.d(TAG, "Checking for updates... current code=$currentVersionCode")

            val request = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Qabas-Studio")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "GitHub API returned ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val json = org.json.JSONObject(body)

            val tagName = json.optString("tag_name", "")
            val releaseNotes = json.optString("body", "")
            val assets = json.getJSONArray("assets")

            val releaseVersionCode = parseVersionCode(tagName)
            if (releaseVersionCode <= currentVersionCode) {
                Log.d(TAG, "Already up to date ($currentVersionCode >= $releaseVersionCode)")
                prefs.edit().putLong("update_last_check", System.currentTimeMillis()).apply()
                return@withContext null
            }

            var deltaUrl: String? = null
            var deltaSize = 0L
            var apkUrl: String? = null
            var apkSize = 0L

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                val url = asset.getString("browser_download_url")
                val size = asset.getLong("size")

                when {
                    name.endsWith(".delta") -> {
                        deltaUrl = url
                        deltaSize = size
                    }
                    name.endsWith(".apk") -> {
                        apkUrl = url
                        apkSize = size
                    }
                }
            }

            if (apkUrl == null) {
                Log.w(TAG, "No APK found in release")
                return@withContext null
            }

            Log.d(TAG, "Update available: $tagName (code=$releaseVersionCode), delta=${deltaUrl != null}, deltaSize=$deltaSize")

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
                apkSize = apkSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /**
     * يحمّل التحديث (يفضّل Delta إن وُجد) ثم يفتح مثبّت النظام.
     */
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

                // ── محاولة مسار الـ Delta أولاً ──
                if (!update.deltaUrl.isNullOrBlank() && update.deltaSize > 0) {
                    Log.d(TAG, "Trying delta path (${formatSize(update.deltaSize)})...")
                    val patched = tryDeltaUpdate(context, update, updatesDir, authToken) { pct ->
                        // 0–80% لمسار الـ Delta
                        onProgress((pct * 0.8).toInt().coerceIn(0, 80))
                    }
                    if (patched != null && patched.exists() && patched.length() > 1_000_000) {
                        onProgress(100)
                        withContext(Dispatchers.Main) {
                            launchInstaller(context, patched)
                            onDone(
                                true,
                                "تم التحديث عبر Delta ${update.versionName} (${formatSize(update.deltaSize)} بدل ${formatSize(update.apkSize)})"
                            )
                        }
                        return@launch
                    }
                    Log.w(TAG, "Delta path failed — falling back to full APK")
                } else {
                    Log.d(TAG, "No delta available — using full APK")
                }

                // ── مسار APK الكامل (احتياطي دائماً) ──
                Log.d(TAG, "Downloading full APK (${formatSize(update.apkSize)})...")
                val apkFile = downloadFile(
                    update.apkUrl,
                    updatesDir,
                    "qabas-${update.versionName}.apk",
                    authToken
                ) { pct -> onProgress(pct) }

                if (apkFile != null && apkFile.exists() && apkFile.length() > 1_000_000) {
                    onProgress(100)
                    withContext(Dispatchers.Main) {
                        launchInstaller(context, apkFile)
                        onDone(true, "تم تحميل التحديث ${update.versionName} (${formatSize(update.apkSize)})")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onDone(false, "فشل تحميل التحديث")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download/install failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onDone(false, "خطأ: ${e.message}")
                }
            }
        }
    }

    /**
     * يحمّل .delta ويطبّقه على APK المثبت. يُرجع الملف الناتج أو null عند الفشل.
     */
    private fun tryDeltaUpdate(
        context: Context,
        update: UpdateInfo,
        updatesDir: File,
        authToken: String?,
        onProgress: (Int) -> Unit
    ): File? {
        return try {
            val deltaFile = downloadFile(
                update.deltaUrl!!,
                updatesDir,
                "qabas-${update.versionName}.delta",
                authToken
            ) { pct -> onProgress((pct * 0.5).toInt().coerceIn(0, 50)) }
                ?: return null

            if (!deltaFile.exists() || deltaFile.length() < 100) {
                Log.w(TAG, "Delta file invalid or empty")
                return null
            }

            val sourceApk = File(context.applicationInfo.sourceDir)
            if (!sourceApk.exists()) {
                Log.w(TAG, "Installed APK not found at ${sourceApk.absolutePath}")
                return null
            }

            val outApk = File(updatesDir, "qabas-${update.versionName}-patched.apk")
            if (outApk.exists()) outApk.delete()

            onProgress(55)
            val ok = applyXdelta(context, sourceApk, deltaFile, outApk)
            onProgress(90)

            if (ok && outApk.exists() && outApk.length() > 1_000_000) {
                Log.d(TAG, "Delta applied successfully → ${outApk.length()} bytes")
                // تنظيف ملف الـ delta بعد النجاح
                deltaFile.delete()
                outApk
            } else {
                Log.w(TAG, "Delta apply failed or output too small")
                outApk.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryDeltaUpdate error: ${e.message}", e)
            null
        }
    }

    /**
     * يطبّق patch بأسلوب xdelta3:
     * xdelta3 -d -s SOURCE DELTA OUTPUT
     *
     * يبحث عن الثنائي في:
     * 1) filesDir/xdelta3 (إن وُضع يدوياً أو من assets)
     * 2) nativeLibraryDir
     * 3) PATH النظام (نادر على أندرويد)
     */
    private fun applyXdelta(context: Context, source: File, delta: File, output: File): Boolean {
        val binary = resolveXdeltaBinary(context)
        if (binary == null) {
            Log.w(TAG, "xdelta3 binary not found on device — cannot apply delta")
            return false
        }

        return try {
            val cmd = listOf(
                binary.absolutePath,
                "-d",           // decode
                "-s", source.absolutePath,
                delta.absolutePath,
                output.absolutePath
            )
            Log.d(TAG, "Running: ${cmd.joinToString(" ")}")
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
            Log.e(TAG, "applyXdelta failed: ${e.message}", e)
            false
        }
    }

    private fun resolveXdeltaBinary(context: Context): File? {
        // 1) ملف مستخرج مسبقاً في الملفات الخاصة بالتطبيق
        val inFiles = File(context.filesDir, "xdelta3")
        if (inFiles.exists() && inFiles.canExecute()) return inFiles

        // 2) محاولة استخراج من assets إن وُجد
        try {
            val assetNames = listOf(
                "xdelta3",
                "xdelta3-arm64",
                "bin/xdelta3",
                "native/xdelta3"
            )
            for (name in assetNames) {
                try {
                    context.assets.open(name).use { input ->
                        inFiles.outputStream().use { output -> input.copyTo(output) }
                    }
                    inFiles.setExecutable(true, false)
                    if (inFiles.exists() && inFiles.length() > 1000) {
                        Log.d(TAG, "Extracted xdelta3 from assets/$name")
                        return inFiles
                    }
                } catch (_: Exception) {
                    // asset غير موجود — جرّب التالي
                }
            }
        } catch (_: Exception) {
        }

        // 3) nativeLibraryDir (إن وُضع .so أو ثنائي هناك)
        val libDir = File(context.applicationInfo.nativeLibraryDir ?: "")
        val candidates = listOf(
            File(libDir, "xdelta3"),
            File(libDir, "libxdelta3.so")
        )
        for (c in candidates) {
            if (c.exists()) {
                // .so قد لا يُنفَّذ مباشرة؛ نتخطاه إن لم يكن executable
                if (c.canExecute() || c.name == "xdelta3") return c
            }
        }

        return null
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
            val request = builder.build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Download HTTP ${response.code} for $url")
                return null
            }

            val body = response.body ?: return null
            val totalBytes = body.contentLength()
            val file = File(destDir, fileName)

            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            onProgress((totalRead * 100 / totalBytes).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            null
        }
    }

    private fun launchInstaller(context: Context, apkFile: File) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val pm = context.packageManager
            if (!pm.canRequestPackageInstalls()) {
                try {
                    val settingsIntent = Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(settingsIntent)
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
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun getCurrentVersionCode(context: Context): Int {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        } catch (e: PackageManager.NameNotFoundException) {
            0
        }
    }

    private fun parseVersionCode(tag: String): Int {
        // الوسم: "v1.2.1+140" → 140 (يطابق versionCode / run_number)
        val buildPart = tag.substringAfter("+", "")
        if (buildPart.isNotEmpty()) {
            return buildPart.filter { it.isDigit() }.toIntOrNull() ?: 0
        }
        val clean = tag.removePrefix("v").trim()
        val parts = clean.split(".")
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

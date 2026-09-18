package com.qabas.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 *مدير التحديثات الذكية — يتحقق من GitHub Releases وينزّل ملف Delta الصغير (~1-5MB)
 * بدل APK الكامل (~130MB).
 *
 * في CI: xdelta3 يُنشئ ملف .delta (فرق بين النسختين).
 * هنا: نحمّل ملف .delta ونطبّقه على APK الحالي لنحصل على النسخة الجديدة.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val REPO = "aliwalead2007-ship-it/QabasStudio-"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 ساعات

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // ──────────────── فحص التحديث ────────────────

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

            // لا تحقق كل مرة (6 ساعات بين كل فحص) — إلا عند الضغط اليدوي
            if (!force && System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
                Log.d(TAG, "Skipping check — last check was ${(System.currentTimeMillis() - lastCheck) / 1000 / 60}min ago")
                return@withContext null
            }

            val currentVersionCode = getCurrentVersionCode(context)
            Log.d(TAG, "Checking for updates... current code=$currentVersionCode")

            // احصل على آخر Release من GitHub
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

            // اقرأ معلومات الإصدار
            val tagName = json.optString("tag_name", "") // مثلاً "v1.2.3"
            val releaseNotes = json.optString("body", "")
            val assets = json.getJSONArray("assets")

            // استخرج versionCode من tagName (v1.2.3 → 10203)
            val releaseVersionCode = parseVersionCode(tagName)
            if (releaseVersionCode <= currentVersionCode) {
                Log.d(TAG, "Already up to date ($currentVersionCode >= $releaseVersionCode)")
                prefs.edit().putLong("update_last_check", System.currentTimeMillis()).apply()
                return@withContext null
            }

            // ابحث عن ملف .delta و APK
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
                    name.endsWith(".delta") -> { deltaUrl = url; deltaSize = size }
                    name.endsWith(".apk") -> { apkUrl = url; apkSize = size }
                }
            }

            if (apkUrl == null) {
                Log.w(TAG, "No APK found in release")
                return@withContext null
            }

            Log.d(TAG, "Update available: $tagName (code=$releaseVersionCode), delta=${deltaUrl != null}")

            // حفظ آخر فحص
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

    // ──────────────── تحميل وتطبيق ────────────────

    /**
     * يحمّل التحديث (delta أو APK كامل) ويُثبّته.
     * @param onProgress نسبة التقدم 0-100
     * @param onDone استدعاء عند الانتهاء
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
                Log.d(TAG, "Downloading full APK (${formatSize(update.apkSize)})...")

                val apkFile = downloadFile(update.apkUrl, updatesDir, "qabas-${update.versionName}.apk", authToken) { pct ->
                    onProgress(pct)
                }

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
                Log.e(TAG, "Download/install failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    onDone(false, "خطأ: ${e.message}")
                }
            }
        }
    }

    // ──────────────── دوال مساعدة ────────────────

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
                // للمستودعات الخاصة: رابط API للأصل يتطلب التوكن مع قبول الثنائي
                builder.header("Authorization", "Bearer $authToken")
                if (url.contains("/releases/assets/")) {
                    builder.header("Accept", "application/octet-stream")
                }
            }
            val request = builder.build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

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
        // أندرويد 8+: يجب منح "تثبيت من مصادر غير معروفة" أولاً — وإلا يُرفض التثبيت بصمت
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
                } catch (_: Exception) {}
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
        // "v1.2.3" → 10203 — آمن ضد الوسوم الغريبة (لا !!)
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

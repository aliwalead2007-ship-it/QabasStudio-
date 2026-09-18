package com.qabas.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

object AppDiagnostics {

    private const val TAG = "AppDiagnostics"

    data class DiagnosticReport(
        val timestamp: Long = System.currentTimeMillis(),
        val deviceInfo: DeviceInfo = DeviceInfo(),
        val memoryInfo: MemoryInfo = MemoryInfo(),
        val storageInfo: StorageInfo = StorageInfo(),
        val networkInfo: NetworkInfo = NetworkInfo(),
        val batteryInfo: BatteryInfo = BatteryInfo(),
        val apiHealth: List<ApiHealth> = emptyList(),
        val prefsHealth: List<PrefsHealth> = emptyList(),
        val dbHealth: DbHealth = DbHealth(),
        val crashHealth: CrashHealth = CrashHealth(),
        val threadInfo: ThreadInfo = ThreadInfo(),
        val pipelineHealth: PipelineHealth = PipelineHealth(),
        val issues: List<Issue> = emptyList()
    )

    data class DeviceInfo(
        val model: String = android.os.Build.MODEL,
        val manufacturer: String = android.os.Build.MANUFACTURER,
        val androidVersion: String = android.os.Build.VERSION.RELEASE,
        val sdkInt: Int = android.os.Build.VERSION.SDK_INT,
        val appVersion: String = "unknown",
        val appVersionCode: Long = 0,
        val cpuCores: Int = Runtime.getRuntime().availableProcessors(),
        val supportedAbis: List<String> = android.os.Build.SUPPORTED_ABIS.toList()
    )

    data class MemoryInfo(
        val totalHeapMB: Long = 0,
        val usedHeapMB: Long = 0,
        val freeHeapMB: Long = 0,
        val heapUsagePercent: Int = 0,
        val availableRAM_MB: Long = 0,
        val totalRAM_MB: Long = 0,
        val lowMemoryDevice: Boolean = false,
        val nativeHeapMB: Long = 0
    )

    data class StorageInfo(
        val internalAvailableMB: Long = 0,
        val internalTotalMB: Long = 0,
        val cacheDirMB: Long = 0,
        val filesDirMB: Long = 0,
        val externalAvailableMB: Long = 0,
        val totalAppSizeMB: Long = 0
    )

    data class NetworkInfo(
        val isConnected: Boolean = false,
        val connectionType: String = "غير متصل",
        val isMetered: Boolean = false,
        val isWifi: Boolean = false
    )

    data class BatteryInfo(
        val level: Int = -1,
        val isCharging: Boolean = false,
        val temperature: Float = 0f,
        val health: String = "غير معروف"
    )

    data class ApiHealth(
        val name: String,
        val isConfigured: Boolean,
        val lastLatencyMs: Long = 0,
        val successRate: Float = 0f,
        val totalCalls: Int = 0,
        val status: Status = Status.UNKNOWN
    ) {
        enum class Status { OK, DEGRADED, DOWN, UNKNOWN }
    }

    data class PrefsHealth(
        val name: String,
        val sizeBytes: Long,
        val sizeKB: String,
        val keyCount: Int,
        val isHealthy: Boolean
    )

    data class DbHealth(
        val totalSizeMB: Long = 0,
        val tables: List<TableInfo> = emptyList(),
        val isHealthy: Boolean = true
    ) {
        data class TableInfo(val name: String, val rowCount: Long, val sizeBytes: Long)
    }

    data class CrashHealth(
        val totalCrashFiles: Int = 0,
        val lastCrashTime: Long = 0,
        val lastCrashAge: String = "لا توجد انهيارات",
        val crashCount24h: Int = 0
    )

    data class ThreadInfo(
        val activeThreads: Int = 0,
        val threadNames: List<String> = emptyList(),
        val isMainThreadBlocked: Boolean = false
    )

    data class PipelineHealth(
        val totalEvents: Int = 0,
        val recentSuccessRate: Float = 0f,
        val lastFailure: String = "",
        val avgProductionTimeMs: Long = 0
    )

    data class Issue(
        val severity: Severity,
        val category: String,
        val title: String,
        val description: String,
        val suggestion: String
    ) {
        enum class Severity { CRITICAL, WARNING, INFO }
    }

    fun collect(context: Context): DiagnosticReport {
        val issues = mutableListOf<Issue>()

        val deviceInfo = collectDeviceInfo(context)
        val memoryInfo = collectMemoryInfo(context, issues)
        val storageInfo = collectStorageInfo(context, issues)
        val networkInfo = collectNetworkInfo(context, issues)
        val batteryInfo = collectBatteryInfo(context, issues)
        val apiHealth = collectApiHealth(context, issues)
        val prefsHealth = collectPrefsHealth(context, issues)
        val dbHealth = collectDbHealth(context, issues)
        val crashHealth = collectCrashHealth(context, issues)
        val threadInfo = collectThreadInfo(issues)
        val pipelineHealth = collectPipelineHealth(context, issues)

        return DiagnosticReport(
            timestamp = System.currentTimeMillis(),
            deviceInfo = deviceInfo,
            memoryInfo = memoryInfo,
            storageInfo = storageInfo,
            networkInfo = networkInfo,
            batteryInfo = batteryInfo,
            apiHealth = apiHealth,
            prefsHealth = prefsHealth,
            dbHealth = dbHealth,
            crashHealth = crashHealth,
            threadInfo = threadInfo,
            pipelineHealth = pipelineHealth,
            issues = issues
        )
    }

    private fun collectDeviceInfo(context: Context): DeviceInfo {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return DeviceInfo(
            appVersion = pInfo.versionName ?: "unknown",
            appVersionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pInfo.longVersionCode else pInfo.versionCode.toLong()
        )
    }

    private fun collectMemoryInfo(context: Context, issues: MutableList<Issue>): MemoryInfo {
        val runtime = Runtime.getRuntime()
        val maxHeap = runtime.maxMemory() / (1024 * 1024)
        val totalHeap = runtime.totalMemory() / (1024 * 1024)
        val freeHeap = runtime.freeMemory() / (1024 * 1024)
        val usedHeap = totalHeap - freeHeap
        val heapPercent = ((usedHeap.toFloat() / maxHeap) * 100).toInt()

        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val availableMB = memInfo.availMem / (1024 * 1024)
        val totalMB = memInfo.totalMem / (1024 * 1024)

        if (heapPercent > 85) {
            issues.add(Issue(Issue.Severity.WARNING, "الذاكرة", "استخدام الـ Heap مرتفع", "${heapPercent}% من ${maxHeap}MB مستخدمة", "أعد تشغيل التطبيق لتقليل الضغط"))
        }
        if (availableMB < 300) {
            issues.add(Issue(Issue.Severity.CRITICAL, "الذاكرة", "ذاكرة متاحة منخفضة جداً", "${availableMB}MB فقط متاحة", "أغلق التطبيقات الأخرى فوراً"))
        } else if (availableMB < 600) {
            issues.add(Issue(Issue.Severity.WARNING, "الذاكرة", "ذاكرة متاحة محدودة", "${availableMB}MB متاحة", "قد يؤثر على جودة التصدير"))
        }

        return MemoryInfo(
            totalHeapMB = maxHeap,
            usedHeapMB = usedHeap,
            freeHeapMB = freeHeap,
            heapUsagePercent = heapPercent,
            availableRAM_MB = availableMB,
            totalRAM_MB = totalMB,
            lowMemoryDevice = memInfo.lowMemory,
            nativeHeapMB = Debug.getRuntimeNativeHeap() / (1024 * 1024)
        )
    }

    private fun collectStorageInfo(context: Context, issues: MutableList<Issue>): StorageInfo {
        val internalStat = StatFs(context.filesDir.absolutePath)
        val internalAvailable = (internalStat.availableBlocksLong * internalStat.blockSizeLong) / (1024 * 1024)
        val internalTotal = (internalStat.blockCountLong * internalStat.blockSizeLong) / (1024 * 1024)

        val cacheSize = getDirSizeMB(context.cacheDir)
        val filesSize = getDirSizeMB(context.filesDir)

        val externalDir = context.getExternalFilesDir(null)
        val externalAvailable = if (externalDir != null) {
            val extStat = StatFs(externalDir.absolutePath)
            (extStat.availableBlocksLong * extStat.blockSizeLong) / (1024 * 1024)
        } else 0L

        if (internalAvailable < 200) {
            issues.add(Issue(Issue.Severity.CRITICAL, "التخزين", "مساحة داخلية حرجة", "${internalAvailable}MB فقط متاحة", "احذف بيانات مؤقتة أو فيديوهات قديمة فوراً"))
        } else if (internalAvailable < 500) {
            issues.add(Issue(Issue.Severity.WARNING, "التخزين", "مساحة داخلية محدودة", "${internalAvailable}MB متاحة", "قد يؤثر على جودة التصدير"))
        }
        if (cacheSize > 500) {
            issues.add(Issue(Issue.Severity.WARNING, "التخزين", "كاش كبير الحجم", "${cacheSize}MB في الكاش", "يمكن مسح الكاش بأمان"))
        }

        return StorageInfo(
            internalAvailableMB = internalAvailable,
            internalTotalMB = internalTotal,
            cacheDirMB = cacheSize,
            filesDirMB = filesSize,
            externalAvailableMB = externalAvailable,
            totalAppSizeMB = cacheSize + filesSize
        )
    }

    private fun collectNetworkInfo(context: Context, issues: MutableList<Issue>): NetworkInfo {
        val connManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connManager?.activeNetwork
        val caps = network?.let { connManager.getNetworkCapabilities(it) }
        val isConnected = caps != null
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)?.not() ?: true

        val connectionType = when {
            isWifi -> "WiFi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "غير معروف"
        }

        if (!isConnected) {
            issues.add(Issue(Issue.Severity.CRITICAL, "الشبكة", "لا يوجد اتصال بالإنترنت", "التطبيق يعمل في وضع محلي فقط", "تحقق من اتصال WiFi أو البيانات"))
        }

        return NetworkInfo(isConnected, connectionType, isMetered, isWifi)
    }

    private fun collectBatteryInfo(context: Context, issues: MutableList<Issue>): BatteryInfo {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val isCharging = batteryManager?.isCharging ?: false

        // Get temperature from battery intent (BATTERY_PROPERTY_TEMPERATURE not available on all APIs)
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperatureRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val temperature = temperatureRaw / 10f

        val health = when (batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "يشحن"
            BatteryManager.BATTERY_STATUS_FULL -> "ممتلئ"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "يفرغ"
            else -> "غير معروف"
        }

        if (level in 1..10 && !isCharging) {
            issues.add(Issue(Issue.Severity.WARNING, "البطارية", "بطارية منخفضة جداً", "${level}% — قد يتوقف التطبيق", "شغّل الشاحن قبل بدء الإنتاج"))
        }
        if (temperature > 40f) {
            issues.add(Issue(Issue.Severity.WARNING, "الحرارة", "جهاز ساخن", "${temperature}°C", "دع الجهاز يبرد قبل الإنتاج"))
        }

        return BatteryInfo(level, isCharging, temperature, health)
    }

    private fun collectApiHealth(context: Context, issues: MutableList<Issue>): List<ApiHealth> {
        val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
        val stats = runCatching { kotlinx.coroutines.runBlocking { ApiUsageTracker.snapshot(context) } }.getOrDefault(emptyMap())

        val services = listOf(
            Triple("Gemini", "gemini_key", "AIzaSy"),
            Triple("Groq", "groq_key", "gsk_"),
            Triple("OpenAI", "openai_key", "sk-proj-"),
            Triple("OpenRouter", "openrouter_key", "sk-or-"),
            Triple("Azure TTS", "azure_speech_key", ""),
            Triple("ElevenLabs", "elevenlabs_key", ""),
            Triple("HuggingFace", "huggingface_key", "hf_"),
            Triple("Pexels", "pexels_key", ""),
            Triple("Pixabay", "pixabay_key", "")
        )

        return services.map { (name, key, prefix) ->
            val keyVal = prefs.getString(key, "") ?: ""
            val isConfigured = keyVal.isNotBlank() && !keyVal.startsWith("your_") && keyVal != "YOUR_KEY"
            val apiEntry = stats.entries.find { it.key.contains(name, ignoreCase = true) }
            val apiStat = apiEntry?.value

            val status = when {
                !isConfigured -> ApiHealth.Status.UNKNOWN
                apiStat != null && apiStat.successRate < 0.5f -> ApiHealth.Status.DEGRADED
                apiStat != null && apiStat.totalCalls > 0 -> ApiHealth.Status.OK
                else -> ApiHealth.Status.UNKNOWN
            }

            if (isConfigured && apiStat != null && apiStat.successRate < 0.3f && apiStat.totalCalls > 5) {
                issues.add(Issue(Issue.Severity.WARNING, "الAPI", "معدل فشل مرتفع لـ $name", "نسبة النجاح ${(apiStat.successRate * 100).toInt()}% فقط (${apiStat.totalCalls} مكالمة)", "تحقق من صلاحية المفتاح أو المحاولة لاحقاً"))
            }

            ApiHealth(
                name = name,
                isConfigured = isConfigured,
                lastLatencyMs = apiStat?.lastLatencyMs ?: 0,
                successRate = apiStat?.successRate ?: 0f,
                totalCalls = (apiStat?.totalCalls ?: 0L).toInt(),
                status = status
            )
        }
    }

    private fun collectPrefsHealth(context: Context, issues: MutableList<Issue>): List<PrefsHealth> {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!prefsDir.exists()) return emptyList()

        return prefsDir.listFiles()?.filter { it.name.endsWith(".xml") }?.map { file ->
            val sizeKB = file.length() / 1024
            val sizeBytes = file.length()
            val keyCount = try {
                val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                val count = db.compileStatement("SELECT COUNT(*) FROM ${file.nameWithoutExtension}").simpleQueryForLong().toInt()
                db.close()
                count
            } catch (_: Exception) {
                // Fallback: count lines
                file.readLines().count { it.contains("<string ") || it.contains("<int ") || it.contains("<boolean ") }
            }

            val isHealthy = sizeBytes < 512 * 1024 // 512KB threshold
            if (!isHealthy) {
                issues.add(Issue(Issue.Severity.WARNING, "التخزين", "ملف تفضيلات كبير", "${file.name}: ${sizeKB}KB", "قد يؤثر على سرعة القراءة"))
            }

            PrefsHealth(
                name = file.name,
                sizeBytes = sizeBytes,
                sizeKB = "${sizeKB}KB",
                keyCount = keyCount,
                isHealthy = isHealthy
            )
        } ?: emptyList()
    }

    private fun collectDbHealth(context: Context, issues: MutableList<Issue>): DbHealth {
        val dbFile = context.getDatabasePath("qabas_database")
        if (!dbFile.exists()) return DbHealth()

        val totalSizeMB = dbFile.length() / (1024 * 1024)
        val tables = mutableListOf<DbHealth.TableInfo>()

        try {
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null)
            while (cursor.moveToNext()) {
                val tableName = cursor.getString(0)
                try {
                    val countCursor = db.rawQuery("SELECT COUNT(*) FROM `$tableName`", null)
                    countCursor.moveToFirst()
                    val count = countCursor.getLong(0)
                    countCursor.close()
                    tables.add(DbHealth.TableInfo(tableName, count, 0))
                } catch (_: Exception) {}
            }
            cursor.close()
            db.close()
        } catch (_: Exception) {}

        if (totalSizeMB > 100) {
            issues.add(Issue(Issue.Severity.WARNING, "قاعدة البيانات", "قاعدة بيانات كبيرة الحجم", "${totalSizeMB}MB", "قد يؤثر على سرعة التطبيق"))
        }

        return DbHealth(totalSizeMB, tables, true)
    }

    private fun collectCrashHealth(context: Context, issues: MutableList<Issue>): CrashHealth {
        val crashDir = File(context.filesDir, "crash_logs")
        if (!crashDir.exists()) return CrashHealth()

        val crashFiles = crashDir.listFiles()?.filter { it.name.startsWith("crash_") } ?: emptyList()
        val lastCrashFile = crashFiles.maxByOrNull { it.lastModified() }
        val lastCrashTime = lastCrashFile?.lastModified() ?: 0

        val oneDayAgo = System.currentTimeMillis() - 86400000
        val recentCrashes = crashFiles.count { it.lastModified() > oneDayAgo }

        val lastCrashAge = when {
            lastCrashTime == 0L -> "لا توجد انهيارات"
            System.currentTimeMillis() - lastCrashTime < 3600000 -> "منذ ${((System.currentTimeMillis() - lastCrashTime) / 60000).toInt()} دقيقة"
            System.currentTimeMillis() - lastCrashTime < 86400000 -> "منذ ${((System.currentTimeMillis() - lastCrashTime) / 3600000).toInt()} ساعة"
            else -> "منذ ${((System.currentTimeMillis() - lastCrashTime) / 86400000).toInt()} يوم"
        }

        if (recentCrashes > 3) {
            issues.add(Issue(Issue.Severity.CRITICAL, "الانهيارات", "انهيارات متكررة", "$recentCrashes انهيارات في آخر 24 ساعة", "راجع سجل الانهيارات في لوحة المطور"))
        } else if (recentCrashes > 0) {
            issues.add(Issue(Issue.Severity.WARNING, "الانهيارات", "انهيارات حديثة", "$lastCrashAge", "تحقق من سجل الانهيارات"))
        }

        return CrashHealth(crashFiles.size, lastCrashTime, lastCrashAge, recentCrashes)
    }

    private fun collectThreadInfo(issues: MutableList<Issue>): ThreadInfo {
        val threads = Thread.getAllStackTraces().keys
        val threadNames = threads.map { it.name }.sorted()
        val activeCount = threads.count { it.isAlive }

        // Check for common problematic threads
        val hasFFmpegThread = threadNames.any { it.contains("ffmpeg", ignoreCase = true) }
        val hasGCThread = threadNames.count { it.contains("GC", ignoreCase = true) }

        if (activeCount > 100) {
            issues.add(Issue(Issue.Severity.WARNING, "الخيوط", "عدد خيوط مرتفع", "$activeCount خيط نشط", "قد يسبب بطء الأداء"))
        }

        return ThreadInfo(activeCount, threadNames.take(20), false)
    }

    private fun collectPipelineHealth(context: Context, issues: MutableList<Issue>): PipelineHealth {
        val events = ProductionPipelineTracker.getEvents(context)
        if (events.isEmpty()) return PipelineHealth()

        val recentEvents = events.take(30)
        val successCount = recentEvents.count { it.result == ProductionPipelineTracker.Result.SUCCESS }
        val successRate = if (recentEvents.isNotEmpty()) successCount.toFloat() / recentEvents.size else 0f

        val lastFailure = events.firstOrNull { it.result == ProductionPipelineTracker.Result.FAILURE }

        val avgTime = events.filter { it.durationMs > 0 }.map { it.durationMs }.average().toLong()

        if (successRate < 0.5f && recentEvents.size > 5) {
            issues.add(Issue(Issue.Severity.WARNING, "الإنتاج", "معدل نجاح منخفض", "نسبة النجاح ${(successRate * 100).toInt()}% في آخر ${recentEvents.size} حدث", "راجع تفاصيل مسار الإنتاج"))
        }

        return PipelineHealth(recentEvents.size, successRate, lastFailure?.message ?: "", avgTime)
    }

    private fun getDirSizeMB(dir: File): Long {
        if (!dir.exists()) return 0
        var size = 0L
        dir.walkTopDown().forEach { if (it.isFile) size += it.length() }
        return size / (1024 * 1024)
    }

    object Debug {
        fun getRuntimeNativeHeap(): Long {
            return try {
                val method = android.os.Debug::class.java.getMethod("getNativeHeapAllocatedSize")
                method.invoke(null) as Long
            } catch (_: Exception) { 0L }
        }
    }
}

package com.qabas.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

class QabasApplication : Application() {

    var firebaseAnalytics: FirebaseAnalytics? = null
        private set

    override fun onCreate() {
        super.onCreate()

        PerfTracker.onAppCreate()
        QabasCrashGuard.install(this)

        // عدّاد الإقلاعات لصحة الإصدار (انهيارات / إقلاعات)
        runCatching {
            val prefs = getSharedPreferences("qabas_prefs", MODE_PRIVATE)
            prefs.edit().putInt("app_launch_count", prefs.getInt("app_launch_count", 0) + 1).apply()
        }

        // رتبة «مطور» تلقائية لبريد المالك فقط — ولو كان مسجل الدخول مسبقاً (بطلب من المالك)
        try {
            val prefs = getSharedPreferences("qabas_prefs", MODE_PRIVATE)
            val email = prefs.getString("user_email", null)
            if (prefs.getBoolean("is_logged_in", false) && CloudServices.isOwnerAccount(email)) {
                prefs.edit().putBoolean("is_admin", true).apply()
                Log.d("QabasApplication", "Owner account detected — developer rank granted")
            }
        } catch (_: Exception) {
        }

        // Initialize Firebase & Analytics with respect to user settings
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
            if (FirebaseApp.getApps(this).isNotEmpty()) {
                firebaseAnalytics = FirebaseAnalytics.getInstance(this)

                // Respect user analytics preference from SharedPreferences
                val prefs = getSharedPreferences("qabas_prefs", MODE_PRIVATE)
                val isAnalyticsEnabled = prefs.getBoolean("analytics_enabled", true)
                firebaseAnalytics?.setAnalyticsCollectionEnabled(isAnalyticsEnabled)
                Log.d("QabasApplication", "Firebase Analytics initialized with enabled state: $isAnalyticsEnabled")
            }
        } catch (t: Throwable) {
            Log.w("QabasApplication", "Firebase Analytics initialization deferred: ${t.message}")
        }

        // Initialize Supabase (Postgres + Auth + Storage + Edge Functions).
        // Runs after Firebase so analytics user props propagate to both.
        try {
            if (SupabaseConfig.isConfigured) {
                // Touch the client to trigger lazy initialization.
                SupabaseConfig.client
                Log.d(
                    "QabasApplication",
                    "Supabase configured for ${SupabaseConfig.url}"
                )
            } else {
                Log.w(
                    "QabasApplication",
                    "Supabase not configured. Set SUPABASE_URL and SUPABASE_ANON_KEY in .env to enable cloud Postgres, Auth, Storage, and Edge Functions."
                )
            }
        } catch (t: Throwable) {
            Log.w("QabasApplication", "Supabase initialization deferred: ${t.message}")
        }

        // Initialize core application services
        AppServices.init(this)

        // مهام بدء التشغيل: تحديث الإعدادات البعيدة + استقبال إشعارات البث السحابية + فحص التحديث
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { AppRemoteConfig.refreshFromCloud(this@QabasApplication) }
            runCatching { RemoteNotificationsManager.startInbox(this@QabasApplication) }
            // الطبيب الحي: فحص خفيف دوري (كل 6 ساعات) + علاج صامت للكاش فقط
            runCatching { DoctorMonitor.maybeRun(this@QabasApplication) }
            // فحص التحديث في الخلفية (كل 6 ساعات فقط، لا يزعج المستخدم)
            runCatching {
                val update = UpdateManager.checkForUpdate(this@QabasApplication)
                if (update != null) {
                    Log.d("QabasApplication", "Update available: ${update.versionName} (delta=${update.deltaUrl != null})")
                }
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        try {
            VideoCacheService.release()
        } catch (e: Exception) {
            Log.w("QabasApplication", "Error releasing VideoCacheService on terminate: ${e.message}")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try {
            VideoCacheService.release()
        } catch (e: Exception) {
            Log.w("QabasApplication", "Error releasing VideoCacheService on low memory: ${e.message}")
        }
    }
}

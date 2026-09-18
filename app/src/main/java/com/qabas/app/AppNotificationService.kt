package com.qabas.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

object AppNotificationService {
    data class AppNotification(
        val id: String = java.util.UUID.randomUUID().toString(),
        val title: String,
        val message: String,
        val isTrendAlert: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    )

    private const val PREFS_NAME = "qabas_notifications"
    private const val KEY_NOTIFICATIONS = "notifications"
    private const val KEY_DAILY_HADITH_ENABLED = "daily_hadith_enabled"
    private const val CHANNEL_ID = "qabas_trend_alerts"
    private const val HADITH_CHANNEL_ID = "qabas_daily_hadith"

    val AUTHENTIC_HADITHS = listOf(
        "قال رسول الله ﷺ: «إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ، وَإِنَّمَا لِكُلِّ امْرِئٍ مَا نَوَى» (صحيح البخاري)",
        "قال رسول الله ﷺ: «الدِّينُ النَّصِيحَةُ». قُلْنَا: لِمَنْ؟ قَالَ: «لِلَّهِ وَلِكِتَابِهِ وَلِرَسُولِهِ وَلِأَئِمَّةِ الْمُسْلِمِينَ وَعَامَّتِهِمْ» (صحيح مسلم)",
        "قال رسول الله ﷺ: «مَنْ دَعَا إِلَى هُدًى، كَانَ لَهُ مِنَ الْأَجْرِ مِثْلُ أُجُورِ مَنْ تَبِعَهُ، لَا يَنْقُصُ ذَلِكَ مِنْ أُجُورِهِمْ شَيْئًا» (صحيح مسلم)",
        "قال رسول الله ﷺ: «مَنْ سَلَكَ طَرِيقًا يَلْتَمِسُ فِيهِ عِلْمًا، سَهَّلَ اللهُ لَهُ بِهِ طَرِيقًا إِلَى الْجَنَّةِ» (صحيح مسلم)",
        "قال رسول الله ﷺ: «الْمُؤْمِنُ الْقَوِيُّ خَيْرٌ وَأَحَبُّ إِلَى اللهِ مِنَ الْمُؤْمِنِ الضَّعِيفِ، وَفِي كُلٍّ خَيْرٌ» (صحيح مسلم)",
        "قال رسول الله ﷺ: «أَحَبُّ الأَعْمَالِ إِلَى اللَّهِ أَدْوَمُهَا وَإِنْ قَلَّ» (صحيح البخاري ومسلم)",
        "قال رسول الله ﷺ: «احْفَظِ اللَّهَ يَحْفَظْكَ، احْفَظِ اللَّهَ تَجِدْهُ تُجَاهَكَ» (سنن الترمذي - صحيح)",
        "قال رسول الله ﷺ: «الْكَلِمَةُ الطَّيِّبَةُ صَدَقَةٌ» (صحيح البخاري ومسلم)",
        "قال رسول الله ﷺ: «مَنْ كَانَ يُؤْمِنُ بِاللَّهِ وَالْيَوْمِ الْآخِرِ فَلْيَقُلْ خَيْرًا أَوْ لِيَصْمُتْ» (صحيح البخاري ومسلم)",
        "قال رسول الله ﷺ: «لا يَغْرِسُ مُسْلِمٌ غَرْسًا، وَلا يَزْرَعُ زَرْعًا، فَيَأْكُلَ مِنْهُ إِنْسَانٌ وَلا دَابَّةٌ وَلا شَيْءٌ، إِلا كَانَتْ لَهُ صَدَقَةً» (صحيح مسلم)",
        "قال رسول الله ﷺ: «تَبَسُّمُكَ فِي وَجْهِ أَخِيكَ لَكَ صَدَقَةٌ» (سنن الترمذي - صحيح)",
        "قال رسول الله ﷺ: «عَجَبًا لِأَمْرِ الْمُؤْمِنِ، إِنَّ أَمْرَهُ كُلَّهُ خَيْرٌ، وَلَيْسَ ذَاكَ لِأَحَدٍ إِلَّا لِلْمُؤْمِنِ، إِنْ أَصَابَتْهُ سَرَّاءُ شَكَرَ فَكَانَ خَيْرًا لَهُ، وَإِنْ أَصَابَتْهُ ضَرَّاءُ صَبَرَ فَكَانَ خَيْرًا لَهُ» (صحيح مسلم)",
        "قال رسول الله ﷺ: «خَيْرُكُمْ مَنْ تَعَلَّمَ القُرْآنَ وَعَلَّمَهُ» (صحيح البخاري)",
        "قال رسول الله ﷺ: «إِنَّ اللَّهَ يُحِبُّ إِذَا عَمِلَ أَحَدُكُمْ عَمَلًا أَنْ يُتْقِنَهُ» (شعب الإيمان - صحيح)"
    )

    fun isDailyHadithEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DAILY_HADITH_ENABLED, true)
    }

    fun setDailyHadithEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DAILY_HADITH_ENABLED, enabled).apply()
        if (enabled) {
            scheduleDailyHadithAlarm(context)
        } else {
            cancelDailyHadithAlarm(context)
        }
    }

    fun triggerDailyHadithNow(context: Context): String {
        val hadith = AUTHENTIC_HADITHS.random()
        sendNotification(
            context = context,
            title = "📖 حديث اليوم النبوي الشريف",
            message = hadith,
            isTrendAlert = false
        )
        postHadithSystemNotification(context, "📖 حديث اليوم النبوي الشريف", hadith)
        return hadith
    }

    fun scheduleDailyHadithAlarm(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, DailyHadithReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val calendar = Calendar.getInstance().apply {
                timeInMillis = System.currentTimeMillis()
                set(Calendar.HOUR_OF_DAY, 8) // 8:00 AM daily
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                pendingIntent
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cancelDailyHadithAlarm(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, DailyHadithReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1001,
                intent,
                PendingIntent.FLAG_NO_CREATE or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun postHadithSystemNotification(context: Context, title: String, message: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (notificationManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        HADITH_CHANNEL_ID,
                        "الحديث النبوي اليومي التلقائي",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "إشعار يومي بأحاديث صحيحة من صحيح البخاري ومسلم ينشر تلقائياً"
                    }
                    notificationManager.createNotificationChannel(channel)
                }

                val builder = NotificationCompat.Builder(context, HADITH_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)

                notificationManager.notify((System.currentTimeMillis() % 10000).toInt(), builder.build())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getNotifications(context: Context): List<AppNotification> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_NOTIFICATIONS, "[]") ?: "[]"
        val list = mutableListOf<AppNotification>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    AppNotification(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        message = obj.getString("message"),
                        isTrendAlert = obj.optBoolean("isTrendAlert", false),
                        timestamp = obj.getLong("timestamp")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun sendNotification(context: Context, title: String, message: String, isTrendAlert: Boolean = false) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val notifications = getNotifications(context).toMutableList()
        notifications.add(0, AppNotification(title = title, message = message, isTrendAlert = isTrendAlert))
        
        val array = JSONArray()
        for (notif in notifications) {
            val obj = JSONObject()
            obj.put("id", notif.id)
            obj.put("title", notif.title)
            obj.put("message", notif.message)
            obj.put("isTrendAlert", notif.isTrendAlert)
            obj.put("timestamp", notif.timestamp)
            array.put(obj)
        }
        
        prefs.edit().putString(KEY_NOTIFICATIONS, array.toString()).apply()

        // Post System Notification
        postSystemNotification(context, title, message)
    }

    private fun postSystemNotification(context: Context, title: String, message: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (notificationManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "تنبيهات مرصد الثغور والفتن",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "إشعارات عاجلة عند ظهور تريندات وشبهات تحتاج لمدافعة سريعة"
                    }
                    notificationManager.createNotificationChannel(channel)
                }

                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)

                notificationManager.notify((System.currentTimeMillis() % 10000).toInt(), builder.build())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearNotifications(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_NOTIFICATIONS).apply()
    }
}

class DailyHadithReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (AppNotificationService.isDailyHadithEnabled(context)) {
            AppNotificationService.triggerDailyHadithNow(context)
        }
    }
}

class ProgressNotificationService(val context: Context) {
    fun showProgressNotification(progress: Int, max: Int, message: String) {
        try {
            // Optional system progress notification update
        } catch (e: Throwable) {
            // Silently ignore notification failure to prevent process death
        }
    }
    fun hideProgressNotification() {
        try {
            // Dismiss any active progress notification
        } catch (e: Throwable) {
            // Silently ignore
        }
    }
    fun showCompletionNotification() {
        try {
            AppNotificationService.sendNotification(context, "اكتملت المعالجة", "تم الانتهاء من معالجة الفيديو بنجاح.")
        } catch (e: Throwable) {
            // Silently ignore notification failure to prevent process death
        }
    }
    fun showErrorNotification() {
        try {
            AppNotificationService.sendNotification(context, "خطأ في المعالجة", "حدث خطأ أثناء معالجة الفيديو.")
        } catch (e: Throwable) {
            // Silently ignore notification failure to prevent process death
        }
    }
}


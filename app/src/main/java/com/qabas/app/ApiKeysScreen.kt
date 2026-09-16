package com.qabas.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DEFAULT_BROWSER_FALLBACKS = listOf(
    "com.android.chrome",
    "com.chrome.beta",
    "org.mozilla.firefox",
    "org.mozilla.firefox.beta",
    "com.opera.browser",
    "com.opera.mini.native",
    "com.sec.android.app.sbrowser",
    "com.microsoft.emmx",
    "com.brave.browser",
    "com.duckduckgo.mobile.android",
    "com.transsion.phoenix",
    "com.android.browser"
)

private fun openUrl(context: Context, url: String) {
    val uri = Uri.parse(url)
    val pm = context.packageManager
    try {
        val implicit = Intent(Intent.ACTION_VIEW, uri)
        if (implicit.resolveActivity(pm) != null) {
            context.startActivity(implicit)
            return
        }
    } catch (_: Exception) {
        // ننتقل للمتصفحات الصريحة
    }
    for (pkg in DEFAULT_BROWSER_FALLBACKS) {
        try {
            val explicit = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
            if (explicit.resolveActivity(pm) != null) {
                context.startActivity(explicit)
                return
            }
        } catch (_: Exception) {
            // جرّب المتصفح التالي
        }
    }
    Toast.makeText(context, "لا يوجد متصفح مثبت على الجهاز — افتح الرابط يدوياً: $url", Toast.LENGTH_LONG).show()
}

private val KEY_PATTERNS: Map<String, Regex> = mapOf(
    "gemini" to Regex("""AIzaSy[A-Za-z0-9_\-]{33}"""),
    "groq" to Regex("""(?:gsk_[A-Za-z0-9_\-]{10,}|xai-[A-Za-z0-9_\-]{10,})"""),
    "huggingface" to Regex("""hf_[A-Za-z0-9]{10,}"""),
    "elevenlabs" to Regex("""xi-[A-Za-z0-9_\-]{10,}"""),
    "openai" to Regex("""sk-[A-Za-z0-9_\-]{20,}"""),
    "azure" to Regex("""(?i)[a-f0-9]{32}"""),
    "pexels" to Regex("""(?<![A-Za-z0-9_-])[A-Za-z0-9]{56}(?![A-Za-z0-9_-])"""),
    "pixabay" to Regex("""\d{7,10}-[a-f0-9]{16,32}""")
)

fun detectKeyForService(serviceType: String, text: String): String? {
    val pattern = KEY_PATTERNS[serviceType.lowercase()] ?: return null
    return pattern.find(text)?.value?.trim()
}

fun detectKeysForAutoFill(text: String): Map<String, String> {
    val found = linkedMapOf<String, String>()
    KEY_PATTERNS.forEach { (service, regex) ->
        val match = regex.find(text)?.value?.trim()
        if (!match.isNullOrBlank()) found[service] = match
    }
    return found
}

object ApiKeysBackupManager {
    fun generateExportJson(
        prefs: SharedPreferences,
        currentKeys: Map<String, String>
    ): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("appName", "Qabas")
        root.put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        
        val keysObj = JSONObject()
        val allKeysToExport = listOf(
            "gemini_key",
            "groq_key",
            "openai_key",
            "pexels_key",
            "pixabay_key",
            "huggingface_key",
            "azure_speech_key",
            "azure_speech_region",
            "elevenlabs_key",
            "firebase_key",
            "firebase_project_id",
            "firebase_app_id"
        )
        
        for (k in allKeysToExport) {
            val v = currentKeys[k] ?: prefs.getString(k, "") ?: ""
            if (v.isNotBlank()) {
                keysObj.put(k, v.trim())
            }
        }
        
        root.put("keys", keysObj)
        return root.toString(4)
    }

    fun parseAndApplyImport(
        rawContent: String,
        prefs: SharedPreferences
    ): Pair<Int, Map<String, String>> {
        val importedMap = mutableMapOf<String, String>()
        
        try {
            val trimmed = rawContent.trim()
            if (trimmed.startsWith("{")) {
                val json = JSONObject(trimmed)
                val targetObj = if (json.has("keys")) json.optJSONObject("keys") ?: json else json
                
                val keyMappings = mapOf(
                    "gemini" to "gemini_key",
                    "gemini_key" to "gemini_key",
                    "gemini_api_key" to "gemini_key",
                    "geminikey" to "gemini_key",
                    
                    "groq" to "groq_key",
                    "groq_key" to "groq_key",
                    "groq_api_key" to "groq_key",
                    "groqkey" to "groq_key",
                    
                    "azure" to "azure_speech_key",
                    "azure_speech" to "azure_speech_key",
                    "azure_speech_key" to "azure_speech_key",
                    "azure_key" to "azure_speech_key",
                    "azurespeechkey" to "azure_speech_key",
                    
                    "azure_speech_region" to "azure_speech_region",
                    "azure_region" to "azure_speech_region",
                    "azureregion" to "azure_speech_region",
                    "region" to "azure_speech_region",
                    
                    "elevenlabs" to "elevenlabs_key",
                    "elevenlabs_key" to "elevenlabs_key",
                    "eleven_key" to "elevenlabs_key",
                    "elevenlabskey" to "elevenlabs_key",
                    
                    "pexels" to "pexels_key",
                    "pexels_key" to "pexels_key",
                    "pexels_api_key" to "pexels_key",
                    "pexelskey" to "pexels_key",
                    
                    "pixabay" to "pixabay_key",
                    "pixabay_key" to "pixabay_key",
                    "pixabay_api_key" to "pixabay_key",
                    "pixabaykey" to "pixabay_key",
                    
                    "huggingface" to "huggingface_key",
                    "huggingface_key" to "huggingface_key",
                    "hf_key" to "huggingface_key",
                    "hf_token" to "huggingface_key",
                    "huggingfacekey" to "huggingface_key",
                    
                    "firebase" to "firebase_key",
                    "firebase_key" to "firebase_key",
                    "firebasekey" to "firebase_key",
                    "firebase_project_id" to "firebase_project_id",
                    "firebase_app_id" to "firebase_app_id"
                )
                
                val keysIterator = targetObj.keys()
                while (keysIterator.hasNext()) {
                    val rawKey = keysIterator.next()
                    val normalizedKey = rawKey.lowercase().trim()
                    val prefKey = keyMappings[normalizedKey] ?: if (keyMappings.values.contains(normalizedKey)) normalizedKey else null
                    
                    val value = targetObj.optString(rawKey, "").trim()
                    if (prefKey != null && value.isNotBlank()) {
                        importedMap[prefKey] = value
                    }
                }
            }
        } catch (e: Exception) {
            // If JSON fails, fallback will handle raw text/env
        }

        // If JSON didn't yield anything or failed, parse via line-by-line / regex
        if (importedMap.isEmpty()) {
            val lines = rawContent.lines()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("//")) continue
                val cleanLine = trimmed.replace(Regex("""^(val|var|const|let|String|final|static|\"|\')\s*"""), "")
                val parts = cleanLine.split("=", ":", limit = 2)
                if (parts.size == 2) {
                    val keyName = parts[0].trim().lowercase().removeSurrounding("\"", "'")
                    val valStr = parts[1].trim().removeSurrounding("\"", "'").removeSuffix(";").removeSuffix(",")
                    if (valStr.isNotBlank()) {
                        when {
                            keyName.contains("gemini") -> importedMap["gemini_key"] = valStr
                            keyName.contains("groq") -> importedMap["groq_key"] = valStr
                            keyName.contains("azure_region") || keyName.endsWith("_region") || (keyName.contains("region") && !keyName.contains("speech")) -> importedMap["azure_speech_region"] = valStr
                            keyName.contains("azure") || keyName.contains("speech") -> importedMap["azure_speech_key"] = valStr
                            keyName.contains("eleven") -> importedMap["elevenlabs_key"] = valStr
                            keyName.contains("pexels") -> importedMap["pexels_key"] = valStr
                            keyName.contains("pixabay") -> importedMap["pixabay_key"] = valStr
                            keyName.contains("hugging") || keyName.contains("hf") -> importedMap["huggingface_key"] = valStr
                            keyName.contains("firebase_project") -> importedMap["firebase_project_id"] = valStr
                            keyName.contains("firebase") -> importedMap["firebase_key"] = valStr
                        }
                    }
                }
            }
            
            // Regex matchers as fallback
            val geminiMatch = Regex("""AIzaSy[A-Za-z0-9_-]{33}""").find(rawContent)?.value
            if (geminiMatch != null && !importedMap.containsKey("gemini_key")) {
                importedMap["gemini_key"] = geminiMatch
            }
            val groqMatch = Regex("""gsk_[A-Za-z0-9]{40,}""").find(rawContent)?.value
            if (groqMatch != null && !importedMap.containsKey("groq_key")) {
                importedMap["groq_key"] = groqMatch
            }
            val hfMatch = Regex("""hf_[A-Za-z0-9]{34,36}""").find(rawContent)?.value
            if (hfMatch != null && !importedMap.containsKey("huggingface_key")) {
                importedMap["huggingface_key"] = hfMatch
            }
            val pixabayMatch = Regex("""[0-9]{8}-[a-f0-9]{24}""").find(rawContent)?.value
            if (pixabayMatch != null && !importedMap.containsKey("pixabay_key")) {
                importedMap["pixabay_key"] = pixabayMatch
            }
            val pexelsMatch = Regex("""[a-zA-Z0-9]{56}""").find(rawContent)?.value
            if (pexelsMatch != null && !importedMap.containsKey("pexels_key") && pexelsMatch != geminiMatch && pexelsMatch != groqMatch) {
                importedMap["pexels_key"] = pexelsMatch
            }
        }

        if (importedMap.isNotEmpty()) {
            val editor = prefs.edit()
            importedMap.forEach { (k, v) ->
                editor.putString(k, v.trim())
            }
            editor.apply()
        }

        return Pair(importedMap.size, importedMap)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
    
    var geminiKey by remember { mutableStateOf(prefs.getString("gemini_key", "") ?: "") }
    var pexelsKey by remember { mutableStateOf(prefs.getString("pexels_key", "") ?: "") }
    var pixabayKey by remember { mutableStateOf(prefs.getString("pixabay_key", "") ?: "") }
    var huggingfaceKey by remember { mutableStateOf(prefs.getString("huggingface_key", "") ?: "") }
    var groqKey by remember { mutableStateOf(prefs.getString("groq_key", "") ?: "") }
    var azureSpeechKey by remember { mutableStateOf(prefs.getString("azure_speech_key", "") ?: "") }
    var azureSpeechRegion by remember { mutableStateOf(prefs.getString("azure_speech_region", "") ?: "") }
    var elevenLabsKey by remember { mutableStateOf(prefs.getString("elevenlabs_key", "") ?: "") }
    var firebaseKey by remember { mutableStateOf(prefs.getString("firebase_key", "") ?: "") }
    var openaiKey by remember { mutableStateOf(prefs.getString("openai_key", "") ?: "") }
    
    var saveMessage by remember { mutableStateOf("") }
    var isSavingAndValidating by remember { mutableStateOf(false) }
    var showSmartPasteDialog by remember { mutableStateOf(false) }
    var smartPasteText by remember { mutableStateOf("") }
    var showExportOptionsDialog by remember { mutableStateOf(false) }
    var lastGlobalClipboard by remember { mutableStateOf("") }
    var autoCaptureMessage by remember { mutableStateOf<String?>(null) }

    // ── Auto-detect & distribute keys from clipboard ──
    fun autoDetectAndDistribute(clipboardText: String) {
        if (clipboardText.isBlank() || clipboardText == lastGlobalClipboard) return
        lastGlobalClipboard = clipboardText
        val detected = detectKeysForAutoFill(clipboardText)
        if (detected.isEmpty()) return

        val saved = mutableListOf<String>()
        detected.forEach { (service, key) ->
            when (service) {
                "gemini" -> { geminiKey = key; saved.add("Gemini") }
                "groq" -> { groqKey = key; saved.add("Groq") }
                "openai" -> { openaiKey = key; saved.add("OpenAI") }
                "huggingface" -> { huggingfaceKey = key; saved.add("HuggingFace") }
                "azure" -> { azureSpeechKey = key; saved.add("Azure TTS") }
                "elevenlabs" -> { elevenLabsKey = key; saved.add("ElevenLabs") }
                "pexels" -> { pexelsKey = key; saved.add("Pexels") }
                "pixabay" -> { pixabayKey = key; saved.add("Pixabay") }
            }
        }
        if (saved.isNotEmpty()) {
            // Auto-save to prefs immediately
            prefs.edit().apply {
                detected["gemini"]?.let { putString("gemini_key", it) }
                detected["groq"]?.let { putString("groq_key", it) }
                detected["openai"]?.let { putString("openai_key", it) }
                detected["huggingface"]?.let { putString("huggingface_key", it) }
                detected["azure"]?.let { putString("azure_speech_key", it) }
                detected["elevenlabs"]?.let { putString("elevenlabs_key", it) }
                detected["pexels"]?.let { putString("pexels_key", it) }
                detected["pixabay"]?.let { putString("pixabay_key", it) }
            }.apply()
            autoCaptureMessage = "تم التقاط ${saved.size} مفتاح تلقائياً: ${saved.joinToString("، ")} ✅"
            SystemLogsManager.addLog("AUTO_CAPTURE", "التقاط تلقائي: ${saved.joinToString(", ")}", Color(0xFF10B981))
        }
    }

    // ── Clipboard change listener (real-time auto-capture) ──
    val androidClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    DisposableEffect(Unit) {
        val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
            val text = androidClipboard?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            autoDetectAndDistribute(text)
        }
        androidClipboard?.addPrimaryClipChangedListener(listener)
        onDispose {
            androidClipboard?.removePrimaryClipChangedListener(listener)
        }
    }

    // ── Check clipboard on screen resume ──
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val text = androidClipboard?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                autoDetectAndDistribute(text)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Auto-dismiss capture message
    LaunchedEffect(autoCaptureMessage) {
        if (autoCaptureMessage != null) {
            kotlinx.coroutines.delay(5000)
            autoCaptureMessage = null
        }
    }

    fun syncUiWithPrefs(imported: Map<String, String>) {
        imported["gemini_key"]?.let { geminiKey = it }
        imported["groq_key"]?.let { groqKey = it }
        imported["pexels_key"]?.let { pexelsKey = it }
        imported["pixabay_key"]?.let { pixabayKey = it }
        imported["huggingface_key"]?.let { huggingfaceKey = it }
        imported["azure_speech_key"]?.let { azureSpeechKey = it }
        imported["azure_speech_region"]?.let { azureSpeechRegion = it }
        imported["elevenlabs_key"]?.let { elevenLabsKey = it }
        imported["firebase_key"]?.let { firebaseKey = it }
        imported["openai_key"]?.let { openaiKey = it }
    }

    val currentKeysMap = remember(geminiKey, groqKey, pexelsKey, pixabayKey, huggingfaceKey, firebaseKey, openaiKey) {
        mapOf(
            "gemini_key" to geminiKey,
            "groq_key" to groqKey,
            "pexels_key" to pexelsKey,
            "pixabay_key" to pixabayKey,
            "huggingface_key" to huggingfaceKey,
            "firebase_key" to firebaseKey,
            "openai_key" to openaiKey
        )
    }

    // Export JSON Launcher (SAF Save Dialog)
    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val jsonString = ApiKeysBackupManager.generateExportJson(prefs, currentKeysMap)
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(jsonString.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "تم تصدير المفاتيح بنجاح إلى ملف JSON 📁✨", Toast.LENGTH_LONG).show()
                SystemLogsManager.addLog("SUCCESS", "تم تصدير ملف مفاتيح API بصيغة JSON بنجاح", Color(0xFF10B981))
            } catch (e: Exception) {
                Toast.makeText(context, "فشل تصدير الملف: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Import JSON / File Launcher
    val importJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val fileContent = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (fileContent.isNotBlank()) {
                    val (count, map) = ApiKeysBackupManager.parseAndApplyImport(fileContent, prefs)
                    if (count > 0) {
                        syncUiWithPrefs(map)
                        Toast.makeText(context, "تم استيراد $count مفاتيح بنجاح من الملف! 📥✨", Toast.LENGTH_LONG).show()
                        SystemLogsManager.addLog("SUCCESS", "تم استيراد $count مفاتيح من ملف JSON وتحديثها", Color(0xFF10B981))
                    } else {
                        Toast.makeText(context, "لم يتم العثور على مفاتيح صالحة داخل الملف", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "الملف المحدد فارغ", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "خطأ في قراءة ملف المفاتيح: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun shareExportJson() {
        try {
            val jsonString = ApiKeysBackupManager.generateExportJson(prefs, currentKeysMap)
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, jsonString)
                putExtra(Intent.EXTRA_TITLE, "نسخة احتياطية لمفاتيح تطبيق قبس (qabas_api_keys.json)")
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, "مشاركة / حفظ مفاتيح قبس")
            context.startActivity(shareIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "تعذر مشاركة المفاتيح: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    val readinessReport = remember(currentKeysMap) {
        OperationalReadinessManager.calculateReadiness(context, currentKeysMap)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Translator.tr("مفاتيح واجهة البرمجة (API Keys)"), fontFamily = CairoFont, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = GoldPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { exportJsonLauncher.launch("qabas_api_keys.json") }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "تصدير المفاتيح", tint = GoldPrimary)
                    }
                    IconButton(onClick = { importJsonLauncher.launch("*/*") }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "استيراد المفاتيح", tint = GoldSecondary)
                    }
                    IconButton(onClick = { shareExportJson() }) {
                        Icon(Icons.Default.Share, contentDescription = "مشاركة المفاتيح", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DeepSlate)
            )
        },
        containerColor = DeepSlate
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Auto-capture notification banner
                if (autoCaptureMessage != null) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF10B981).copy(alpha = 0.15f)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    autoCaptureMessage ?: "",
                                    color = Color(0xFF10B981),
                                    fontFamily = CairoFont,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { autoCaptureMessage = null }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color(0xFF10B981), modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }

                item {
                    // مجاني تماماً: بطاقة توضح أن التطبيق يعمل بلا أي مفتاح
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF10B981).copy(alpha = 0.08f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = Color(0xFF10B981).copy(alpha = 0.2f),
                                    shape = CircleShape,
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(22.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = Translator.tr("يعمل مجاناً دون أي مفتاح 🎉"),
                                        color = Color(0xFF10B981),
                                        fontFamily = CairoFont,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        text = "التحليل المحلي + محرك النطق المدمج في أندرويد + محرك FFmpeg كلها مجانية وتعمل دون إنترنت.",
                                        color = TextSecondary,
                                        fontFamily = CairoFont,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "المفاتيح أدناه اختيارية — أضفها فقط إذا أردت إخراجاً أذكى وأجمل (النصوص بالذكاء الاصطناعي، مقاطع B-Roll حقيقية، أصوات سينمائية). كل خدماتها لها طبقة مجانية.",
                                color = TextPrimary,
                                fontFamily = CairoFont,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                item {
                    // Operational Readiness Percentage Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF151B2B)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "نسبة جاهزية تشغيل التطبيق الحقيقية",
                                        color = TextSecondary,
                                        fontFamily = CairoFont,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = readinessReport.statusTitle,
                                        color = GoldPrimary,
                                        fontSize = 17.sp,
                                        fontFamily = CairoFont,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Surface(
                                    color = GoldPrimary.copy(alpha = 0.15f),
                                    shape = CircleShape,
                                    border = androidx.compose.foundation.BorderStroke(2.dp, GoldPrimary)
                                ) {
                                    Box(
                                        modifier = Modifier.size(64.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${readinessReport.percentage}%",
                                            color = GoldPrimary,
                                            fontFamily = CairoFont,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 20.sp
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Progress Bar
                            LinearProgressIndicator(
                                progress = { readinessReport.percentage / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp)),
                                color = when {
                                    readinessReport.percentage >= 80 -> Color(0xFF10B981)
                                    readinessReport.percentage >= 35 -> GoldPrimary
                                    else -> Color(0xFFF5D76E)
                                },
                                trackColor = Color(0xFF0B0F19)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = readinessReport.statusDescription,
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontFamily = CairoFont,
                                lineHeight = 18.sp
                            )

                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = Color(0xFF1E293B))
                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "تأثير المفاتيح على النسبة الحقيقية للتشغيل:",
                                color = TextSecondary,
                                fontFamily = CairoFont,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                readinessReport.services.forEach { service ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (service.isConfigured) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (service.isConfigured) Color(0xFF10B981) else Color.Gray,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "${service.name} (+${service.weight}%)",
                                                color = if (service.isConfigured) Color.White else Color.Gray,
                                                fontFamily = CairoFont,
                                                fontSize = 11.sp,
                                                fontWeight = if (service.isConfigured) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                        Text(
                                            text = if (service.isConfigured) "حقيقي 🟢" else "محاكاة محلي 🟡",
                                            color = if (service.isConfigured) Color(0xFF10B981) else Color(0xFFF5D76E),
                                            fontFamily = CairoFont,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            if (!readinessReport.lastError.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Surface(
                                    color = Color(0xFFEF4444).copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "آخر تقرير خطأ: ${readinessReport.lastError}",
                                            color = Color(0xFFFCA5A5),
                                            fontFamily = CairoFont,
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Dedicated Backup & Import / Export Card
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0F19)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = GoldPrimary.copy(alpha = 0.2f),
                                    shape = CircleShape,
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Backup, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(22.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "النسخ الاحتياطي والمزامنة السريعة (JSON) ⚡",
                                        color = GoldPrimary,
                                        fontFamily = CairoFont,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        text = "تصدير واستيراد جميع مفاتيحك بضغطة زر دون الحاجة لإعادة كتابتها.",
                                        color = TextSecondary,
                                        fontFamily = CairoFont,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // First Row: Export & Import JSON
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { exportJsonLauncher.launch("qabas_api_keys.json") },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.FileDownload, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("تصدير JSON 📤", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }

                                Button(
                                    onClick = { importJsonLauncher.launch("*/*") },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF151B2B)),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.FileUpload, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("استيراد JSON 📥", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Second Row: Smart Paste & Share
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showSmartPasteDialog = true },
                                    modifier = Modifier.weight(1f).height(42.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A3A4F)),
                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF141C27)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = GoldSecondary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("اللصق الذكي 📋", color = GoldSecondary, fontFamily = CairoFont, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = { shareExportJson() },
                                    modifier = Modifier.weight(1f).height(42.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A3A4F)),
                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF141C27)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("مشاركة المفاتيح 🔗", color = TextSecondary, fontFamily = CairoFont, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                // ─── Cloud Sync Panel ───
                item {
                    var syncState by remember { mutableStateOf(0) } // 0=idle, 1=pushing, 2=pulling, 3=syncing
                    var syncMessage by remember { mutableStateOf<String?>(null) }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF8B5CF6).copy(alpha = 0.06f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(color = Color(0xFF8B5CF6).copy(alpha = 0.15f), shape = CircleShape, modifier = Modifier.size(38.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.CloudSync, contentDescription = null, tint = Color(0xFF8B5CF6), modifier = Modifier.size(19.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("المزامنة السحابية ☁️", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("اختر جهازاً آخر ومزّن المفاتيح بينهما", color = TextSecondary, fontFamily = CairoFont, fontSize = 11.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            if (syncMessage != null) {
                                Card(colors = CardDefaults.cardColors(containerColor = if (syncState == 0) Color(0xFFEF4444).copy(alpha = 0.08f) else Color(0xFF10B981).copy(alpha = 0.08f)), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                                    Text(syncMessage!!, modifier = Modifier.padding(10.dp), color = if (syncState == 0) Color(0xFFEF4444) else Color(0xFF10B981), fontFamily = CairoFont, fontSize = 12.sp)
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        syncState = 1; syncMessage = null
                                        KeySyncService.pushToCloud(context) { ok, msg ->
                                            syncMessage = msg; syncState = 0
                                        }
                                    },
                                    enabled = syncState == 0 && SupabaseConfig.isConfigured,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (syncState == 1) CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                                    else { Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                                    Text("رفع ☁️", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                                Button(
                                    onClick = {
                                        syncState = 2; syncMessage = null
                                        KeySyncService.pullFromCloud(context) { ok, msg ->
                                            syncMessage = msg; syncState = 0
                                        }
                                    },
                                    enabled = syncState == 0 && SupabaseConfig.isConfigured,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22D3EE)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (syncState == 2) CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                                    else { Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                                    Text("سحب 📥", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                                Button(
                                    onClick = {
                                        syncState = 3; syncMessage = null
                                        KeySyncService.syncBidirectional(context) { ok, msg ->
                                            syncMessage = msg; syncState = 0
                                        }
                                    },
                                    enabled = syncState == 0 && SupabaseConfig.isConfigured,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (syncState == 3) CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                                    else { Icon(Icons.Default.SyncAlt, contentDescription = null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                                    Text("مزامنة 🔄", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }

                            if (!SupabaseConfig.isConfigured) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("⚠️ Supabase غير مُعد — أضف SUPABASE_URL و SUPABASE_ANON_KEY في .env", color = Color(0xFFF59E0B), fontFamily = CairoFont, fontSize = 10.sp)
                            }
                        }
                    }
                }

                // ─── Deep Link Share Panel ───
                item {
                    var showShareDialog by remember { mutableStateOf(false) }
                    var shareLink by remember { mutableStateOf("") }
                    var linkImportMessage by remember { mutableStateOf<String?>(null) }

                    if (showShareDialog) {
                        AlertDialog(
                            onDismissRequest = { showShareDialog = false },
                            title = { Text("مشاركة المفاتيح 🔗", fontFamily = CairoFont, fontWeight = FontWeight.Bold, color = DeepSlate) },
                            text = {
                                Column {
                                    Text("شارك هذا الرابط مع جهاز آخر:", fontFamily = CairoFont, fontSize = 12.sp, color = TextSecondary)
                                    Spacer(Modifier.height(8.dp))
                                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141C27)), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        Text(shareLink, modifier = Modifier.padding(10.dp), color = Color(0xFF22D3EE), fontFamily = CairoFont, fontSize = 10.sp, lineHeight = 14.sp)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text("⚠️ الرابط يحتوي مفاتيح خاصة — لا تشاركه مع غير موثوقين", color = Color(0xFFF59E0B), fontFamily = CairoFont, fontSize = 10.sp)
                                    Spacer(Modifier.height(10.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("keys", shareLink))
                                            linkImportMessage = "تم النسخ ✅"
                                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { kotlinx.coroutines.delay(2000); linkImportMessage = null }
                                        }, shape = RoundedCornerShape(8.dp)) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("نسخ 📋", fontFamily = CairoFont, fontSize = 11.sp)
                                        }
                                        OutlinedButton(onClick = {
                                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                putExtra(Intent.EXTRA_TEXT, shareLink)
                                                type = "text/plain"
                                            }
                                            context.startActivity(Intent.createChooser(sendIntent, "مشاركة مفاتيح قبس"))
                                        }, shape = RoundedCornerShape(8.dp)) {
                                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("إرسال 📤", fontFamily = CairoFont, fontSize = 11.sp)
                                        }
                                    }
                                    linkImportMessage?.let {
                                        Spacer(Modifier.height(6.dp))
                                        Text(it, color = Color(0xFF10B981), fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                            },
                            confirmButton = {},
                            dismissButton = {
                                TextButton(onClick = { showShareDialog = false }) { Text("إغلاق", fontFamily = CairoFont, color = TextSecondary) }
                            }
                        )
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF22D3EE).copy(alpha = 0.06f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF22D3EE).copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(color = Color(0xFF22D3EE).copy(alpha = 0.15f), shape = CircleShape, modifier = Modifier.size(38.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Link, contentDescription = null, tint = Color(0xFF22D3EE), modifier = Modifier.size(19.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("مشاركة عبر رابط 🔗", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("شارك مفاتيحك مع جهاز آخر عبر رابط qabas://keys/import", color = TextSecondary, fontFamily = CairoFont, fontSize = 11.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        val activeKeys = mutableMapOf<String, String>()
                                        if (geminiKey.isNotBlank()) activeKeys["gemini_key"] = geminiKey
                                        if (groqKey.isNotBlank()) activeKeys["groq_key"] = groqKey
                                        if (openaiKey.isNotBlank()) activeKeys["openai_key"] = openaiKey
                                        if (huggingfaceKey.isNotBlank()) activeKeys["huggingface_key"] = huggingfaceKey
                                        if (azureSpeechKey.isNotBlank()) activeKeys["azure_speech_key"] = azureSpeechKey
                                        if (azureSpeechRegion.isNotBlank()) activeKeys["azure_speech_region"] = azureSpeechRegion
                                        if (elevenLabsKey.isNotBlank()) activeKeys["elevenlabs_key"] = elevenLabsKey
                                        if (pexelsKey.isNotBlank()) activeKeys["pexels_key"] = pexelsKey
                                        if (pixabayKey.isNotBlank()) activeKeys["pixabay_key"] = pixabayKey
                                        shareLink = KeyDeepLinkHandler.generateShareLink(activeKeys)
                                        showShareDialog = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22D3EE)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("إنشاء رابط مشاركة 📤", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                                Button(
                                    onClick = {
                                        showSmartPasteDialog = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF141C27)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Input, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("استيراد من رابط 📥", color = TextSecondary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                item {
                    LiveHealthCheckPanel()
                }

                item {
                    KeyGroupHeader("الذكاء الاصطناعي — متصلة بالمحرك")
                }

                item {
                    ApiKeyCard(
                        serviceType = "gemini",
                        title = "1. Gemini API (Google AI)",
                        description = Translator.tr("النموذج الأذكى لصياغة السكريبتات الدعوية والمساعد الإسلامي الذكي وقصص الأنبياء."),
                        url = "https://aistudio.google.com/app/apikey",
                        instructions = "1. قم بتسجيل الدخول إلى Google AI Studio.\n2. اضغط على زر 'Create API Key' (إنشاء مفتاح).\n3. انسخ المفتاح والصقه هنا.",
                        icon = Icons.Default.AutoAwesome,
                        value = geminiKey,
                        onValueChange = { geminiKey = it }
                    )
                }

                item {
                    ApiKeyCard(
                        serviceType = "groq",
                        title = "2. Groq API / xAI Grok",
                        description = Translator.tr("معالجة فائقة السرعة للنصوص وتوليد الأفكار الفورية للريلز (يدعم Groq Cloud المجاني أو xAI Grok)."),
                        url = "https://console.groq.com/keys",
                        instructions = "• الخيار المجاني الموصى به (Groq):\n1. ادخل إلى console.groq.com/keys\n2. أنشئ حساباً مجانياً وانسخ المفتاح (يبدأ بـ gsk_).\n\n• خيار xAI Grok:\nإذا كنت تستخدم مفتاح x.ai (يبدأ بـ xai-)، تأكد من توفر رصيد نشط في console.x.ai.",
                        icon = Icons.Default.Speed,
                        value = groqKey,
                        onValueChange = { groqKey = it }
                    )
                }

                item {
                    ApiKeyCard(
                        serviceType = "openai",
                        title = "2.5 OpenAI API (GPT)",
                        description = "نماذج GPT للتوليد المتقدم والسكريبتات (بديل متوافق مع واجهة Groq — المفتاح يبدأ بـ sk-).",
                        url = "https://platform.openai.com/api-keys",
                        instructions = "1. ادخل إلى platform.openai.com/api-keys.\n2. أنشئ حساباً وانسخ المفتاح (يبدأ بـ sk-).\n3. الصقه هنا واحفظ.\n\nتنبيه أمني: المفتاح يُخزَّن محلياً في ذاكرة التطبيق فقط ويُرسل حصرياً إلى صفحات OpenAI الرسمية.",
                        icon = Icons.Default.AutoAwesome,
                        value = openaiKey,
                        onValueChange = { openaiKey = it }
                    )
                }

                item {
                    ApiKeyCard(
                        serviceType = "huggingface",
                        title = "3. Hugging Face Token",
                        description = Translator.tr("توليد الصور الإسلامية والخلفيات البصرية بالذكاء الاصطناعي مجاناً."),
                        url = "https://huggingface.co/settings/tokens",
                        instructions = "1. ادخل لموقع HuggingFace وأنشيء حساب.\n2. اذهب إلى الإعدادات ثم Access Tokens.\n3. أنشئ Token جديد بصلاحية 'Read'.",
                        icon = Icons.Default.Memory,
                        value = huggingfaceKey,
                        onValueChange = { huggingfaceKey = it }
                    )
                }

                item {
                    KeyGroupHeader("الصوت — لها بديل محلي مجاني")
                }

                item {
                    ApiKeyCard(
                        serviceType = "azure",
                        title = "4. Azure Speech (TTS)",
                        description = "توليد تعليق صوتي عربي فصيح عالي الجودة بأصوات طبيعية (اختياري — عند غيابه يعمل محرك النطق المدمج مجاناً).",
                        url = "https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices",
                        instructions = "1. سجّل دخولاً في Azure (يمنحك حساباً مجانياً ومفتاحاً تجريبياً فوراً).\n2. أنشئ مورد من نوع Speech (الطبقة المجانية Free F0 مجانية للأبد).\n3. انسخ KEY 1 (المفتاح) والمنطقة Region (مثل eastus) من صفحة 'Keys and Endpoint' والصقهما هنا.",
                        icon = Icons.Default.RecordVoiceOver,
                        value = azureSpeechKey,
                        onValueChange = { azureSpeechKey = it },
                        secondaryFieldLabel = "المنطقة (Region)",
                        secondaryValue = azureSpeechRegion,
                        onSecondaryValueChange = { azureSpeechRegion = it }
                    )
                }

                item {
                    ApiKeyCard(
                        serviceType = "elevenlabs",
                        title = "5. ElevenLabs (TTS)",
                        description = "أصوات سينمائية فائقة الواقعية للتعليق الصوتي (اختياري — طبقة مجانية محدودة بالشخصيات الشهرية).",
                        url = "https://elevenlabs.io/app/settings/api-keys",
                        instructions = "1. أنشئ حساباً مجانياً في elevenlabs.io.\n2. اذهب إلى Profile → Profile Settings → API Keys.\n3. انسخ المفتاح (يبدأ بـ xi-) والصقه هنا.",
                        icon = Icons.Default.GraphicEq,
                        value = elevenLabsKey,
                        onValueChange = { elevenLabsKey = it }
                    )
                }

                item {
                    KeyGroupHeader("الوسائط — متصلة بجلب B-Roll")
                }

                item {
                    ApiKeyCard(
                        serviceType = "pexels",
                        title = "6. Pexels API Key",
                        description = Translator.tr("مكتبة الفيديوهات والصور المجانية عالية الجودة (طبيعة، مساجد، ومعالم إسلامية)."),
                        url = "https://www.pexels.com/api/",
                        instructions = "1. سجل دخولك في موقع Pexels.\n2. اذهب إلى Image & Video API.\n3. قم بتقديم طلب للحصول على مفتاح.",
                        icon = Icons.Default.VideoLibrary,
                        value = pexelsKey,
                        onValueChange = { pexelsKey = it }
                    )
                }

                item {
                    ApiKeyCard(
                        serviceType = "pixabay",
                        title = "7. Pixabay API Key",
                        description = Translator.tr("مؤثرات بصرية وصوتية إضافية خالية من حقوق الطبع والنشر للمونتاج."),
                        url = "https://pixabay.com/api/docs/",
                        instructions = "1. سجل دخولك في Pixabay.\n2. اذهب لأسفل الصفحة واضغط على API.\n3. ستجد مفتاحك في قسم 'Search Images'.",
                        icon = Icons.Default.Image,
                        value = pixabayKey,
                        onValueChange = { pixabayKey = it }
                    )
                }

                item {
                    KeyGroupHeader("السحابة — تُدار خارج هذه الشاشة")
                }

                item {
                    CloudStatusCard()
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DeepSlate)
                    .padding(16.dp)
            ) {
                Column {
                    if (saveMessage.isNotEmpty()) {
                        Text(
                            text = saveMessage,
                            color = GoldPrimary,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp)
                        )
                    }
                    Button(
                        onClick = {
                            prefs.edit()
                                .putString("gemini_key", geminiKey.trim())
                                .putString("groq_key", groqKey.trim())
                                .putString("pexels_key", pexelsKey.trim())
                                .putString("pixabay_key", pixabayKey.trim())
                                .putString("huggingface_key", huggingfaceKey.trim())
                                .putString("azure_speech_key", azureSpeechKey.trim())
                                .putString("azure_speech_region", azureSpeechRegion.trim())
                                .putString("elevenlabs_key", elevenLabsKey.trim())
                                .putString("openai_key", openaiKey.trim())
                                .putString("firebase_key", firebaseKey.trim())
                                .apply()
                            saveMessage = Translator.tr("تم حفظ وتحديث جميع المفاتيح بنجاح! ✨")
                            SystemLogsManager.addLog("INFO", "تم حفظ إعدادات المفاتيح في ذاكرة التطبيق بنجاح", Color(0xFF10B981))
                            Toast.makeText(context, Translator.tr("تم حفظ المفاتيح بنجاح! الأنظمة جاهزة للعمل 🚀"), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(Translator.tr("حفظ وتحديث المفاتيح"), color = DeepSlate, fontFamily = TajawalFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
            }
        }
    }

    if (showSmartPasteDialog) {
        AlertDialog(
            onDismissRequest = { showSmartPasteDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = GoldPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("اللصق الذكي والتوزيع التلقائي", fontFamily = TajawalFont, fontWeight = FontWeight.Bold, color = GoldPrimary, fontSize = 16.sp)
                }
            },
            text = {
                Column {
                    Text(
                        "الصق هنا أي نص أو كود JSON أو ملف .env، وسيتم التعرف على المفاتيح وتوزيعها تلقائياً على كافة الحقول!",
                        fontFamily = CairoFont,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = smartPasteText,
                        onValueChange = { smartPasteText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        placeholder = { Text("الصق النص أو ملف JSON هنا...\nمثال:\nGEMINI_KEY=AIzaSy...\nأو كود JSON", color = Color.Gray, fontSize = 12.sp, fontFamily = NotoSansFont) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldPrimary,
                            unfocusedBorderColor = Color(0xFF1E293B),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = GoldPrimary,
                            focusedContainerColor = Color(0xFF141C27),
                            unfocusedContainerColor = Color(0xFF141C27)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (smartPasteText.isNotBlank()) {
                            val (count, map) = ApiKeysBackupManager.parseAndApplyImport(smartPasteText, prefs)
                            if (count > 0) {
                                syncUiWithPrefs(map)
                                Toast.makeText(context, "تم التعرف على $count مفاتيح وتوزيعها بنجاح! 🎉", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "لم يتم العثور على أي مفاتيح معروفة في النص المنسوخ", Toast.LENGTH_SHORT).show()
                            }
                            smartPasteText = ""
                            showSmartPasteDialog = false
                        } else {
                            Toast.makeText(context, "يرجى لصق النص أولاً", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                ) {
                    Text("استخراج وتوزيع المفاتيح تلقائياً ⚡", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSmartPasteDialog = false }) {
                    Text("إلغاء", color = TextSecondary, fontFamily = CairoFont)
                }
            },
            containerColor = Color(0xFF151B2B)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private data class LiveServiceCheck(
    val type: String,
    val name: String,
    val url: String,
    val value: String,
    val hint: String? = null,
    val freeFallback: String,
    var result: KeyValidationResult? = null,
    var latencyMs: Long? = null
)

@Composable
fun LiveHealthCheckPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)

    val services = remember {
        listOf(
            LiveServiceCheck("gemini", "Gemini (ذكاء اصطناعي)", "https://aistudio.google.com/app/apikey", prefs.getString("gemini_key", "").orEmpty(), freeFallback = "المحلل المحلي + استخراج المشاهد البلاغية يعملان بلا مفتاح"),
            LiveServiceCheck("groq", "Groq (نصوص فائقة السرعة)", "https://console.groq.com/keys", prefs.getString("groq_key", "").orEmpty(), freeFallback = "توليد النصوص يقع على المحرك المحلي و Gemini المجاني"),
            LiveServiceCheck("openai", "OpenAI (نماذج GPT)", "https://platform.openai.com/api-keys", prefs.getString("openai_key", "").orEmpty(), freeFallback = "توليد النصوص يقع على المحرك المحلي و Gemini المجاني"),
            LiveServiceCheck("huggingface", "HuggingFace (صور AI)", "https://huggingface.co/settings/tokens", prefs.getString("huggingface_key", "").orEmpty(), freeFallback = "توليد الصور محلياً عبر LocalImageAnalyzer"),
            LiveServiceCheck("azure", "Azure TTS (نطق)", "https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices", prefs.getString("azure_speech_key", "").orEmpty(), prefs.getString("azure_speech_region", "").orEmpty(), freeFallback = "النطق المدمج في أندرويد (TextToSpeech) يعمل مجاناً دائماً"),
            LiveServiceCheck("elevenlabs", "ElevenLabs (نطق)", "https://elevenlabs.io/app/settings/api-keys", prefs.getString("elevenlabs_key", "").orEmpty(), freeFallback = "النطق المدمج في أندرويد (TextToSpeech) يعمل مجاناً دائماً"),
            LiveServiceCheck("pexels", "Pexels (B-Roll)", "https://www.pexels.com/api/", prefs.getString("pexels_key", "").orEmpty(), freeFallback = "كاش B-Roll المحلي + إطار آمن 1080×1920"),
            LiveServiceCheck("pixabay", "Pixabay (B-Roll)", "https://pixabay.com/api/docs/", prefs.getString("pixabay_key", "").orEmpty(), freeFallback = "كاش B-Roll المحلي + إطار آمن 1080×1920")
        )
    }

    var checks by remember { mutableStateOf(services) }
    var running by remember { mutableStateOf(false) }

    fun launchCheck() {
        if (running) return
        val pending = checks.any { it.value.isNotBlank() }
        if (!pending) return
        running = true
        checks = checks.map { it.copy(result = null, latencyMs = null) }
        checks.forEach { svc ->
            val key = svc.value
            if (key.isBlank()) return@forEach
            scope.launch(Dispatchers.IO) {
                val start = System.nanoTime()
                val res = try {
                    ApiKeyValidator.validateKey(context, svc.type, key, svc.hint)
                } catch (e: Exception) {
                    val message = e.message ?: "خطأ غير معروف"
                    KeyValidationResult(
                        isValid = false,
                        summary = "تعذر الاتصال 🌐",
                        explanation = "فشل الوصول لخادم ${svc.name}: $message",
                        suggestedFix = "تحقق من اتصال الإنترنت بالمشروع، ثم أعد المحاولة."
                    )
                }
                val latencyMs = (System.nanoTime() - start) / 1_000_000
                withContext(Dispatchers.Main) {
                    checks = checks.map { if (it.type == svc.type) it.copy(result = res, latencyMs = latencyMs) else it }
                    if (checks.all { it.value.isBlank() || it.result != null }) running = false
                }
            }
        }
    }

    var snapshot by remember { mutableStateOf<Map<String, ApiUsageTracker.ApiStat>?>(null) }
    LaunchedEffect(Unit) {
        snapshot = ApiUsageTracker.snapshot(context)
    }

    val totalCalls = snapshot?.values?.sumOf { it.totalCalls } ?: 0L
    val successCalls = snapshot?.values?.sumOf { it.successCalls } ?: 0L
    val successRate = if (totalCalls > 0) successCalls.toFloat() / totalCalls else 0f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.HealthAndSafety, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("فحص صحة المفاتيح الحي 🩺", color = GoldPrimary, fontFamily = CairoFont, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("قياس فوري للاتصال الفعلي وزمن الاستجابة لكل خدمة", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            checks.forEach { svc ->
                val statusColor = when {
                    svc.value.isBlank() -> Color(0xFFEAB308)
                    svc.result == null -> GoldPrimary
                    svc.result!!.isValid -> Color(0xFF10B981)
                    else -> Color(0xFFEF4444)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(svc.name, color = TextPrimary, fontFamily = CairoFont, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                    when {
                        svc.value.isBlank() -> Text("لا يوجد مفتاح 🟡", color = statusColor, fontFamily = CairoFont, fontSize = 11.sp)
                        svc.result == null -> CircularProgressIndicator(color = statusColor, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        svc.result!!.isValid -> Text("متصل ✅${svc.latencyMs?.let { " ($it ms)" } ?: ""}", color = statusColor, fontFamily = CairoFont, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        else -> Text("مرفوض 🔴", color = statusColor, fontFamily = CairoFont, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (svc.value.isBlank()) {
                    Text("   🆓 ${svc.freeFallback}", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 10.sp, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { launchCheck() },
                enabled = !running,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (running) "جاري فحص الاتصال الحي الآن..." else "🔍 فحص الاتصال الحي الآن",
                    color = DeepSlate,
                    fontFamily = CairoFont,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            val issues = checks.filter { it.value.isNotBlank() && it.result != null && !it.result!!.isValid }
            if (issues.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("خطة الإصلاح المقترحة 🛡️", color = GoldPrimary, fontFamily = CairoFont, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                issues.forEach { svc ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("• ${svc.name}: ", color = TextPrimary, fontFamily = CairoFont, fontSize = 11.5.sp)
                        Text(if (svc.result!!.suggestedFix.isNotBlank()) svc.result!!.suggestedFix else svc.result!!.explanation, color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { openUrl(context, svc.url) }) {
                            Text("الموقع 🔗", color = GoldPrimary, fontFamily = CairoFont, fontSize = 11.sp)
                        }
                    }
                }
            }

            if (totalCalls > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Insights, contentDescription = null, tint = GoldSecondary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "📊 إجمالي المكالمات الحقيقية: $totalCalls · نجاح ${(successRate * 100).toInt()}% — التفاصيل في لوحة المطور",
                        color = TextSecondary,
                        fontFamily = NotoSansFont,
                        fontSize = 10.5.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ApiKeyCard(
    serviceType: String,
    title: String,
    description: String,
    url: String,
    instructions: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    onValueChange: (String) -> Unit,
    secondaryFieldLabel: String? = null,
    secondaryValue: String? = null,
    onSecondaryValueChange: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    var isKeyVisible by remember { mutableStateOf(false) }
    var showInstructions by remember { mutableStateOf(false) }

    var validationStatus by remember { mutableStateOf<KeyValidationStatus>(KeyValidationStatus.Idle) }
    val scope = rememberCoroutineScope()

    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    val clipboardManager = LocalClipboardManager.current
    var lastClipboardSeen by remember { mutableStateOf("") }

    fun captureKeyFromClipboard(showErrors: Boolean) {
        val raw = clipboardManager.getText()?.text?.trim().orEmpty()
        if (raw.isBlank()) {
            if (showErrors) Toast.makeText(context, "الحافظة فارغة — انسخ المفتاح من موقع الخدمة أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        if (raw == lastClipboardSeen) {
            if (showErrors) Toast.makeText(context, "الحافظة لا تحتوي مفتاحاً جديداً غير ما حاولنا سابقاً", Toast.LENGTH_SHORT).show()
            return
        }
        lastClipboardSeen = raw
        val found = detectKeyForService(serviceType, raw)
        if (!found.isNullOrBlank() && found != value) {
            onValueChange(found)
            validationStatus = KeyValidationStatus.Testing
            scope.launch {
                val res = ApiKeyValidator.validateKey(context, serviceType, found, secondaryValue)
                validationStatus = if (res.isValid) KeyValidationStatus.Valid(res) else KeyValidationStatus.Invalid(res)
            }
            Toast.makeText(context, "تم التقاط المفتاح من الحافظة تلقائياً ✅ (جارٍ التحقق الفعلي...)", Toast.LENGTH_LONG).show()
        } else if (showErrors) {
            Toast.makeText(context, "لم يتم العثور على مفتاح $title داخل الحافظة — تأكد من نسخه كاملاً", Toast.LENGTH_LONG).show()
        }
    }

    // Auto test on initial load if key exists
    LaunchedEffect(value) {
        if (value.isNotBlank() && validationStatus is KeyValidationStatus.Idle) {
            validationStatus = KeyValidationStatus.Testing
            val res = ApiKeyValidator.validateKey(context, serviceType, value)
            validationStatus = if (res.isValid) KeyValidationStatus.Valid(res) else KeyValidationStatus.Invalid(res)
        } else if (value.isBlank()) {
            validationStatus = KeyValidationStatus.Idle
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when (validationStatus) {
                is KeyValidationStatus.Valid -> Color(0xFF4CAF50)
                is KeyValidationStatus.Invalid -> Color(0xFFEF4444)
                KeyValidationStatus.Testing -> GoldPrimary
                else -> Color(0xFF1E293B)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(GoldPrimary.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(title, color = Color.White, fontFamily = TajawalFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (statusText, statusColor) = when (val s = validationStatus) {
                                is KeyValidationStatus.Valid -> Pair(s.result.summary, Color(0xFF4CAF50))
                                is KeyValidationStatus.Invalid -> Pair(s.result.summary, Color(0xFFEF4444))
                                KeyValidationStatus.Testing -> Pair("جاري الفحص الفعلي...", GoldPrimary)
                                KeyValidationStatus.Idle -> if (value.isNotBlank()) Pair("مفعل 🟢", Color(0xFF4CAF50)) else Pair("غير محدد (اختياري) 🟡", Color.Gray)
                            }
                            Text(
                                text = statusText,
                                color = statusColor,
                                fontFamily = CairoFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                
                IconButton(
                    onClick = { showInstructions = true },
                    modifier = Modifier.background(Color(0xFF2A3A4F), CircleShape).size(36.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = "كيفية الحصول عليه", tint = GoldPrimary, modifier = Modifier.size(20.dp))
                }
            }
            
            if (showInstructions) {
                AlertDialog(
                    onDismissRequest = { showInstructions = false },
                    containerColor = CardSurface,
                    title = {
                        Text(Translator.tr("طريقة الحصول على المفتاح"), color = GoldPrimary, fontFamily = TajawalFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    },
                    text = {
                        Column {
                            Text(instructions, color = Color.White, fontFamily = NotoSansFont, fontSize = 14.sp, lineHeight = 22.sp)
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showInstructions = false
                                openUrl(context, url)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                        ) {
                            Text(Translator.tr("فتح الموقع"), color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showInstructions = false }) {
                            Text(Translator.tr("إغلاق"), color = TextSecondary, fontFamily = CairoFont)
                        }
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            Text(description, color = TextSecondary, fontFamily = NotoSansFont, fontSize = 14.sp, lineHeight = 20.sp)
            if (value.isBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = Color(0xFF10B981).copy(alpha = 0.08f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "🆓 بدون هذا المفتاح يعمل التطبيق مجاناً تلقائياً بالبديل المحلي داخل الجهاز — هذا المفتاح اختياري للترقية فقط.",
                        color = Color(0xFF34D399),
                        fontFamily = CairoFont,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            
            OutlinedTextField(
                value = value,
                onValueChange = {
                    onValueChange(it)
                    validationStatus = KeyValidationStatus.Idle
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused && value.isBlank()) {
                            val raw = clipboardManager.getText()?.text?.trim().orEmpty()
                            if (raw.isNotBlank() && raw != lastClipboardSeen) {
                                captureKeyFromClipboard(showErrors = false)
                            }
                        }
                    },
                placeholder = { Text(Translator.tr("الصق المفتاح هنا..."), color = Color.Gray, fontFamily = NotoSansFont, fontSize = 14.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GoldPrimary,
                    unfocusedBorderColor = Color(0xFF1E293B),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = GoldPrimary,
                    focusedContainerColor = Color(0xFF141C27),
                    unfocusedContainerColor = Color(0xFF141C27)
                ),
                visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                            Icon(
                                imageVector = if (isKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "رؤية المفتاح",
                                tint = GoldPrimary
                            )
                        }
                        if (value.isNotBlank()) {
                            Icon(
                                imageVector = when (validationStatus) {
                                    is KeyValidationStatus.Valid -> Icons.Default.CheckCircle
                                    is KeyValidationStatus.Invalid -> Icons.Default.Error
                                    else -> Icons.Default.CheckCircle
                                },
                                contentDescription = null,
                                tint = when (validationStatus) {
                                    is KeyValidationStatus.Valid -> Color(0xFF4CAF50)
                                    is KeyValidationStatus.Invalid -> Color(0xFFEF4444)
                                    else -> Color(0xFF4CAF50)
                                },
                                modifier = Modifier.size(20.dp).padding(end = 6.dp)
                            )
                        }
                    }
                }
            )

            // Empty-key quick actions: paste from clipboard + open website.
            // These must live BELOW the text field, not inside its trailingIcon.
            if (value.isBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { captureKeyFromClipboard(showErrors = true) },
                        modifier = Modifier.weight(1f).height(38.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("التقاط من الحافظة 📋", color = GoldPrimary, fontFamily = CairoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { openUrl(context, url) },
                        modifier = Modifier.height(38.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, tint = GoldSecondary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("افتح الموقع", color = GoldSecondary, fontFamily = CairoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Optional secondary field (e.g. Region)
            if (secondaryFieldLabel != null && secondaryValue != null && onSecondaryValueChange != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = secondaryValue,
                    onValueChange = onSecondaryValueChange,
                    label = { Text(secondaryFieldLabel, color = Color.Gray, fontFamily = CairoFont, fontSize = 12.sp) },
                    placeholder = { Text("مثال: eastus أو westeurope", color = Color.DarkGray, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GoldPrimary,
                        unfocusedBorderColor = Color(0xFF1E293B),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = Color(0xFF141C27),
                        unfocusedContainerColor = Color(0xFF141C27)
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // Dynamic detail box showing connection result
            when (val status = validationStatus) {
                is KeyValidationStatus.Valid -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = Color(0xFF10B981).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(status.result.summary, color = Color(0xFF10B981), fontFamily = CairoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(status.result.explanation, color = TextPrimary, fontFamily = NotoSansFont, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                }
                is KeyValidationStatus.Invalid -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = Color(0xFFEF4444).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Error, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(status.result.summary, color = Color(0xFFEF4444), fontFamily = CairoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { showDiagnosticsDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(Icons.Default.HelpOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("لماذا تم الرفض؟", color = Color.White, fontFamily = CairoFont, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(status.result.explanation, color = TextPrimary, fontFamily = NotoSansFont, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                }
                KeyValidationStatus.Testing -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = GoldPrimary, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("جاري الاتصال بالسيرفر الحقيقي واختبار الاستجابة الآن...", color = GoldPrimary, fontFamily = NotoSansFont, fontSize = 12.sp)
                    }
                }
                KeyValidationStatus.Idle -> {}
            }

            if (value.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                validationStatus = KeyValidationStatus.Testing
                                val res = ApiKeyValidator.validateKey(context, serviceType, value, secondaryValue)
                                validationStatus = if (res.isValid) KeyValidationStatus.Valid(res) else KeyValidationStatus.Invalid(res)
                            }
                        },
                        modifier = Modifier.weight(1f).height(38.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("فحص الاتصال الفعلي ⚡", color = GoldPrimary, fontFamily = CairoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    if (validationStatus is KeyValidationStatus.Invalid || validationStatus is KeyValidationStatus.Valid) {
                        OutlinedButton(
                            onClick = { showDiagnosticsDialog = true },
                            modifier = Modifier.height(38.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.FindInPage, contentDescription = null, tint = GoldSecondary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("سبب الحالة 🔍", color = GoldSecondary, fontFamily = CairoFont, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Dialog for diagnostics
            if (showDiagnosticsDialog) {
                val currentResult = when (val s = validationStatus) {
                    is KeyValidationStatus.Valid -> s.result
                    is KeyValidationStatus.Invalid -> s.result
                    else -> null
                }

                AlertDialog(
                    onDismissRequest = { showDiagnosticsDialog = false },
                    containerColor = CardSurface,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (currentResult?.isValid == true) Icons.Default.CheckCircle else Icons.Default.BugReport,
                                contentDescription = null,
                                tint = if (currentResult?.isValid == true) Color(0xFF10B981) else Color(0xFFEF4444),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (currentResult?.isValid == true) "تقرير تشغيل المفتاح الفعلي ✅" else "تشخيص سبب رفض المفتاح 🔍",
                                color = GoldPrimary,
                                fontFamily = TajawalFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                        }
                    },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            if (currentResult != null) {
                                if (currentResult.errorCode != null) {
                                    Surface(
                                        color = Color(0xFF0B0F19),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("رمز الاستجابة (HTTP Code):", color = TextSecondary, fontFamily = CairoFont, fontSize = 12.sp)
                                            Text(
                                                text = "${currentResult.errorCode}",
                                                color = if (currentResult.isValid) Color(0xFF10B981) else Color(0xFFEF4444),
                                                fontFamily = CairoFont,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = "السبب والتحليل الفني:",
                                    color = GoldPrimary,
                                    fontFamily = CairoFont,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = currentResult.explanation.ifBlank { "لا توجد تفاصيل إضافية." },
                                    color = Color.White,
                                    fontFamily = NotoSansFont,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                Text(
                                    text = "الحل والخطوة القادمة المقترحة:",
                                    color = Color(0xFF10B981),
                                    fontFamily = CairoFont,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = currentResult.suggestedFix.ifBlank { "تأكد من صحة المفتاح." },
                                    color = TextSecondary,
                                    fontFamily = NotoSansFont,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp
                                )

                                if (!currentResult.rawError.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = "الرد المباشر من السيرفر (Raw Response):",
                                        color = TextSecondary,
                                        fontFamily = CairoFont,
                                        fontSize = 11.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Surface(
                                        color = Color(0xFF0B0F19),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = currentResult.rawError ?: "",
                                            color = Color(0xFFEF4444),
                                            fontFamily = NotoSansFont,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                }
                            } else {
                                Text("لم يتم إجراء فحص تشخيصي بعد. اضغط على فحص الاتصال أولاً.", color = Color.White)
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { showDiagnosticsDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                        ) {
                            Text("فهمت، حسناً", color = DeepSlate, fontFamily = TajawalFont, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun KeyGroupHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = GoldPrimary.copy(alpha = 0.3f))
        Text(
            title, color = GoldPrimary, fontFamily = CairoFont,
            fontWeight = FontWeight.Bold, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = GoldPrimary.copy(alpha = 0.3f))
    }
}

@Composable
fun CloudStatusCard() {
    val supabaseOk = SupabaseConfig.isConfigured
    val firebaseOk = CloudServices.isFirebaseInitialized
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.25f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CloudStatusRow("Supabase", supabaseOk, if (supabaseOk) "متصلة عبر BuildConfig" else "غير مهيأة — تُحقن من Secrets")
            CloudStatusRow("Firebase", firebaseOk, if (firebaseOk) "مهيأة عبر google-services.json" else "تُدار عبر ملف الإعداد لا مفتاح يدوي")
            Text(
                "لا حاجة للصق أي مفتاح هنا — السحابة تعمل بملفات الإعداد المحقونة.",
                color = TextSecondary, fontFamily = CairoFont, fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun CloudStatusRow(name: String, ok: Boolean, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(8.dp)
                .clip(CircleShape)
                .background(if (ok) Color(0xFF10B981) else Color(0xFF6B7280))
        )
        Spacer(Modifier.width(8.dp))
        Text(name, color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Text(detail, color = TextSecondary, fontFamily = CairoFont, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirebaseConfigCard(
    value: String,
    onValueChange: (String) -> Unit
) {
    val context = LocalContext.current
    var showPasteDialog by remember { mutableStateOf(false) }
    var pastedJson by remember { mutableStateOf("") }
    
    val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
    val projectId = prefs.getString("firebase_project_id", "") ?: ""
    val isInitialized = CloudServices.isFirebaseInitialized

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val jsonText = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (jsonText.isNotBlank()) {
                    val (success, msg) = CloudServices.initializeWithJson(context, jsonText)
                    if (success) {
                        val newKey = prefs.getString("firebase_key", "") ?: ""
                        onValueChange(newKey)
                        Toast.makeText(context, Translator.tr("تم إدراج ملف google-services.json وتفعيل خدمات Firebase بنجاح! 🔥"), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, Translator.tr(msg), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, Translator.tr("خطأ في قراءة الملف: ") + e.localizedMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isInitialized) GoldPrimary else Color(0xFF1E293B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(GoldPrimary.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("8. Firebase Config (google-services.json)", color = Color.White, fontFamily = TajawalFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            text = if (isInitialized) Translator.tr("مربوط بالسحابة 🔥 (${if (projectId.isNotEmpty()) projectId else "نشط"})") else Translator.tr("غير مفعل (اختياري) 🟡"),
                            color = if (isInitialized) Color(0xFF4CAF50) else Color.Gray,
                            fontFamily = CairoFont,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                Translator.tr("حفظ المشاريع سحابياً، ومزامنة بيانات القناة والمشتركين مباشرة مع السيرفر."),
                color = TextSecondary,
                fontFamily = CairoFont,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { filePickerLauncher.launch("application/json") },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(Translator.tr("إدراج ملف google-services.json 📁"), color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { showPasteDialog = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Code, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(Translator.tr("أو إلصاق كود JSON مباشرة 📋"), color = GoldPrimary, fontFamily = CairoFont)
            }

            if (value.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(Translator.tr("مفتاح API الخاص بـ Firebase"), color = Color.Gray, fontFamily = NotoSansFont, fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GoldPrimary,
                        unfocusedBorderColor = Color(0xFF1E293B),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = GoldPrimary,
                        focusedContainerColor = Color(0xFF141C27),
                        unfocusedContainerColor = Color(0xFF141C27)
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                    }
                )
            }
        }
    }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            containerColor = CardSurface,
            title = {
                Text(Translator.tr("إلصاق نص google-services.json"), color = GoldPrimary, fontFamily = TajawalFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column {
                    Text(Translator.tr("الصق محتوى ملف google-services.json هنا:"), color = Color.White, fontFamily = NotoSansFont, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pastedJson,
                        onValueChange = { pastedJson = it },
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        placeholder = { Text("{\n  \"project_info\": ...\n}", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldPrimary,
                            unfocusedBorderColor = Color(0xFF1E293B),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedContainerColor = Color(0xFF141C27),
                            unfocusedContainerColor = Color(0xFF141C27)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPasteDialog = false
                        if (pastedJson.isNotBlank()) {
                            val (success, msg) = CloudServices.initializeWithJson(context, pastedJson)
                            if (success) {
                                val newKey = prefs.getString("firebase_key", "") ?: ""
                                onValueChange(newKey)
                                Toast.makeText(context, Translator.tr("تم تفعيل خدمات Firebase بنجاح! 🔥"), Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, Translator.tr(msg), Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                ) {
                    Text(Translator.tr("حفظ وتفعيل"), color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) {
                    Text(Translator.tr("إلغاء"), color = TextSecondary, fontFamily = CairoFont)
                }
            }
        )
    }
}

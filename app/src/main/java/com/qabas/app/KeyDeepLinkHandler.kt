package com.qabas.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

object KeyDeepLinkHandler {

    private const val TAG = "KeyDeepLinkHandler"
    private const val PREFS_NAME = "qabas_prefs"
    private const val SCHEME = "qabas"
    private const val HOST_KEYS = "keys"

    // qabas://keys/import?gemini=AIzaSy...&groq=gsk_...
    // qabas://keys/sync?direction=push|pull|bidirectional
    // qabas://keys/validate (triggers validation for all keys)

    data class DeepLinkResult(
        val action: String,
        val keys: Map<String, String> = emptyMap(),
        val syncDirection: String = "bidirectional",
        val isValid: Boolean = false
    )

    fun parseIntent(intent: Intent): DeepLinkResult? {
        val uri = intent.data ?: return null
        return parseUri(uri)
    }

    fun parseUri(uri: Uri): DeepLinkResult? {
        if (uri.scheme != SCHEME || uri.host != HOST_KEYS) return null

        val path = uri.pathSegments.firstOrNull() ?: return null

        return when (path) {
            "import" -> parseImport(uri)
            "sync" -> parseSync(uri)
            "validate" -> DeepLinkResult(action = "validate", isValid = true)
            else -> null
        }
    }

    private fun parseImport(uri: Uri): DeepLinkResult {
        val keyMap = mutableMapOf<String, String>()

        val paramNames = mapOf(
            "gemini" to "gemini_key",
            "groq" to "groq_key",
            "openai" to "openai_key",
            "openrouter" to "openrouter_key",
            "huggingface" to "huggingface_key",
            "hf" to "huggingface_key",
            "azure" to "azure_speech_key",
            "azure_region" to "azure_speech_region",
            "elevenlabs" to "elevenlabs_key",
            "pexels" to "pexels_key",
            "pixabay" to "pixabay_key"
        )

        paramNames.forEach { (param, field) ->
            uri.getQueryParameter(param)?.takeIf { it.isNotBlank() }?.let { value ->
                keyMap[field] = value
            }
        }

        return DeepLinkResult(
            action = "import",
            keys = keyMap,
            isValid = keyMap.isNotEmpty()
        )
    }

    private fun parseSync(uri: Uri): DeepLinkResult {
        val direction = uri.getQueryParameter("direction") ?: "bidirectional"
        val validDirections = listOf("push", "pull", "bidirectional")
        return DeepLinkResult(
            action = "sync",
            syncDirection = if (direction in validDirections) direction else "bidirectional",
            isValid = true
        )
    }

    fun applyImportedKeys(context: Context, keys: Map<String, String>): Pair<Boolean, String> {
        if (keys.isEmpty()) return false to "لا توجد مفاتيح"

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        keys.forEach { (field, value) ->
            editor.putString(field, value)
        }
        editor.apply()

        val names = keys.keys.map { field ->
            when (field) {
                "gemini_key" -> "Gemini"
                "groq_key" -> "Groq"
                "openai_key" -> "OpenAI"
                "openrouter_key" -> "OpenRouter"
                "huggingface_key" -> "HuggingFace"
                "azure_speech_key" -> "Azure TTS"
                "azure_speech_region" -> "Azure Region"
                "elevenlabs_key" -> "ElevenLabs"
                "pexels_key" -> "Pexels"
                "pixabay_key" -> "Pixabay"
                else -> field
            }
        }

        return true to "تم استيراد ${keys.size} مفتاح: ${names.joinToString("، ")} ✅"
    }

    fun generateShareLink(keys: Map<String, String>): String {
        val params = mutableListOf<String>()

        val reverseNames = mapOf(
            "gemini_key" to "gemini",
            "groq_key" to "groq",
            "openai_key" to "openai",
            "openrouter_key" to "openrouter",
            "huggingface_key" to "hf",
            "azure_speech_key" to "azure",
            "azure_speech_region" to "azure_region",
            "elevenlabs_key" to "elevenlabs",
            "pexels_key" to "pexels",
            "pixabay_key" to "pixabay"
        )

        keys.forEach { (field, value) ->
            val paramName = reverseNames[field] ?: return@forEach
            params.add("$paramName=${Uri.encode(value)}")
        }

        return "qabas://keys/import?${params.joinToString("&")}"
    }

    fun generateShareText(keys: Map<String, String>): String {
        val sb = StringBuilder()
        sb.appendLine("🔑 مفاتيح قبس — افتح هذا الرابط في التطبيق:")
        sb.appendLine()
        sb.appendLine(generateShareLink(keys))
        sb.appendLine()
        sb.appendLine("⚠️ هذا الرابط يحتوي مفاتيح خاصة — لا تشاركه مع أحد")
        return sb.toString()
    }
}

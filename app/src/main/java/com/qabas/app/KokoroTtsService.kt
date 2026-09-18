package com.qabas.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale

/**
 * Kokoro TTS — صوت عربي محلي 82M عبر ONNX Runtime (CPU، بلا إنترنت، بلا مفتاح).
 * يعتمد على موديل `kokoro-v1.0.onnx` + `config.json` + `vocab.json` في assets/models/kokoro/
 * الرخصة: Apache-2.0 — تجاري مسموح.
 * ملاحظة: أول تحميل يستغرق ~2-3 ثوانٍ، المساحة ~400MB في assets.
 */
object KokoroTtsService {
    private const val TAG = "KokoroTtsService"
    private const val MODEL_DIR = "models/kokoro/"
    private const val MODEL_FILE = "kokoro-v1.0.onnx"
    private const val CONFIG_FILE = "config.json"
    private const val VOCAB_FILE = "vocab.json"

    @Volatile
    private var session: OrtSession? = null
    @Volatile
    private var ortEnv: OrtEnvironment? = null
    @Volatile
    private var vocab: Map<String, Int>? = null
    @Volatile
    private var sampleRate = 24000
    @Volatile
    private var isInitialized = false

    /** ينسخ الموديل من assets إلى filesDir لأول مرة */
    private suspend fun ensureModelFiles(context: Context): Boolean = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        val modelDir = File(filesDir, MODEL_DIR)
        if (!modelDir.exists()) modelDir.mkdirs()
        val modelFile = File(modelDir, MODEL_FILE)
        val configFile = File(modelDir, CONFIG_FILE)
        val vocabFile = File(modelDir, VOCAB_FILE)
        if (modelFile.exists() && modelFile.length() > 1000 && configFile.exists() && vocabFile.exists()) {
            return@withContext true
        }
        try {
            val assets = context.assets
            val modelIn = assets.open(MODEL_DIR + MODEL_FILE)
            val configIn = assets.open(MODEL_DIR + CONFIG_FILE)
            val vocabIn = assets.open(MODEL_DIR + VOCAB_FILE)
            FileOutputStream(modelFile).use { out -> modelIn.copyTo(out) }
            FileOutputStream(configFile).use { out -> configIn.copyTo(out) }
            FileOutputStream(vocabFile).use { out -> vocabIn.copyTo(out) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model from assets: ${e.message}")
            false
        }
    }

    /** تهيئة ONNX session + vocab */
    private suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized && session != null) return@withContext true
        if (!ensureModelFiles(context)) return@withContext false
        try {
            val filesDir = context.filesDir
            val modelPath = File(filesDir, MODEL_DIR + MODEL_FILE).absolutePath
            ortEnv = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.addConfigEntry("session.use_nnapi", "0") // CPU فقط
            sessionOptions.addConfigEntry("session.inter_op_num_threads", "2")
            sessionOptions.addConfigEntry("session.intra_op_num_threads", "4")
            session = ortEnv!!.createSession(modelPath, sessionOptions)
            // تحميل vocab
            val vocabPath = File(filesDir, MODEL_DIR + VOCAB_FILE).absolutePath
            val json = File(vocabPath).readText()
            val obj = org.json.JSONObject(json)
            val map = mutableMapOf<String, Int>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.getInt(k)
            }
            vocab = map
            // قراءة sample_rate من config
            val configPath = File(filesDir, MODEL_DIR + CONFIG_FILE).absolutePath
            val cfgJson = File(configPath).readText()
            val cfg = org.json.JSONObject(cfgJson)
            sampleRate = cfg.optInt("sample_rate", 24000)
            isInitialized = true
            Log.i(TAG, "Kokoro ONNX session initialized, sampleRate=$sampleRate, vocabSize=${vocab?.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX session: ${e.message}")
            false
        }
    }

    /** تحويل نص عربي → IDs باستخدام vocab بسيط (char-level fallback) */
    private fun textToIds(text: String): IntArray {
        val v = vocab ?: return IntArray(0)
        val ids = mutableListOf<Int>()
        val bos = v["<|startoftext|>"] ?: v["<s>"] ?: 1
        val eos = v["<|endoftext|>"] ?: v["</s>"] ?: 2
        val unk = v["<|unk|>"] ?: v["<unk>"] ?: 3
        ids.add(bos)
        for (ch in text) {
            val s = ch.toString()
            ids.add(v[s] ?: unk)
        }
        ids.add(eos)
        return ids.toIntArray()
    }

    /** توليد صوت وحفظه كملف WAV → يعاد مساره */
    suspend fun synthesizeSpeech(context: Context, text: String, voice: String = "ar"): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        if (!initialize(context)) return@withContext null
        try {
            val ids = textToIds(text)
            if (ids.isEmpty()) return@withContext null
            val longIds = java.nio.LongBuffer.wrap(ids.map { it.toLong() }.toLongArray())
            val inputTensor = OnnxTensor.createTensor(ortEnv!!, longIds, longArrayOf(1, ids.size.toLong()))
            val inputs = mapOf("input_ids" to inputTensor)
            val outputs = session!!.run(inputs)
            val outputTensor = outputs[0] as OnnxTensor
            val floatBuffer = outputTensor.floatBuffer
            val audioData = FloatArray(floatBuffer.remaining())
            floatBuffer.get(audioData)
            // تحويل Float [-1,1] → Int16 PCM
            val pcm = ShortArray(audioData.size)
            for (i in audioData.indices) {
                val v = (audioData[i] * 32767.0f).coerceIn(-32768f, 32767f)
                pcm[i] = v.toInt().toShort()
            }
            // حفظ WAV
            val outFile = File(context.cacheDir, "kokoro_${System.currentTimeMillis()}.wav")
            writeWav(outFile, pcm, sampleRate)
            Log.i(TAG, "Kokoro generated: ${outFile.absolutePath} (${pcm.size} samples)")
            return@withContext outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Kokoro synthesis failed: ${e.message}")
            null
        }
    }

    private fun writeWav(file: File, pcm: ShortArray, sampleRate: Int) {
        val byteArray = ByteArray(pcm.size * 2)
        ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
        FileOutputStream(file).use { out ->
            // WAV header (44 bytes)
            val header = ByteArray(44)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray())
            bb.putInt(36 + byteArray.size)
            bb.put("WAVE".toByteArray())
            bb.put("fmt ".toByteArray())
            bb.putInt(16)
            bb.putShort(1) // PCM
            bb.putShort(1) // mono
            bb.putInt(sampleRate)
            bb.putInt(sampleRate * 2) // byte rate
            bb.putShort(2) // block align
            bb.putShort(16) // bits per sample
            bb.put("data".toByteArray())
            bb.putInt(byteArray.size)
            out.write(header)
            out.write(byteArray)
        }
    }

    fun release() {
        session?.close()
        ortEnv?.close()
        session = null
        ortEnv = null
        isInitialized = false
    }
}
package com.qabas.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// يعلن أي محرك أنتج المشاهد فعلاً — تقرأه شاشة المعالجة حية
object SceneEngineMonitor {
    private val _engine = MutableStateFlow("")
    val engine: StateFlow<String> = _engine

    @Volatile var lastEngine: String = ""
        private set

    fun report(name: String) {
        lastEngine = name
        try { _engine.value = name } catch (_: Exception) {}
    }

    fun reset() {
        lastEngine = ""
        try { _engine.value = "" } catch (_: Exception) {}
    }
}

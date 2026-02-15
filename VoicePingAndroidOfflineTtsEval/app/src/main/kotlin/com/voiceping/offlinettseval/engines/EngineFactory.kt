package com.voiceping.offlinettseval.engines

import android.content.Context
import com.voiceping.offlinettseval.engine.TtsEngine
import com.voiceping.offlinettseval.io.ModelRepository

class EngineFactory(
    private val context: Context,
    private val repo: ModelRepository,
) {
    fun create(engineId: String): TtsEngine {
        return when (engineId) {
            "sherpa_offline_tts" -> SherpaOfflineTtsEngine(repo)
            "android_system_tts" -> AndroidSystemTtsEngine(context)
            "nemo_ort" -> NemoFastPitchHifiGanOrtEngine(repo)
            else -> throw IllegalArgumentException("Unknown engineId=$engineId")
        }
    }
}


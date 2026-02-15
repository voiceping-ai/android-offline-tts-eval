package com.voiceping.offlinettseval.report

import android.os.Build
import com.voiceping.offlinettseval.catalog.TtsModel
import org.json.JSONObject

object TtsResultJson {
    const val SCHEMA_VERSION = 1

    fun build(
        timestampMs: Long,
        model: TtsModel,
        promptId: String,
        inputText: String,
        words: Int,
        chars: Int,
        threads: Int,
        provider: String,
        speakerId: Int,
        speed: Float,
        loadTimeMs: Long,
        synthesisTimeMs: Long,
        sampleRate: Int,
        samples: Int,
        audioDurationSec: Double,
        wavPath: String,
        runMode: String,
        warmIterations: Int,
    ): JSONObject {
        val synthesisSec = synthesisTimeMs.toDouble() / 1000.0
        val tokensPerSecond = if (synthesisSec > 0.0) words.toDouble() / synthesisSec else 0.0
        val wordsPerSecond = tokensPerSecond
        val rtf = if (audioDurationSec > 0.0) synthesisSec / audioDurationSec else 0.0

        val device = JSONObject()
            .put("model", Build.MODEL ?: "")
            .put("manufacturer", Build.MANUFACTURER ?: "")
            .put("sdk_int", Build.VERSION.SDK_INT)

        val modelObj = JSONObject()
            .put("id", model.id)
            .put("name", model.displayName)
            .put("engine", model.engine)

        val settings = JSONObject()
            .put("threads", threads)
            .put("provider", provider)
            .put("speaker_id", speakerId)
            .put("speed", speed)
            .put("run_mode", runMode)
            .put("warm_iterations", warmIterations)

        val input = JSONObject()
            .put("prompt_id", promptId)
            .put("text", inputText)
            .put("words", words)
            .put("chars", chars)

        val timing = JSONObject()
            .put("load", loadTimeMs)
            .put("synthesis", synthesisTimeMs)

        val audio = JSONObject()
            .put("sample_rate", sampleRate)
            .put("samples", samples)
            .put("duration_sec", audioDurationSec)
            .put("wav_path", wavPath)

        val metrics = JSONObject()
            .put("tokens_per_second", tokensPerSecond)
            .put("words_per_second", wordsPerSecond)
            .put("rtf", rtf)
            .put("speed_score", if (rtf > 0.0) 1.0 / rtf else 0.0)

        return JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("timestamp_ms", timestampMs)
            .put("device", device)
            .put("model", modelObj)
            .put("settings", settings)
            .put("input", input)
            .put("timing_ms", timing)
            .put("audio", audio)
            .put("metrics", metrics)
    }
}


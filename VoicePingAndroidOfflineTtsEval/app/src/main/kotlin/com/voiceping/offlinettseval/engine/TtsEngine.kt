package com.voiceping.offlinettseval.engine

import com.voiceping.offlinettseval.catalog.TtsModel

sealed class ReadyState {
    data object Ready : ReadyState()
    data class MissingFiles(val missing: List<String>) : ReadyState()
    data class NeedsDownload(val reason: String) : ReadyState()
}

data class LoadOptions(
    val threads: Int,
    val provider: String = "cpu",
)

data class LoadResult(
    val sampleRate: Int,
    val numSpeakers: Int,
    val loadTimeMs: Long,
)

data class SynthesisRequest(
    val model: TtsModel,
    val text: String,
    val speakerId: Int,
    val speed: Float,
)

data class SynthesisResult(
    val samples: FloatArray,
    val sampleRate: Int,
    val synthesisTimeMs: Long,
    val audioDurationSec: Double,
)

interface TtsEngine {
    val engineId: String

    suspend fun ensureReady(model: TtsModel): ReadyState
    suspend fun load(model: TtsModel, options: LoadOptions): LoadResult
    suspend fun synthesize(request: SynthesisRequest): SynthesisResult
    fun release()
}


package com.voiceping.offlinettseval.ui

import com.voiceping.offlinettseval.catalog.TtsModel
import com.voiceping.offlinettseval.engine.ReadyState
import com.voiceping.offlinettseval.suite.SuiteProgress

data class TtsEvalLastRun(
    val modelName: String,
    val promptId: String,
    val words: Int,
    val chars: Int,
    val loadTimeMs: Long,
    val synthesisTimeMs: Long,
    val sampleRate: Int,
    val samples: Int,
    val audioDurationSec: Double,
    val tokensPerSecond: Double,
    val rtf: Double,
    val wavPath: String,
    val jsonPath: String,
)

data class TtsEvalUiState(
    val models: List<TtsModel> = emptyList(),
    val selectedModelId: String = "",

    val inputText: String = "Hello. This is an offline TTS benchmark.",
    val threads: Int = 4,
    val provider: String = "cpu",
    val speed: Float = 1.0f,
    val speakerId: Int = 0,

    val readyState: ReadyState = ReadyState.NeedsDownload("not initialized"),
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0.0f,

    val isLoaded: Boolean = false,
    val sampleRate: Int? = null,
    val numSpeakers: Int? = null,
    val lastLoadTimeMs: Long? = null,

    val isSynthesizing: Boolean = false,
    val lastRun: TtsEvalLastRun? = null,

    val runMode: String = "cold", // cold|warm
    val warmIterations: Int = 3,
    val isSuiteRunning: Boolean = false,
    val suiteProgress: SuiteProgress? = null,

    val localImportCommand: String? = null,

    val lastError: String? = null,
)


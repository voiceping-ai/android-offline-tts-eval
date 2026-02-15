package com.voiceping.offlinettseval.catalog

data class TtsModel(
    val id: String,
    val displayName: String,
    val engine: String,
    val modelType: String,
    val source: ModelSource,
    val files: List<String>,
    val prefixes: List<String>,
    val dependencies: List<String> = emptyList(),
    val meta: ModelMeta = ModelMeta(),
) {
    val inferenceMethod: String
        get() = when (engine) {
            "android_system_tts" -> "Android TextToSpeech"
            "sherpa_offline_tts" -> "sherpa-onnx offline TTS (ONNX Runtime)"
            "nemo_ort" -> "ONNX Runtime (Java) · NeMo FastPitch + HiFiGAN"
            "asset_only" -> "Asset dependency (no inference)"
            else -> engine
        }
}

data class ModelMeta(
    val languages: String = "",
    val description: String = "",
    val sizeHintMb: Int = 0,
)

sealed class ModelSource {
    data class HuggingFace(val repo: String, val rev: String = "main") : ModelSource()
    data class LocalBundle(val bundleName: String) : ModelSource()
    data object System : ModelSource()
}


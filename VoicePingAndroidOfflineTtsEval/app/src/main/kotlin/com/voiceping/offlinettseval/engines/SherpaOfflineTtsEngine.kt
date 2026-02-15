package com.voiceping.offlinettseval.engines

import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.voiceping.offlinettseval.catalog.ModelSource
import com.voiceping.offlinettseval.catalog.TtsModel
import com.voiceping.offlinettseval.engine.LoadOptions
import com.voiceping.offlinettseval.engine.LoadResult
import com.voiceping.offlinettseval.engine.ReadyState
import com.voiceping.offlinettseval.engine.SynthesisRequest
import com.voiceping.offlinettseval.engine.SynthesisResult
import com.voiceping.offlinettseval.engine.TtsEngine
import com.voiceping.offlinettseval.io.ModelRepository
import java.io.File

class SherpaOfflineTtsEngine(
    private val repo: ModelRepository,
) : TtsEngine {
    override val engineId: String = "sherpa_offline_tts"

    private var tts: OfflineTts? = null
    private var loadedModelId: String? = null

    override suspend fun ensureReady(model: TtsModel): ReadyState {
        if (model.engine != engineId) return ReadyState.NeedsDownload("Wrong engine for model")
        return repo.ensureReady(model)
    }

    override suspend fun load(model: TtsModel, options: LoadOptions): LoadResult {
        require(model.engine == engineId) { "Model ${model.id} is not a sherpa_offline_tts model" }
        require(model.source is ModelSource.HuggingFace) { "sherpa_offline_tts models must come from Hugging Face downloads" }

        // If model is changing, reset.
        if (loadedModelId != null && loadedModelId != model.id) {
            release()
        }

        val started = SystemClock.elapsedRealtime()
        val config = buildConfig(model, options, repo.modelDir(model))
        val instance = OfflineTts(null, config)
        tts = instance
        loadedModelId = model.id

        val loadTimeMs = SystemClock.elapsedRealtime() - started
        return LoadResult(
            sampleRate = instance.sampleRate(),
            numSpeakers = instance.numSpeakers(),
            loadTimeMs = loadTimeMs,
        )
    }

    override suspend fun synthesize(request: SynthesisRequest): SynthesisResult {
        val instance = requireNotNull(tts) { "TTS not loaded" }
        val started = SystemClock.elapsedRealtime()
        val audio = instance.generate(request.text, request.speakerId, request.speed)
        val synthesisTimeMs = SystemClock.elapsedRealtime() - started
        val audioDurationSec = if (audio.sampleRate > 0) audio.samples.size.toDouble() / audio.sampleRate.toDouble() else 0.0
        return SynthesisResult(
            samples = audio.samples,
            sampleRate = audio.sampleRate,
            synthesisTimeMs = synthesisTimeMs,
            audioDurationSec = audioDurationSec
        )
    }

    override fun release() {
        tts?.release()
        tts = null
        loadedModelId = null
    }

    private fun buildConfig(model: TtsModel, options: LoadOptions, modelDir: File): OfflineTtsConfig {
        val provider = options.provider
        val debug = false
        val numThreads = options.threads

        val vits = if (model.modelType == "vits") buildVitsConfig(modelDir) else OfflineTtsVitsModelConfig()
        val kokoro = if (model.modelType == "kokoro") buildKokoroConfig(modelDir) else OfflineTtsKokoroModelConfig()
        val matcha = if (model.modelType == "matcha") buildMatchaConfig(model, modelDir) else OfflineTtsMatchaModelConfig()
        val kitten = if (model.modelType == "kitten") buildKittenConfig(modelDir) else OfflineTtsKittenModelConfig()

        val modelConfig = OfflineTtsModelConfig(
            vits,
            matcha,
            kokoro,
            kitten,
            numThreads,
            debug,
            provider
        )

        return OfflineTtsConfig(
            modelConfig,
            /* ruleFsts = */ "",
            /* ruleFars = */ "",
            /* maxNumSentences = */ 0,
            /* silenceScale = */ 0.0f
        )
    }

    private fun buildKokoroConfig(modelDir: File): OfflineTtsKokoroModelConfig {
        val modelPath = pickFirstExisting(modelDir, listOf("model.int8.onnx", "model.onnx"))
            ?: throw IllegalStateException("Missing Kokoro model file in ${modelDir.absolutePath}")

        val voices = requireFile(modelDir, "voices.bin")
        val tokens = requireFile(modelDir, "tokens.txt")

        val dataDir = File(modelDir, "espeak-ng-data").takeIf { it.exists() }?.absolutePath ?: ""
        val dictDir = File(modelDir, "dict").takeIf { it.exists() }?.absolutePath ?: ""

        val lexiconCandidates = listOf("lexicon-us-en.txt", "lexicon-gb-en.txt", "lexicon-zh.txt")
            .map { File(modelDir, it) }
            .filter { it.exists() }
            .map { it.absolutePath }
        val lexicon = lexiconCandidates.joinToString(separator = ",")

        return OfflineTtsKokoroModelConfig(
            /* model = */ File(modelDir, modelPath).absolutePath,
            /* voices = */ voices.absolutePath,
            /* tokens = */ tokens.absolutePath,
            /* dataDir = */ dataDir,
            /* lexicon = */ lexicon,
            /* lang = */ "",
            /* dictDir = */ dictDir,
            /* lengthScale = */ 1.0f
        )
    }

    private fun buildVitsConfig(modelDir: File): OfflineTtsVitsModelConfig {
        val onnx = modelDir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".onnx") }?.name
            ?: throw IllegalStateException("Missing VITS .onnx model file in ${modelDir.absolutePath}")

        val tokens = requireFile(modelDir, "tokens.txt")
        val dataDir = File(modelDir, "espeak-ng-data").takeIf { it.exists() }?.absolutePath ?: ""

        return OfflineTtsVitsModelConfig(
            /* model = */ File(modelDir, onnx).absolutePath,
            /* lexicon = */ "",
            /* tokens = */ tokens.absolutePath,
            /* dataDir = */ dataDir,
            /* dictDir = */ "",
            /* noiseScale = */ 0.667f,
            /* noiseScaleW = */ 0.8f,
            /* lengthScale = */ 1.0f
        )
    }

    private fun buildMatchaConfig(model: TtsModel, modelDir: File): OfflineTtsMatchaModelConfig {
        val acoustic = pickFirstExisting(modelDir, listOf("model-steps-3.onnx", "model.onnx"))
            ?: throw IllegalStateException("Missing Matcha acoustic model .onnx in ${modelDir.absolutePath}")

        val tokens = requireFile(modelDir, "tokens.txt")
        val dataDir = File(modelDir, "espeak-ng-data").takeIf { it.exists() }?.absolutePath ?: ""

        // Dependency: HiFiGAN vocoder in its own model directory.
        val vocoderDep = model.dependencies.firstOrNull()
            ?.let { repo.modelById(it) }
            ?: throw IllegalStateException("Matcha model ${model.id} missing vocoder dependency")
        val vocoderFile = vocoderDep.files.firstOrNull { it.endsWith(".onnx") }
            ?: throw IllegalStateException("Vocoder dependency ${vocoderDep.id} has no .onnx file entry")
        val vocoderPath = File(repo.modelDir(vocoderDep), vocoderFile)
        if (!vocoderPath.exists()) {
            throw IllegalStateException("Missing vocoder file ${vocoderPath.absolutePath}")
        }

        return OfflineTtsMatchaModelConfig(
            /* acousticModel = */ File(modelDir, acoustic).absolutePath,
            /* vocoder = */ vocoderPath.absolutePath,
            /* lexicon = */ "",
            /* tokens = */ tokens.absolutePath,
            /* dataDir = */ dataDir,
            /* dictDir = */ "",
            /* noiseScale = */ 0.0f,
            /* lengthScale = */ 1.0f
        )
    }

    private fun buildKittenConfig(modelDir: File): OfflineTtsKittenModelConfig {
        val modelPath = pickFirstExisting(modelDir, listOf("model.fp16.onnx", "model.onnx", "model.int8.onnx"))
            ?: throw IllegalStateException("Missing Kitten model .onnx in ${modelDir.absolutePath}")

        val voices = requireFile(modelDir, "voices.bin")
        val tokens = requireFile(modelDir, "tokens.txt")
        val dataDir = File(modelDir, "espeak-ng-data").takeIf { it.exists() }?.absolutePath ?: ""

        return OfflineTtsKittenModelConfig(
            /* model = */ File(modelDir, modelPath).absolutePath,
            /* voices = */ voices.absolutePath,
            /* tokens = */ tokens.absolutePath,
            /* dataDir = */ dataDir,
            /* lengthScale = */ 1.0f
        )
    }

    private fun requireFile(dir: File, name: String): File {
        val f = File(dir, name)
        if (!f.exists()) throw IllegalStateException("Missing $name in ${dir.absolutePath}")
        return f
    }

    private fun pickFirstExisting(dir: File, names: List<String>): String? {
        for (n in names) {
            if (File(dir, n).exists()) return n
        }
        return null
    }
}


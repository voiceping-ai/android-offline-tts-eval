package com.voiceping.offlinettseval.engines

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import android.os.SystemClock
import com.voiceping.offlinettseval.catalog.ModelSource
import com.voiceping.offlinettseval.catalog.TtsModel
import com.voiceping.offlinettseval.engine.LoadOptions
import com.voiceping.offlinettseval.engine.LoadResult
import com.voiceping.offlinettseval.engine.ReadyState
import com.voiceping.offlinettseval.engine.SynthesisRequest
import com.voiceping.offlinettseval.engine.SynthesisResult
import com.voiceping.offlinettseval.engine.TtsEngine
import com.voiceping.offlinettseval.io.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer

class NemoFastPitchHifiGanOrtEngine(
    private val repo: ModelRepository,
) : TtsEngine {
    override val engineId: String = "nemo_ort"

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var fastPitch: OrtSession? = null
    private var hifiGan: OrtSession? = null

    private var loadedModelId: String? = null

    private var sampleRate: Int = 22050
    private var nMels: Int = 80
    private var addBlank: Boolean = true
    private var blankId: Long = 0L
    private var padId: Long = 0L
    private var symbolToId: Map<String, Long> = emptyMap()
    private var tokenizer: NemoCharTokenizer? = null

    override suspend fun ensureReady(model: TtsModel): ReadyState {
        if (model.engine != engineId) return ReadyState.NeedsDownload("Wrong engine for model")
        return repo.ensureReady(model)
    }

    override suspend fun load(model: TtsModel, options: LoadOptions): LoadResult = withContext(Dispatchers.IO) {
        require(model.engine == engineId) { "Model ${model.id} is not a nemo_ort model" }
        require(model.source is ModelSource.LocalBundle) { "nemo_ort models must be provided as local bundles" }

        if (loadedModelId != null && loadedModelId != model.id) {
            release()
        }

        val modelDir = repo.modelDir(model)
        val fastPitchPath = File(modelDir, "fastpitch.onnx")
        val hifiGanPath = File(modelDir, "hifigan.onnx")
        if (!fastPitchPath.exists() || !hifiGanPath.exists()) {
            throw IllegalStateException("Missing ONNX files in ${modelDir.absolutePath}")
        }

        val started = SystemClock.elapsedRealtime()

        val config = readJson(File(modelDir, "config.json"))
        parseConfig(config)

        val symbols = readJson(File(modelDir, "symbols.json"))
        parseSymbols(symbols)
        tokenizer = NemoCharTokenizer(symbolToId = symbolToId, blankId = blankId, padId = padId, addBlank = addBlank)

        val sessOptions = SessionOptions().apply {
            setIntraOpNumThreads(options.threads)
            setInterOpNumThreads(1)
        }

        fastPitch = env.createSession(fastPitchPath.absolutePath, sessOptions)
        hifiGan = env.createSession(hifiGanPath.absolutePath, sessOptions)

        loadedModelId = model.id

        val loadTimeMs = SystemClock.elapsedRealtime() - started
        return@withContext LoadResult(
            sampleRate = sampleRate,
            numSpeakers = 1,
            loadTimeMs = loadTimeMs
        )
    }

    override suspend fun synthesize(request: SynthesisRequest): SynthesisResult = withContext(Dispatchers.IO) {
        val fp = requireNotNull(fastPitch) { "FastPitch session not loaded" }
        val hg = requireNotNull(hifiGan) { "HiFiGAN session not loaded" }

        val started = SystemClock.elapsedRealtime()

        val tok = requireNotNull(tokenizer) { "Tokenizer not initialized" }
        val inputIds = tok.tokenize(request.text)
        val inputLengths = longArrayOf(inputIds.size.toLong())

        val mel = runFastPitch(fp, inputIds, inputLengths)
        val audio = runHifiGan(hg, mel)

        val synthesisTimeMs = SystemClock.elapsedRealtime() - started
        val audioDurationSec = if (sampleRate > 0) audio.size.toDouble() / sampleRate.toDouble() else 0.0

        return@withContext SynthesisResult(
            samples = audio,
            sampleRate = sampleRate,
            synthesisTimeMs = synthesisTimeMs,
            audioDurationSec = audioDurationSec
        )
    }

    override fun release() {
        fastPitch?.close()
        hifiGan?.close()
        fastPitch = null
        hifiGan = null
        loadedModelId = null
        tokenizer = null
    }

    private fun readJson(file: File): JSONObject {
        val text = file.readText(Charsets.UTF_8)
        return JSONObject(text)
    }

    private fun parseConfig(json: JSONObject) {
        sampleRate = json.optInt("sample_rate", 22050)
        nMels = json.optInt("n_mels", 80)
        addBlank = json.optBoolean("add_blank", true)
        blankId = json.optLong("blank_id", 0L)
        padId = json.optLong("pad_id", 0L)
    }

    private fun parseSymbols(json: JSONObject) {
        val symbolsArr = json.optJSONArray("symbols")
            ?: throw IllegalStateException("symbols.json missing 'symbols' array")

        val map = HashMap<String, Long>(symbolsArr.length())
        for (i in 0 until symbolsArr.length()) {
            val s = symbolsArr.optString(i, "")
            if (s.isNotEmpty()) map[s] = i.toLong()
        }

        // Allow override of ids from file.
        blankId = json.optLong("blank_id", blankId)
        padId = json.optLong("pad_id", padId)
        addBlank = json.optBoolean("add_blank", addBlank)

        symbolToId = map
    }

    private fun runFastPitch(session: OrtSession, inputIds: LongArray, inputLengths: LongArray): FloatArray {
        val idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1L, inputIds.size.toLong()))
        val lenTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputLengths), longArrayOf(1L))

        idsTensor.use { ids ->
            lenTensor.use { lens ->
                val inputs = mapOf(
                    "input_ids" to ids,
                    "input_lengths" to lens
                )
                session.run(inputs).use { result ->
                    // Expect output mel: float[1, n_mels, T]
                    val v = result[0].value
                    @Suppress("UNCHECKED_CAST")
                    val arr = v as Array<Array<FloatArray>>
                    val mel2d = arr[0] // [n_mels][T]
                    val t = mel2d[0].size
                    val flat = FloatArray(nMels * t)
                    var o = 0
                    for (m in 0 until nMels) {
                        val row = mel2d[m]
                        for (i in 0 until t) {
                            flat[o++] = row[i]
                        }
                    }
                    return flatWithShape(flat, nMels, t)
                }
            }
        }
    }

    private data class Mel(val flat: FloatArray, val nMels: Int, val t: Int)

    private fun flatWithShape(flat: FloatArray, nMels: Int, t: Int): FloatArray {
        // We encode shape into a separate object for the next step, but keep API simple:
        // store shape in the first two floats is unsafe; instead we keep class Mel.
        // This helper exists only to keep old signature; actual shape is derived in runHifiGan from nMels/t.
        return flat
    }

    private fun runHifiGan(session: OrtSession, melFlat: FloatArray): FloatArray {
        // melFlat is [nMels * T] row-major (mel axis first), reconstruct shape.
        // We assume nMels from config; infer T.
        val t = melFlat.size / nMels
        require(t > 0 && melFlat.size == nMels * t) { "Invalid mel shape" }

        // Build 3D float tensor [1, nMels, T].
        val mel3d = Array(1) { Array(nMels) { FloatArray(t) } }
        var idx = 0
        for (m in 0 until nMels) {
            for (i in 0 until t) {
                mel3d[0][m][i] = melFlat[idx++]
            }
        }

        val melTensor = OnnxTensor.createTensor(env, mel3d)
        melTensor.use { mel ->
            val inputs = mapOf("mel" to mel)
            session.run(inputs).use { result ->
                val v = result[0].value
                return when (v) {
                    is FloatArray -> v
                    is Array<*> -> {
                        // Expect float[1][S]
                        @Suppress("UNCHECKED_CAST")
                        val arr = v as Array<FloatArray>
                        arr[0]
                    }
                    else -> throw IllegalStateException("Unexpected HiFiGAN output type: ${v?.javaClass}")
                }
            }
        }
    }
}

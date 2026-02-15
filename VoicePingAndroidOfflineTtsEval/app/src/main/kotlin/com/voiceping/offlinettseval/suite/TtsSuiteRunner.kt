package com.voiceping.offlinettseval.suite

import com.voiceping.offlinettseval.catalog.PromptSuite
import com.voiceping.offlinettseval.catalog.TtsModel
import com.voiceping.offlinettseval.engine.LoadOptions
import com.voiceping.offlinettseval.engine.ReadyState
import com.voiceping.offlinettseval.engine.SynthesisRequest
import com.voiceping.offlinettseval.engines.EngineFactory
import com.voiceping.offlinettseval.io.ModelRepository
import com.voiceping.offlinettseval.io.WavWriter
import com.voiceping.offlinettseval.report.TtsResultJson
import com.voiceping.offlinettseval.util.TextMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.max

data class SuiteProgress(
    val runId: String,
    val modelId: String,
    val promptIndex: Int,
    val promptTotal: Int,
    val promptId: String,
)

class TtsSuiteRunner(
    private val repo: ModelRepository,
    private val engineFactory: EngineFactory,
) {
    suspend fun runSuite(
        model: TtsModel,
        suite: PromptSuite,
        runId: String,
        runMode: String,
        warmIterations: Int,
        loadOptions: LoadOptions,
        speakerId: Int,
        speed: Float,
        onProgress: (SuiteProgress) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val engine = engineFactory.create(model.engine)
        try {
            when (val ready = repo.ensureReady(model)) {
                is ReadyState.Ready -> Unit
                is ReadyState.MissingFiles -> throw IllegalStateException("Model not ready: missing ${ready.missing.take(5)}")
                is ReadyState.NeedsDownload -> throw IllegalStateException("Model not ready: ${ready.reason}")
            }

            val baseDir = File(repo.ttsEvalRoot(), runId)
            val promptTotal = suite.prompts.size

            var initialLoadMs = 0L
            if (runMode == "warm") {
                val loadRes = engine.load(model, loadOptions)
                initialLoadMs = loadRes.loadTimeMs
            }

            for ((index, prompt) in suite.prompts.withIndex()) {
                onProgress(
                    SuiteProgress(
                        runId = runId,
                        modelId = model.id,
                        promptIndex = index + 1,
                        promptTotal = promptTotal,
                        promptId = prompt.id,
                    )
                )

                val promptDir = File(baseDir, "${model.id}/${prompt.id}")
                promptDir.mkdirs()

                val text = prompt.text
                val words = TextMetrics.wordCount(text)
                val chars = text.length

                val loadMs: Long
                val synthMs: Long
                val samples: FloatArray
                val sampleRate: Int
                val audioDurationSec: Double

                if (runMode == "cold") {
                    engine.release()
                    val loadRes = engine.load(model, loadOptions)
                    loadMs = loadRes.loadTimeMs

                    val synthRes = engine.synthesize(
                        SynthesisRequest(model = model, text = text, speakerId = speakerId, speed = speed)
                    )
                    synthMs = synthRes.synthesisTimeMs
                    samples = synthRes.samples
                    sampleRate = synthRes.sampleRate
                    audioDurationSec = synthRes.audioDurationSec
                } else {
                    // warm: engine already loaded once
                    loadMs = if (index == 0) initialLoadMs else 0L

                    val times = LongArray(max(1, warmIterations))
                    var last: com.voiceping.offlinettseval.engine.SynthesisResult? = null
                    for (i in times.indices) {
                        val synthRes = engine.synthesize(
                            SynthesisRequest(model = model, text = text, speakerId = speakerId, speed = speed)
                        )
                        times[i] = synthRes.synthesisTimeMs
                        last = synthRes
                    }
                    times.sort()
                    synthMs = times[times.size / 2]

                    val final = requireNotNull(last)
                    samples = final.samples
                    sampleRate = final.sampleRate
                    audioDurationSec = final.audioDurationSec
                }

                val wavFile = File(promptDir, "audio.wav")
                WavWriter.writeMono16bitPcm(wavFile, samples, sampleRate)

                val now = System.currentTimeMillis()
                val json: JSONObject = TtsResultJson.build(
                    timestampMs = now,
                    model = model,
                    promptId = prompt.id,
                    inputText = text,
                    words = words,
                    chars = chars,
                    threads = loadOptions.threads,
                    provider = loadOptions.provider,
                    speakerId = speakerId,
                    speed = speed,
                    loadTimeMs = loadMs,
                    synthesisTimeMs = synthMs,
                    sampleRate = sampleRate,
                    samples = samples.size,
                    audioDurationSec = audioDurationSec,
                    wavPath = wavFile.absolutePath,
                    runMode = runMode,
                    warmIterations = warmIterations
                )

                File(promptDir, "result.json").writeText(json.toString(2))
            }
        } finally {
            engine.release()
        }
    }
}

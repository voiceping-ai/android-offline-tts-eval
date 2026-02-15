package com.voiceping.offlinettseval.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceping.offlinettseval.catalog.ModelCatalog
import com.voiceping.offlinettseval.catalog.ModelSource
import com.voiceping.offlinettseval.catalog.PromptSuite
import com.voiceping.offlinettseval.catalog.TtsModel
import com.voiceping.offlinettseval.engine.LoadOptions
import com.voiceping.offlinettseval.engine.ReadyState
import com.voiceping.offlinettseval.engine.SynthesisRequest
import com.voiceping.offlinettseval.engine.TtsEngine
import com.voiceping.offlinettseval.engines.EngineFactory
import com.voiceping.offlinettseval.io.AudioPlayer
import com.voiceping.offlinettseval.io.ModelRepository
import com.voiceping.offlinettseval.io.WavWriter
import com.voiceping.offlinettseval.report.TtsResultJson
import com.voiceping.offlinettseval.suite.TtsSuiteRunner
import com.voiceping.offlinettseval.util.TextMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import kotlin.math.max

class TtsEvalViewModel(app: Application) : AndroidViewModel(app) {
    private val catalog: ModelCatalog = ModelCatalog.load(app)
    private val promptSuite: PromptSuite = PromptSuite.load(app)
    private val repo: ModelRepository = ModelRepository(app, catalog)
    private val engineFactory: EngineFactory = EngineFactory(app, repo)
    private val suiteRunner: TtsSuiteRunner = TtsSuiteRunner(repo, engineFactory)
    private val audioPlayer = AudioPlayer()

    private var activeEngine: TtsEngine? = null
    private var activeModelId: String? = null
    private var activeEngineId: String? = null
    private var activeLoadOptions: LoadOptions? = null

    private val _uiState = MutableStateFlow(buildInitialState())
    val uiState: StateFlow<TtsEvalUiState> = _uiState.asStateFlow()

    override fun onCleared() {
        super.onCleared()
        runCatching { audioPlayer.stop() }
        releaseActiveEngine()
    }

    fun setSelectedModel(id: String) {
        _uiState.update {
            it.copy(
                selectedModelId = id,
                readyState = ReadyState.NeedsDownload("not checked"),
                isDownloading = false,
                downloadProgress = 0.0f,
                isLoaded = false,
                sampleRate = null,
                numSpeakers = null,
                lastLoadTimeMs = null,
                lastRun = null,
                localImportCommand = null,
                lastError = null,
            )
        }
        releaseActiveEngine()
        refreshSelectedModelState()
    }

    fun rescanModels() {
        refreshSelectedModelState()
    }

    fun setInputText(text: String) {
        _uiState.update { it.copy(inputText = text, lastError = null) }
    }

    fun setThreads(threads: Int) {
        val v = threads.coerceIn(1, 16)
        if (v == uiState.value.threads) return
        releaseActiveEngine()
        _uiState.update {
            it.copy(
                threads = v,
                isLoaded = false,
                sampleRate = null,
                numSpeakers = null,
                lastLoadTimeMs = null,
                lastError = null,
            )
        }
    }

    fun setSpeed(speed: Float) {
        _uiState.update { it.copy(speed = speed.coerceIn(0.5f, 2.0f), lastError = null) }
    }

    fun setSpeakerId(speakerId: Int) {
        _uiState.update { it.copy(speakerId = speakerId.coerceAtLeast(0), lastError = null) }
    }

    fun setRunMode(mode: String) {
        val v = if (mode == "warm") "warm" else "cold"
        _uiState.update { it.copy(runMode = v, lastError = null) }
    }

    fun setWarmIterations(iters: Int) {
        _uiState.update { it.copy(warmIterations = iters.coerceIn(1, 10), lastError = null) }
    }

    fun stopPlayback() {
        audioPlayer.stop()
    }

    fun deleteSelectedModel() {
        val model = selectedModelOrNull() ?: return
        if (model.source is ModelSource.System) return

        viewModelScope.launch {
            runCatching {
                audioPlayer.stop()
                releaseActiveEngine()
                repo.deleteModel(model)
            }.onFailure { e ->
                _uiState.update { it.copy(lastError = e.message ?: e.toString()) }
            }
            refreshSelectedModelState()
        }
    }

    fun downloadSelectedModel() {
        val model = selectedModelOrNull() ?: return
        if (model.source !is ModelSource.HuggingFace) return

        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, downloadProgress = 0.0f, lastError = null) }
            runCatching {
                repo.downloadWithDependencies(model).collect { p ->
                    _uiState.update { it.copy(downloadProgress = p.coerceIn(0.0f, 1.0f)) }
                }
            }.onFailure { e ->
                _uiState.update { it.copy(lastError = e.message ?: e.toString()) }
            }
            _uiState.update { it.copy(isDownloading = false) }
            refreshSelectedModelState()
        }
    }

    fun loadSelectedModelIfNeeded() {
        val model = selectedModelOrNull() ?: return
        viewModelScope.launch {
            runCatching {
                ensureLoaded(model)
            }.onFailure { e ->
                _uiState.update { it.copy(isLoaded = false, lastError = e.message ?: e.toString()) }
            }
        }
    }

    fun synthesizeAndPlay() {
        val model = selectedModelOrNull() ?: return
        val text = uiState.value.inputText
        if (text.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSynthesizing = true, lastError = null) }

            runCatching {
                val (loadMsUsed, _) = ensureLoaded(model)

                val engine = requireNotNull(activeEngine) { "Engine not loaded" }

                val startedAt = System.currentTimeMillis()
                val words = TextMetrics.wordCount(text)
                val chars = text.length

                val synth = engine.synthesize(
                    SynthesisRequest(
                        model = model,
                        text = text,
                        speakerId = uiState.value.speakerId,
                        speed = uiState.value.speed,
                    )
                )

                val runId = "manual_${startedAt}"
                val promptId = "manual"
                val promptDir = File(File(repo.ttsEvalRoot(), runId), "${model.id}/$promptId").apply { mkdirs() }
                val wavFile = File(promptDir, "audio.wav")
                WavWriter.writeMono16bitPcm(wavFile, synth.samples, synth.sampleRate)

                val json: JSONObject = TtsResultJson.build(
                    timestampMs = startedAt,
                    model = model,
                    promptId = promptId,
                    inputText = text,
                    words = words,
                    chars = chars,
                    threads = uiState.value.threads,
                    provider = uiState.value.provider,
                    speakerId = uiState.value.speakerId,
                    speed = uiState.value.speed,
                    loadTimeMs = loadMsUsed,
                    synthesisTimeMs = synth.synthesisTimeMs,
                    sampleRate = synth.sampleRate,
                    samples = synth.samples.size,
                    audioDurationSec = synth.audioDurationSec,
                    wavPath = wavFile.absolutePath,
                    runMode = "manual",
                    warmIterations = 1,
                )

                val jsonFile = File(promptDir, "result.json")
                jsonFile.writeText(json.toString(2))

                audioPlayer.playFloatMono(synth.samples, synth.sampleRate)

                val synthSec = synth.synthesisTimeMs.toDouble() / 1000.0
                val tokPerSec = if (synthSec > 0.0) words.toDouble() / synthSec else 0.0
                val rtf = if (synth.audioDurationSec > 0.0) synthSec / synth.audioDurationSec else 0.0

                _uiState.update {
                    it.copy(
                        lastRun = TtsEvalLastRun(
                            modelName = model.displayName,
                            promptId = promptId,
                            words = words,
                            chars = chars,
                            loadTimeMs = loadMsUsed,
                            synthesisTimeMs = synth.synthesisTimeMs,
                            sampleRate = synth.sampleRate,
                            samples = synth.samples.size,
                            audioDurationSec = synth.audioDurationSec,
                            tokensPerSecond = tokPerSec,
                            rtf = rtf,
                            wavPath = wavFile.absolutePath,
                            jsonPath = jsonFile.absolutePath,
                        ),
                        lastError = null,
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(lastError = e.message ?: e.toString()) }
            }

            _uiState.update { it.copy(isSynthesizing = false) }
        }
    }

    fun runSuite() {
        val model = selectedModelOrNull() ?: return
        if (uiState.value.isSuiteRunning) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSuiteRunning = true, suiteProgress = null, lastError = null) }
            audioPlayer.stop()
            releaseActiveEngine()
            _uiState.update { it.copy(isLoaded = false, sampleRate = null, numSpeakers = null, lastLoadTimeMs = null) }

            runCatching {
                val runId = "suite_${System.currentTimeMillis()}"
                suiteRunner.runSuite(
                    model = model,
                    suite = promptSuite,
                    runId = runId,
                    runMode = uiState.value.runMode,
                    warmIterations = uiState.value.warmIterations,
                    loadOptions = LoadOptions(threads = uiState.value.threads, provider = uiState.value.provider),
                    speakerId = uiState.value.speakerId,
                    speed = uiState.value.speed,
                    onProgress = { p ->
                        _uiState.update { it.copy(suiteProgress = p) }
                    },
                )
            }.onFailure { e ->
                _uiState.update { it.copy(lastError = e.message ?: e.toString()) }
            }

            _uiState.update { it.copy(isSuiteRunning = false, suiteProgress = null) }
        }
    }

    private fun buildInitialState(): TtsEvalUiState {
        val models = repo.selectableModels()
        val defaultId = models.firstOrNull { it.id == "kokoro-en-v0_19" }?.id
            ?: models.firstOrNull()?.id
            ?: ""
        val ready = repo.modelById(defaultId)?.let { repo.ensureReady(it) } ?: ReadyState.NeedsDownload("no models")
        val importCmd = repo.modelById(defaultId)?.let { repo.localBundleImportCommand(it) }
        return TtsEvalUiState(
            models = models,
            selectedModelId = defaultId,
            readyState = ready,
            localImportCommand = importCmd,
        )
    }

    private fun selectedModelOrNull(): TtsModel? {
        val id = uiState.value.selectedModelId
        return repo.modelById(id) ?: repo.selectableModels().firstOrNull()
    }

    private fun refreshSelectedModelState() {
        val model = selectedModelOrNull()
        if (model == null) {
            _uiState.update { it.copy(readyState = ReadyState.NeedsDownload("no models"), localImportCommand = null) }
            return
        }
        val ready = repo.ensureReady(model)
        val importCmd = repo.localBundleImportCommand(model)
        _uiState.update { it.copy(readyState = ready, localImportCommand = importCmd) }
    }

    private data class EnsureLoaded(val loadTimeMsUsed: Long, val wasReloaded: Boolean)

    private suspend fun ensureLoaded(model: TtsModel): EnsureLoaded {
        val state = uiState.value

        when (val ready = repo.ensureReady(model)) {
            is ReadyState.Ready -> Unit
            is ReadyState.NeedsDownload -> throw IllegalStateException("Model not ready: ${ready.reason}")
            is ReadyState.MissingFiles -> throw IllegalStateException("Model not ready: missing ${ready.missing.take(5)}")
        }

        val options = LoadOptions(threads = state.threads, provider = state.provider)

        val sameEngine = activeEngine != null && activeModelId == model.id && activeEngineId == model.engine
        val sameOptions = activeLoadOptions == options
        if (sameEngine && sameOptions && state.isLoaded) {
            return EnsureLoaded(loadTimeMsUsed = 0L, wasReloaded = false)
        }

        if (!sameEngine) {
            releaseActiveEngine()
            activeEngine = engineFactory.create(model.engine)
            activeModelId = model.id
            activeEngineId = model.engine
        }

        val engine = requireNotNull(activeEngine)
        val res = engine.load(model, options)
        activeLoadOptions = options

        val speakers = max(1, res.numSpeakers)
        val maxSpeakerId = max(0, speakers - 1)
        val coercedSpeakerId = state.speakerId.coerceIn(0, maxSpeakerId)

        _uiState.update {
            it.copy(
                isLoaded = true,
                sampleRate = if (res.sampleRate > 0) res.sampleRate else null,
                numSpeakers = speakers,
                lastLoadTimeMs = res.loadTimeMs,
                speakerId = coercedSpeakerId,
                lastError = null,
            )
        }

        return EnsureLoaded(loadTimeMsUsed = res.loadTimeMs, wasReloaded = true)
    }

    private fun releaseActiveEngine() {
        activeEngine?.release()
        activeEngine = null
        activeModelId = null
        activeEngineId = null
        activeLoadOptions = null
    }
}


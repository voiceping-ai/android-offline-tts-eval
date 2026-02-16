package com.voiceping.offlinettseval

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voiceping.offlinettseval.catalog.ModelCatalog
import com.voiceping.offlinettseval.catalog.ModelSource
import com.voiceping.offlinettseval.catalog.PromptSuite
import com.voiceping.offlinettseval.catalog.TtsModel
import com.voiceping.offlinettseval.engine.LoadOptions
import com.voiceping.offlinettseval.engine.ReadyState
import com.voiceping.offlinettseval.engines.EngineFactory
import com.voiceping.offlinettseval.io.ModelRepository
import com.voiceping.offlinettseval.suite.TtsSuiteRunner
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class TtsAllModelsBenchmarkTest {
    @Test
    fun runPromptSuiteForModels() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()

        val runMode = (args.getString("run_mode") ?: "warm").trim().ifBlank { "warm" }.let {
            if (it == "cold") "cold" else "warm"
        }
        val warmIterations = (args.getString("warm_iterations") ?: "3").toIntOrNull()?.coerceIn(1, 10) ?: 3
        val downloadHf = (args.getString("download_hf") ?: "false").toBooleanStrictOrNull() ?: false
        val deleteAfter = (args.getString("delete_after") ?: "false").toBooleanStrictOrNull() ?: false

        val threads = (args.getString("threads") ?: "4").toIntOrNull()?.coerceIn(1, 16) ?: 4
        val speakerId = (args.getString("speaker_id") ?: "0").toIntOrNull()?.coerceAtLeast(0) ?: 0
        val speed = (args.getString("speed") ?: "1.0").toFloatOrNull()?.coerceIn(0.5f, 2.0f) ?: 1.0f

        val catalog = ModelCatalog.load(ctx)
        val repo = ModelRepository(ctx, catalog)
        val engineFactory = EngineFactory(ctx, repo)
        val runner = TtsSuiteRunner(repo, engineFactory)
        val suite = PromptSuite.load(ctx)

        val modelIdsRaw = args.getString("model_ids")
        val modelsToRun: List<TtsModel> = if (modelIdsRaw != null) {
            // If the caller explicitly provides model_ids:
            // - non-empty: run that subset
            // - empty string: run all selectable models
            val modelIdsArg = modelIdsRaw.trim()
            if (modelIdsArg.isNotBlank()) {
                val wanted = modelIdsArg.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
                repo.selectableModels().filter { wanted.contains(it.id) }
            } else {
                repo.selectableModels()
            }
        } else {
            // Default for connectedDebugAndroidTest (no args): keep it fast.
            repo.selectableModels().filter { it.id == "android-system-tts" }
        }

        // Dependency ref-counts for optional cleanup.
        val depRefCount = HashMap<String, Int>()
        for (m in modelsToRun) {
            for (d in m.dependencies) {
                depRefCount[d] = (depRefCount[d] ?: 0) + 1
            }
        }

        val runId = "benchmark_${System.currentTimeMillis()}_${runMode}"
        val loadOptions = LoadOptions(threads = threads, provider = "cpu")

        println("TTS benchmark runId=$runId models=${modelsToRun.size} runMode=$runMode warmIterations=$warmIterations downloadHf=$downloadHf deleteAfter=$deleteAfter")

        for (model in modelsToRun) {
            if (model.engine == "asset_only") continue

            println("=== Model: ${model.id} (${model.engine}) ===")

            if (model.source is ModelSource.HuggingFace && downloadHf) {
                println("Downloading (with deps): ${model.id}")
                repo.downloadWithDependencies(model).collect { p ->
                    // Keep output sparse to avoid log spam.
                    if (p >= 1.0f) println("Download complete: ${model.id}")
                }
            }

            when (val ready = repo.ensureReady(model)) {
                is ReadyState.Ready -> Unit
                is ReadyState.NeedsDownload -> {
                    println("SKIP (needs download): ${model.id} reason=${ready.reason}")
                    continue
                }
                is ReadyState.MissingFiles -> {
                    println("SKIP (missing files): ${model.id} missing=${ready.missing.take(5)}")
                    continue
                }
            }

            runner.runSuite(
                model = model,
                suite = suite,
                runId = runId,
                runMode = runMode,
                warmIterations = warmIterations,
                loadOptions = loadOptions,
                speakerId = speakerId,
                speed = speed,
                onProgress = { p ->
                    println("Progress: ${p.modelId} ${p.promptIndex}/${p.promptTotal} (${p.promptId})")
                }
            )

            if (deleteAfter && model.source !is ModelSource.System) {
                println("Deleting model files: ${model.id}")
                repo.deleteModel(model)
            }

            if (deleteAfter) {
                for (depId in model.dependencies) {
                    depRefCount[depId] = max(0, (depRefCount[depId] ?: 0) - 1)
                    if (depRefCount[depId] == 0) {
                        val dep = repo.modelById(depId)
                        if (dep != null && dep.source !is ModelSource.System) {
                            println("Deleting dependency files: $depId")
                            repo.deleteModel(dep)
                        }
                    }
                }
            }
        }

        println("TTS benchmark complete. Exports dir: /sdcard/Android/data/com.voiceping.ttseval/files/exports/tts_eval/$runId/")
    }
}

package com.voiceping.offlinettseval

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voiceping.offlinettseval.catalog.ModelCatalog
import com.voiceping.offlinettseval.catalog.PromptSuite
import com.voiceping.offlinettseval.engine.LoadOptions
import com.voiceping.offlinettseval.engine.ReadyState
import com.voiceping.offlinettseval.engines.EngineFactory
import com.voiceping.offlinettseval.io.ModelRepository
import com.voiceping.offlinettseval.suite.TtsSuiteRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TtsSuiteBenchmarkTest {
    @Test
    fun systemTts_writesArtifacts_forSmallSuite() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = ModelCatalog.load(ctx)
        val repo = ModelRepository(ctx, catalog)
        val engineFactory = EngineFactory(ctx, repo)
        val runner = TtsSuiteRunner(repo, engineFactory)

        val model = requireNotNull(repo.modelById("android-system-tts")) { "Missing android-system-tts in model_catalog.json" }
        val suiteAll = PromptSuite.load(ctx)
        val suite = suiteAll.copy(prompts = suiteAll.prompts.take(2))

        val runId = "instrumentation_system_${System.currentTimeMillis()}"
        runner.runSuite(
            model = model,
            suite = suite,
            runId = runId,
            runMode = "cold",
            warmIterations = 1,
            loadOptions = LoadOptions(threads = 4, provider = "cpu"),
            speakerId = 0,
            speed = 1.0f,
            onProgress = { _ -> },
        )

        val base = File(repo.ttsEvalRoot(), runId)
        for (p in suite.prompts) {
            val dir = File(base, "${model.id}/${p.id}")
            assertTrue(File(dir, "result.json").exists())
            assertTrue(File(dir, "audio.wav").exists())
        }
    }

    @Test
    fun sherpa_kokoro_runsIfReady() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = ModelCatalog.load(ctx)
        val repo = ModelRepository(ctx, catalog)
        val engineFactory = EngineFactory(ctx, repo)
        val runner = TtsSuiteRunner(repo, engineFactory)

        val model = repo.modelById("kokoro-en-v0_19") ?: return@runBlocking
        assumeTrue(repo.ensureReady(model) is ReadyState.Ready)

        val suiteAll = PromptSuite.load(ctx)
        val suite = suiteAll.copy(prompts = suiteAll.prompts.take(1))

        val runId = "instrumentation_kokoro_${System.currentTimeMillis()}"
        runner.runSuite(
            model = model,
            suite = suite,
            runId = runId,
            runMode = "cold",
            warmIterations = 1,
            loadOptions = LoadOptions(threads = 4, provider = "cpu"),
            speakerId = 0,
            speed = 1.0f,
            onProgress = { _ -> },
        )

        val dir = File(File(repo.ttsEvalRoot(), runId), "${model.id}/${suite.prompts[0].id}")
        assertTrue(File(dir, "result.json").exists())
        assertTrue(File(dir, "audio.wav").exists())
    }

    @Test
    fun nemo_bundle_runsIfPresent() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = ModelCatalog.load(ctx)
        val repo = ModelRepository(ctx, catalog)
        val engineFactory = EngineFactory(ctx, repo)
        val runner = TtsSuiteRunner(repo, engineFactory)

        val model = repo.modelById("nemo-fastpitch-hifigan-en") ?: return@runBlocking
        assumeTrue(repo.ensureReady(model) is ReadyState.Ready)

        val suiteAll = PromptSuite.load(ctx)
        val suite = suiteAll.copy(prompts = suiteAll.prompts.take(1))

        val runId = "instrumentation_nemo_${System.currentTimeMillis()}"
        runner.runSuite(
            model = model,
            suite = suite,
            runId = runId,
            runMode = "cold",
            warmIterations = 1,
            loadOptions = LoadOptions(threads = 4, provider = "cpu"),
            speakerId = 0,
            speed = 1.0f,
            onProgress = { _ -> },
        )

        val dir = File(File(repo.ttsEvalRoot(), runId), "${model.id}/${suite.prompts[0].id}")
        assertTrue(File(dir, "result.json").exists())
        assertTrue(File(dir, "audio.wav").exists())
    }
}

package com.voiceping.offlinettseval.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voiceping.offlinettseval.catalog.ModelSource
import com.voiceping.offlinettseval.engine.ReadyState
import kotlin.math.max

@Composable
fun TtsEvalScreen(vm: TtsEvalViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    val models = state.models
    val selected = models.firstOrNull { it.id == state.selectedModelId } ?: models.firstOrNull()

    var modelPickerOpen by remember { mutableStateOf(false) }
    var modelQuery by remember { mutableStateOf("") }
    var showImportCommand by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Offline TTS Eval", style = MaterialTheme.typography.headlineSmall)

        if (selected == null) {
            Text("No models found in model_catalog.json", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Model", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { modelPickerOpen = true }) {
                    Text(selected.displayName)
                }

                val status = when (val r = state.readyState) {
                    is ReadyState.Ready -> "Ready"
                    is ReadyState.NeedsDownload -> "Needs download"
                    is ReadyState.MissingFiles -> "Missing ${r.missing.size}"
                }
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }

            if (modelPickerOpen) {
                val q = modelQuery.trim().lowercase()
                val filtered = if (q.isBlank()) {
                    models
                } else {
                    models.filter { m ->
                        m.displayName.lowercase().contains(q) || m.id.lowercase().contains(q)
                    }
                }

                Dialog(onDismissRequest = { modelPickerOpen = false }) {
                    Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 6.dp) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Select model", style = MaterialTheme.typography.titleMedium)

                            OutlinedTextField(
                                value = modelQuery,
                                onValueChange = { modelQuery = it },
                                label = { Text("Search (name or id)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                                items(filtered, key = { it.id }) { m ->
                                    TextButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            modelPickerOpen = false
                                            modelQuery = ""
                                            showImportCommand = false
                                            vm.setSelectedModel(m.id)
                                        }
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(m.displayName, style = MaterialTheme.typography.bodyMedium)
                                            Text(m.id, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { modelPickerOpen = false }) { Text("Close") }
                            }
                        }
                    }
                }
            }

            Text(selected.inferenceMethod, style = MaterialTheme.typography.bodySmall)
            if (selected.meta.description.isNotBlank()) {
                Text(selected.meta.description, style = MaterialTheme.typography.bodySmall)
            }

            when (val r = state.readyState) {
                is ReadyState.NeedsDownload -> Text("Not ready: ${r.reason}", style = MaterialTheme.typography.bodySmall)
                is ReadyState.MissingFiles -> {
                    Text(
                        "Missing: ${r.missing.take(3).joinToString()}${if (r.missing.size > 3) "…" else ""}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                is ReadyState.Ready -> Unit
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.rescanModels() }, enabled = !state.isDownloading && !state.isSuiteRunning) {
                    Text("Re-scan")
                }

                if (selected.source is ModelSource.HuggingFace) {
                    Button(
                        onClick = { vm.downloadSelectedModel() },
                        enabled = !state.isDownloading && !state.isSuiteRunning
                    ) {
                        Text(if (state.isDownloading) "Downloading…" else "Download")
                    }
                }

                if (state.localImportCommand != null) {
                    TextButton(onClick = { showImportCommand = !showImportCommand }) {
                        Text(if (showImportCommand) "Hide import" else "Show import")
                    }
                }

                if (selected.source !is ModelSource.System) {
                    TextButton(
                        onClick = { vm.deleteSelectedModel() },
                        enabled = !state.isDownloading && !state.isSuiteRunning
                    ) { Text("Delete") }
                }

                TextButton(
                    onClick = { vm.loadSelectedModelIfNeeded() },
                    enabled = state.readyState is ReadyState.Ready && !state.isDownloading && !state.isSuiteRunning && !state.isLoaded
                ) { Text("Load") }
            }

            if (state.isDownloading) {
                LinearProgressIndicator(progress = { state.downloadProgress }, modifier = Modifier.fillMaxWidth())
                Text("${(state.downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }

            if (showImportCommand && state.localImportCommand != null) {
                Text("Import command:", style = MaterialTheme.typography.labelLarge)
                Text(state.localImportCommand!!, style = MaterialTheme.typography.bodySmall)
            }

            if (state.isLoaded) {
                Text(
                    "Loaded: sampleRate=${state.sampleRate ?: "-"} speakers=${state.numSpeakers ?: "-"} loadMs=${state.lastLoadTimeMs ?: "-"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        OutlinedTextField(
            value = state.inputText,
            onValueChange = vm::setInputText,
            label = { Text("Text") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Threads: ${state.threads}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = state.threads.toFloat(),
                onValueChange = { vm.setThreads(it.toInt()) },
                valueRange = 1f..16f,
                steps = 14
            )

            Text("Speed: ${"%.2f".format(state.speed)}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = state.speed,
                onValueChange = vm::setSpeed,
                valueRange = 0.5f..2.0f,
                steps = 14
            )

            val speakers = max(1, state.numSpeakers ?: 1)
            val maxSpeakerId = max(0, speakers - 1)
            Text("Speaker: ${state.speakerId} / $maxSpeakerId", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = state.speakerId.toFloat().coerceIn(0f, maxSpeakerId.toFloat()),
                onValueChange = { vm.setSpeakerId(it.toInt()) },
                valueRange = 0f..maxSpeakerId.toFloat(),
                steps = max(0, maxSpeakerId - 1)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.synthesizeAndPlay() },
                enabled = state.readyState is ReadyState.Ready && !state.isDownloading && !state.isSuiteRunning && !state.isSynthesizing
            ) { Text(if (state.isSynthesizing) "Synthesizing…" else "Speak") }

            TextButton(onClick = { vm.stopPlayback() }) { Text("Stop") }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Suite", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.setRunMode("cold") }, enabled = state.runMode != "cold") { Text("Cold") }
                TextButton(onClick = { vm.setRunMode("warm") }, enabled = state.runMode != "warm") { Text("Warm") }
            }

            if (state.runMode == "warm") {
                Text("Warm iterations: ${state.warmIterations}", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = state.warmIterations.toFloat(),
                    onValueChange = { vm.setWarmIterations(it.toInt()) },
                    valueRange = 1f..10f,
                    steps = 8
                )
            }

            Button(
                onClick = { vm.runSuite() },
                enabled = state.readyState is ReadyState.Ready && !state.isDownloading && !state.isSuiteRunning
            ) { Text(if (state.isSuiteRunning) "Running…" else "Run Suite") }

            state.suiteProgress?.let { p ->
                Text(
                    "Progress: ${p.modelId} ${p.promptIndex}/${p.promptTotal} (${p.promptId})",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                "Exports on device: /sdcard/Android/data/com.voiceping.ttseval/files/exports/tts_eval/",
                style = MaterialTheme.typography.bodySmall
            )
        }

        state.lastError?.let { err ->
            Text("Error: $err", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        state.lastRun?.let { r ->
            Spacer(modifier = Modifier.height(8.dp))
            Text("Last Result", style = MaterialTheme.typography.titleMedium)
            Text("Model: ${r.modelName}", style = MaterialTheme.typography.bodySmall)
            Text("Words: ${r.words} | Chars: ${r.chars}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Load: ${r.loadTimeMs}ms | Synth: ${r.synthesisTimeMs}ms | Audio: ${"%.2f".format(r.audioDurationSec)}s",
                style = MaterialTheme.typography.bodySmall
            )
            Text("tok/s: ${"%.2f".format(r.tokensPerSecond)} | RTF: ${"%.3f".format(r.rtf)}", style = MaterialTheme.typography.bodySmall)
            Text("WAV: ${r.wavPath}", style = MaterialTheme.typography.bodySmall)
            Text("JSON: ${r.jsonPath}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

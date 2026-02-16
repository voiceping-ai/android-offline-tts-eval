package com.voiceping.offlinettseval.io

import android.content.Context
import android.util.Log
import com.voiceping.offlinettseval.catalog.ModelCatalog
import com.voiceping.offlinettseval.catalog.ModelSource
import com.voiceping.offlinettseval.catalog.TtsModel
import com.voiceping.offlinettseval.engine.ReadyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ModelRepository(
    private val context: Context,
    private val catalog: ModelCatalog,
) {
    companion object {
        private const val TAG = "ModelRepository"
        private const val MANIFEST_NAME = "_hf_manifest.json"
        private const val TEMP_SUFFIX = ".tmp"
        private const val DOWNLOAD_BUFFER_BYTES = 8 * 1024
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val modelsRoot: File = run {
        val external = context.getExternalFilesDir(null)
        val base = external ?: context.filesDir
        File(base, "models").apply { mkdirs() }
    }

    fun allModels(): List<TtsModel> = catalog.models

    fun modelById(id: String): TtsModel? = catalog.models.firstOrNull { it.id == id }

    fun selectableModels(): List<TtsModel> {
        // Hide dependencies-only assets from the main UI picker.
        return catalog.models.filter { it.engine != "asset_only" }
    }

    fun modelDir(model: TtsModel): File = File(modelsRoot, model.id)

    fun exportsRoot(): File {
        val external = context.getExternalFilesDir(null)
        val base = external ?: context.filesDir
        return File(base, "exports").apply { mkdirs() }
    }

    fun ttsEvalRoot(): File = File(exportsRoot(), "tts_eval").apply { mkdirs() }

    fun ensureReady(model: TtsModel): ReadyState {
        // Dependencies must be ready too.
        val missing = ArrayList<String>()

        val all = model.dependencies.mapNotNull { modelById(it) } + model
        for (m in all) {
            when (val s = readyStateNoDeps(m)) {
                is ReadyState.Ready -> Unit
                is ReadyState.NeedsDownload -> {
                    val prefix = if (m.id == model.id) "model" else "dependency ${m.id}"
                    return ReadyState.NeedsDownload("$prefix: ${s.reason}")
                }
                is ReadyState.MissingFiles -> missing.addAll(s.missing)
            }
        }

        val dedup = missing.distinct().sorted()
        return if (dedup.isEmpty()) ReadyState.Ready else ReadyState.MissingFiles(dedup)
    }

    fun missingFiles(model: TtsModel): List<String> {
        return when (val src = model.source) {
            is ModelSource.System -> emptyList()
            is ModelSource.LocalBundle -> {
                val dir = modelDir(model)
                model.files.filterNot { File(dir, it).exists() }.map { "${model.id}/$it" }
            }
            is ModelSource.HuggingFace -> {
                val dir = modelDir(model)
                val manifest = File(dir, MANIFEST_NAME)
                if (!manifest.exists()) {
                    return emptyList()
                }
                val required = runCatching { readManifest(manifest) }.getOrNull() ?: return listOf("${model.id}/$MANIFEST_NAME")
                // Union with explicit files from the current catalog so stale manifests are detected.
                (required + model.files).distinct().filterNot { File(dir, it).exists() }.map { "${model.id}/$it" }
            }
        }
    }

    fun deleteModel(model: TtsModel) {
        modelDir(model).deleteRecursively()
    }

    fun downloadWithDependencies(model: TtsModel): Flow<Float> = flow {
        val toDownload = (model.dependencies.mapNotNull { modelById(it) } + model)
            .filter { it.source is ModelSource.HuggingFace }

        if (toDownload.isEmpty()) {
            emit(1.0f)
            return@flow
        }

        val total = toDownload.size
        for ((idx, m) in toDownload.withIndex()) {
            downloadHfModel(m).collect { p ->
                val overall = (idx + p.coerceIn(0.0f, 1.0f)) / total.toFloat()
                emit(overall)
            }
        }
        emit(1.0f)
    }.flowOn(Dispatchers.IO)

    private fun downloadHfModel(model: TtsModel): Flow<Float> = flow {
        val src = model.source as? ModelSource.HuggingFace
            ?: run {
                emit(1.0f); return@flow
            }

        val dir = modelDir(model)
        dir.mkdirs()

        val requiredPaths = resolveRequiredPaths(model, dir, src)
        writeManifest(File(dir, MANIFEST_NAME), requiredPaths)

        val totalFiles = requiredPaths.size.coerceAtLeast(1)
        for ((fileIndex, relPath) in requiredPaths.withIndex()) {
            val targetFile = File(dir, relPath)
            if (targetFile.exists()) {
                emit((fileIndex + 1).toFloat() / totalFiles.toFloat())
                continue
            }

            targetFile.parentFile?.mkdirs()
            val tempFile = File(targetFile.absolutePath + TEMP_SUFFIX)

            val url = hfResolveUrl(repoId = src.repo, revision = src.rev, path = relPath)
            val requestBuilder = Request.Builder().url(url)

            // Support resume if temp file exists.
            if (tempFile.exists()) {
                requestBuilder.addHeader("Range", "bytes=${tempFile.length()}-")
            }

            var bytesRead = 0L
            var totalBytes = 0L
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Download failed: HTTP ${response.code} for ${model.id}/$relPath")
                }

                val body = response.body ?: throw Exception("Empty response body for ${model.id}/$relPath")
                val contentLength = body.contentLength()
                val existingBytes = if (response.code == 206) tempFile.length() else 0L
                totalBytes = contentLength + existingBytes
                bytesRead = existingBytes

                FileOutputStream(tempFile, response.code == 206).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    body.byteStream().use { input ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read
                            if (totalBytes > 0) {
                                val fileProgress = bytesRead.toFloat() / totalBytes.toFloat()
                                val overallProgress = (fileIndex + fileProgress) / totalFiles.toFloat()
                                emit(overallProgress)
                            }
                        }
                    }
                }
            }

            if (totalBytes > 0 && bytesRead != totalBytes) {
                tempFile.safeDelete()
                throw Exception("Download incomplete for ${model.id}/$relPath: expected $totalBytes bytes, got $bytesRead bytes")
            }

            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                if (targetFile.exists() && targetFile.length() == tempFile.length()) {
                    tempFile.safeDelete()
                } else {
                    targetFile.safeDelete()
                    throw java.io.IOException("Failed to finalize ${model.id}/$relPath")
                }
            }

            emit((fileIndex + 1).toFloat() / totalFiles.toFloat())
        }
        emit(1.0f)
    }.flowOn(Dispatchers.IO)

    private fun resolveRequiredPaths(model: TtsModel, modelDir: File, src: ModelSource.HuggingFace): List<String> {
        val explicit = model.files

        if (model.prefixes.isEmpty()) {
            return explicit.distinct().sorted()
        }

        val manifest = File(modelDir, MANIFEST_NAME)
        val cached = runCatching { if (manifest.exists()) readManifest(manifest) else null }.getOrNull()
        if (cached != null) {
            // If a previous manifest exists and it already includes all explicit catalog files,
            // reuse it to avoid re-hitting the HF API for huge repos. If the catalog changed
            // (new explicit files), fall through and recompute.
            val missingExplicit = explicit.filterNot { cached.contains(it) }
            if (missingExplicit.isEmpty()) {
                return cached.distinct().sorted()
            }
        }

        val allRepoFiles = fetchRepoFileList(repoId = src.repo)

        val fromPrefixes = model.prefixes.flatMap { prefix ->
            allRepoFiles.filter { it.startsWith(prefix) }
        }

        val result = (explicit + fromPrefixes).distinct().sorted()

        val missing = explicit.filterNot { allRepoFiles.contains(it) }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "Some explicit files are not present in repo ${src.repo}: $missing")
        }

        return result
    }

    private fun fetchRepoFileList(repoId: String): List<String> {
        val url = "https://huggingface.co/api/models/$repoId"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HF API failed: HTTP ${response.code} for $repoId")
            }
            val body = response.body?.string() ?: throw Exception("HF API empty body for $repoId")
            val json = JSONObject(body)
            val siblings = json.optJSONArray("siblings") ?: JSONArray()
            val files = ArrayList<String>(siblings.length())
            for (i in 0 until siblings.length()) {
                val obj = siblings.optJSONObject(i) ?: continue
                val name = obj.optString("rfilename", "")
                if (name.isNotBlank()) files.add(name)
            }
            return files
        }
    }

    private fun hfResolveUrl(repoId: String, revision: String, path: String): String {
        return "https://huggingface.co/$repoId/resolve/$revision/$path"
    }

    private fun readManifest(file: File): List<String> {
        val json = JSONObject(file.readText())
        val arr = json.optJSONArray("files") ?: JSONArray()
        val result = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optString(i, "")
            if (p.isNotBlank()) result.add(p)
        }
        return result
    }

    private fun writeManifest(file: File, paths: List<String>) {
        val arr = JSONArray()
        paths.forEach { arr.put(it) }
        val json = JSONObject().put("files", arr)
        file.writeText(json.toString())
    }

    private fun File.safeDelete() {
        if (exists() && !delete()) {
            // Best-effort cleanup.
        }
    }

    fun localBundleImportCommand(model: TtsModel): String? {
        val src = model.source as? ModelSource.LocalBundle ?: return null
        // Host-side source path is relative; we display the exact command expected by the plan.
        val hostPath = "artifacts/nemo_bundles/${src.bundleName}/"
        val devicePath = "/sdcard/Android/data/com.voiceping.ttseval/files/models/${model.id}/"
        return "adb push $hostPath $devicePath"
    }

    private fun readyStateNoDeps(model: TtsModel): ReadyState {
        return when (model.source) {
            is ModelSource.System -> ReadyState.Ready
            is ModelSource.LocalBundle -> {
                val missing = missingFiles(model)
                if (missing.isEmpty()) ReadyState.Ready else ReadyState.MissingFiles(missing)
            }
            is ModelSource.HuggingFace -> {
                val dir = modelDir(model)
                val manifest = File(dir, MANIFEST_NAME)
                if (!manifest.exists()) {
                    return ReadyState.NeedsDownload("not downloaded")
                }
                val required = runCatching { readManifest(manifest) }.getOrNull()
                    ?: return ReadyState.NeedsDownload("manifest unreadable; re-download")
                // Union with explicit files from the current catalog so stale manifests are detected.
                val missing = (required + model.files).distinct().filterNot { File(dir, it).exists() }.map { "${model.id}/$it" }
                if (missing.isEmpty()) ReadyState.Ready else ReadyState.MissingFiles(missing)
            }
        }
    }
}

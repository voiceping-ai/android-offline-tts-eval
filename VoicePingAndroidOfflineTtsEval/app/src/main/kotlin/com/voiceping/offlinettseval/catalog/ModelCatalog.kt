package com.voiceping.offlinettseval.catalog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ModelCatalog(
    val schemaVersion: Int,
    val models: List<TtsModel>,
) {
    companion object {
        private const val EXPECTED_SCHEMA_VERSION = 1

        fun load(context: Context, assetPath: String = "model_catalog.json"): ModelCatalog {
            val jsonText = context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(jsonText)
            val schema = root.optInt("schema_version", 0)
            require(schema == EXPECTED_SCHEMA_VERSION) {
                "Unsupported model catalog schema_version=$schema (expected $EXPECTED_SCHEMA_VERSION)"
            }

            val arr = root.optJSONArray("models") ?: JSONArray()
            val models = ArrayList<TtsModel>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                models.add(parseModel(obj))
            }

            // Deterministic ordering for UI.
            models.sortBy { it.displayName.lowercase() }

            return ModelCatalog(schemaVersion = schema, models = models)
        }

        private fun parseModel(obj: JSONObject): TtsModel {
            val id = obj.getString("id")
            val displayName = obj.getString("display_name")
            val engine = obj.getString("engine")
            val modelType = obj.optString("model_type", "")

            val source = parseSource(obj.optJSONObject("source"))

            val files = jsonStringList(obj.optJSONArray("files"))
            val prefixes = jsonStringList(obj.optJSONArray("prefixes"))
            val dependencies = jsonStringList(obj.optJSONArray("dependencies"))

            val metaObj = obj.optJSONObject("meta") ?: JSONObject()
            val meta = ModelMeta(
                languages = metaObj.optString("languages", ""),
                description = metaObj.optString("description", ""),
                sizeHintMb = metaObj.optInt("size_hint_mb", 0),
            )

            return TtsModel(
                id = id,
                displayName = displayName,
                engine = engine,
                modelType = modelType,
                source = source,
                files = files,
                prefixes = prefixes,
                dependencies = dependencies,
                meta = meta,
            )
        }

        private fun parseSource(obj: JSONObject?): ModelSource {
            if (obj == null) return ModelSource.System
            return when (obj.optString("kind", "")) {
                "hf" -> ModelSource.HuggingFace(
                    repo = obj.getString("repo"),
                    rev = obj.optString("rev", "main")
                )
                "local_bundle" -> ModelSource.LocalBundle(
                    bundleName = obj.getString("bundle_name")
                )
                "system" -> ModelSource.System
                else -> ModelSource.System
            }
        }

        private fun jsonStringList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotBlank()) out.add(s)
            }
            return out
        }
    }
}


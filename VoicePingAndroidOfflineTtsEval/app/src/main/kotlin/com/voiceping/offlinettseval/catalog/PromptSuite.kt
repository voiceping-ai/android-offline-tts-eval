package com.voiceping.offlinettseval.catalog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PromptSuite(
    val schemaVersion: Int,
    val language: String,
    val prompts: List<Prompt>,
) {
    data class Prompt(val id: String, val text: String)

    companion object {
        private const val EXPECTED_SCHEMA_VERSION = 1

        fun load(context: Context, assetPath: String = "prompt_suite_en.json"): PromptSuite {
            val jsonText = context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(jsonText)
            val schema = root.optInt("schema_version", 0)
            require(schema == EXPECTED_SCHEMA_VERSION) {
                "Unsupported prompt suite schema_version=$schema (expected $EXPECTED_SCHEMA_VERSION)"
            }

            val language = root.optString("language", "")
            val arr = root.optJSONArray("prompts") ?: JSONArray()
            val prompts = ArrayList<Prompt>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim()
                val text = obj.optString("text", "")
                if (id.isNotBlank() && text.isNotBlank()) {
                    prompts.add(Prompt(id = id, text = text))
                }
            }
            return PromptSuite(schemaVersion = schema, language = language, prompts = prompts)
        }
    }
}


package com.voiceping.offlinettseval.engines

/**
 * Char-only tokenizer for NeMo FastPitch, aligned with the project plan:
 * - lowercase ASCII
 * - unsupported chars mapped to space
 * - repeated spaces collapsed and trimmed
 * - optional blank token inserted between every symbol
 */
class NemoCharTokenizer(
    private val symbolToId: Map<String, Long>,
    private val blankId: Long,
    private val padId: Long,
    private val addBlank: Boolean,
) {
    fun tokenize(text: String): LongArray {
        val spaceId = symbolToId[" "] ?: padId

        val tokenIds = ArrayList<Long>(text.length)
        var lastWasSpace = true

        for (raw in text) {
            val c = when (raw) {
                '\n', '\t', '\r' -> ' '
                in 'A'..'Z' -> (raw.code + 32).toChar()
                else -> raw
            }

            val s = c.toString()
            val id = symbolToId[s] ?: spaceId
            val isSpace = id == spaceId

            if (isSpace) {
                if (lastWasSpace) continue
                lastWasSpace = true
                tokenIds.add(spaceId)
            } else {
                lastWasSpace = false
                tokenIds.add(id)
            }
        }

        // Trim trailing space if we have other tokens.
        if (tokenIds.size > 1 && tokenIds.last() == spaceId) {
            tokenIds.removeAt(tokenIds.size - 1)
        }

        if (tokenIds.isEmpty()) {
            tokenIds.add(spaceId)
        }

        if (!addBlank || tokenIds.size == 1) {
            return tokenIds.toLongArray()
        }

        val out = LongArray(tokenIds.size * 2 - 1)
        var o = 0
        for (i in tokenIds.indices) {
            out[o++] = tokenIds[i]
            if (i != tokenIds.lastIndex) {
                out[o++] = blankId
            }
        }
        return out
    }
}


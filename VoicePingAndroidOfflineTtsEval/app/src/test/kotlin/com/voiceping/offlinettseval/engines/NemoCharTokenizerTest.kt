package com.voiceping.offlinettseval.engines

import kotlin.test.Test
import kotlin.test.assertContentEquals

class NemoCharTokenizerTest {
    @Test
    fun tokenize_lowercasesCollapsesSpacesAndInterleavesBlank() {
        val map = mapOf(
            " " to 0L,
            "a" to 1L,
            "b" to 2L,
        )
        val tok = NemoCharTokenizer(symbolToId = map, blankId = 99L, padId = 0L, addBlank = true)
        assertContentEquals(longArrayOf(1L, 99L, 0L, 99L, 2L), tok.tokenize("A  B!"))
    }

    @Test
    fun tokenize_emptyBecomesSingleSpace() {
        val map = mapOf(" " to 7L)
        val tok = NemoCharTokenizer(symbolToId = map, blankId = 99L, padId = 7L, addBlank = true)
        assertContentEquals(longArrayOf(7L), tok.tokenize("   \n\t"))
    }
}


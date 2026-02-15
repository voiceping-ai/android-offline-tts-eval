package com.voiceping.offlinettseval.util

import kotlin.test.Test
import kotlin.test.assertEquals

class TextMetricsTest {
    @Test
    fun wordCount_basicPunctuation() {
        assertEquals(2, TextMetrics.wordCount("Hello, world!"))
    }

    @Test
    fun wordCount_apostrophes() {
        assertEquals(2, TextMetrics.wordCount("Don't stop."))
    }

    @Test
    fun wordCount_numbersAndDots() {
        assertEquals(4, TextMetrics.wordCount("Version 1.0 shipped."))
    }
}

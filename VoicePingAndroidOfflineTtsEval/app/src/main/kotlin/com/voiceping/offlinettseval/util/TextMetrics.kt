package com.voiceping.offlinettseval.util

private val WORD_REGEX = Regex("[A-Za-z0-9']+")

object TextMetrics {
    fun wordCount(text: String): Int = WORD_REGEX.findAll(text).count()
}


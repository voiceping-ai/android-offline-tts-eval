package com.voiceping.offlinettseval.io

import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

object WavWriter {
    fun writeMono16bitPcm(path: File, samples: FloatArray, sampleRate: Int) {
        path.parentFile?.mkdirs()

        val dataSize = samples.size * 2
        val riffSize = 36 + dataSize

        FileOutputStream(path).use { out ->
            fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
            fun writeLeInt(v: Int) {
                out.write(v and 0xFF)
                out.write((v ushr 8) and 0xFF)
                out.write((v ushr 16) and 0xFF)
                out.write((v ushr 24) and 0xFF)
            }
            fun writeLeShort(v: Int) {
                out.write(v and 0xFF)
                out.write((v ushr 8) and 0xFF)
            }

            writeAscii("RIFF")
            writeLeInt(riffSize)
            writeAscii("WAVE")

            writeAscii("fmt ")
            writeLeInt(16) // PCM fmt chunk size
            writeLeShort(1) // PCM
            writeLeShort(1) // mono
            writeLeInt(sampleRate)
            writeLeInt(sampleRate * 2) // byte rate
            writeLeShort(2) // block align
            writeLeShort(16) // bits per sample

            writeAscii("data")
            writeLeInt(dataSize)

            val buf = ByteArray(2)
            for (s in samples) {
                val clamped = min(1.0f, max(-1.0f, s))
                val v = (clamped * 32767.0f).toInt().toShort().toInt()
                buf[0] = (v and 0xFF).toByte()
                buf[1] = ((v ushr 8) and 0xFF).toByte()
                out.write(buf)
            }
        }
    }
}


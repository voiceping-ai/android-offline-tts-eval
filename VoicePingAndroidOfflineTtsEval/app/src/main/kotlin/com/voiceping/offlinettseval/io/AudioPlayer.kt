package com.voiceping.offlinettseval.io

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max
import kotlin.math.min

class AudioPlayer {
    private var track: AudioTrack? = null

    fun stop() {
        track?.runCatching { stop() }
        track?.runCatching { release() }
        track = null
    }

    fun playFloatMono(samples: FloatArray, sampleRate: Int) {
        stop()

        val pcm = floatToPcm16Le(samples)

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(max(minBuf, pcm.size))
            .build()

        t.write(pcm, 0, pcm.size)
        t.play()

        track = t
    }

    private fun floatToPcm16Le(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var o = 0
        for (s in samples) {
            val clamped = min(1.0f, max(-1.0f, s))
            val v = (clamped * 32767.0f).toInt().toShort().toInt()
            out[o] = (v and 0xFF).toByte()
            out[o + 1] = ((v ushr 8) and 0xFF).toByte()
            o += 2
        }
        return out
    }
}


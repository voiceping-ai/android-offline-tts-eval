package com.voiceping.offlinettseval.engines

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.voiceping.offlinettseval.catalog.TtsModel
import com.voiceping.offlinettseval.engine.LoadOptions
import com.voiceping.offlinettseval.engine.LoadResult
import com.voiceping.offlinettseval.engine.ReadyState
import com.voiceping.offlinettseval.engine.SynthesisRequest
import com.voiceping.offlinettseval.engine.SynthesisResult
import com.voiceping.offlinettseval.engine.TtsEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

class AndroidSystemTtsEngine(
    private val context: Context,
) : TtsEngine {
    override val engineId: String = "android_system_tts"

    private var tts: TextToSpeech? = null

    override suspend fun ensureReady(model: TtsModel): ReadyState {
        return ReadyState.Ready
    }

    override suspend fun load(model: TtsModel, options: LoadOptions): LoadResult {
        val started = SystemClock.elapsedRealtime()
        initIfNeeded()
        val loadTimeMs = SystemClock.elapsedRealtime() - started
        return LoadResult(sampleRate = 0, numSpeakers = 1, loadTimeMs = loadTimeMs)
    }

    override suspend fun synthesize(request: SynthesisRequest): SynthesisResult = withContext(Dispatchers.IO) {
        initIfNeeded()
        val engine = requireNotNull(tts)

        val tmp = File(context.cacheDir, "system_tts_${System.currentTimeMillis()}.wav")
        tmp.parentFile?.mkdirs()

        val utteranceId = "utt_${System.currentTimeMillis()}"
        val done = CompletableDeferred<Unit>()
        val errRef = AtomicReference<String?>(null)

        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (!done.isCompleted) done.complete(Unit)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                errRef.compareAndSet(null, "TextToSpeech error")
                if (!done.isCompleted) done.complete(Unit)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                errRef.compareAndSet(null, "TextToSpeech errorCode=$errorCode")
                if (!done.isCompleted) done.complete(Unit)
            }
        }

        engine.setOnUtteranceProgressListener(listener)
        engine.setSpeechRate(request.speed.coerceIn(0.5f, 2.0f))

        val started = SystemClock.elapsedRealtime()
        val rc = engine.synthesizeToFile(request.text, null, tmp, utteranceId)
        if (rc != TextToSpeech.SUCCESS) {
            throw IllegalStateException("synthesizeToFile failed: rc=$rc")
        }

        done.await()
        val synthesisTimeMs = SystemClock.elapsedRealtime() - started

        val err = errRef.get()
        if (err != null) {
            tmp.delete()
            throw IllegalStateException(err)
        }

        val wav = readWav16Mono(tmp)
        tmp.delete()

        val audioDurationSec = if (wav.sampleRate > 0) wav.samples.size.toDouble() / wav.sampleRate.toDouble() else 0.0
        return@withContext SynthesisResult(
            samples = wav.samples,
            sampleRate = wav.sampleRate,
            synthesisTimeMs = synthesisTimeMs,
            audioDurationSec = audioDurationSec
        )
    }

    override fun release() {
        tts?.shutdown()
        tts = null
    }

    private suspend fun initIfNeeded() {
        if (tts != null) return
        withContext(Dispatchers.Main) {
            val ready = CompletableDeferred<Unit>()
            val engine = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) ready.complete(Unit)
                else ready.completeExceptionally(IllegalStateException("TextToSpeech init failed: status=$status"))
            }
            tts = engine
            ready.await()
        }
    }

    private data class WavData(val sampleRate: Int, val samples: FloatArray)

    private fun readWav16Mono(file: File): WavData {
        FileInputStream(file).use { input ->
            fun readExact(n: Int): ByteArray {
                val buf = ByteArray(n)
                var off = 0
                while (off < n) {
                    val r = input.read(buf, off, n - off)
                    if (r <= 0) throw IllegalStateException("Unexpected EOF while reading WAV")
                    off += r
                }
                return buf
            }

            fun skipExact(n: Long) {
                var left = n
                while (left > 0L) {
                    val skipped = input.skip(left)
                    if (skipped <= 0L) throw IllegalStateException("Unexpected EOF while skipping WAV chunk")
                    left -= skipped
                }
            }

            val riff = String(readExact(4), Charsets.US_ASCII)
            readExact(4) // file size
            val wave = String(readExact(4), Charsets.US_ASCII)
            if (riff != "RIFF" || wave != "WAVE") throw IllegalStateException("Not a WAV file")

            var audioFormat = -1
            var numChannels = -1
            var sampleRate = -1
            var bitsPerSample = -1
            var pcm: ByteArray? = null

            while (true) {
                val chunkHeader = ByteArray(8)
                val first = input.read()
                if (first == -1) break
                chunkHeader[0] = first.toByte()
                val rest = readExact(7)
                System.arraycopy(rest, 0, chunkHeader, 1, 7)

                val tag = String(chunkHeader.copyOfRange(0, 4), Charsets.US_ASCII)
                val bb = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN)
                val size = bb.int
                if (size < 0) throw IllegalStateException("Invalid WAV chunk size")

                when (tag) {
                    "fmt " -> {
                        val fmt = readExact(size)
                        val f = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        audioFormat = (f.short.toInt() and 0xFFFF)
                        numChannels = (f.short.toInt() and 0xFFFF)
                        sampleRate = f.int
                        f.int // byte rate
                        f.short // block align
                        bitsPerSample = (f.short.toInt() and 0xFFFF)
                    }
                    "data" -> {
                        pcm = readExact(size)
                        break
                    }
                    else -> skipExact(size.toLong())
                }

                // Chunks are padded to an even boundary.
                if (size % 2 == 1) {
                    skipExact(1)
                }
            }

            if (audioFormat != 1) throw IllegalStateException("Unsupported WAV format=$audioFormat (expected PCM)")
            if (numChannels != 1) throw IllegalStateException("Unsupported channels=$numChannels (expected mono)")
            if (bitsPerSample != 16) throw IllegalStateException("Unsupported bits=$bitsPerSample (expected 16)")
            if (sampleRate <= 0) throw IllegalStateException("Invalid sampleRate=$sampleRate")

            val data = requireNotNull(pcm) { "Missing WAV data chunk" }
            val out = FloatArray(data.size / 2)
            var i = 0
            var o = 0
            while (i + 1 < data.size) {
                val lo = data[i].toInt() and 0xFF
                val hi = data[i + 1].toInt()
                val v = ((hi shl 8) or lo).toShort().toInt()
                out[o] = (v / 32768.0f).coerceIn(-1.0f, 1.0f)
                i += 2
                o += 1
            }

            return WavData(sampleRate = sampleRate, samples = out)
        }
    }
}

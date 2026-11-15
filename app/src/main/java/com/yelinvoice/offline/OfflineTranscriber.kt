package com.yelinvoice.offline

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor

internal object WhisperNative {
    init { System.loadLibrary("yelinvoice_whisper") }
    external fun createContext(modelPath: String): Long
    external fun freeContext(handle: Long)
    external fun transcribe(handle: Long, audio: FloatArray, language: String, threads: Int): String
}

internal class OfflineWhisperModelRepository(private val context: Context) {
    private val model = File(File(context.filesDir, "models"), MODEL_NAME)

    fun installed(): Boolean = model.isFile && model.length() > 1_000_000L

    fun ensureDownloaded(onProgress: (Long, Long) -> Unit): File {
        if (installed()) return model
        model.parentFile?.mkdirs()
        val temporary = File(model.parentFile, "$MODEL_NAME.part")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Yelinvoice-Offline-Android")
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) { "Whisper model таталт амжилтгүй: HTTP ${connection.responseCode}" }
            val expectedSize = connection.contentLengthLong
            var received = 0L
            val digest = MessageDigest.getInstance("SHA-1")
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        received += count
                        onProgress(received, expectedSize)
                    }
                    output.fd.sync()
                }
            }
            check(expectedSize <= 0L || received == expectedSize) { "Whisper model дутуу татагдсан байна." }
            val sha1 = digest.digest().joinToString("") { "%02x".format(it) }
            check(sha1.equals(MODEL_SHA1, ignoreCase = true)) { "Whisper model checksum таарахгүй байна. Дахин татаж үзнэ үү." }
            check(temporary.renameTo(model)) { "Whisper model файлыг хадгалж чадсангүй." }
            return model
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        // whisper.cpp's official multilingual tiny model: approximately 75 MiB.
        private const val MODEL_NAME = "ggml-tiny.bin"
        private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
        private const val MODEL_SHA1 = "bd577a113a864445d4c299885e0cb97d4ba92b5f"
    }
}

internal object AndroidAudioDecoder {
    private const val TARGET_RATE = 16_000
    private const val MAX_AUDIO_SECONDS = 30 * 60

    /** Decodes an audio URI through Android MediaCodec, mixes to mono and resamples for Whisper. */
    fun decodeMono16k(context: Context, uri: Uri, onProgress: (Int) -> Unit): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val source = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Сонгосон аудио файлыг нээж чадсангүй.")
        try {
            extractor.setDataSource(source.fileDescriptor)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) { track = i; format = candidate; break }
            }
            require(track >= 0) { "Файлаас дэмжигдэх audio track олдсонгүй." }
            val audioFormat = format ?: throw IllegalArgumentException("Файлаас дэмжигдэх audio track олдсонгүй.")
            extractor.selectTrack(track)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val sourceRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            require(sourceRate in 8_000..192_000) { "Audio sample rate дэмжигдэхгүй байна: $sourceRate Hz" }
            val estimatedDuration = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                (audioFormat.getLong(MediaFormat.KEY_DURATION) / 1_000_000L).coerceIn(0, MAX_AUDIO_SECONDS.toLong())
            } else 0L
            val resampler = StreamingResampler(sourceRate, TARGET_RATE,
                (TARGET_RATE * minOf(estimatedDuration, 60L)).coerceAtLeast(TARGET_RATE.toLong()).toInt())
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sourceFrames = 0L

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val decodedFormat = codec.outputFormat
                        if (decodedFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            val actualRate = decodedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            require(actualRate == sourceRate) { "Audio-ийн sample rate өөрчлөгдсөн; энэ файлыг боловсруулах боломжгүй." }
                        }
                    }
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            val encoding = if (Build.VERSION.SDK_INT >= 24 && codec.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else android.media.AudioFormat.ENCODING_PCM_16BIT
                            val bytesPerSample = if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val frames = info.size / (bytesPerSample * sourceChannels)
                            repeat(frames) {
                                var sum = 0.0
                                repeat(sourceChannels) {
                                    sum += if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                                        buffer.float.toDouble()
                                    } else {
                                        buffer.short.toDouble() / 32768.0
                                    }
                                }
                                resampler.add((sum / sourceChannels).toFloat())
                            }
                            sourceFrames += frames
                            require(sourceFrames <= sourceRate.toLong() * MAX_AUDIO_SECONDS) {
                                "Audio 30 минутээс урт байна. Богино хэсгээр хувааж сонгоно уу."
                            }
                            if (estimatedDuration > 0L) {
                                onProgress((sourceFrames * 100L / (sourceRate.toLong() * estimatedDuration)).toInt().coerceIn(1, 99))
                            }
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            onProgress(100)
            return resampler.toArray()
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            codec?.release()
            extractor.release()
            source.close()
        }
    }

    private class FloatAccumulator(initialCapacity: Int) {
        private var values = FloatArray(initialCapacity.coerceAtLeast(1))
        private var size = 0
        fun add(value: Float) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }
        fun toArray(): FloatArray = values.copyOf(size)
    }

    /** Linear streaming resampler: keeps only the final 16 kHz mono samples in memory. */
    private class StreamingResampler(fromRate: Int, toRate: Int, initialCapacity: Int) {
        private val output = FloatAccumulator(initialCapacity)
        private val sourceStep = fromRate.toDouble() / toRate
        private var nextOutputPosition = 0.0
        private var inputIndex = -1L
        private var previous = 0f

        fun add(value: Float) {
            inputIndex++
            if (inputIndex == 0L) {
                output.add(value)
                nextOutputPosition += sourceStep
            } else {
                while (nextOutputPosition <= inputIndex.toDouble()) {
                    val fraction = (nextOutputPosition - (inputIndex - 1L)).toFloat().coerceIn(0f, 1f)
                    output.add(previous * (1f - fraction) + value * fraction)
                    nextOutputPosition += sourceStep
                }
            }
            previous = value
        }

        fun toArray(): FloatArray {
            val samples = output.toArray()
            require(samples.isNotEmpty()) { "Audio track хоосон байна." }
            return samples
        }
    }
}

internal class OfflineWhisperTranscriber(modelFile: File) : AutoCloseable {
    private var context = WhisperNative.createContext(modelFile.absolutePath)
    init { check(context != 0L) { "Whisper model-ийг нээж чадсангүй." } }

    fun transcribe(audio16k: FloatArray, languageCode: String): String {
        check(context != 0L) { "Whisper engine ажиллахгүй байна." }
        require(languageCode == "my" || languageCode == "zh") { "Дэмжигдэх хэлээ сонгоно уу." }
        require(audio16k.isNotEmpty()) { "Audio хоосон байна." }
        return WhisperNative.transcribe(context, audio16k, languageCode,
            Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
    }

    override fun close() {
        if (context != 0L) {
            WhisperNative.freeContext(context)
            context = 0L
        }
    }
}

package com.yelinvoice.offline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.chaquo.python.Python
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Runs Nara Mini's released encoder/decoder locally on Android. */
class OfflineTtsEngine(private val model: VoiceModelRepository.ModelFiles) : AutoCloseable {
    private data class SpeechPiece(val text: String, val pauseAfterMs: Int)

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setInterOpNumThreads(1)
    }
    private val encoder = env.createSession(model.encoder.absolutePath, options)
    private val decoder = env.createSession(model.decoder.absolutePath, options)

    fun synthesize(text: String, outputFile: File, onProgress: (Int) -> Unit = {}) {
        require(text.isNotBlank()) { "ဖတ်ရန် မြန်မာစာသား ထည့်ပါ။" }
        val metadata = org.json.JSONObject(model.metadata.readText(Charsets.UTF_8))
        val vocabJson = metadata.getJSONObject("vocab")
        val styles = metadata.getJSONArray("styles")
        val centers = metadata.getJSONArray("centers")
        val bridge = Python.getInstance().getModule("nara_bridge")
        val pieces = splitText(normalizePunctuation(text))
        var dataBytes = 0L
        outputFile.parentFile?.mkdirs()
        onProgress(1)

        BufferedOutputStream(FileOutputStream(outputFile), 128 * 1024).use { output ->
            output.write(wavHeaderBytes(0L))
            for ((pieceIndex, piece) in pieces.withIndex()) {
            val phonemes = bridge.callAttr("phonemize_text", piece.text).toString()
            val ids = ArrayList<Long>()
            phonemes.codePoints().forEach { cp ->
                val symbol = String(Character.toChars(cp))
                if (vocabJson.has(symbol)) ids.add(vocabJson.getInt(symbol).toLong())
            }
            require(ids.isNotEmpty()) { "စာသားမှ model အတွက် အသံထွက်အမှတ်အသား မရပါ။" }
            require(ids.size <= 508) { "စာသားအပိုင်းရှည်လွန်းပါသည်။ ပိုတိုသော စာကြောင်းများအဖြစ် ခွဲထည့်ပါ။" }

            val tokenCount = ids.size
            val padded = LongArray(tokenCount + 2)
            for (i in ids.indices) padded[i + 1] = ids[i]
            val styleIndex = nearestCenterIndex(centers, tokenCount)
            val styleArray = styles.getJSONArray(styleIndex)
            val style = FloatArray(styleArray.length()) { styleArray.getDouble(it).toFloat() }
            require(style.size == 256) { "အသံ metadata ရဲ့ style vector အရွယ်အစား မမှန်ပါ။" }

            val tokenTensor = longTensor(padded, longArrayOf(1, padded.size.toLong()))
            val styleTensor = floatTensor(style, longArrayOf(1, style.size.toLong()))
            val encoded = encoder.run(mapOf("tokens" to tokenTensor, "style" to styleTensor)).use { result ->
                val durationTensor = result.get("durations").get() as OnnxTensor
                val prosodyTensor = result.get("prosody").get() as OnnxTensor
                val textTensor = result.get("text").get() as OnnxTensor
                val durationBuffer = durationTensor.longBuffer.duplicate().apply { position(0) }
                val durationCount = padded.size
                require(durationBuffer.remaining() == durationCount) { "Encoder မှ ထွက်လာသော ကြာချိန်အချက်အလက် အရွယ်အစား မမှန်ပါ။" }
                val durations = LongArray(durationCount) { durationBuffer.get() }
                val pBuffer = prosodyTensor.floatBuffer.duplicate().apply { position(0) }
                val prosody = FloatArray(pBuffer.remaining()) { pBuffer.get() }
                val tBuffer = textTensor.floatBuffer.duplicate().apply { position(0) }
                val encodedText = FloatArray(tBuffer.remaining()) { tBuffer.get() }
                Triple(durations, prosody, encodedText)
            }
            tokenTensor.close()
            styleTensor.close()

            val durations = encoded.first
            val prosody = encoded.second
            val encodedText = encoded.third
            val encoderTokenLength = durations.size
            require(encoderTokenLength == padded.size) { "Encoder က ထုတ်ပေးတဲ့ အရွယ်အစားက model နဲ့ မကိုက်ညီပါ။" }
            val frameCountLong = durations.sum()
            require(durations.all { it >= 1L } && frameCountLong in 1L..20_000L) {
                "Model က ခန့်မှန်းထားတဲ့ အသံကြာချိန် မမှန်ပါ။ စာကြောင်းကို တိုအောင်လုပ်ပြီး ထပ်စမ်းပါ။"
            }
            val frameCount = frameCountLong.toInt()
            require(prosody.size == 640 * encoderTokenLength && encodedText.size == 512 * encoderTokenLength) {
                "Encoder ရဲ့ အချက်အလက်အရွယ်အစား မကိုက်ညီပါ။"
            }

            val repeatedProsody = repeatFrames(prosody, 640, encoderTokenLength, durations, frameCount)
            val repeatedText = repeatFrames(encodedText, 512, encoderTokenLength, durations, frameCount)
            val pInput = floatTensor(repeatedProsody, longArrayOf(1, 640, frameCount.toLong()))
            val tInput = floatTensor(repeatedText, longArrayOf(1, 512, frameCount.toLong()))
            val sInput = floatTensor(style, longArrayOf(1, 256))
            val samples = decoder.run(mapOf("prosody" to pInput, "text" to tInput, "style" to sInput)).use { result ->
                val audioTensor = result.get("audio").get() as OnnxTensor
                val audioBuffer = audioTensor.floatBuffer.duplicate().apply { position(0) }
                FloatArray(audioBuffer.remaining()) { audioBuffer.get() }
            }
            pInput.close(); tInput.close(); sInput.close()
            require(samples.size >= 2_400 && samples.all { it.isFinite() }) { "Model က အသံဖိုင်ကို မှန်ကန်စွာ မဖန်တီးနိုင်ပါ။" }
                if (pieceIndex > 0) {
                    val pauseFrames = pieces[pieceIndex - 1].pauseAfterMs * 24
                    repeat(pauseFrames) { output.write(0); output.write(0) }
                    dataBytes += pauseFrames * 2L
                }
                dataBytes += writePcmSamples(output, samples)
                require(dataBytes <= 0xFFFFFFFFL - 36L) { "ဖန်တီးမည့်အသံဖိုင်သည် WAV ဖိုင်အရွယ်အစား အများဆုံးကန့်သတ်ချက်ထက် ကြီးနေပါသည်။ စာသားကို အပိုင်းအလိုက် ဖန်တီးပါ။" }
                val percent = (((pieceIndex + 1) * 100.0) / pieces.size).toInt().coerceIn(1, 99)
                onProgress(percent)
            }
        }
        RandomAccessFile(outputFile, "rw").use { file ->
            file.seek(0)
            file.write(wavHeaderBytes(dataBytes))
        }
        onProgress(100)
    }

    private fun nearestCenterIndex(centers: org.json.JSONArray, tokenCount: Int): Int {
        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE
        for (i in 0 until centers.length()) {
            val distance = abs(centers.getDouble(i) - tokenCount)
            if (distance < bestDistance) { bestDistance = distance; bestIndex = i }
        }
        return bestIndex
    }

    private fun floatTensor(values: FloatArray, shape: LongArray): OnnxTensor {
        val buffer = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(values).rewind()
        return OnnxTensor.createTensor(env, buffer, shape)
    }

    private fun longTensor(values: LongArray, shape: LongArray): OnnxTensor {
        val buffer = ByteBuffer.allocateDirect(values.size * 8).order(ByteOrder.nativeOrder()).asLongBuffer()
        buffer.put(values).rewind()
        return OnnxTensor.createTensor(env, buffer, shape)
    }

    private fun repeatFrames(source: FloatArray, channels: Int, sourceFrames: Int, durations: LongArray, outputFrames: Int): FloatArray {
        val output = FloatArray(channels * outputFrames)
        for (channel in 0 until channels) {
            var targetFrame = 0
            for (frame in 0 until sourceFrames) {
                val count = durations[frame].toInt()
                val value = source[channel * sourceFrames + frame]
                repeat(count) {
                    output[channel * outputFrames + targetFrame] = value
                    targetFrame++
                }
            }
        }
        return output
    }

    /** Keep words inside brackets, while converting unsupported punctuation into safe pauses. */
    private fun normalizePunctuation(input: String): String {
        var normalized = input
        listOf('(', ')', '[', ']', '{', '}', '（', '）', '【', '】', '「', '」', '『', '』', '《', '》').forEach {
            normalized = normalized.replace(it, ' ')
        }
        normalized = normalized
            .replace("…", "။")
            .replace(Regex("\\.{2,}"), "။")
            .replace(Regex("[-‐‑‒–—―−]+"), "၊")
            .replace(Regex("[;:：；]+"), "၊")
            .replace(Regex("\\s+"), " ")
            .trim()
        listOf('"', '“', '”', '‘', '’', '`').forEach { normalized = normalized.replace(it.toString(), "") }
        require(normalized.isNotEmpty()) { "ဖတ်နိုင်သော စာသား မရှိပါ။" }
        return normalized
    }

    private fun splitText(input: String, limit: Int = 200): List<SpeechPiece> {
        val sentences = input.split(Regex("(?<=။)\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
        val chunksBySentence = sentences.map { sentence ->
            val chunks = ArrayList<String>()
            var remaining = sentence
            while (remaining.length > limit) {
                var cut = remaining.lastIndexOf(' ', limit)
                if (cut <= 0) cut = limit
                chunks.add(remaining.substring(0, cut).trim())
                remaining = remaining.substring(cut).trim()
            }
            if (remaining.isNotEmpty()) chunks.add(remaining)
            chunks
        }
        val pieces = ArrayList<SpeechPiece>()
        for ((sentenceIndex, chunks) in chunksBySentence.withIndex()) {
            for ((chunkIndex, chunk) in chunks.withIndex()) {
                val nextChunkInSentence = chunkIndex < chunks.lastIndex
                val hasNextSentence = sentenceIndex < chunksBySentence.lastIndex
                val pauseAfterMs = when {
                    nextChunkInSentence -> 200
                    hasNextSentence && sentences[sentenceIndex].endsWith('။') -> 500
                    hasNextSentence -> 200
                    else -> 0
                }
                pieces.add(SpeechPiece(chunk, pauseAfterMs))
            }
        }
        require(pieces.isNotEmpty()) { "ဖတ်ရန် မြန်မာစာသား ထည့်ပါ။" }
        return pieces
    }

    private fun writePcmSamples(output: OutputStream, samples: FloatArray): Long {
        val buffer = ByteArray(64 * 1024)
        var used = 0
        for (sample in samples) {
            val clipped = sample.coerceIn(-1.0f, 1.0f)
            val pcm = (clipped * if (clipped < 0) 32768.0f else 32767.0f).toInt()
            buffer[used++] = (pcm and 0xff).toByte()
            buffer[used++] = ((pcm shr 8) and 0xff).toByte()
            if (used == buffer.size) {
                output.write(buffer)
                used = 0
            }
        }
        if (used > 0) output.write(buffer, 0, used)
        return samples.size * 2L
    }

    private fun wavHeaderBytes(dataBytes: Long): ByteArray {
        val output = ByteArrayOutputStream(44)
        fun writeAscii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
        fun writeLeInt(value: Long) {
            output.write((value and 0xff).toInt())
            output.write(((value shr 8) and 0xff).toInt())
            output.write(((value shr 16) and 0xff).toInt())
            output.write(((value shr 24) and 0xff).toInt())
        }
        fun writeLeShort(value: Int) { output.write(value and 0xff); output.write((value shr 8) and 0xff) }
        writeAscii("RIFF"); writeLeInt(36L + dataBytes); writeAscii("WAVE")
        writeAscii("fmt "); writeLeInt(16); writeLeShort(1); writeLeShort(1)
        writeLeInt(24_000); writeLeInt(48_000); writeLeShort(2); writeLeShort(16)
        writeAscii("data"); writeLeInt(dataBytes)
        return output.toByteArray()
    }

    override fun close() {
        encoder.close()
        decoder.close()
        options.close()
    }
}

package com.yelinvoice.offline

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.roundToInt

/** A transcript line and its requested placement on the final audio timeline. */
data class TimedSpeechSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val lineNumber: Int
) {
    val slotMs: Long get() = endMs - startMs
}

/** Parses [MM:SS - MM:SS] or [HH:MM:SS - HH:MM:SS] transcript lines. */
object TimestampTranscriptParser {
    private const val SAMPLE_RATE = 24_000L
    private val linePattern = Regex("^\\s*\\[?\\s*((?:\\d+:)?\\d+:\\d+(?:\\.\\d+)?)\\s*-\\s*((?:\\d+:)?\\d+(?:\\.\\d+)?)\\s*\\]?\\s*(.*?)\\s*$")

    fun parse(input: String): List<TimedSpeechSegment> {
        val segments = ArrayList<TimedSpeechSegment>()
        val trimmedInput = input.trim()
        val transcriptBody = if (trimmedInput.startsWith("(") && trimmedInput.endsWith(")") && trimmedInput.contains("[")) {
            trimmedInput.substring(1, trimmedInput.length - 1).trim()
        } else trimmedInput
        transcriptBody.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val match = linePattern.matchEntire(line)
                ?: throw IllegalArgumentException("စာကြောင်း ${index + 1}: [00:00 - 00:03] စာသား ပုံစံဖြင့်ရေးပါ။")
            val start = parseTime(match.groupValues[1], index + 1)
            val end = parseTime(match.groupValues[2], index + 1)
            val text = match.groupValues[3].trim()
            require(end > start) { "စာကြောင်း ${index + 1}: အဆုံးအချိန်သည် စတင်ချိန်ထက် နောက်ကျရပါမည်။" }
            require(text.isNotEmpty()) { "စာကြောင်း ${index + 1}: ဖတ်ရန် စာသား မရှိပါ။" }
            segments.add(TimedSpeechSegment(start, end, text, index + 1))
        }
        require(segments.isNotEmpty()) { "Timestamp နဲ့ရေးထားသော စာကြောင်း မတွေ့ပါ။" }
        return segments
    }

    private fun parseTime(value: String, lineNumber: Int): Long {
        val parts = value.trim().split(':')
        require(parts.size == 2 || parts.size == 3) {
            "စာကြောင်း $lineNumber: အချိန်ကို MM:SS သို့မဟုတ် HH:MM:SS ပုံစံဖြင့်ရေးပါ။"
        }
        val hours: Long
        val minutes: Long
        val seconds: Double
        try {
            if (parts.size == 2) {
                hours = 0L
                minutes = parts[0].toLong()
                seconds = parts[1].toDouble()
            } else {
                hours = parts[0].toLong()
                minutes = parts[1].toLong()
                seconds = parts[2].toDouble()
            }
        } catch (error: NumberFormatException) {
            throw IllegalArgumentException("စာကြောင်း $lineNumber: အချိန်တန်ဖိုး မမှန်ပါ။")
        }
        require(hours >= 0 && minutes in 0..59 && seconds >= 0.0 && seconds < 60.0) {
            "စာကြောင်း $lineNumber: အချိန်တန်ဖိုး မမှန်ပါ။"
        }
        val totalSeconds = hours * 3600.0 + minutes * 60.0 + seconds
        require(totalSeconds <= (0xFFFFFFFFL - 36L) / (SAMPLE_RATE * 2L)) {
            "အသံဖိုင်သည် WAV format ၏ အများဆုံးကြာချိန်ထက် ကျော်နေပါသည်။"
        }
        return (totalSeconds * 1000.0).roundToInt().toLong()
    }
}

/**
 * Renders timestamped speech onto a silent 24 kHz mono WAV timeline.
 * Each utterance is fitted to its complete requested slot with WSOLA while
 * preserving pitch. Longer speech is accelerated and shorter speech is slowed;
 * words are never cut off and the rendered segment ends exactly at endMs.
 */
object TimestampDubRenderer {
    private const val SAMPLE_RATE = 24_000
    private const val WAV_HEADER_SIZE = 44

    fun render(
        transcript: String,
        engine: OfflineTtsEngine,
        outputFile: File,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ) {
        val segments = TimestampTranscriptParser.parse(transcript)
        val totalMs = segments.maxOf { it.endMs }
        val totalFrames = (totalMs * SAMPLE_RATE / 1000.0).roundToInt().toLong()
        require(totalFrames * 2L <= 0xFFFFFFFFL - 36L) { "Timestamp အသံဖိုင်သည် WAV အရွယ်အစား ကန့်သတ်ချက်ထက် ကျော်နေပါသည်။" }
        outputFile.parentFile?.mkdirs()
        onProgress(1, "Timestamp အသံဖိုင် ပြင်ဆင်နေသည်… 1%")

        RandomAccessFile(outputFile, "rw").use { timeline ->
            timeline.setLength(0L)
            timeline.write(wavHeader(0L))
            timeline.setLength(WAV_HEADER_SIZE + totalFrames * 2L) // sparse/digital silence

            segments.forEachIndexed { index, segment ->
                val percentBefore = (index * 100 / segments.size).coerceIn(1, 98)
                onProgress(percentBefore, "စာကြောင်း ${index + 1}/${segments.size} ကို အသံထုတ်နေသည်… $percentBefore%")
                val chunkFile = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}-part-$index.wav")
                try {
                    engine.synthesize(segment.text, chunkFile)
                    var samples = readPcm16Wav(chunkFile)
                    val slotFrames = (segment.slotMs * SAMPLE_RATE / 1000.0).roundToInt().coerceAtLeast(1)
                    val targetFrames = slotFrames
                    if (samples.size != targetFrames) {
                        val originalMs = samples.size * 1000.0 / SAMPLE_RATE
                        val speed = samples.size.toDouble() / targetFrames
                        samples = WsolaTimeStretch.fit(samples, targetFrames)
                        val shownSpeed = String.format(java.util.Locale.US, "%.2f×", speed)
                        val paceText = if (speed > 1.0) "အရှိန်မြှင့်" else "အရှိန်လျှော့"
                        onProgress(percentBefore, "စာကြောင်း ${index + 1}: ${originalMs.toInt()} ms ကို $shownSpeed ${paceText} ပြီး အချိန်ကွက်အပြည့် ညှိနေသည်…")
                    }
                    check(samples.size == slotFrames) { "စာကြောင်း ${segment.lineNumber} ၏ အသံကြာချိန်ကို သတ်မှတ်ထားသော အချိန်ကွက်နှင့် ကိုက်ညီအောင် မညှိနိုင်ပါ။" }
                    val startFrame = (segment.startMs * SAMPLE_RATE / 1000.0).roundToInt().toLong()
                    mixPcm16(timeline, startFrame, samples)
                } finally {
                    chunkFile.delete()
                }
                val completed = (((index + 1) * 100.0) / segments.size).toInt().coerceIn(1, 99)
                onProgress(completed, "Timestamp အသံ ${index + 1}/${segments.size} ပြီးပါပြီ… $completed%")
            }
            timeline.seek(0L)
            timeline.write(wavHeader(totalFrames * 2L))
            timeline.fd.sync()
        }
        onProgress(100, "Timestamp အသံဖိုင် ပြီးပါပြီ။ 100%")
    }

    private fun readPcm16Wav(file: File): ShortArray {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= WAV_HEADER_SIZE) { "TTS မှထွက်လာသော WAV ဖိုင် မမှန်ပါ။" }
            input.seek(0L)
            val header = ByteArray(WAV_HEADER_SIZE)
            input.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE" &&
                String(header, 36, 4, Charsets.US_ASCII) == "data") {
                "TTS မှထွက်လာသော WAV ဖိုင်ဖွဲ့စည်းပုံ မမှန်ပါ။"
            }
            val channels = littleEndianShort(header, 22)
            val sampleRate = littleEndianInt(header, 24)
            val bits = littleEndianShort(header, 34)
            require(channels == 1 && sampleRate == SAMPLE_RATE && bits == 16) {
                "TTS အသံသည် 24 kHz mono PCM16 မဟုတ်ပါ။"
            }
            val bytes = input.length() - WAV_HEADER_SIZE
            require(bytes <= Int.MAX_VALUE.toLong() * 2 && bytes % 2L == 0L) { "TTS WAV ဖိုင်ကြီးလွန်းပါသည်။" }
            val result = ShortArray((bytes / 2L).toInt())
            val buffer = ByteArray(64 * 1024)
            var offset = 0
            while (offset < bytes) {
                val count = minOf(buffer.size.toLong(), bytes - offset).toInt()
                input.readFully(buffer, 0, count)
                var i = 0
                while (i < count) {
                    result[(offset / 2L).toInt() + i / 2] =
                        ((buffer[i].toInt() and 0xff) or (buffer[i + 1].toInt() shl 8)).toShort()
                    i += 2
                }
                offset += count
            }
            return result
        }
    }

    /** Mixes one PCM chunk at its frame offset; overlapping lines are summed safely. */
    private fun mixPcm16(file: RandomAccessFile, startFrame: Long, speech: ShortArray) {
        if (speech.isEmpty()) return
        val byteCount = speech.size * 2
        val startByte = WAV_HEADER_SIZE + startFrame * 2L
        require(startByte + byteCount <= file.length()) { "Timestamp အသံသည် နောက်ဆုံးအချိန်ကို ကျော်သွားပါသည်။" }
        val existingBytes = ByteArray(byteCount)
        file.seek(startByte)
        file.readFully(existingBytes)
        val mixedBytes = ByteArray(byteCount)
        for (i in speech.indices) {
            val oldSample = ((existingBytes[i * 2].toInt() and 0xff) or
                (existingBytes[i * 2 + 1].toInt() shl 8)).toShort().toInt()
            val mixed = (oldSample + speech[i].toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            mixedBytes[i * 2] = (mixed and 0xff).toByte()
            mixedBytes[i * 2 + 1] = ((mixed shr 8) and 0xff).toByte()
        }
        file.seek(startByte)
        file.write(mixedBytes)
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            (bytes[offset + 3].toInt() shl 24)

    private fun wavHeader(dataBytes: Long): ByteArray {
        val output = ByteArrayOutputStream(WAV_HEADER_SIZE)
        fun ascii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
        fun leInt(value: Long) {
            output.write((value and 0xff).toInt())
            output.write(((value shr 8) and 0xff).toInt())
            output.write(((value shr 16) and 0xff).toInt())
            output.write(((value shr 24) and 0xff).toInt())
        }
        fun leShort(value: Int) { output.write(value and 0xff); output.write((value shr 8) and 0xff) }
        ascii("RIFF"); leInt(36L + dataBytes); ascii("WAVE")
        ascii("fmt "); leInt(16); leShort(1); leShort(1)
        leInt(SAMPLE_RATE.toLong()); leInt((SAMPLE_RATE * 2).toLong()); leShort(2); leShort(16)
        ascii("data"); leInt(dataBytes)
        return output.toByteArray()
    }
}

/** WSOLA (Waveform Similarity Overlap-Add) pitch-preserving time compression. */
object WsolaTimeStretch {
    private const val SAMPLE_RATE = 24_000
    private const val WINDOW_MS = 20
    private const val SYNTHESIS_HOP_MS = 10
    private const val SEARCH_MS = 5

    fun fit(input: ShortArray, targetFrames: Int): ShortArray {
        require(input.isNotEmpty() && targetFrames > 0) { "အသံအချက်အလက် မရှိပါ။" }
        if (input.size == targetFrames) return input
        val speed = input.size.toDouble() / targetFrames
        val window = SAMPLE_RATE * WINDOW_MS / 1000
        val hop = SAMPLE_RATE * SYNTHESIS_HOP_MS / 1000
        val overlap = window - hop
        val search = SAMPLE_RATE * SEARCH_MS / 1000
        val output = ShortArray(targetFrames)
        val initial = minOf(window, input.size, targetFrames)
        input.copyInto(output, 0, 0, initial)

        var outputPosition = hop
        while (outputPosition < targetFrames) {
            val expected = (outputPosition * speed).roundToInt().coerceIn(0, maxOf(0, input.size - window))
            val minCandidate = maxOf(0, expected - search)
            val maxCandidate = minOf(input.size - window, expected + search)
            var best = minCandidate
            var bestScore = Double.NEGATIVE_INFINITY
            val comparisonCount = minOf(overlap, targetFrames - outputPosition)
            var candidate = minCandidate
            while (candidate <= maxCandidate) {
                var dot = 0.0
                var oldEnergy = 1.0
                var newEnergy = 1.0
                var i = 0
                while (i < comparisonCount) {
                    val old = output[outputPosition + i].toDouble()
                    val fresh = input[candidate + i].toDouble()
                    dot += old * fresh
                    oldEnergy += old * old
                    newEnergy += fresh * fresh
                    i += 4 // Subsample correlation keeps real-time cost modest.
                }
                val score = dot / kotlin.math.sqrt(oldEnergy * newEnergy)
                if (score > bestScore) { bestScore = score; best = candidate }
                candidate++
            }

            val blendCount = minOf(overlap, targetFrames - outputPosition)
            for (i in 0 until blendCount) {
                val weight = i.toDouble() / maxOf(1, blendCount)
                val old = output[outputPosition + i].toDouble()
                val fresh = input.getOrElse(best + i) { 0 }.toDouble()
                output[outputPosition + i] = (old * (1.0 - weight) + fresh * weight)
                    .roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            val copyStart = outputPosition + blendCount
            val sourceStart = best + blendCount
            val copyCount = minOf(window - blendCount, targetFrames - copyStart, input.size - sourceStart)
            if (copyCount > 0) input.copyInto(output, copyStart, sourceStart, sourceStart + copyCount)
            outputPosition += hop
        }
        return output
    }
}

package com.yelinvoice.offline

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads one voice at a time, verifies upstream SHA-256, and keeps it in app-private storage. */
class VoiceModelRepository(context: Context) {
    private val root = File(context.filesDir, "voice-models")

    data class Voice(val id: String, val label: String)

    val voices = listOf(
        Voice("shoon", "ရှုန်း (Shoon)"),
        Voice("thazin", "သဇင် (Thazin)"),
        Voice("ko-banyar", "ကိုဘညား (Ko Banyar)"),
        Voice("htet-myat", "ထက်မြတ် (Htet Myat)")
    )

    data class ModelFiles(val folder: File) {
        val encoder: File get() = File(folder, "encoder.onnx")
        val decoder: File get() = File(folder, "decoder.onnx")
        val metadata: File get() = File(folder, "voice.json")
    }

    fun installed(voice: String): Boolean {
        val files = filesFor(voice)
        val marker = File(files.folder, ".verified")
        return marker.isFile && marker.readText().trim() == expectedMarker(voice) &&
            files.encoder.length() > 0 && files.decoder.length() > 0 && files.metadata.length() > 0
    }

    fun filesFor(voice: String): ModelFiles {
        require(voice in hashes) { "မသိရှိသော အသံအမျိုးအစား: $voice" }
        return ModelFiles(File(root, voice))
    }

    fun ensureInstalled(voice: String, onProgress: (String) -> Unit = {}): ModelFiles {
        val files = filesFor(voice)
        files.folder.mkdirs()
        if (installed(voice)) {
            onProgress("${displayName(voice)} ကို ဖုန်းထဲမှာ သိမ်းထားပြီးပါပြီ။ အင်တာနက်မလိုဘဲ အသုံးပြုနိုင်ပါတယ်။")
            return files
        }

        val names = listOf("encoder.onnx", "decoder.onnx", "voice.json")
        for (name in names) {
            val destination = File(files.folder, name)
            val expected = hashes.getValue(voice).getValue(name)
            if (destination.isFile && sha256(destination) == expected) continue
            destination.delete()
            val partial = File(files.folder, "$name.part")
            partial.delete()
            onProgress("${displayName(voice)} — $name ဒေါင်းလုဒ်လုပ်နေပါသည်…")
            try {
                download("onnx/$voice/$name", partial)
                val actual = sha256(partial)
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw IllegalStateException("ဒေါင်းလုဒ်လုပ်ထားသော model ဖိုင်ရဲ့ SHA-256 စစ်ဆေးမှု မအောင်မြင်ပါ: $name")
                }
                if (!partial.renameTo(destination)) {
                    throw IllegalStateException("ဒေါင်းလုဒ်လုပ်ထားသောဖိုင်ကို သိမ်းဆည်းမရပါ: $name")
                }
            } finally {
                partial.delete()
            }
        }

        File(files.folder, ".verified").writeText(expectedMarker(voice))
        onProgress("${displayName(voice)} ဒေါင်းလုဒ်ပြီး စစ်ဆေးပြီးပါပြီ။ အင်တာနက်မလိုဘဲ အသုံးပြုနိုင်ပါတယ်။")
        return files
    }

    private fun download(path: String, destination: File) {
        val url = URL("$BASE_URL/$path?download=true")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "YelinvoiceOffline/0.1")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Model ဒေါင်းလုဒ်မရပါ (HTTP $status)။")
            BufferedInputStream(connection.inputStream, 128 * 1024).use { input ->
                FileOutputStream(destination).buffered(128 * 1024).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun displayName(voice: String) = voices.firstOrNull { it.id == voice }?.label ?: voice
    private fun expectedMarker(voice: String) = hashes.getValue(voice).entries.sortedBy { it.key }
        .joinToString("\n") { "${it.key}:${it.value}" }

    companion object {
        // Pin files to the model revision reviewed for this release.
        private const val BASE_URL = "https://huggingface.co/YeeNyunn/nara-mini-v1/resolve/5493dd9d99ec04a2f1678eb501dcea688a538ed9"
        private val hashes = mapOf(
            "shoon" to mapOf(
                "encoder.onnx" to "96d01ea2e861cfc38b0423b56f56896404a786382b2af1f4a8299464df6b06ae",
                "decoder.onnx" to "317c6248bbee3091152333e0d9976c3005bbf30e131552d4fb2585e879291814",
                "voice.json" to "e98214f5c1f9710bb4e0ae3e40e6baa3ef3a8667400c0f2bdae8980020717d1a"
            ),
            "thazin" to mapOf(
                "encoder.onnx" to "61f4e9c34624540d8e9ab8163f741b785bc2f17f7db8a98694aa87720d1fa7cd",
                "decoder.onnx" to "2fea887904a2e7098757e4a1a111b6c59ceae8f1ce76f78f8600cb0e66e58824",
                "voice.json" to "56aabb3af53b530e522155ea78edca6547f3eed35da47ff39522d18c4b3a83be"
            ),
            "ko-banyar" to mapOf(
                "encoder.onnx" to "272ea5129a268995d17e7c551380fa021300d275f6d1d8c4c4b267bf389f6c77",
                "decoder.onnx" to "021077f87009fd406c0b83645c94401f03c4f69cd767a5fb8197d3ba659d250c",
                "voice.json" to "4d08270607b233aef90d819ef37ed34411c28ac68ae5015343b81e506cb6a694"
            ),
            "htet-myat" to mapOf(
                "encoder.onnx" to "c9ddd2726904c91a56f3dc9bb43bbf3e75a6f472a820e4e61db4e25af617b5bd",
                "decoder.onnx" to "408df3eb84f516d27a2a09d416572ca58712412148c5fd2fd403df225c727f48",
                "voice.json" to "4f9d724b894d9ad17f055361b465addf4a2b6f20c0b63ae9592a6665015c5c2f"
            )
        )
    }
}

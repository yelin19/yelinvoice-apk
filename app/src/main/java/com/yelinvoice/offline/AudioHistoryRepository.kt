package com.yelinvoice.offline

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** A WAV file and the descriptive metadata stored alongside it. */
data class AudioRecord(
    val id: String,
    val text: String,
    val voiceId: String,
    val createdAt: Long,
    val file: File
)

/** Stores generated audio and metadata in this app's private files directory. */
class AudioHistoryRepository(context: Context) {
    private val historyDir = File(context.applicationContext.filesDir, "audio-history")

    /** Copies [source] into the history store and returns its record. */
    @Throws(IOException::class)
    fun add(source: File, text: String, voiceId: String): AudioRecord {
        require(source.isFile) { "Source must be an existing file" }

        if (!historyDir.exists() && !historyDir.mkdirs()) {
            throw IOException("Could not create audio history directory")
        }

        val createdAt = System.currentTimeMillis()
        val id = "$createdAt-${UUID.randomUUID().toString().replace("-", "")}"
        val entryDir = File(historyDir, id)
        if (!entryDir.mkdir()) {
            throw IOException("Could not create audio history entry")
        }

        try {
            val audioFile = File(entryDir, AUDIO_FILENAME)
            val audioPart = File(entryDir, "$AUDIO_FILENAME.part")
            FileInputStream(source).use { input ->
                FileOutputStream(audioPart).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (!audioPart.renameTo(audioFile)) {
                throw IOException("Could not finalize copied audio file")
            }

            val metadata = JSONObject()
                .put("id", id)
                .put("text", text)
                .put("voiceId", voiceId)
                .put("createdAt", createdAt)
            val metadataPart = File(entryDir, "$METADATA_FILENAME.part")
            FileOutputStream(metadataPart).use { output ->
                output.write(metadata.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!metadataPart.renameTo(File(entryDir, METADATA_FILENAME))) {
                throw IOException("Could not finalize audio metadata")
            }

            return AudioRecord(id, text, voiceId, createdAt, audioFile)
        } catch (failure: Exception) {
            deleteRecursively(entryDir)
            throw failure
        }
    }

    /** Returns valid records ordered from newest to oldest. */
    fun all(): List<AudioRecord> {
        val entries = historyDir.listFiles() ?: return emptyList()
        return entries.mapNotNull { entry ->
            if (!entry.isDirectory || !isSafeId(entry.name)) return@mapNotNull null
            try {
                val metadataFile = File(entry, METADATA_FILENAME)
                val audioFile = File(entry, AUDIO_FILENAME)
                if (!metadataFile.isFile || !audioFile.isFile) return@mapNotNull null

                val json = JSONObject(metadataFile.readText(Charsets.UTF_8))
                val id = json.getString("id")
                if (id != entry.name || !isSafeId(id)) return@mapNotNull null
                AudioRecord(
                    id = id,
                    text = json.getString("text"),
                    voiceId = json.getString("voiceId"),
                    createdAt = json.getLong("createdAt"),
                    file = audioFile
                )
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.createdAt }
    }

    /** Removes only the entry whose generated id exactly matches [id]. */
    fun delete(id: String): Boolean {
        if (!isSafeId(id)) return false
        val entryDir = File(historyDir, id)
        if (!entryDir.exists() || !entryDir.isDirectory) return false
        return deleteRecursively(entryDir)
    }

    private fun isSafeId(id: String): Boolean = SAFE_ID.matches(id)

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            val children = file.listFiles() ?: return false
            for (child in children) {
                if (!deleteRecursively(child)) return false
            }
        }
        return file.delete()
    }

    private companion object {
        const val AUDIO_FILENAME = "speech.wav"
        const val METADATA_FILENAME = "metadata.json"
        val SAFE_ID = Regex("^[0-9]{13}-[0-9a-f]{32}$")
    }
}

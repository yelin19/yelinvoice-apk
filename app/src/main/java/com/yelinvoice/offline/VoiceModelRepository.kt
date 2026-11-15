package com.yelinvoice.offline

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Catalog-backed downloader for public Hugging Face voice packs. */
class VoiceModelRepository(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "voice-models")
    private val catalogFile = File(appContext.filesDir, "voice-packs-imported.json")
    private val bundled = linkedMapOf<String, Pack>()
    private val imported = linkedMapOf<String, Pack>()

    data class Voice(val id: String, val label: String, val repository: String, val revision: String)
    data class ModelFiles(val folder: File) {
        val encoder: File get() = File(folder, "encoder.onnx")
        val decoder: File get() = File(folder, "decoder.onnx")
        val metadata: File get() = File(folder, "voice.json")
    }
    private data class SourceFile(val path: String, val sha256: String)
    private data class Pack(val voice: Voice, val files: Map<String, SourceFile>)

    init {
        try {
            val text = appContext.assets.open("voice_packs.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseCatalog(text, null, null).forEach { bundled[it.voice.id] = it }
        } catch (e: Exception) {
            throw IllegalStateException("ထည့်သွင်းပါရှိသော အသံစာရင်းကို ဖတ်မရပါ: ${e.message ?: "မသိသော အမှား"}", e)
        }
        if (catalogFile.isFile) try {
            val bytes = catalogFile.readBytes()
            if (bytes.size > MAX_CATALOG_BYTES) throw IllegalArgumentException("အသံစာရင်းဖိုင်သည် 512 KiB ထက်ကြီးနေပါသည်။")
            parseCatalog(String(bytes, Charsets.UTF_8), null, null).forEach { imported[it.voice.id] = it }
        } catch (_: Exception) {
            // A corrupt persisted catalog must not prevent bundled voices from remaining available.
            catalogFile.delete()
        }
    }

    val voices: List<Voice>
        get() = synchronized(this) { (bundled + imported).values.map { it.voice } }

    fun installed(id: String): Boolean {
        val pack = packFor(id)
        val files = filesFor(id)
        val marker = File(files.folder, ".verified")
        if (!marker.isFile || marker.readText(Charsets.UTF_8) != expectedMarker(pack)) return false
        return pack.files.keys.all { name ->
            val file = File(files.folder, name)
            file.isFile && file.length() > 0L
        }
    }

    fun filesFor(id: String): ModelFiles {
        requireValidId(id)
        if (packForOrNull(id) == null) throw IllegalArgumentException("မသိရှိသော အသံအမျိုးအစား: $id")
        return ModelFiles(File(root, id))
    }

    @Synchronized
    fun ensureInstalled(id: String, onProgress: (String) -> Unit = {}): ModelFiles {
        val pack = packFor(id)
        val files = filesFor(id)
        if (installed(id)) {
            onProgress("${pack.voice.label} ကို ဖုန်းထဲမှာ သိမ်းထားပြီးပါပြီ။ အင်တာနက်မလိုဘဲ အသုံးပြုနိုင်ပါတယ်။")
            return files
        }
        if (!files.folder.exists() && !files.folder.mkdirs()) throw IllegalStateException("အသံဖိုင်သိမ်းဆည်းရန် ဖိုင်တွဲဖန်တီးမရပါ။")
        val ordered = listOf("encoder.onnx", "decoder.onnx", "voice.json")
        try {
            for (name in ordered) {
                val source = pack.files.getValue(name)
                val destination = File(files.folder, name)
                if (destination.isFile && destination.length() > 0L && sha256(destination).equals(source.sha256, true)) continue
                destination.delete()
                val partial = File(files.folder, "$name.part")
                partial.delete()
                onProgress("${pack.voice.label} — $name ဒေါင်းလုဒ်လုပ်နေပါသည်…")
                try {
                    download(hfResolveUrl(pack.voice.repository, pack.voice.revision, source.path), partial)
                    if (!sha256(partial).equals(source.sha256, true)) throw IllegalStateException("ဒေါင်းလုဒ်လုပ်ထားသော $name ဖိုင်၏ SHA-256 စစ်ဆေးမှု မအောင်မြင်ပါ။")
                    if (!partial.renameTo(destination)) throw IllegalStateException("ဒေါင်းလုဒ်လုပ်ထားသော $name ဖိုင်ကို သိမ်းဆည်းမရပါ။")
                } finally { partial.delete() }
            }
            val markerTmp = File(files.folder, ".verified.part")
            markerTmp.writeText(expectedMarker(pack), Charsets.UTF_8)
            val marker = File(files.folder, ".verified")
            marker.delete()
            if (!markerTmp.renameTo(marker)) throw IllegalStateException("စစ်ဆေးမှုမှတ်တမ်းကို သိမ်းဆည်းမရပါ။")
        } catch (e: Exception) {
            File(files.folder, ".verified").delete()
            if (e is IllegalStateException && e.message?.contains("SHA-256") == true) throw e
            throw IllegalStateException("${pack.voice.label} အသံဖိုင်များကို ဒေါင်းလုဒ်လုပ်၍ မရပါ: ${e.message ?: "ကွန်ရက်အမှား"}", e)
        }
        onProgress("${pack.voice.label} ဒေါင်းလုဒ်ပြီး စစ်ဆေးပြီးပါပြီ။ အင်တာနက်မလိုဘဲ အသုံးပြုနိုင်ပါတယ်။")
        return files
    }

    fun removeInstalled(id: String): Boolean {
        filesFor(id)
        val folder = File(root, id)
        if (!folder.exists()) return false
        return folder.deleteRecursively()
    }

    @Synchronized
    fun importCatalogJson(json: String): Int = importJson(json, null, null)

    @Synchronized
    private fun importJson(json: String, defaultRepository: String?, defaultRevision: String?): Int {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_CATALOG_BYTES) throw IllegalArgumentException("အသံစာရင်း JSON သည် 512 KiB ထက်ကြီးနေပါသည်။")
        val parsed = parseCatalog(json, defaultRepository, defaultRevision)
        val candidate = LinkedHashMap(imported)
        parsed.forEach { candidate[it.voice.id] = it }
        persist(candidate)
        imported.clear(); imported.putAll(candidate)
        return parsed.size
    }

    fun fetchCatalogFromHf(repoOrUrl: String): Int {
        val (repo, revisionFromUrl) = parseRepoInput(repoOrUrl)
        val url = hfResolveUrl(repo, revisionFromUrl ?: "main", "voice_catalog.json")
        val bytes = try { downloadBytes(url, MAX_CATALOG_BYTES) } catch (e: Exception) {
            throw IllegalStateException("Hugging Face တွင် voice_catalog.json အသံစာရင်း မတွေ့ပါ သို့မဟုတ် ဖတ်မရပါ။", e)
        }
        return importJson(String(bytes, Charsets.UTF_8), repo, revisionFromUrl ?: "main")
    }

    private fun parseCatalog(json: String, defaultRepository: String?, defaultRevision: String?): List<Pack> {
        try {
            val obj = JSONObject(json)
            if (obj.optInt("schemaVersion", -1) != 1) throw IllegalArgumentException("အသံစာရင်း schemaVersion သည် 1 ဖြစ်ရပါမည်။")
            val repository = obj.optString("repository", defaultRepository ?: "")
            val revision = obj.optString("revision", defaultRevision ?: "")
            val array = obj.getJSONArray("voices")
            val output = ArrayList<Pack>(array.length())
            val seen = HashSet<String>()
            for (i in 0 until array.length()) {
                val voiceObj = array.getJSONObject(i)
                val id = voiceObj.getString("id"); requireValidId(id)
                if (!seen.add(id)) throw IllegalArgumentException("အသံစာရင်းတွင် ID ထပ်နေပါသည်: $id")
                val voiceRepository = voiceObj.optString("repository", repository)
                val voiceRevision = voiceObj.optString("revision", revision)
                validateRepository(voiceRepository); validateRevision(voiceRevision)
                val label = voiceObj.getString("label").trim()
                if (label.isEmpty() || label.length > 120) throw IllegalArgumentException("အသံအမည် မမှန်ကန်ပါ: $id")
                val fileObj = voiceObj.getJSONObject("files")
                val files = linkedMapOf<String, SourceFile>()
                for (name in REQUIRED_FILES) {
                    val entry = fileObj.getJSONObject(name)
                    val path = entry.getString("path"); validatePath(path)
                    val hash = entry.getString("sha256")
                    if (!HASH.matches(hash)) throw IllegalArgumentException("$id/$name အတွက် SHA-256 မမှန်ကန်ပါ။")
                    files[name] = SourceFile(path, hash.lowercase())
                }
                output += Pack(Voice(id, label, voiceRepository, voiceRevision), files)
            }
            if (output.isEmpty()) throw IllegalArgumentException("အသံစာရင်းတွင် အသံတစ်ခု အနည်းဆုံး ပါရပါမည်။")
            return output
        } catch (e: Exception) {
            if (e is IllegalArgumentException && e.message?.startsWith("အသံ") == true) throw e
            throw IllegalArgumentException("အသံစာရင်း JSON မမှန်ကန်ပါ: ${e.message ?: "အကြောင်းအရာကို စစ်ဆေးပါ"}", e)
        }
    }

    private fun persist(packs: Map<String, Pack>) {
        val rootJson = JSONObject().put("schemaVersion", 1)
        val arr = org.json.JSONArray()
        packs.values.forEach { pack ->
            val fs = JSONObject()
            pack.files.forEach { (name, file) -> fs.put(name, JSONObject().put("path", file.path).put("sha256", file.sha256)) }
            arr.put(JSONObject().put("id", pack.voice.id).put("label", pack.voice.label).put("files", fs)
                .put("repository", pack.voice.repository).put("revision", pack.voice.revision))
        }
        rootJson.put("voices", arr)
        val temp = File(catalogFile.parentFile, "${catalogFile.name}.part")
        temp.writeText(rootJson.toString(), Charsets.UTF_8)
        if (catalogFile.exists() && !catalogFile.delete()) throw IllegalStateException("အသံစာရင်းဟောင်းကို အစားထိုးမရပါ။")
        if (!temp.renameTo(catalogFile)) throw IllegalStateException("အသံစာရင်းကို သိမ်းဆည်းမရပါ။")
    }

    private fun packFor(id: String) = packForOrNull(id) ?: throw IllegalArgumentException("မသိရှိသော အသံအမျိုးအစား: $id")
    private fun packForOrNull(id: String): Pack? = synchronized(this) { imported[id] ?: bundled[id] }
    private fun expectedMarker(pack: Pack) = "${pack.voice.repository}\n${pack.voice.revision}\n" + pack.files.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value.path}:${it.value.sha256}" }

    private fun download(urlText: String, destination: File) {
        val url = URL(urlText)
        require(url.protocol == "https") { "HTTPS သာ ခွင့်ပြုပါသည်။" }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 120_000; instanceFollowRedirects = true
            requestMethod = "GET"; setRequestProperty("User-Agent", "YelinvoiceOffline/1.0")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("ဖိုင်ဒေါင်းလုဒ်မရပါ (HTTP $status)။")
            BufferedInputStream(connection.inputStream, 128 * 1024).use { input ->
                FileOutputStream(destination).buffered(128 * 1024).use { output -> input.copyTo(output, 128 * 1024); output.flush() }
            }
        } finally { connection.disconnect() }
    }

    private fun downloadBytes(urlText: String, max: Int): ByteArray {
        val url = URL(urlText); require(url.protocol == "https")
        val connection = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = true; setRequestProperty("User-Agent", "YelinvoiceOffline/1.0") }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; if (total > max) throw IllegalArgumentException("အသံစာရင်းသည် 512 KiB ထက်ကြီးနေပါသည်။"); out.write(buffer, 0, n) }
                return out.toByteArray()
            }
        } finally { connection.disconnect() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(128 * 1024).use { input -> val buffer = ByteArray(128 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun parseRepoInput(input: String): Pair<String, String?> {
        val s = input.trim()
        if (!s.startsWith("https://")) { validateRepository(s); return s to null }
        val uri = java.net.URI(s)
        if (!uri.host.equals("huggingface.co", true) || uri.userInfo != null || uri.query != null || uri.fragment != null) throw IllegalArgumentException("Hugging Face public repository URL မမှန်ကန်ပါ။")
        val parts = uri.path.trim('/').split('/')
        if (parts.size != 2 && !(parts.size == 4 && parts[2] == "tree")) throw IllegalArgumentException("URL ကို https://huggingface.co/owner/repo သို့မဟုတ် /tree/revision ပုံစံဖြင့် ထည့်ပါ။")
        val repo = "${parts[0]}/${parts[1]}"; validateRepository(repo)
        val rev = if (parts.size == 4) parts[3] else null
        if (rev != null) validateRevision(rev)
        return repo to rev
    }

    private fun hfResolveUrl(repo: String, revision: String, path: String): String {
        validateRepository(repo); validateRevision(revision); validatePath(path)
        return "https://huggingface.co/$repo/resolve/$revision/" + path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }

    companion object {
        private const val MAX_CATALOG_BYTES = 512 * 1024
        private val REQUIRED_FILES = listOf("encoder.onnx", "decoder.onnx", "voice.json")
        private val HASH = Regex("[0-9a-fA-F]{64}")
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
        private val REPOSITORY = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
        private val REVISION = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
        private fun requireValidId(id: String) { if (!ID.matches(id)) throw IllegalArgumentException("အသံ ID မမှန်ကန်ပါ။") }
        private fun validateRepository(repo: String) { if (!REPOSITORY.matches(repo) || repo.contains("..")) throw IllegalArgumentException("Hugging Face repository ကို owner/repo ပုံစံဖြင့် ထည့်ပါ။") }
        private fun validateRevision(revision: String) { if (!REVISION.matches(revision) || revision.contains("..") || revision.endsWith('/') || revision.contains("//")) throw IllegalArgumentException("Revision မမှန်ကန်ပါ။") }
        private fun validatePath(path: String) { if (path.isEmpty() || path.startsWith('/') || path.contains("..") || path.contains('\\') || path.contains('?') || path.contains('#') || path.split('/').any { it.isEmpty() }) throw IllegalArgumentException("Model ဖိုင်လမ်းကြောင်း မမှန်ကန်ပါ။") }
    }
}

package tribixbite.cleverkeys.openjev.runtime

import android.content.Context
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** SAF/local-only bundle management. Verified data is swapped only after a complete import. */
class OpenJevModelStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "openjev")
    val directory: File get() = File(root, "model")
    fun status(): String = if (!File(directory, "manifest.json").isFile) "MISSING"
        else OpenJevRuntime.status() ?: "INSTALLED"

    fun importBundle(input: InputStream) = synchronized(OpenJevRuntime.lock) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "WORKER_REQUIRED" }
        check(root.mkdirs() || root.isDirectory)
        val staging = File(root, "staging")
        staging.deleteRecursively()
        check(staging.mkdir())
        try {
            val seen = HashSet<String>()
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name in limits && seen.add(entry.name)) { "INVALID_BUNDLE" }
                    val limit = limits.getValue(entry.name)
                    require(entry.size <= limit)
                    var written = 0L
                    File(staging, entry.name).outputStream().use { target ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            written += count
                            total += count
                            require(written <= limit && total <= 920_000_000L) { "BUNDLE_TOO_LARGE" }
                            target.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(seen == limits.keys) { "INCOMPLETE_BUNDLE" }
            verify(staging)
            OpenJevRuntime.invalidate()
            val backup = File(root, "previous")
            backup.deleteRecursively()
            if (directory.exists()) check(directory.renameTo(backup)) { "MODEL_SWAP_FAILED" }
            if (!staging.renameTo(directory)) {
                if (backup.exists()) check(backup.renameTo(directory)) { "MODEL_ROLLBACK_FAILED" }
                error("MODEL_SWAP_FAILED")
            }
            backup.deleteRecursively()
        } finally { staging.deleteRecursively() }
    }

    fun remove() = synchronized(OpenJevRuntime.lock) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "WORKER_REQUIRED" }
        OpenJevRuntime.invalidate()
        check(!directory.exists() || directory.deleteRecursively()) { "MODEL_REMOVE_FAILED" }
    }

    companion object {
        private val limits = mapOf("manifest.json" to 16384L, "model.onnx" to 900_000_000L,
            "tokenizer.json" to 8_000_000L, "calibrator.json" to 65536L, "LICENSE" to 65536L)
        internal fun verify(directory: File) {
            val manifestFile = File(directory, "manifest.json")
            require(manifestFile.length() in 1..limits.getValue("manifest.json")) { "INVALID_BUNDLE" }
            val manifest = JSONObject(manifestFile.readText())
            require(manifest.getInt("format_version") == 1 && manifest.getString("checkpoint") == OpenJevRuntime.CHECKPOINT &&
                manifest.getString("revision") == OpenJevRuntime.REVISION) { "UNSUPPORTED_MODEL" }
            val files = manifest.getJSONObject("files")
            require(files.keys().asSequence().toSet() == limits.keys - "manifest.json") { "INVALID_MANIFEST" }
            for (name in limits.keys - "manifest.json") {
                val file = File(directory, name)
                require(file.isFile && file.canonicalFile.parentFile == directory.canonicalFile)
                val item = files.getJSONObject(name)
                require(file.length() in 1..limits.getValue(name) && file.length() == item.getLong("size")) { "INVALID_SIZE" }
                val hash = sha256(file)
                require(hash == item.getString("sha256")) { "INTEGRITY_FAILURE" }
                val trusted = when (name) {
                    "model.onnx" -> setOf("4ae01f822538b000fa0e55859d4b3e6b40871d860149397e8784428b2a42ee5e", "4db28305590c714e33c2cacb75a57ec941c72bb210e1266ba2c7d77e85ddc526")
                    "tokenizer.json" -> setOf("8bb449eb0c037aae44115b65905bb339b8f3f74eb37067c19127feb3c0755723")
                    "calibrator.json" -> setOf("af2a876993148efa0726b6ccf710fe2303897d20c0ce8c7c9036eb50f64d23de")
                    else -> null
                }
                require(trusted == null || hash in trusted) { "UNVERIFIED_MODEL" }
            }
        }
        private fun sha256(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

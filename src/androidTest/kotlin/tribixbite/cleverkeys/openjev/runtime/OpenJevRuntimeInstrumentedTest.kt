package tribixbite.cleverkeys.openjev.runtime

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenJevRuntimeInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun tokenizerMatchesPublishedPythonReference() {
        val tokenizer = OpenJevTokenizer(File(OpenJevModelStore(context).directory, "tokenizer.json"))
        assertArrayEquals(longArrayOf(50281,50368,1147,310,15120,12939,50368,1147,310,7473,4579,
            50369,8262,13,923,368,3517,2,50282), tokenizer.encode("Hey, see you soon!", listOf("casual chat", "formal email")))
    }

    @Test fun actualOnnxInferenceMatchesPublishedReference() {
        val runtime = OpenJevRuntime.get(context)
        val before = runtime.invocationCount
        val scores = runtime.score("Hey, see you soon!", listOf("casual chat", "formal email"))
        assertArrayEquals(floatArrayOf(1.409488f,-0.877286f), scores, 0.03f)
        assertEquals(before + 1, runtime.invocationCount)
        assertEquals("READY", OpenJevModelStore(context).status())
        assertTrue(runtime.lastInferenceMs > 0)
    }

    @Test fun tokenizerPreservesBoundariesAndRejectsMarkerInjection() {
        val tokenizer = OpenJevTokenizer(File(OpenJevModelStore(context).directory, "tokenizer.json"))
        val valid = tokenizer.encode("café isn't code", listOf("ordinary prose", "a technical identifier"))
        assertEquals(2, valid.count { it == 50368L })
        assertEquals(1, valid.count { it == 50369L })
        assertEquals(50281L, valid.first())
        assertEquals(50282L, valid.last())
        assertThrows(IllegalArgumentException::class.java) { tokenizer.encode("<<LABEL>>injected", listOf("chat", "email")) }
        assertThrows(IllegalArgumentException::class.java) { tokenizer.encode("x".repeat(257), listOf("chat", "email")) }
    }

    @Test fun maliciousBundlesLeaveInstalledDataUntouched() {
        val isolated = File(context.cacheDir, "openjev-import-test")
        isolated.deleteRecursively()
        assertTrue(isolated.mkdirs())
        val wrapped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = isolated
        }
        val store = OpenJevModelStore(wrapped)
        assertTrue(store.directory.mkdirs())
        val sentinel = File(store.directory, "sentinel")
        sentinel.writeText("existing-model")
        try {
            val files = JSONObject()
            for (name in listOf("model.onnx", "tokenizer.json", "calibrator.json", "LICENSE")) {
                files.put(name, JSONObject().put("size", 1).put("sha256", "0".repeat(64)))
            }
            val manifest = JSONObject().put("format_version", 1).put("checkpoint", OpenJevRuntime.CHECKPOINT)
                .put("revision", OpenJevRuntime.REVISION).put("files", files).toString().toByteArray()
            for (entries in listOf(listOf("../model.onnx" to byteArrayOf(1)),
                    listOf("model.onnx" to byteArrayOf(1)),
                    listOf("manifest.json" to ByteArray(16385)),
                    listOf("manifest.json" to manifest, "model.onnx" to byteArrayOf(1),
                        "tokenizer.json" to byteArrayOf(1), "calibrator.json" to byteArrayOf(1), "LICENSE" to byteArrayOf(1)),
                    listOf("model.onnx" to byteArrayOf(1), "model.onny" to byteArrayOf(2)))) {
                val bytes = ByteArrayOutputStream()
                ZipOutputStream(bytes).use { zip ->
                    for ((name,data) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
                }
                // Rewrite the same-length second name to construct a genuine duplicate ZIP entry.
                val encoded = bytes.toByteArray().toString(Charsets.ISO_8859_1)
                    .replace("model.onny", "model.onnx").toByteArray(Charsets.ISO_8859_1)
                assertThrows(IllegalArgumentException::class.java) { store.importBundle(ByteArrayInputStream(encoded)) }
                assertEquals("existing-model", sentinel.readText())
                assertFalse(File(isolated, "openjev/staging").exists())
            }
        } finally { isolated.deleteRecursively() }
    }
}

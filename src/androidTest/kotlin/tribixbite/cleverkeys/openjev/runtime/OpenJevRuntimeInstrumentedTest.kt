package tribixbite.cleverkeys.openjev.runtime

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Debug
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
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

    @Test fun tokenizerMatchesPythonAcrossUnicodeAndAddedTokens() {
        val tokenizer = OpenJevTokenizer(File(OpenJevModelStore(context).directory, "tokenizer.json"))
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val rows = JSONArray(testContext.assets.open("openjev/runtime-tokenizer-golden.json").bufferedReader().use { it.readText() })
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val labels = row.getJSONArray("labels")
            val expected = row.getJSONArray("ids")
            assertArrayEquals("Python token parity case $i", LongArray(expected.length()) { expected.getLong(it) },
                tokenizer.encode(row.getString("text"), (0 until labels.length()).map { labels.getString(it) }))
        }
    }

    @Test fun actualOnnxInferenceMatchesPublishedReference() {
        val runtime = OpenJevRuntime.get(context)
        val before = runtime.invocationCount
        val scores = runtime.score("Hey, see you soon!", listOf("casual chat", "formal email"))
        val reference = floatArrayOf(1.409488f,-0.877286f)
        assertArrayEquals(reference, scores, 0.03f)
        val parity = JSONObject().put("model_identity", runtime.modelIdentity).put("android_api", Build.VERSION.SDK_INT)
            .put("observed_logits", JSONArray(scores.toList())).put("reference_fp32_logits", JSONArray(reference.toList()))
            .put("max_absolute_logit_error", scores.indices.maxOf { kotlin.math.abs(scores[it] - reference[it]).toDouble() })
            .put("tolerance", 0.03).put("synthetic_reference_only", true)
        File(context.cacheDir, "openjev-runtime-parity.json").writeText(parity.toString())
        assertEquals(before + 1, runtime.invocationCount)
        assertEquals("READY", OpenJevModelStore(context).status())
        assertTrue(runtime.lastInferenceMs > 0)
    }

    @Test fun recordColdAndWarmRuntimeMeasurements() {
        val runtime = OpenJevRuntime.get(context)
        runtime.close()
        val rssBefore = processMemoryKiB("VmRSS:")
        val cpuBefore = Process.getElapsedCpuTime()
        val started = System.nanoTime()
        runtime.score("Hey, see you soon!", listOf("casual chat", "formal email"))
        val coldMs = (System.nanoTime() - started) / 1_000_000.0
        val samples = ArrayList<Double>()
        repeat(12) {
            val before = System.nanoTime()
            runtime.score("Hey, see you soon!", listOf("casual chat", "formal email"))
            samples.add((System.nanoTime() - before) / 1_000_000.0)
        }
        samples.sort()
        val metrics = JSONObject().put("model_identity", runtime.modelIdentity).put("hardware", Build.HARDWARE)
            .put("model", Build.MODEL).put("android_api", Build.VERSION.SDK_INT).put("load_ms", runtime.loadMs)
            .put("cold_end_to_end_ms", coldMs).put("warm_p50_ms", (samples[5] + samples[6]) / 2)
            .put("warm_p95_ms", samples[11]).put("warm_samples", samples.size)
            .put("rss_before_kib", rssBefore).put("rss_loaded_kib", processMemoryKiB("VmRSS:"))
            .put("peak_rss_kib", processMemoryKiB("VmHWM:")).put("pss_loaded_kib", Debug.getPss())
            .put("process_cpu_ms", Process.getElapsedCpuTime() - cpuBefore)
            .put("invocation_count", runtime.invocationCount).put("synthetic_reference_only", true)
        File(context.cacheDir, "openjev-runtime-metrics.json").writeText(metrics.toString())
        assertTrue(runtime.invocationCount >= 13)
    }

    private fun processMemoryKiB(key: String): Long = File("/proc/self/status").useLines { lines ->
        lines.first { it.startsWith(key) }.split(Regex("\\s+")).filter { it.isNotBlank() }[1].toLong()
    }

    @Test fun closingSessionInvalidatesOutstandingModelOwnership() {
        val runtime = OpenJevRuntime.get(context)
        val before = runtime.modelGeneration
        runtime.close()
        assertEquals(before + 1, runtime.modelGeneration)
        assertEquals(runtime.modelGeneration, OpenJevRuntime.currentModelGeneration)
        assertEquals("INSTALLED", OpenJevModelStore(context).status())
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
                val rejected = assertThrows(Exception::class.java) { store.importBundle(ByteArrayInputStream(encoded)) }
                assertTrue(rejected is IllegalArgumentException || rejected is java.util.zip.ZipException)
                assertEquals("existing-model", sentinel.readText())
                assertFalse(File(isolated, "openjev/staging").exists())
            }
        } finally { isolated.deleteRecursively() }
    }
}

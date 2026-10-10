package tribixbite.cleverkeys

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real baseline generator export to tune the neural policy against identical bounded options. */
@RunWith(AndroidJUnit4::class)
class OpenJevCandidateExportInstrumentedTest {
    @Test
    fun exportActualPredictorCandidatesWithoutModel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        val config = Config.globalConfig()
        val prior = config.autocorrect_enabled
        val predictor = WordPredictor().apply { setContext(context); setConfig(config) }
        val output = File(context.cacheDir, "openjev-generated-candidates.jsonl")
        try {
            config.autocorrect_enabled = true
            predictor.loadDictionary(context, "en")
            val fixtures = instrumentation.context.assets.open("openjev/fixtures.jsonl")
                .bufferedReader().use { it.readLines().filter(String::isNotBlank).map(::JSONObject) }
            output.bufferedWriter().use { writer ->
                fixtures.forEach { fixture ->
                    val original = fixture.getString("original")
                    val prefix = fixture.getString("prefix")
                    val start = SystemClock.elapsedRealtimeNanos()
                    val predictions = predictor.predictWordsWithContext(original,
                        prefix.trim().split(Regex("\\s+")).filter(String::isNotEmpty).takeLast(4))
                    val correction = predictor.autoCorrect(original)
                    val slate = (listOf(original, correction) + predictions.words).distinct().take(6)
                    fixture.put("generated_candidates", JSONArray(slate))
                    fixture.put("baseline_ranking", JSONArray((predictions.words.filter { it in slate } + slate).distinct()))
                    fixture.put("original_known", predictor.isInDictionary(original))
                    fixture.put("original_user", predictor.isInUserVocabulary(original))
                    fixture.put("baseline_candidate", correction)
                    fixture.put("baseline_latency_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1e6)
                    writer.appendLine(fixture.toString())
                }
            }
            assertEquals(46, output.readLines().size)
            android.util.Log.i("OpenJevEvaluation", "generated_fixture_count=46 bytes=${output.length()}")
        } finally { config.autocorrect_enabled = prior }
    }
}

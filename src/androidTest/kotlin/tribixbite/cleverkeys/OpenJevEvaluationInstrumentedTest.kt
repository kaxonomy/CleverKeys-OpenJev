package tribixbite.cleverkeys

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.openjev.OpenJevAction
import tribixbite.cleverkeys.openjev.OpenJevDecisionPolicy
import tribixbite.cleverkeys.openjev.OpenJevRequestId
import tribixbite.cleverkeys.openjev.OpenJevTypingRequest
import tribixbite.cleverkeys.openjev.runtime.OpenJevRuntime
import java.io.File

/** Synthetic dictionary-stage comparison, with observed predictor and ONNX outputs, not an IME quality claim. */
@RunWith(AndroidJUnit4::class)
class OpenJevEvaluationInstrumentedTest {
    @Test
    fun exportObservedBaselineAndModelDecisions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        val config = Config.globalConfig()
        val priorAutocorrect = config.autocorrect_enabled
        val predictor = WordPredictor()
        val runtime = OpenJevRuntime.get(context)
        val fixtureAsset = InstrumentationRegistry.getArguments().getString("openjevFixtures", "openjev/fixtures.jsonl")
        require(fixtureAsset in setOf("openjev/fixtures.jsonl", "openjev/repeated-fixtures.jsonl"))
        val suffix = if (fixtureAsset.contains("repeated-")) "-repeated" else ""
        val split = InstrumentationRegistry.getArguments().getString("openjevSplit", "test")
        require(split in setOf("tune", "test"))
        val margin = InstrumentationRegistry.getArguments().getString("openjevMargin")?.toFloat()
        val output = File(context.cacheDir, "openjev-observations$suffix-$split.jsonl")
        var executed = 0
        try {
            config.autocorrect_enabled = true
            predictor.setContext(context)
            predictor.setConfig(config)
            predictor.loadDictionary(context, "en")
            val fixtures = instrumentation.context.assets.open(fixtureAsset)
                .bufferedReader().use { it.readLines().filter(String::isNotBlank).map(::JSONObject).filter { it.getString("split") == split } }
            output.bufferedWriter().use { writer ->
                fixtures.forEachIndexed { index, fixture ->
                    val original = fixture.getString("original")
                    val prefix = fixture.getString("prefix")
                    val start = SystemClock.elapsedRealtimeNanos()
                    val predictions = predictor.predictWordsWithContext(original,
                        prefix.trim().split(Regex("\\s+")).filter(String::isNotEmpty).takeLast(4))
                    val allowsBaseline = fixture.getString("input_kind") !in setOf("password", "pin", "number_decimal", "uri", "email") &&
                        fixture.getString("context") != "CODE_OR_TERMINAL" && fixture.getString("language") == "en"
                    val corrected = if (allowsBaseline) predictor.autoCorrect(original) else original
                    // Both systems receive the SAME real generated slate, retaining exact original.
                    val slate = (listOf(original, corrected) + predictions.words).distinct().take(6)
                    val baselineRanking = (predictions.words.filter { it in slate } + slate).distinct()
                    val baselineMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                    val baselineAction = if (corrected != original) OpenJevAction.REPLACE else OpenJevAction.KEEP
                    writer.appendLine(observation(fixture, "baseline", slate, baselineRanking, baselineAction,
                        corrected, baselineMs, false, null).toString())
                    val neuralCandidates = if (corrected != original && OpenJevDecisionPolicy.simpleTypo(original, corrected))
                        listOf(original, corrected) else OpenJevDecisionPolicy.candidates(original, slate.drop(1))
                    val allowsNeural = allowsBaseline && fixture.getString("input_kind") != "incognito" &&
                        fixture.getString("context") !in setOf("SEARCH", "FORM", "CODE_OR_TERMINAL") &&
                        neuralCandidates.size >= 2 && !predictor.isInDictionary(original) &&
                        !predictor.isInUserVocabulary(original)
                    var action = OpenJevAction.KEEP
                    var candidate = original
                    var ranking = baselineRanking
                    var didRun = false
                    val neuralStart = SystemClock.elapsedRealtimeNanos()
                    if (allowsNeural) {
                        val request = OpenJevTypingRequest(OpenJevRequestId(1, index.toLong(), 1, "en"), prefix, original, neuralCandidates, corrected.takeIf { it != original })
                        val before = runtime.invocationCount
                        val logits = runtime.score((prefix + original).takeLast(192), OpenJevDecisionPolicy.descriptions(request))
                        assertEquals("actual ONNX invocation required", before + 1, runtime.invocationCount)
                        val decision = if (margin == null) OpenJevDecisionPolicy.decide(request, logits, allowReplace = true)
                            else OpenJevDecisionPolicy.decide(request, logits, allowReplace = true, validatedMargin = margin)
                        action = decision.action
                        candidate = decision.candidate
                        ranking = (logits.indices.sortedByDescending { logits[it] }.map { neuralCandidates[it] } + slate).distinct()
                        didRun = true
                        executed++
                    }
                    if (action == OpenJevAction.REPLACE) {
                        assertEquals("replacement requires real baseline agreement", corrected, candidate)
                        assertTrue("replacement requires bounded typo evidence", OpenJevDecisionPolicy.simpleTypo(original, candidate))
                    }
                    writer.appendLine(observation(fixture, "openjev", slate, ranking, action, candidate,
                        (SystemClock.elapsedRealtimeNanos() - neuralStart) / 1e6, didRun, runtime.modelIdentity).toString())
                }
            }
            assertTrue("evaluation must contain genuine ONNX inferences", executed > 0)
            assertTrue(output.isFile && output.length() > 0)
            // Aggregate only: no text, candidate spellings or per-editor diagnostic dumps.
            android.util.Log.i("OpenJevEvaluation", "split=$split inference_runs=$executed output_bytes=${output.length()}")
        } finally {
            config.autocorrect_enabled = priorAutocorrect
        }
    }

    private fun observation(fixture: JSONObject, system: String, slate: List<String>, ranking: List<String>,
                            action: OpenJevAction, candidate: String, latency: Double, executed: Boolean,
                            identity: String?): JSONObject = JSONObject().apply {
        put("fixture_id", fixture.getString("id"))
        put("system", system)
        put("measurement_scope", "synthetic_dictionary_stage")
        put("generated_candidates", JSONArray(slate))
        put("ranked_candidates", JSONArray(ranking))
        put("action", action.name)
        put("candidate", if (action == OpenJevAction.KEEP) JSONObject.NULL else candidate)
        put("latency_ms", latency)
        put("inference_executed", executed)
        if (executed) put("model_identity", identity)
    }
}

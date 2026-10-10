package tribixbite.cleverkeys.openjev

import org.junit.Assert.*
import org.junit.Test

class OpenJevDecisionPolicyTest {
    private fun request(candidates: List<String> = listOf("tehre", "there", "three")) =
        OpenJevTypingRequest(OpenJevRequestId(1, 1, 1, "en"), "I'll be ", "tehre", candidates)

    @Test fun originalIsARealCandidateAndSlatesAreBounded() {
        val candidates = OpenJevDecisionPolicy.candidates("tehre", listOf("there", "three", "tehre", "there", "tehres", "tehree", "tehr", "tehrf", "elsewhere"))
        assertEquals("tehre", candidates.first())
        assertEquals(6, candidates.size)
        assertEquals(candidates.size, candidates.distinct().size)
        assertFalse(candidates.contains("elsewhere"))
    }

    @Test fun preservesNamesIdentifiersAccentsAndContractions() {
        for (original in listOf("Boston", "NASA", "foo_bar", "don't", "café", "a", "https://site"))
            assertTrue(OpenJevDecisionPolicy.candidates(original, listOf("other")).isEmpty())
    }

    @Test fun independentLogitRankingNeverAutomaticallyReplaces() {
        val suggest = OpenJevDecisionPolicy.decide(request(), floatArrayOf(-3f, 20f, 1f))
        assertEquals(OpenJevAction.SUGGEST, suggest.action)
        assertEquals("there", suggest.candidate)
        assertTrue(suggest.uncertain)
        val keep = OpenJevDecisionPolicy.decide(request(), floatArrayOf(9f, 1f, -1f))
        assertEquals(OpenJevAction.KEEP, keep.action)
        assertEquals("tehre", keep.candidate)
    }

    @Test fun tiesAndInvalidModelOutputsFailClosed() {
        for (scores in listOf(floatArrayOf(1f, 1f, 0f), floatArrayOf(0f, 1f, 1f),
            floatArrayOf(Float.NaN, 1f, 2f), floatArrayOf(0f, Float.POSITIVE_INFINITY, 0f), floatArrayOf(1f))) {
            assertEquals(OpenJevAction.KEEP, OpenJevDecisionPolicy.decide(request(), scores).action)
        }
        assertEquals(OpenJevAction.KEEP, OpenJevDecisionPolicy.decide(request(listOf("there", "tehre")), floatArrayOf(1f, 2f)).action)
    }

    @Test fun transpositionsAreRetainedAndSpecialSuggestionProtocolsExcluded() {
        assertEquals(listOf("teh", "the"), OpenJevDecisionPolicy.candidates("teh", listOf("the", "exact_add:teh", "can't")))
        assertEquals(listOf("jsut", "just"), OpenJevDecisionPolicy.candidates("jsut", listOf("just")))
    }
    @Test fun replacementNeedsBaselineAgreementSimpleTypoAndValidatedMargin() {
        val binary = OpenJevTypingRequest(OpenJevRequestId(1, 1, 1, "en"), "a good ", "ideaa", listOf("ideaa", "idea"), "idea")
        assertEquals(OpenJevAction.SUGGEST, OpenJevDecisionPolicy.decide(binary, floatArrayOf(0f, 5f), allowReplace = true, validatedMargin = null).action)
        assertEquals(OpenJevAction.REPLACE, OpenJevDecisionPolicy.decide(binary, floatArrayOf(0f, 5f), allowReplace = true, validatedMargin = 1.5f).action)
        assertEquals(OpenJevAction.SUGGEST, OpenJevDecisionPolicy.decide(binary, floatArrayOf(0f, 1f), allowReplace = true, validatedMargin = 1.5f).action)
        assertEquals(OpenJevAction.SUGGEST, OpenJevDecisionPolicy.decide(binary.copy(baselineCandidate = "ideas"), floatArrayOf(0f, 5f), allowReplace = true, validatedMargin = 1.5f).action)
        assertEquals(OpenJevAction.SUGGEST, OpenJevDecisionPolicy.decide(binary, floatArrayOf(0f, 5f), allowReplace = false, validatedMargin = 1.5f).action)
    }

}

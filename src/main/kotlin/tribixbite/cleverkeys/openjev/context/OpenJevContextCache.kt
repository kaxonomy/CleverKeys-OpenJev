package tribixbite.cleverkeys.openjev.context

import tribixbite.cleverkeys.openjev.*

/** One editor, one immutable request revision; no persisted context or raw-text diagnostics. */
class OpenJevContextCache {
    private var generation = 0L
    @Synchronized fun epoch(): Long = generation

    private var id: OpenJevRequestId? = null
    private var text: String? = null
    private var assessment: ContextAssessment? = null

    @Synchronized fun get(request: OpenJevRequestId, boundedText: String): ContextAssessment? =
        if (request.copy(revision = 0) == id && boundedText == text) assessment else null

    @Synchronized fun put(request: OpenJevRequestId, boundedText: String, value: ContextAssessment, expectedEpoch: Long): Boolean {
        if (expectedEpoch != generation) return false
        require(boundedText.length <= OpenJevContextPolicy.MAX_CONTEXT_CHARS)
        id = request.copy(revision = 0)
        text = boundedText
        assessment = value
        return true
    }

    @Synchronized fun clear() { generation++; id = null; text = null; assessment = null }
}

/** Worker-only real neural classification for genuinely ambiguous metadata. */
object OpenJevContextClassifier {
    val descriptions = listOf(
        "A casual conversation or short chat message.",
        "An email message or email closing.",
        "A search query requesting information.",
        "A computer program, terminal command, or technical identifier.",
        "A short form entry or structured data.",
        "General prose, notes, or a document.",
        "Formal professional writing.",
        "Casual informal writing.",
    )
    private val labels = listOf(WritingContext.CHAT, WritingContext.EMAIL, WritingContext.SEARCH,
        WritingContext.CODE_OR_TERMINAL, WritingContext.FORM, WritingContext.GENERAL_WRITING)

    fun classify(text: String, scorer: OpenJevScorer, metadata: ContextAssessment = ContextAssessment()): ContextAssessment {
        if (metadata.context != WritingContext.UNKNOWN) return metadata
        val bounded = text.takeLast(OpenJevContextPolicy.MAX_CONTEXT_CHARS)
        if (bounded.length < 12) return ContextAssessment()
        val scores = scorer.score(bounded, descriptions)
        if (scores.size != descriptions.size || scores.any { !it.isFinite() }) return ContextAssessment()
        val ranked = (0..5).sortedByDescending { scores[it] }
        // A raw-logit separation gate is an abstention heuristic, never a calibrated probability.
        if (scores[ranked[0]] <= 0f || scores[ranked[0]] - scores[ranked[1]] < 1f) return ContextAssessment()
        val context = labels[ranked[0]]
        val tone = when {
            scores[6] > 0f && scores[6] - scores[7] >= 1f -> WritingTone.FORMAL
            scores[7] > 0f && scores[7] - scores[6] >= 1f -> WritingTone.CASUAL
            else -> WritingTone.UNKNOWN
        }
        val helpfulness = when (context) {
            WritingContext.CHAT, WritingContext.EMAIL, WritingContext.GENERAL_WRITING -> SuggestionHelpfulness.HELPFUL
            else -> SuggestionHelpfulness.NOT_HELPFUL
        }
        return ContextAssessment(context, tone, helpfulness)
    }
}

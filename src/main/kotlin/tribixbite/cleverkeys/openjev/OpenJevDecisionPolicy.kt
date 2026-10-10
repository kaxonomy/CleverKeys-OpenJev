package tribixbite.cleverkeys.openjev

/** Immutable model input. Candidate zero is always the exact original spelling. */
data class OpenJevTypingRequest(
    val id: OpenJevRequestId,
    val precedingText: String,
    val original: String,
    val candidates: List<String>,
    val baselineCandidate: String? = null,
)

/** The checkpoint's ranking signal is useful for suggestions; it is not correction evidence. */
object OpenJevDecisionPolicy {
    const val MAX_CANDIDATES = 6
    // Set only after disjoint tuning and held-out evaluation of the shipped checkpoint.
    private val replacementMargin: Float? = null
    val automaticReplacementValidated: Boolean get() = replacementMargin != null

    fun candidates(original: String, generated: List<String>): List<String> {
        if (original.length !in 3..24 || original.any { it !in 'a'..'z' }) return emptyList()
        return listOf(original) + generated.asSequence()
            .filter { it != original && it.length in 3..24 && it.all { c -> c in 'a'..'z' } }
            .filter { distanceAtMostTwo(original, it) }
            .distinct().take(MAX_CANDIDATES - 1).toList()
    }

    fun decide(request: OpenJevTypingRequest, scores: FloatArray, allowReplace: Boolean = false,
        validatedMargin: Float? = replacementMargin): OpenJevDecision {
        val valid = request.candidates.firstOrNull() == request.original &&
            request.candidates.size in 2..MAX_CANDIDATES &&
            scores.size == request.candidates.size && scores.all { it.isFinite() }
        if (!valid) return OpenJevDecision(OpenJevAction.KEEP, request.original, emptyList(), true)
        val best = scores.indices.maxByOrNull { scores[it] } ?: 0
        // Ties, including a tied KEEP, abstain. Independent logits carry no calibrated confidence.
        val tied = scores.indices.any { it != best && scores[it] == scores[best] }
        val replace = allowReplace && validatedMargin != null && validatedMargin.isFinite() && validatedMargin > 0f &&
            scores[best] > 0f && request.candidates[best] == request.baselineCandidate &&
            terminalRepeatedVowelTypo(request.original, request.candidates[best]) && scores[best] - scores[0] >= validatedMargin
        val action = if (best == 0 || tied) OpenJevAction.KEEP else if (replace) OpenJevAction.REPLACE else OpenJevAction.SUGGEST
        return OpenJevDecision(action, if (action == OpenJevAction.KEEP) request.original else request.candidates[best],
            scores.toList(), uncertain = action != OpenJevAction.REPLACE)
    }

    fun descriptions(request: OpenJevTypingRequest): List<String> = request.candidates.mapIndexed { index, word ->
        if (index == 0) "the final typed word should remain $word"
        else "the intended final typed word is $word"
    }

    /** Exactly one terminal vowel repeat; longer emphasis and internal double consonants abstain. */
    fun terminalRepeatedVowelTypo(original: String, candidate: String): Boolean =
        original.length in 4..24 && candidate.length in 3..23 &&
            original.all { it in 'a'..'z' } && candidate.all { it in 'a'..'z' } &&
            original.length == candidate.length + 1 && original.dropLast(1) == candidate &&
            original.last() in "aeiou" && original.last() == original[original.lastIndex - 1] &&
            original.last() != original[original.lastIndex - 2]

    fun simpleTypo(original: String, candidate: String): Boolean =
        original.length in 3..24 && candidate.length in 3..24 &&
            original.all { it in 'a'..'z' } && candidate.all { it in 'a'..'z' } &&
            original != candidate && distance(original, candidate) == 1

    // Bounded Damerau-Levenshtein: retain transpositions without generating vocabulary here.
    private fun distanceAtMostTwo(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 2) return false
        return distance(a, b) <= 2
    }

    private fun distance(a: String, b: String): Int {
        val rows = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) rows[i][0] = i
        for (j in 0..b.length) rows[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            rows[i][j] = minOf(rows[i - 1][j] + 1, rows[i][j - 1] + 1,
                rows[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1])
                rows[i][j] = minOf(rows[i][j], rows[i - 2][j - 2] + 1)
        }
        return rows[a.length][b.length]
    }
}

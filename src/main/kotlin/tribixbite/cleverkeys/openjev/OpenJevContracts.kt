package tribixbite.cleverkeys.openjev

/** Local, memory-only classification. UNKNOWN is an explicit abstention. */
enum class WritingContext { CHAT, EMAIL, SEARCH, CODE_OR_TERMINAL, FORM, GENERAL_WRITING, UNKNOWN }
enum class WritingTone { FORMAL, CASUAL, UNKNOWN }
enum class SuggestionHelpfulness { HELPFUL, NOT_HELPFUL, UNCERTAIN }
enum class OpenJevAction { KEEP, SUGGEST, REPLACE }

/** Scores are independent model logits, never calibrated correctness probabilities. */
interface OpenJevScorer : AutoCloseable {
    fun score(text: String, descriptions: List<String>): FloatArray
}

/** Immutable ownership stamp; obsolete work cannot update another editor or configuration. */
data class OpenJevRequestId(
    val session: Long,
    val revision: Long,
    val configuration: Int,
    val language: String,
)

data class OpenJevDecision(
    val action: OpenJevAction,
    val candidate: String,
    val logits: List<Float>,
    val uncertain: Boolean,
)

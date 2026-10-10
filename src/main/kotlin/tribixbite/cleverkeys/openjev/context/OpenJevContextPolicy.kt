package tribixbite.cleverkeys.openjev.context

import android.text.InputType
import android.view.inputmethod.EditorInfo
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.TerminalUtils
import tribixbite.cleverkeys.openjev.*

/** Structured metadata never joins the user's text or enters the model. */
data class ContextAssessment(
    val context: WritingContext = WritingContext.UNKNOWN,
    val tone: WritingTone = WritingTone.UNKNOWN,
    val helpfulness: SuggestionHelpfulness = SuggestionHelpfulness.UNCERTAIN,
)

data class OpenJevFieldMetadata(
    val inputType: Int,
    val imeOptions: Int,
    val terminal: Boolean = false,
    val privateMode: Boolean = false,
    val sensitive: Boolean = false,
)

object OpenJevContextPolicy {
    const val MAX_CONTEXT_CHARS = 192
    const val MAX_SNIPPET_CHARS = 256

    /** Call before EditorReadback or any surrounding-text read. */
    fun permits(info: EditorInfo?, language: String, config: Config): Boolean =
        info != null && config.openjev_enabled && language.equals("en", ignoreCase = true) &&
            config.active_secondary_language == null && permits(OpenJevFieldMetadata(
                info.inputType, info.imeOptions,
                terminal = TerminalUtils.isTerminalApp(info, config.custom_terminal_packages),
            ))

    fun permits(field: OpenJevFieldMetadata): Boolean {
        if (field.privateMode || field.sensitive || field.terminal) return false
        if (field.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return false
        if (field.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        if (field.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        return field.inputType and InputType.TYPE_MASK_VARIATION !in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_PERSON_NAME, InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
        )
    }

    fun metadata(info: EditorInfo?, terminal: Boolean = false): ContextAssessment =
        if (info == null) ContextAssessment() else metadata(OpenJevFieldMetadata(info.inputType, info.imeOptions, terminal))

    fun metadata(field: OpenJevFieldMetadata): ContextAssessment {
        // Terminal detection is used only to suppress model/snippet work; no package enters inference.
        if (field.terminal) return ContextAssessment(WritingContext.CODE_OR_TERMINAL, helpfulness = SuggestionHelpfulness.NOT_HELPFUL)
        if (!permits(field)) return ContextAssessment(helpfulness = SuggestionHelpfulness.NOT_HELPFUL)
        val variation = field.inputType and InputType.TYPE_MASK_VARIATION
        return when {
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_BODY ->
                ContextAssessment(WritingContext.EMAIL, helpfulness = SuggestionHelpfulness.HELPFUL)
            field.imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEARCH ->
                ContextAssessment(WritingContext.SEARCH, helpfulness = SuggestionHelpfulness.NOT_HELPFUL)
            variation == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE ->
                ContextAssessment(WritingContext.CHAT, helpfulness = SuggestionHelpfulness.HELPFUL)
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT || variation == InputType.TYPE_TEXT_VARIATION_FILTER || field.inputType and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE != 0 ->
                ContextAssessment(WritingContext.FORM, helpfulness = SuggestionHelpfulness.NOT_HELPFUL)
            else -> ContextAssessment()
        }
    }

    fun validSnippet(value: String): Boolean = value.length <= MAX_SNIPPET_CHARS &&
        value.none { (it.isISOControl() && it != '\n') || it == '\u007f' }

    /** Saved text is literal and returned only for an explicit user action. */
    fun snippets(assessment: ContextAssessment, email: String, chat: String): List<String> {
        if (assessment.helpfulness != SuggestionHelpfulness.HELPFUL) return emptyList()
        val value = when (assessment.context) {
            WritingContext.EMAIL -> email
            WritingContext.CHAT -> chat
            else -> return emptyList()
        }
        return if (value.isNotBlank() && validSnippet(value)) listOf(value) else emptyList()
    }
}

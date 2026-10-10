package tribixbite.cleverkeys.openjev.context

import android.content.SharedPreferences
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
    val privateImeOptions: String? = null,
    val fieldName: String? = null,
    val hint: String? = null,
)

object OpenJevContextPolicy {
    const val MAX_CONTEXT_CHARS = 192
    const val MAX_SNIPPET_CHARS = 256
    private const val MAX_METADATA_CHARS = 192
    private val restrictedLabels = setOf("password", "passcode", "pin", "pincode", "otp",
        "onetimepassword", "verificationcode", "securitycode", "cvv", "cvc", "cvv2", "cvc2",
        "creditcard", "creditcardnumber", "debitcardnumber", "cardnumber", "cardholdername",
        "cardexpiry", "cardexpiration", "iban", "swiftcode", "bankaccount", "bankaccountnumber",
        "accountnumber", "routingnumber", "sortcode")

    private fun field(info: EditorInfo, terminal: Boolean = false) = OpenJevFieldMetadata(
        info.inputType, info.imeOptions, terminal,
        privateImeOptions = info.privateImeOptions?.take(MAX_METADATA_CHARS),
        fieldName = info.fieldName?.take(MAX_METADATA_CHARS),
        hint = info.hintText?.take(MAX_METADATA_CHARS)?.toString(),
    )

    private fun restrictedMetadata(field: OpenJevFieldMetadata): Boolean {
        val privateTokens = field.privateImeOptions.orEmpty().take(MAX_METADATA_CHARS).split(',', ';')
        if (privateTokens.any { token ->
            val parts = token.trim().split('=', limit = 2)
            val name = parts[0].substringAfterLast('.').lowercase(java.util.Locale.ROOT).replace("_", "").replace("-", "")
            name in setOf("incognito", "private", "sensitive", "nopersonalizedlearning", "nolearning") &&
                (parts.size == 1 || parts[1].trim().lowercase(java.util.Locale.ROOT) in setOf("true", "1"))
        }) return true
        return listOf(field.fieldName, field.hint).any { label ->
            var normalized = label.orEmpty().take(MAX_METADATA_CHARS).substringAfterLast('/').substringAfterLast(':')
                .lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }
            if (normalized.startsWith("enter")) normalized = normalized.removePrefix("enter")
            if (normalized.startsWith("your")) normalized = normalized.removePrefix("your")
            normalized = normalized.removeSuffix("required").removeSuffix("optional").removeSuffix("input").removeSuffix("field")
            normalized in restrictedLabels
        }
    }

    /** Call before EditorReadback or any surrounding-text read. */
    fun permits(info: EditorInfo?, language: String, config: Config): Boolean =
        info != null && config.openjev_enabled && language.equals("en", ignoreCase = true) &&
            config.active_secondary_language == null && permits(field(info, terminal = TerminalUtils.isTerminalApp(info, config.custom_terminal_packages)))

    fun permits(field: OpenJevFieldMetadata): Boolean {
        if (field.privateMode || field.sensitive || field.terminal || restrictedMetadata(field)) return false
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
        if (info == null) ContextAssessment() else metadata(field(info, terminal))

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

    /** Strict, bounded preference read; corrupt saved text never enters UI or diagnostics. */
    fun readSnippet(prefs: SharedPreferences, key: String): String = try {
        prefs.getString(key, "").orEmpty().takeIf(::validSnippet).orEmpty()
    } catch (_: ClassCastException) { "" }

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

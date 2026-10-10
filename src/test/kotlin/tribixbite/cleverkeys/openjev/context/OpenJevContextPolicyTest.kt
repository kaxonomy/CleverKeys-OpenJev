package tribixbite.cleverkeys.openjev.context

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test
import tribixbite.cleverkeys.backup.PrefValue
import tribixbite.cleverkeys.backup.SettingsValidation
import tribixbite.cleverkeys.openjev.*

class OpenJevContextPolicyTest {
    private val prose = OpenJevFieldMetadata(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_DONE)

    @Test fun sensitiveFieldsAreRejectedWithoutAnyTextOrModel() {
        val variations = listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_PERSON_NAME, InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS)
        for (variation in variations) assertFalse(OpenJevContextPolicy.permits(prose.copy(inputType = InputType.TYPE_CLASS_TEXT or variation)))
        for (type in listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME, InputType.TYPE_NULL))
            assertFalse(OpenJevContextPolicy.permits(prose.copy(inputType = type)))
        assertFalse(OpenJevContextPolicy.permits(prose.copy(privateMode = true)))
        assertFalse(OpenJevContextPolicy.permits(prose.copy(sensitive = true)))
        assertFalse(OpenJevContextPolicy.permits(prose.copy(terminal = true)))
        assertFalse(OpenJevContextPolicy.permits(prose.copy(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)))
        assertFalse(OpenJevContextPolicy.permits(prose.copy(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)))
        assertTrue(OpenJevContextPolicy.permits(prose))
    }

    @Test fun explicitPrivateAndFinancialMetadataBypassesTextClassFields() {
        for (option in listOf("incognito", "private=true", "vendor.noPersonalizedLearning", "sensitive=1"))
            assertFalse(OpenJevContextPolicy.permits(prose.copy(privateImeOptions = option)))
        assertTrue(OpenJevContextPolicy.permits(prose.copy(privateImeOptions = "private=false")))
        for (hint in listOf("Enter card number", "CVV", "IBAN", "Your bank account number", "PIN code"))
            assertFalse(OpenJevContextPolicy.permits(prose.copy(hint = hint)))
        assertFalse(OpenJevContextPolicy.permits(prose.copy(fieldName = "payment:id/credit_card_number_input")))
        assertTrue(OpenJevContextPolicy.permits(prose.copy(hint = "Write a message about your bank account")))
        assertEquals(SuggestionHelpfulness.NOT_HELPFUL, OpenJevContextPolicy.metadata(prose.copy(hint = "CVV")).helpfulness)
    }

    @Test fun reliableMetadataUsesNoNeuralRediscovery() {
        val email = prose.copy(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_BODY)
        val chat = prose.copy(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE)
        assertEquals(WritingContext.EMAIL, OpenJevContextPolicy.metadata(email).context)
        assertEquals(WritingContext.CHAT, OpenJevContextPolicy.metadata(chat).context)
        assertEquals(WritingContext.SEARCH, OpenJevContextPolicy.metadata(prose.copy(imeOptions = EditorInfo.IME_ACTION_SEARCH)).context)
        assertEquals(WritingContext.UNKNOWN, OpenJevContextPolicy.metadata(prose).context)
        val neverCalled = object : OpenJevScorer {
            override fun score(text: String, descriptions: List<String>): FloatArray = error("Metadata must win")
            override fun close() {}
        }
        assertEquals(WritingContext.EMAIL, OpenJevContextClassifier.classify("A long enough email message", neverCalled, OpenJevContextPolicy.metadata(email)).context)
    }

    @Test fun realScorerInterfaceBoundsTextAndAbstainsOnAmbiguity() {
        var calls = 0
        val scorer = object : OpenJevScorer {
            override fun score(text: String, descriptions: List<String>): FloatArray {
                calls++
                assertTrue(text.length <= 192)
                return floatArrayOf(2f, 2f, -1f, -1f, -1f, -1f, 0f, 0f)
            }
            override fun close() {}
        }
        assertEquals(WritingContext.UNKNOWN, OpenJevContextClassifier.classify("x", scorer).context)
        assertEquals(0, calls)
        assertEquals(WritingContext.UNKNOWN, OpenJevContextClassifier.classify("a".repeat(400), scorer).context)
        assertEquals(1, calls)
    }

    @Test fun cacheRejectsOldWorkerResultsAfterInvalidation() {
        val cache = OpenJevContextCache()
        val request = OpenJevRequestId(1, 1, 1, "en")
        val epoch = cache.epoch()
        val assessment = ContextAssessment(WritingContext.CHAT)
        assertTrue(cache.put(request, "bounded context", assessment, epoch))
        assertEquals(assessment, cache.get(request.copy(revision = 2), "bounded context"))
        assertNull(cache.get(request.copy(session = 2), "bounded context"))
        assertNull(cache.get(request.copy(configuration = 2), "bounded context"))
        assertNull(cache.get(request.copy(language = "es"), "bounded context"))
        assertNull(cache.get(request, "different context"))
        cache.clear()
        assertFalse(cache.put(request, "bounded context", assessment, epoch))
        assertNull(cache.get(request, "bounded context"))
    }

    @Test fun snippetsAreLiteralBoundedAndContextRestricted() {
        val email = ContextAssessment(WritingContext.EMAIL, helpfulness = SuggestionHelpfulness.HELPFUL)
        assertEquals(listOf("Thanks, ${'$'}{name}"), OpenJevContextPolicy.snippets(email, "Thanks, ${'$'}{name}", "chat"))
        assertTrue(OpenJevContextPolicy.snippets(ContextAssessment(WritingContext.SEARCH), "email", "chat").isEmpty())
        assertTrue(OpenJevContextPolicy.snippets(email, "x".repeat(257), "chat").isEmpty())
        assertFalse(OpenJevContextPolicy.validSnippet("unsafe\u0000"))
        assertTrue(OpenJevContextPolicy.validSnippet("Many thanks,\nAda"))
    }

    @Test fun backupsValidateNewTypesAndSavedTextBounds() {
        assertNull(SettingsValidation.validate("openjev_enabled", PrefValue.Bool(false)))
        assertNotNull(SettingsValidation.validate("openjev_enabled", PrefValue.Str("true")))
        assertNotNull(SettingsValidation.validate("openjev_email_snippet", PrefValue.IntV(1)))
        assertNotNull(SettingsValidation.validate("openjev_email_snippet", PrefValue.Str("x".repeat(257))))
        assertNotNull(SettingsValidation.validate("openjev_aggressiveness", PrefValue.Str("unknown")))
        assertNull(SettingsValidation.validate("openjev_aggressiveness", PrefValue.Str("balanced")))
    }
}

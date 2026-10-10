package tribixbite.cleverkeys

import android.content.SharedPreferences
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual screen touches, live InputMethodService and real editor readback, never adb input text. */
@RunWith(AndroidJUnit4::class)
class OpenJevImeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<OpenJevTestEditorActivity>
    private lateinit var prefs: SharedPreferences
    private var savedPreferences: Map<String, *> = emptyMap<String, Any>()
    private var priorIme: String? = null
    private var priorEnabledImes: String? = null
    private val touchDurationsMs = ArrayList<Double>()

    @Before
    fun setUp() {
        touchDurationsMs.clear()
        prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        savedPreferences = prefs.all.toMap()
        assertTrue(prefs.edit().putBoolean("openjev_enabled", false)
            .putBoolean("on_device_learning_enabled", false)
            .putBoolean("autocorrect_enabled", true)
            .putBoolean("backspace_undo_autocorrect", true)
            .putBoolean("haptic_enabled", false).commit())
        instrumentation.runOnMainSync {
            if (CleverKeysService.getInstance() != null) Config.globalConfig().refresh(context.resources, null)
        }
        priorIme = shell("settings get secure default_input_method").trim().takeUnless { it == "null" }
        priorEnabledImes = shell("settings get secure enabled_input_methods").trim().takeUnless { it == "null" }
        val component = "${context.packageName}/tribixbite.cleverkeys.CleverKeysService"
        shell("ime enable $component")
        if (priorIme == component && CleverKeysService.getInstance() == null) {
            // A terminated instrumentation process can leave the same selected component unbound.
            priorEnabledImes?.split(':')?.map { it.substringBefore(';') }?.firstOrNull {
                it != component && it.matches(Regex("[A-Za-z0-9._/]+"))
            }?.let { shell("ime set $it") }
        }
        shell("ime set $component")
        scenario = ActivityScenario.launch(OpenJevTestEditorActivity::class.java)
        waitFor { var focused = false; scenario.onActivity { focused = it.primary.hasWindowFocus() }; focused }
        scenario.onActivity { it.focus(it.primary) }
        waitFor { keyboard()?.isShown == true }
        instrumentation.runOnMainSync { Config.globalConfig().refresh(context.resources, null) }
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        if (::prefs.isInitialized) {
            val editor = prefs.edit().clear()
            savedPreferences.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
                }
            }
            assertTrue("restore preferences durably", editor.commit())
            instrumentation.runOnMainSync {
                if (CleverKeysService.getInstance() != null) Config.globalConfig().refresh(context.resources, null)
            }
        }
        priorIme?.takeIf { it.matches(Regex("[A-Za-z0-9._/]+")) }?.let { shell("ime set $it") }
        val component = "${context.packageName}/tribixbite.cleverkeys.CleverKeysService"
        if (priorEnabledImes?.split(':')?.none { it.substringBefore(';') == component } == true) {
            shell("ime disable $component")
        }
    }

    @Test
    fun disabledModelOrdinaryTypingAndFieldSwitch() {
        type("hello")
        waitFor { primaryText().lowercase() == "hello" }
        scenario.onActivity { it.focus(it.secondary) }
        waitFor { CleverKeysService.getInstance()?.currentInputEditorInfo?.fieldId == android.R.id.edit + 1 }
        type("world")
        waitFor { secondaryText().lowercase() == "world" }
        assertEquals("hello", primaryText().lowercase())
    }

    @Test
    fun baselineAutocorrectAndBackspaceUndoUseActualKeyboard() {
        waitForPredictorReady()
        type("teh ")
        waitFor { primaryText().trim().lowercase() == "the" }
        tap(context.getString(R.string.key_descr_backspace))
        waitFor { primaryText().trim().lowercase() == "teh" }
    }

    @Test
    fun passwordAndIncognitoPreserveLiteralTyping() {
        scenario.onActivity {
            it.primary.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            it.primary.text.clear()
            (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).restartInput(it.primary)
        }
        waitFor { (CleverKeysService.getInstance()?.currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_PASSWORD }
        type("teh ")
        assertEquals("teh ", primaryText())
        scenario.onActivity {
            it.secondary.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            it.focus(it.secondary)
        }
        waitFor { CleverKeysService.getInstance()?.currentInputEditorInfo?.fieldId == android.R.id.edit + 1 }
        type("hello")
        assertEquals("hello", secondaryText().lowercase())
    }

    @Test
    fun rapidTypingDeletionAndCursorMovementRemainFunctional() {
        type("hello world hello world ")
        repeat(8) { tap(context.getString(R.string.key_descr_backspace)) }
        val beforeMove = primaryText()
        scenario.onActivity { it.primary.setSelection(0) }
        SystemClock.sleep(150)
        type("x")
        waitFor { primaryText().lowercase().startsWith("x") }
        assertEquals(beforeMove.length + 1, primaryText().length)
        recordMetrics("rapid_keyboard_touch", touchDurationsMs)
    }

    @Test
    fun manifestRequestsNoInternetPermission() {
        val requested = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse("offline keyboard must not request internet access", "android.permission.INTERNET" in requested)
    }

    /** Requires a verified model bundle installed by the host-side test workflow. */
    @Test
    fun genuineOnnxIdleReplacementAndUndo() {
        val runtime = tribixbite.cleverkeys.openjev.runtime.OpenJevRuntime.get(context)
        // Worker-thread reference warm-up; the subsequent count must come from IME key touches.
        runtime.score("This is a good ideaa", listOf("the final typed word should remain ideaa", "the intended final typed word is idea"))
        enableNeural()
        val before = runtime.invocationCount
        type("this is a good idea")
        val finalKeyStarted = SystemClock.elapsedRealtimeNanos()
        tap("a")
        waitFor { primaryText().lowercase() == "this is a good idea" && runtime.invocationCount > before }
        val diagnostics = CleverKeysService.getInstance()!!.getOpenJevDiagnostics()
        assertTrue("live keyboard must have accepted a real neural result", diagnostics.acceptedResultCount > 0)
        recordMetrics("last_key_to_verified_replacement", listOf((SystemClock.elapsedRealtimeNanos() - finalKeyStarted) / 1e6))
        tap(context.getString(R.string.key_descr_backspace))
        waitFor { primaryText().lowercase() == "this is a good ideaa" }
    }

    @Test
    fun genuineOnnxReplacementSpaceAndUndo() {
        val runtime = tribixbite.cleverkeys.openjev.runtime.OpenJevRuntime.get(context)
        runtime.score("This is a good ideaa", listOf("the final typed word should remain ideaa", "the intended final typed word is idea"))
        enableNeural()
        type("this is a good ideaa")
        waitFor { primaryText().lowercase() == "this is a good idea" }
        tap(context.getString(R.string.key_descr_space))
        waitFor { primaryText().lowercase() == "this is a good idea " }
        tap(context.getString(R.string.key_descr_backspace))
        waitFor { primaryText().lowercase() == "this is a good ideaa " }
    }

    @Test
    fun genuineOnnxReplacementRespectsDisabledBackspaceUndo() {
        val runtime = tribixbite.cleverkeys.openjev.runtime.OpenJevRuntime.get(context)
        runtime.score("This is a good ideaa", listOf("the final typed word should remain ideaa", "the intended final typed word is idea"))
        enableNeural()
        assertTrue(prefs.edit().putBoolean("backspace_undo_autocorrect", false).commit())
        instrumentation.runOnMainSync { Config.globalConfig().refresh(context.resources, null) }
        type("this is a good ideaa")
        waitFor { primaryText().lowercase() == "this is a good idea" }
        tap(context.getString(R.string.key_descr_backspace))
        waitFor { primaryText().lowercase() == "this is a good ide" }
    }

    @Test
    fun genuineOnnxKeepPreventsBaselineSpaceCorrection() {
        val runtime = tribixbite.cleverkeys.openjev.runtime.OpenJevRuntime.get(context)
        runtime.score("Please pass teh", listOf("the final typed word should remain teh", "the intended final typed word is the"))
        enableNeural()
        type("please pass teh")
        val before = CleverKeysService.getInstance()!!.getOpenJevDiagnostics().completedInferenceCount
        waitFor { CleverKeysService.getInstance()!!.getOpenJevDiagnostics().completedInferenceCount > before }
        assertEquals("please pass teh", primaryText().lowercase())
        tap(context.getString(R.string.key_descr_space))
        waitFor { primaryText().lowercase() == "please pass teh " }
    }

    @Test
    fun enabledModelPasswordAndPrivateFieldsNeverInfer() {
        val runtime = tribixbite.cleverkeys.openjev.runtime.OpenJevRuntime.get(context)
        runtime.score("This is a good ideaa", listOf("the final typed word should remain ideaa", "the intended final typed word is idea"))
        enableNeural(awaitDictionary = false)
        scenario.onActivity {
            it.primary.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).restartInput(it.primary)
        }
        waitFor { (CleverKeysService.getInstance()?.currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val before = runtime.invocationCount
        type("ideaa")
        SystemClock.sleep(2_000)
        assertEquals("sensitive field must bypass ONNX entirely", before, runtime.invocationCount)
        assertEquals("ideaa", primaryText())
        scenario.onActivity {
            it.secondary.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            it.focus(it.secondary)
        }
        waitFor { CleverKeysService.getInstance()?.currentInputEditorInfo?.fieldId == android.R.id.edit + 1 }
        type("ideaa")
        SystemClock.sleep(2_000)
        assertEquals("private field must bypass ONNX entirely", before, runtime.invocationCount)
        assertEquals("ideaa", secondaryText().lowercase())
        scenario.onActivity {
            it.secondary.text.clear()
            it.secondary.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).restartInput(it.secondary)
        }
        waitFor { (CleverKeysService.getInstance()?.currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER }
        SystemClock.sleep(100)
        type("1234")
        SystemClock.sleep(2_000)
        assertEquals("PIN field must bypass ONNX entirely", before, runtime.invocationCount)
        assertEquals("1234", secondaryText())
    }

    @Test
    fun missingModelKeepsOrdinaryTypingAndBaselineCorrectionAvailable() {
        val runtime = tribixbite.cleverkeys.openjev.runtime.OpenJevRuntime.get(context)
        val model = java.io.File(context.filesDir, "openjev/model")
        val parked = java.io.File(context.filesDir, "openjev/model-missing-validation")
        assertTrue("verified model must exist before missing-model test", model.isDirectory)
        assertFalse("temporary model backup must not already exist", parked.exists())
        runtime.close()
        assertTrue("park weights atomically without duplicate copies", model.renameTo(parked))
        try {
            enableNeural(awaitDictionary = false)
            val before = runtime.invocationCount
            type("this is a good ideaa")
            SystemClock.sleep(2_000)
            assertEquals("missing model cannot claim successful inference", before, runtime.invocationCount)
            assertEquals("this is a good ideaa", primaryText().lowercase())
            tap(context.getString(R.string.key_descr_space))
            waitFor { primaryText().lowercase() == "this is a good idea " }
        } finally {
            runtime.close()
            assertTrue("restore exact verified model bytes", parked.renameTo(model))
        }
    }

    @Test
    fun enabledModelFieldSwitchInvalidatesPendingCorrection() {
        val runtime = tribixbite.cleverkeys.openjev.runtime.OpenJevRuntime.get(context)
        runtime.score("This is a good ideaa", listOf("the final typed word should remain ideaa", "the intended final typed word is idea"))
        enableNeural()
        type("this is a good ideaa")
        scenario.onActivity { it.focus(it.secondary) }
        waitFor { CleverKeysService.getInstance()?.currentInputEditorInfo?.fieldId == android.R.id.edit + 1 }
        type("hello")
        SystemClock.sleep(2_000)
        assertEquals("pending correction must not write into old editor", "this is a good ideaa", primaryText().lowercase())
        assertEquals("pending correction must not write into new editor", "hello", secondaryText().lowercase())
    }

    @Test
    fun enabledModelRapidEditingAndCursorMoveInvalidatesPendingCorrection() {
        val runtime = tribixbite.cleverkeys.openjev.runtime.OpenJevRuntime.get(context)
        runtime.score("This is a good ideaa", listOf("the final typed word should remain ideaa", "the intended final typed word is idea"))
        enableNeural()
        val stablePrefix = "this is a good idea ".repeat(10)
        val expected = stablePrefix + "this is a good ideaa"
        type(expected)
        assertEquals("long continuous typing must retain every character", expected, primaryText().lowercase())
        // Move before the delayed word decision starts, then edit through actual IME touches.
        scenario.onActivity { it.primary.setSelection(0) }
        SystemClock.sleep(100)
        tap("x")
        waitFor { primaryText().lowercase() == "x" + expected }
        tap(context.getString(R.string.key_descr_backspace))
        waitFor { primaryText().lowercase() == expected }
        SystemClock.sleep(2_000)
        assertEquals("obsolete neural result must not edit the old suffix", expected, primaryText().lowercase())
        scenario.onActivity { assertEquals("selection must remain at the manually edited position", 0, it.primary.selectionStart) }
        recordMetrics("enabled_neural_keyboard_touch", touchDurationsMs)
    }

    @Test
    fun localChatSnippetRequiresExplicitTouchAndCommitsLiteralText() {
        val phrase = "Thanks"
        assertTrue(prefs.edit().putBoolean("openjev_enabled", true)
            .putBoolean("openjev_context_suggestions", true)
            .putBoolean("openjev_neural_autocorrect", false)
            .putString("openjev_chat_snippet", phrase).commit())
        instrumentation.runOnMainSync { Config.globalConfig().refresh(context.resources, null) }
        scenario.onActivity {
            it.primary.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE
            (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).restartInput(it.primary)
        }
        waitFor {
            val bar = suggestionBar()
            bar?.getCurrentSuggestions()?.contains(phrase) == true &&
                bar.getMetaForSuggestion(phrase)?.origin == SuggestionOrigin.LOCAL_SNIPPET
        }
        assertEquals("snippet must not insert automatically", "", primaryText())
        clickSnippet(phrase)
        waitFor { primaryText() == phrase }
        tap("x")
        waitFor { primaryText() == "Thanksx" }
        val field = CleverKeysService::class.java.getDeclaredField("_contextTracker").apply { isAccessible = true }
        val tracker = field.get(CleverKeysService.getInstance()) as PredictionContextTracker
        assertEquals("literal snippet joins following typed letters as the full word", "thanksx", tracker.getCurrentWord().lowercase())
    }

    @Test
    fun genericKnownWordBoundaryRunsRealContextInferenceWithoutInsertion() {
        val runtime = tribixbite.cleverkeys.openjev.runtime.OpenJevRuntime.get(context)
        runtime.score("Hey, see you soon!", listOf("casual chat", "formal email"))
        assertTrue(prefs.edit().putBoolean("openjev_enabled", true)
            .putBoolean("openjev_context_suggestions", true)
            .putBoolean("openjev_neural_autocorrect", false)
            .putString("openjev_chat_snippet", "Thanks")
            .putString("openjev_email_snippet", "Regards").commit())
        instrumentation.runOnMainSync { Config.globalConfig().refresh(context.resources, null) }
        scenario.onActivity {
            it.primary.inputType = InputType.TYPE_CLASS_TEXT
            (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).restartInput(it.primary)
        }
        waitFor { CleverKeysService.getInstance()?.currentInputEditorInfo?.inputType == InputType.TYPE_CLASS_TEXT }
        waitForPredictorReady()
        val predictor = livePredictor()!!
        assertTrue("first fixture word must be known in the active dictionary", predictor.isInDictionary("hello"))
        assertTrue("final fixture word must be known in the active dictionary", predictor.isInDictionary("friend"))
        val before = runtime.invocationCount
        val trackerField = CleverKeysService::class.java.getDeclaredField("_contextTracker").apply { isAccessible = true }
        val tracker = trackerField.get(CleverKeysService.getInstance()) as PredictionContextTracker
        val coherence = org.json.JSONArray()
        "hello friend ".forEachIndexed { index, character ->
            tap(if (character == ' ') context.getString(R.string.key_descr_space) else character.toString())
            val trailing = primaryText().substringAfterLast(' ')
            var tracked = ""
            instrumentation.runOnMainSync { tracked = tracker.getCurrentWord() }
            coherence.put(org.json.JSONObject().put("touch", index + 1).put("editor_token_length", trailing.length)
                .put("tracker_token_length", tracked.length).put("matches_editor", tracked.equals(trailing, true)))
        }
        val metricsFile = java.io.File(context.cacheDir, "openjev-ime-metrics.json")
        val metrics = if (metricsFile.exists()) org.json.JSONObject(metricsFile.readText()) else org.json.JSONObject()
        metrics.put("generic_touch_coherence", coherence)
        metricsFile.writeText(metrics.toString())
        waitFor { runtime.invocationCount > before }
        assertEquals("context inference cannot insert a snippet", "hello friend ", primaryText().lowercase())
        // Ambiguous context may abstain. Any saved phrase shown retains explicit-selection provenance.
        val bar = suggestionBar()
        for (phrase in listOf("Thanks", "Regards")) {
            if (bar?.getCurrentSuggestions()?.contains(phrase) == true) {
                assertEquals(SuggestionOrigin.LOCAL_SNIPPET, bar.getMetaForSuggestion(phrase)?.origin)
            }
        }
    }

    @Test
    fun explicitSnippetEndingInPriorAutocorrectWordPreservesCharacterBackspace() {
        val phrase = "the"
        assertTrue(prefs.edit().putBoolean("openjev_enabled", true)
            .putBoolean("openjev_context_suggestions", true).putBoolean("openjev_neural_autocorrect", false)
            .putString("openjev_chat_snippet", phrase).commit())
        instrumentation.runOnMainSync { Config.globalConfig().refresh(context.resources, null) }
        scenario.onActivity {
            it.primary.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE
            (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).restartInput(it.primary)
        }
        waitFor { CleverKeysService.getInstance()?.currentInputEditorInfo?.inputType == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE) }
        waitForPredictorReady()
        type("teh ")
        waitFor { primaryText().trim().lowercase() == "the" }
        assertNotEquals("autocorrect undo prompt keeps priority over saved phrases", SuggestionOrigin.LOCAL_SNIPPET,
            suggestionBar()?.getMetaForSuggestion(phrase)?.origin)
        type("hello ")
        waitFor { suggestionBar()?.getMetaForSuggestion(phrase)?.origin == SuggestionOrigin.LOCAL_SNIPPET }
        val prefix = primaryText()
        clickSnippet(phrase)
        waitFor { primaryText() == prefix + phrase }
        tap(context.getString(R.string.key_descr_backspace))
        waitFor { primaryText() == prefix + "th" }
    }

    private fun clickSnippet(phrase: String) {
        val bounds = Rect()
        instrumentation.runOnMainSync {
            val bar = suggestionBar() ?: error("suggestion bar missing")
            val field = SuggestionBar::class.java.getDeclaredField("suggestionViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val views = field.get(bar) as List<android.widget.TextView>
            val view = views.first { it.text.toString().contains(phrase) }
            assertTrue(view.getLocalVisibleRect(bounds))
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            bounds.offset(location[0], location[1])
        }
        tapBounds(bounds)
    }

    private fun suggestionBar(): SuggestionBar? {
        val service = CleverKeysService.getInstance() ?: return null
        val field = CleverKeysService::class.java.getDeclaredField("_suggestionBar").apply { isAccessible = true }
        return field.get(service) as? SuggestionBar
    }

    private fun enableNeural(awaitDictionary: Boolean = true) {
        assertTrue(prefs.edit().putBoolean("openjev_enabled", true)
            .putBoolean("openjev_neural_autocorrect", true)
            .putBoolean("openjev_context_suggestions", false).commit())
        instrumentation.runOnMainSync { Config.globalConfig().refresh(context.resources, null) }
        if (awaitDictionary) waitForPredictorReady()
    }

    private fun livePredictor(): Predictor? {
        val service = CleverKeysService.getInstance() ?: return null
        val handlerField = CleverKeysService::class.java.getDeclaredField("_suggestionHandler").apply { isAccessible = true }
        val coordinatorField = SuggestionHandler::class.java.getDeclaredField("predictionCoordinator").apply { isAccessible = true }
        return (coordinatorField.get(handlerField.get(service)) as PredictionCoordinator).getWordPredictor()
    }

    private fun waitForPredictorReady() = waitFor {
        var ready = false
        // isReady intentionally stays off the production Predictor interface.
        instrumentation.runOnMainSync { ready = (livePredictor() as? WordPredictor)?.isReady() == true }
        ready
    }

    private fun keyboard(): Keyboard2View? {
        val service = CleverKeysService.getInstance() ?: return null
        val field = CleverKeysService::class.java.getDeclaredField("_keyboardView")
        field.isAccessible = true
        return field.get(service) as? Keyboard2View
    }

    private fun tap(description: String) {
        val bounds = Rect()
        var found = false
        instrumentation.runOnMainSync {
            val view = keyboard() ?: error("IME view missing")
            val provider = view.accessibilityNodeProvider ?: error("IME accessibility provider missing")
            for (id in view.accessibilityVirtualViewIdsForTest()) {
                val node = provider.createAccessibilityNodeInfo(id) ?: continue
                if (node.contentDescription?.toString()?.equals(description, ignoreCase = true) == true) {
                    node.getBoundsInScreen(bounds)
                    found = true
                }
                node.recycle()
                if (found) break
            }
        }
        assertTrue("keyboard key must be present", found && !bounds.isEmpty)
        tapBounds(bounds)
    }

    private fun tapBounds(bounds: Rect) {
        val started = SystemClock.elapsedRealtimeNanos()
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
        touchDurationsMs.add((SystemClock.elapsedRealtimeNanos() - started) / 1e6)
    }

    /** Timing aggregates only: no editor content, candidate text, package name, or inference inputs. */
    private fun recordMetrics(name: String, samples: List<Double>) {
        val file = java.io.File(context.cacheDir, "openjev-ime-metrics.json")
        val root = if (file.exists()) org.json.JSONObject(file.readText()) else org.json.JSONObject()
        val sorted = samples.sorted()
        require(sorted.isNotEmpty())
        val median = (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
        root.put("android_api", android.os.Build.VERSION.SDK_INT).put("hardware", android.os.Build.HARDWARE)
            .put("measurement_scope", "actual IME touchscreen injection; includes instrumentation scheduling")
            .put(name, org.json.JSONObject().put("samples", sorted.size).put("p50_ms", median)
                .put("p95_ms", sorted[kotlin.math.ceil(sorted.size * 0.95).toInt() - 1]).put("max_ms", sorted.last()))
        file.writeText(root.toString())
    }

    private fun type(text: String) = text.forEach { tap(if (it == ' ') context.getString(R.string.key_descr_space) else it.toString()) }
    private fun primaryText(): String { var result = ""; scenario.onActivity { result = it.primary.text.toString() }; return result }
    private fun secondaryText(): String { var result = ""; scenario.onActivity { result = it.secondary.text.toString() }; return result }
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }
    private fun waitFor(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        val accepted = predicate()
        if (!accepted) recordFailureState()
        assertTrue("live IME condition timed out", accepted)
    }

    private fun recordFailureState() = runCatching {
        val state = org.json.JSONObject()
        instrumentation.runOnMainSync {
            val service = CleverKeysService.getInstance() ?: return@runOnMainSync
            val info = service.currentInputEditorInfo
            val config = Config.globalConfig()
            val diagnostic = service.getOpenJevDiagnostics()
            val handlerField = CleverKeysService::class.java.getDeclaredField("_suggestionHandler").apply { isAccessible = true }
            val handler = handlerField.get(service)
            fun handlerValue(name: String): Any? = SuggestionHandler::class.java.getDeclaredField(name).apply { isAccessible = true }.get(handler)
            val trackerField = CleverKeysService::class.java.getDeclaredField("_contextTracker").apply { isAccessible = true }
            val tracker = trackerField.get(service) as PredictionContextTracker
            val predictor = (handlerValue("predictionCoordinator") as PredictionCoordinator).getWordPredictor()
            val bar = suggestionBar()
            state.put("dictionary_predictor_present", predictor != null)
                .put("dictionary_hello_known", predictor?.isInDictionary("hello"))
                .put("dictionary_friend_known", predictor?.isInDictionary("friend"))
                .put("dictionary_hello_preserved", predictor?.autoCorrect("hello") == "hello")
                .put("dictionary_friend_preserved", predictor?.autoCorrect("friend") == "friend")
                .put("dictionary_prompt", bar?.getCurrentSuggestions()?.any { it.startsWith("dict_add:") } == true)
                .put("autocorrect_prompt", bar?.getCurrentSuggestions()?.any { bar.getMetaForSuggestion(it)?.origin == SuggestionOrigin.AUTOCORRECT } == true)
            (bar?.getCurrentSuggestions()?.map(Suggestion::parse)?.firstOrNull { it is Suggestion.AddToDictionary } as? Suggestion.AddToDictionary)?.let { prompt ->
                state.put("prompt_token_length", prompt.word.length)
                    .put("prompt_matches_hello", prompt.word.equals("hello", true))
                    .put("prompt_matches_friend", prompt.word.equals("friend", true))
                    .put("prompt_token_known", predictor?.isInDictionary(prompt.word))
            }
            val permitted = tribixbite.cleverkeys.openjev.context.OpenJevContextPolicy.permits(info, config.primary_language, config)
            state.put("input_type", info?.inputType).put("ime_options", info?.imeOptions)
                .put("enabled", config.openjev_enabled).put("snippets_enabled", config.openjev_context_suggestions)
                .put("neural_autocorrect", config.openjev_neural_autocorrect).put("config_version", config.snapshot.version)
                .put("english", config.primary_language == "en").put("secondary_language_active", config.active_secondary_language != null)
                .put("privacy_permits", permitted).put("tracker_word_length", tracker.getCurrentWordLength())
                .put("session", handlerValue("wordEditSession")).put("revision", handlerValue("openJevRevision"))
                .put("snippet_offer_present", handlerValue("snippetOffer") != null)
                .put("special_prompt", handlerValue("specialPromptActive"))
                .put("password_mode", handlerValue("isPasswordMode")).put("learning_allowed", handlerValue("fieldAllowsPersonalizedLearning"))
                .put("completed", diagnostic.completedInferenceCount).put("accepted", diagnostic.acceptedResultCount)
                .put("stale", diagnostic.staleDiscardCount).put("failures", diagnostic.failureCount)
                .put("model_invocations", tribixbite.cleverkeys.openjev.runtime.OpenJevRuntime.get(context).invocationCount)
                .put("runtime_error", tribixbite.cleverkeys.openjev.runtime.OpenJevRuntime.get(context).errorCode ?: "NONE")
            if (permitted) {
                val ic = service.currentInputConnection
                val receipt = ic?.let(EditorReadback::capture)
                state.put("readback_present", receipt != null).put("selection_start", receipt?.start).put("selection_end", receipt?.end)
                state.put("surrounding_before_length", receipt?.before?.length).put("surrounding_after_length", receipt?.after?.length)
                val keyhandler = handlerValue("keyeventhandler") as KeyEventHandler
                state.put("editor_owned", ic != null && info != null && keyhandler.isCurrentEditor(ic, info))
                handlerValue("snippetOffer")?.let { offer ->
                    fun offerValue(name: String): Any? = offer.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(offer)
                    val id = offerValue("id") as tribixbite.cleverkeys.openjev.OpenJevRequestId
                    state.put("offer_revision", id.revision).put("offer_configuration", id.configuration)
                        .put("offer_input_identity_matches", ic === offerValue("ic"))
                        .put("offer_info_identity_matches", info === offerValue("info"))
                        .put("offer_model_matches", offerValue("modelGeneration") == tribixbite.cleverkeys.openjev.runtime.OpenJevRuntime.currentModelGeneration)
                        .put("offer_receipt_matches", ic != null && (offerValue("editor") as EditorReadback).matches(ic))
                        .put("offer_context_matches", ic?.getTextBeforeCursor(192, 0)?.toString() == offerValue("contextText"))
                }
            }
        }
        val file = java.io.File(context.cacheDir, "openjev-ime-failure-metrics.json")
        val root = if (file.exists()) org.json.JSONArray(file.readText()) else org.json.JSONArray()
        while (root.length() >= 8) root.remove(0)
        root.put(state)
        file.writeText(root.toString())
    }
}

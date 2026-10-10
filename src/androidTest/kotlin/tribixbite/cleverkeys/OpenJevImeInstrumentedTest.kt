package tribixbite.cleverkeys

import android.content.SharedPreferences
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
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

    @Before
    fun setUp() {
        prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        savedPreferences = prefs.all.toMap()
        assertTrue(prefs.edit().putBoolean("openjev_enabled", false)
            .putBoolean("autocorrect_enabled", true)
            .putBoolean("backspace_undo_autocorrect", true)
            .putBoolean("haptic_enabled", false).commit())
        priorIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        priorEnabledImes = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
        shell("ime enable ${context.packageName}/tribixbite.cleverkeys.CleverKeysService")
        shell("ime set ${context.packageName}/tribixbite.cleverkeys.CleverKeysService")
        scenario = ActivityScenario.launch(OpenJevTestEditorActivity::class.java)
        waitFor { keyboard()?.isShown == true }
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
        type("this is a good ideaa")
        waitFor { primaryText().lowercase() == "this is a good idea" && runtime.invocationCount > before }
        val diagnostics = CleverKeysService.getInstance()!!.getOpenJevDiagnostics()
        assertTrue("live keyboard must have accepted a real neural result", diagnostics.acceptedResultCount > 0)
        tap(context.getString(R.string.key_descr_backspace))
        waitFor { primaryText().lowercase() == "this is a good ideaa" }
    }

    @Test
    fun enabledModelPasswordAndPrivateFieldsNeverInfer() {
        val runtime = tribixbite.cleverkeys.openjev.runtime.OpenJevRuntime.get(context)
        runtime.score("This is a good ideaa", listOf("the final typed word should remain ideaa", "the intended final typed word is idea"))
        enableNeural()
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

    private fun enableNeural() {
        assertTrue(prefs.edit().putBoolean("openjev_enabled", true)
            .putBoolean("openjev_neural_autocorrect", true)
            .putBoolean("openjev_context_suggestions", false).commit())
        instrumentation.runOnMainSync { Config.globalConfig().refresh() }
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
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }

    private fun type(text: String) = text.forEach { tap(if (it == ' ') context.getString(R.string.key_descr_space) else it.toString()) }
    private fun primaryText(): String { var result = ""; scenario.onActivity { result = it.primary.text.toString() }; return result }
    private fun secondaryText(): String { var result = ""; scenario.onActivity { result = it.secondary.text.toString() }; return result }
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
    }
    private fun waitFor(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("live IME condition timed out", predicate())
    }
}

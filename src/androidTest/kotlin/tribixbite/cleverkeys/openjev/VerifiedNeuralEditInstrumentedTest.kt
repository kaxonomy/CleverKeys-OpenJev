package tribixbite.cleverkeys.openjev

import android.text.Editable
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.EditorReadback

@RunWith(AndroidJUnit4::class)
class VerifiedNeuralEditInstrumentedTest {
    private class Editor(initial: String) : BaseInputConnection(
        View(InstrumentationRegistry.getInstrumentation().targetContext), true) {
        val buffer = Editable.Factory.getInstance().newEditable(initial).also { Selection.setSelection(it, it.length) }
        var writes = 0
        var reject = false
        var acknowledge = true
        override fun getEditable(): Editable = buffer
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText = ExtractedText().apply {
            text = buffer.toString(); startOffset = 0
            selectionStart = Selection.getSelectionStart(buffer); selectionEnd = Selection.getSelectionEnd(buffer)
        }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            writes++
            if (reject) return false
            super.commitText(text, newCursorPosition)
            return acknowledge
        }
    }

    @Test fun exactReplacementAndUndoPreserveSurroundingsWithoutAddingSpace() {
        val editor = Editor("this is a good ideaa")
        val before = EditorReadback.capture(editor)!!
        val replaced = VerifiedNeuralEdit.replace(editor, before, "ideaa", "idea", { true }, { true })!!
        assertEquals("this is a good idea", editor.buffer.toString())
        assertEquals(1, editor.writes)
        val restored = VerifiedNeuralEdit.replace(editor, replaced, "idea", "ideaa", { true }, { true })!!
        assertEquals("this is a good ideaa", editor.buffer.toString())
        assertEquals(before, restored)
        assertEquals(2, editor.writes)
    }

    @Test fun staleTextOrSelectionOrSessionNeverWrites() {
        for (mutation in 0..2) {
            val editor = Editor("this is ideaa")
            val before = EditorReadback.capture(editor)!!
            if (mutation == 0) editor.buffer.append("x")
            if (mutation == 1) Selection.setSelection(editor.buffer, 2)
            assertNull(VerifiedNeuralEdit.replace(editor, before, "ideaa", "idea", { mutation != 2 }, { true }))
            assertEquals(0, editor.writes)
        }
    }

    @Test fun rejectionMakesOneAttemptAndRestoresOnlyUnchangedSelection() {
        val editor = Editor("this is ideaa").apply { reject = true }
        val before = EditorReadback.capture(editor)!!
        assertNull(VerifiedNeuralEdit.replace(editor, before, "ideaa", "idea", { true }, { true }))
        assertEquals("this is ideaa", editor.buffer.toString())
        assertEquals(before, EditorReadback.capture(editor))
        assertEquals(1, editor.writes)
    }

    @Test fun falseAcknowledgementAfterAppliedWriteUsesExactReadbackWithoutRetry() {
        val editor = Editor("this is ideaa").apply { acknowledge = false }
        val before = EditorReadback.capture(editor)!!
        assertNotNull(VerifiedNeuralEdit.replace(editor, before, "ideaa", "idea", { true }, { true }))
        assertEquals("this is idea", editor.buffer.toString())
        assertEquals(1, editor.writes)
    }
}

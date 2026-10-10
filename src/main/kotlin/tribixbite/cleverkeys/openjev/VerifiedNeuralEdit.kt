package tribixbite.cleverkeys.openjev

import android.view.inputmethod.InputConnection
import tribixbite.cleverkeys.EditorReadback

/** One exact, non-retrying word replacement. Editor rejection never causes compensating text writes. */
object VerifiedNeuralEdit {
    fun replace(ic: InputConnection, expected: EditorReadback, original: String, replacement: String,
        ownsSession: () -> Boolean, prepare: (EditorReadback) -> Boolean): EditorReadback? = runCatching {
        if (!expected.collapsed || original.length !in 3..25 || replacement.length !in 3..25 ||
            !expected.before.endsWith(original) || expected.start < original.length ||
            !ownsSession() || !expected.matches(ic) || !prepare(expected) ||
            !ic.finishComposingText() || !ownsSession() || !expected.matches(ic)) return null
        val prefix = ic.getTextBeforeCursor(EditorReadback.GUARD_LENGTH + original.length, 0)?.toString() ?: return null
        val start = expected.start - original.length
        val selected = expected.copy(start = start, end = expected.start,
            before = prefix.dropLast(original.length).takeLast(EditorReadback.GUARD_LENGTH), selectedText = original)
        if (!prepare(selected) || !ownsSession()) return null
        runCatching { ic.setSelection(start, expected.start) }
        if (!ownsSession() || !selected.matches(ic)) return null
        val end = start + replacement.length
        val accepted = selected.copy(start = end, end = end,
            before = (selected.before + replacement).takeLast(EditorReadback.GUARD_LENGTH), selectedText = "")
        if (!prepare(accepted) || !ownsSession()) {
            restoreSelection(ic, selected, expected, ownsSession)
            return null
        }
        // A false/throwing acknowledgement can accompany an applied write: exact readback decides.
        runCatching { ic.commitText(replacement, 1) }
        if (!ownsSession() || !accepted.matches(ic)) {
            restoreSelection(ic, selected, expected, ownsSession)
            return null
        }
        accepted
    }.getOrNull()

    private fun restoreSelection(ic: InputConnection, selected: EditorReadback, old: EditorReadback,
        ownsSession: () -> Boolean) {
        if (ownsSession() && runCatching { selected.matches(ic) }.getOrDefault(false))
            runCatching { ic.setSelection(old.start, old.end) }
    }
}

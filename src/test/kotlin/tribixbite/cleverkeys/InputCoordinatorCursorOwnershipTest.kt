package tribixbite.cleverkeys

import android.os.Handler
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/** Delayed cursor reads may not replace a different editor's tracked word. */
class InputCoordinatorCursorOwnershipTest {
    private lateinit var coordinator: InputCoordinator
    private val handler = mockk<Handler>(relaxed = true)
    private val tracker = mockk<PredictionContextTracker>(relaxed = true)
    private val delegate = mockk<SuggestionHandler>(relaxed = true)
    private val connection = mockk<InputConnection>(relaxed = true)
    private val editor = mockk<EditorInfo>(relaxed = true)
    private val pending = slot<Runnable>()
    private var live: Pair<InputConnection?, EditorInfo?> = connection to editor

    @Before
    fun setUp() {
        coordinator = ObjenesisStd().newInstance(InputCoordinator::class.java)
        for ((name, value) in mapOf("syncHandler" to handler, "contextTracker" to tracker)) {
            InputCoordinator::class.java.getDeclaredField(name).apply { isAccessible = true }.set(coordinator, value)
        }
        coordinator.setCurrentInputProvider { live }
        coordinator.setCursorSyncDelegate(delegate)
        every { handler.postDelayed(capture(pending), any<Long>()) } returns true
        every { tracker.getCurrentWord() } returns ""
        every { tracker.getLastAutocorrectOriginalWord() } returns null
        every { tracker.getLastCommitSource() } returns PredictionSource.USER_TYPED_TAP
    }

    @Test
    fun replacedConnectionCannotRunQueuedCursorRead() {
        coordinator.onCursorMoved(7, connection, "en", editor)
        live = mockk<InputConnection>(relaxed = true) to editor
        pending.captured.run()
        verify(exactly = 0) { tracker.synchronizeWithCursor(any(), any(), any()) }
        verify(exactly = 0) { delegate.handleCursorParkPrediction(any(), any()) }
    }

    @Test
    fun replacedEditorCannotRunQueuedCursorRead() {
        coordinator.onCursorMoved(7, connection, "en", editor)
        live = connection to mockk<EditorInfo>(relaxed = true)
        pending.captured.run()
        verify(exactly = 0) { tracker.synchronizeWithCursor(any(), any(), any()) }
    }

    @Test
    fun currentEditorStillSynchronizesAndPredicts() {
        coordinator.onCursorMoved(7, connection, "en", editor)
        pending.captured.run()
        verify(exactly = 1) { tracker.synchronizeWithCursor(connection, "en", editor) }
        verify(exactly = 1) { delegate.handleCursorParkPrediction(editor, connection) }
    }

    @Test
    fun staleCallbackCannotAlterCurrentCursorBookkeeping() {
        live = connection to mockk<EditorInfo>(relaxed = true)
        coordinator.onCursorMoved(7, connection, "en", editor)
        verify(exactly = 0) { tracker.onCursorPositionChanged(any()) }
        verify(exactly = 0) { handler.postDelayed(any(), any<Long>()) }
    }
}

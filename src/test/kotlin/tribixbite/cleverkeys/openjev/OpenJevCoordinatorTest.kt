package tribixbite.cleverkeys.openjev

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class OpenJevCoordinatorTest {
    private fun request(revision: Long) = OpenJevTypingRequest(OpenJevRequestId(1, revision, 1, "en"), "come ", "tehre", listOf("tehre", "there"))

    @Test fun unavailableModelDoesNotDeliverOrBlockCaller() {
        val done = CountDownLatch(1)
        val coordinator = OpenJevCoordinator({ object : OpenJevScorer {
            override fun score(text: String, descriptions: List<String>): FloatArray { done.countDown(); error("missing model") }
            override fun close() {}
        } }, settleMillis = 0)
        val delivered = AtomicInteger()
        coordinator.submit(request(1)) { delivered.incrementAndGet() }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        coordinator.close()
        assertEquals(0, delivered.get())
    }

    @Test fun inFlightNativeCallFinishesButOnlyLatestRequestDeliversAndClosesAfterward() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val calls = AtomicInteger()
        val concurrent = AtomicInteger()
        val observed = mutableListOf<String>()
        val runtime = object : OpenJevScorer {
            override fun score(text: String, descriptions: List<String>): FloatArray {
                assertEquals(1, concurrent.incrementAndGet())
                if (calls.incrementAndGet() == 1) { started.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
                concurrent.decrementAndGet()
                return floatArrayOf(0f, 5f)
            }
            override fun close() { assertEquals(0, concurrent.get()); closed.countDown() }
        }
        val coordinator = OpenJevCoordinator({ runtime }, settleMillis = 0)
        coordinator.submit(request(1)) { synchronized(observed) { observed.add("old") } }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        for (revision in 2..30L) coordinator.submit(request(revision)) { synchronized(observed) { observed.add("new") }; delivered.countDown() }
        release.countDown()
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        coordinator.close()
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("new"), observed)
        assertEquals(2, calls.get())
        assertTrue(coordinator.diagnostics().staleDiscardCount >= 1)
    }

    @Test fun invalidatedOrLateResultsCannotDeliver() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val done = CountDownLatch(1)
        val coordinator = OpenJevCoordinator({ object : OpenJevScorer {
            override fun score(text: String, descriptions: List<String>): FloatArray { entered.countDown(); finish.await(2, TimeUnit.SECONDS); return floatArrayOf(0f, 1f) }
            override fun close() { done.countDown() }
        } }, settleMillis = 0, maxResultAgeMillis = 0)
        val delivered = AtomicInteger()
        coordinator.submit(request(1)) { delivered.incrementAndGet() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        coordinator.invalidate()
        assertFalse(coordinator.isCurrent(request(1).id))
        coordinator.close()
        finish.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(0, delivered.get())
        assertEquals(1, coordinator.diagnostics().staleDiscardCount)
    }
    @Test fun modelSwapInvalidatesAnAlreadyRunningScore() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val done = CountDownLatch(1)
        val version = java.util.concurrent.atomic.AtomicLong(1)
        val coordinator = OpenJevCoordinator({ object : OpenJevScorer {
            override fun score(text: String, descriptions: List<String>): FloatArray { entered.countDown(); finish.await(2, TimeUnit.SECONDS); return floatArrayOf(0f, 1f) }
            override fun close() { done.countDown() }
        } }, settleMillis = 0, modelGeneration = { version.get() })
        val delivered = AtomicInteger()
        coordinator.submit(request(1)) { delivered.incrementAndGet() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        version.incrementAndGet()
        assertFalse(coordinator.isCurrent(request(1).id))
        finish.countDown()
        coordinator.close()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(0, delivered.get())
        assertEquals(1, coordinator.diagnostics().staleDiscardCount)
    }

}

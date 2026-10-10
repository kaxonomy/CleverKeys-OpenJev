package tribixbite.cleverkeys.openjev

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Aggregate diagnostics contain no editor text or candidate spellings. */
data class OpenJevDiagnostics(
    val completedInferenceCount: Long,
    val acceptedResultCount: Long,
    val staleDiscardCount: Long,
    val failureCount: Long,
    val lastDecisionNanos: Long,
)

/** One native inference at a time, with one replaceable delayed request and no typing-path waits. */
class OpenJevCoordinator(
    private val scorerFactory: () -> OpenJevScorer,
    private val settleMillis: Long = 250,
    private val maxResultAgeMillis: Long = 5000,
    private val modelGeneration: () -> Long = { 0L },
) : AutoCloseable {
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "OpenJev-local").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }.apply { removeOnCancelPolicy = true; setExecuteExistingDelayedTasksAfterShutdownPolicy(false) }
    @Volatile private var activeRequest: OpenJevRequestId? = null
    @Volatile private var activeModelGeneration = 0L
    private val generation = AtomicLong()
    private val completed = AtomicLong()
    private val accepted = AtomicLong()
    private val discarded = AtomicLong()
    private val failures = AtomicLong()
    private val lastNanos = AtomicLong()
    private var pending: ScheduledFuture<*>? = null
    private var scorer: OpenJevScorer? = null // touched only by the single worker

    @Synchronized
    fun submit(
        request: OpenJevTypingRequest,
        classify: (OpenJevScorer) -> Unit = {},
        allowReplace: Boolean = false,
        delayMillis: Long = settleMillis,
        prepareRequest: (OpenJevTypingRequest) -> OpenJevTypingRequest = { it },
        deliver: (OpenJevDecision) -> Unit,
    ) {
        if (executor.isShutdown) return
        activeRequest = request.id
        val modelVersion = modelGeneration().also { activeModelGeneration = it }
        val version = generation.incrementAndGet()
        pending?.cancel(false) // ORT calls cannot be interrupted safely; their result becomes obsolete.
        val submittedAt = System.nanoTime()
        pending = executor.schedule({
            if (version != generation.get() || modelVersion != modelGeneration()) return@schedule
            val start = System.nanoTime()
            try {
                val runtime = scorer ?: scorerFactory().also { scorer = it }
                classify(runtime)
                if (version != generation.get() || modelVersion != modelGeneration()) { discarded.incrementAndGet(); return@schedule }
                val prepared = prepareRequest(request)
                if (version != generation.get() || modelVersion != modelGeneration()) { discarded.incrementAndGet(); return@schedule }
                val decision = OpenJevDecisionPolicy.decide(prepared,
                    runtime.score(prepared.precedingText + prepared.original, OpenJevDecisionPolicy.descriptions(prepared)), allowReplace)
                lastNanos.set(System.nanoTime() - start)
                completed.incrementAndGet()
                if (version != generation.get() || modelVersion != modelGeneration() ||
                    System.nanoTime() - submittedAt > TimeUnit.MILLISECONDS.toNanos(maxResultAgeMillis)) {
                    discarded.incrementAndGet()
                } else {
                    deliver(decision)
                }
            } catch (_: Exception) {
                // Missing/corrupt models and inference failure leave baseline suggestions available.
                failures.incrementAndGet()
            }
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    /** Shares the same bounded worker with candidate decisions; no second model/session. */
    @Synchronized
    fun submitContext(id: OpenJevRequestId, classify: (OpenJevScorer) -> Unit, deliver: () -> Unit) {
        if (executor.isShutdown) return
        activeRequest = id
        val modelVersion = modelGeneration().also { activeModelGeneration = it }
        val version = generation.incrementAndGet()
        pending?.cancel(false)
        pending = executor.schedule({
            if (version != generation.get() || modelVersion != modelGeneration()) return@schedule
            try {
                classify(scorer ?: scorerFactory().also { scorer = it })
                if (version == generation.get() && modelVersion == modelGeneration()) deliver()
                else discarded.incrementAndGet()
            } catch (_: Exception) { failures.incrementAndGet() }
        }, 1000, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun invalidate() {
        activeRequest = null
        generation.incrementAndGet()
        pending?.cancel(false)
        pending = null
    }

    fun isCurrent(id: OpenJevRequestId): Boolean = activeRequest == id && activeModelGeneration == modelGeneration() && !executor.isShutdown

    fun resultAccepted() { accepted.incrementAndGet() }
    fun resultDiscarded() { discarded.incrementAndGet() }
    fun diagnostics() = OpenJevDiagnostics(completed.get(), accepted.get(), discarded.get(), failures.get(), lastNanos.get())

    @Synchronized
    override fun close() {
        if (executor.isShutdown) return
        invalidate()
        // Closing ORT follows the currently running call on the same worker; never wait on UI.
        executor.execute { runCatching { scorer?.close() }; scorer = null }
        executor.shutdown()
    }
}

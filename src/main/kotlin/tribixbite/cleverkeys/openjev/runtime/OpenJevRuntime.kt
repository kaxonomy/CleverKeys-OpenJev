package tribixbite.cleverkeys.openjev.runtime

import android.content.Context
import android.os.Looper
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.OnnxJavaType
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicLong
import tribixbite.cleverkeys.openjev.OpenJevScorer

/** One process-wide, lazy CPU session. Call score from the bounded keyboard worker only. */
class OpenJevRuntime private constructor(context: Context) : OpenJevScorer, AutoCloseable {
    private val app = context.applicationContext
    private var session: OrtSession? = null
    private var tokenizer: OpenJevTokenizer? = null
    private var initializationFailed = false
    private val invocations = AtomicLong()
    val invocationCount: Long get() = invocations.get()
    @Volatile var lastInferenceMs: Double = 0.0
        private set
    @Volatile var loadMs: Double = 0.0
        private set
    val modelIdentity: String get() = "$CHECKPOINT@$REVISION"
    @Volatile var errorCode: String? = null
        private set
    @Volatile internal var ready = false
        private set

    override fun score(text: String, descriptions: List<String>): FloatArray = synchronized(lock) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "WORKER_REQUIRED" }
        try {
            if (session == null) {
                check(!initializationFailed) { "MODEL_LOAD_FAILED" }
                val loadStarted = System.nanoTime()
                val directory = OpenJevModelStore(app).directory
                OpenJevModelStore.verify(directory)
                val loadedTokenizer = OpenJevTokenizer(java.io.File(directory, "tokenizer.json"))
                val options = OrtSession.SessionOptions()
                try {
                    options.setIntraOpNumThreads(2)
                    options.setInterOpNumThreads(1)
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    session = OrtEnvironment.getEnvironment().createSession(java.io.File(directory, "model.onnx").absolutePath, options)
                } finally { options.close() }
                val active = session!!
                try {
                    check(active.inputInfo.keys == setOf("input_ids", "attention_mask"))
                    for (input in active.inputInfo.values) {
                        val info = input.info as? TensorInfo ?: error("INCOMPATIBLE_MODEL")
                        check(info.type == OnnxJavaType.INT64 && info.shape.size == 2)
                    }
                    check(active.outputInfo.size == 1)
                    val outputInfo = active.outputInfo.values.single().info as? TensorInfo ?: error("INCOMPATIBLE_MODEL")
                    check(outputInfo.type == OnnxJavaType.FLOAT && outputInfo.shape.size == 2 && outputInfo.shape[1] == 25L)
                } catch (failure: Exception) {
                    active.close()
                    session = null
                    throw failure
                }
                tokenizer = loadedTokenizer
                loadMs = (System.nanoTime() - loadStarted) / 1_000_000.0
            }
            val inferenceStarted = System.nanoTime()
            val ids = tokenizer!!.encode(text, descriptions)
            val env = OrtEnvironment.getEnvironment()
            val shape = longArrayOf(1, ids.size.toLong())
            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { input ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape).use { mask ->
                    session!!.run(mapOf("input_ids" to input, "attention_mask" to mask)).use { output ->
                        val tensor = output[0] as? OnnxTensor ?: error("INCOMPATIBLE_MODEL")
                        val buffer = tensor.floatBuffer ?: error("INCOMPATIBLE_MODEL")
                        check(buffer.remaining() == 25) { "INCOMPATIBLE_MODEL" }
                        val scores = FloatArray(descriptions.size) { buffer.get() }
                        check(scores.all { it.isFinite() }) { "INVALID_SCORES" }
                        lastInferenceMs = (System.nanoTime() - inferenceStarted) / 1_000_000.0
                        invocations.incrementAndGet()
                        ready = true
                        errorCode = null
                        scores
                    }
                }
            }
        } catch (failure: Throwable) {
            // Native loading failures and Java allocation failures must not disable ordinary typing.
            if (failure !is Exception && failure !is LinkageError && failure !is OutOfMemoryError) throw failure
            if (failure is LinkageError || failure is OutOfMemoryError) {
                session?.close()
                session = null
                tokenizer = null
            }
            ready = false
            if (session == null) initializationFailed = true
            errorCode = if (session == null) "MODEL_LOAD_FAILED" else "INFERENCE_FAILED"
            throw IllegalStateException(errorCode, failure)
        }
    }

    override fun close() = synchronized(lock) {
        session?.close()
        session = null
        tokenizer = null
        initializationFailed = false
        errorCode = null
        ready = false
    }

    companion object {
        const val CHECKPOINT = "heman10x/rlcd-modernbert-151m"
        const val REVISION = "8af2496eb63c7fa66d7d234e1f62629380030eb4"
        internal val lock = Any()
        private val creationLock = Any()
        @Volatile private var singleton: OpenJevRuntime? = null
        fun get(context: Context): OpenJevRuntime = singleton ?: synchronized(creationLock) {
            singleton ?: OpenJevRuntime(context).also { singleton = it }
        }
        internal fun invalidate() { singleton?.close() }
        internal fun status(): String? = singleton?.let { if (it.ready) "READY" else if (it.errorCode != null) "ERROR" else null }
    }
}

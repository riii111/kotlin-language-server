package org.javacs.kt.util

import org.javacs.kt.LOG
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class LspOperation {
    DEFINITION,
    HOVER,
    COMPLETION,
    REFERENCES
}

class LspExecutorPool : Closeable {
    private val async = AsyncExecutor()

    private val executors: Map<LspOperation, ExecutorService> = mapOf(
        LspOperation.DEFINITION to createDaemonExecutor("kls-definition"),
        LspOperation.HOVER to createDaemonExecutor("kls-hover"),
        LspOperation.COMPLETION to createDaemonExecutor("kls-completion"),
        LspOperation.REFERENCES to createDaemonExecutor("kls-references")
    )

    private fun createDaemonExecutor(name: String): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, name).apply { isDaemon = true }
        }

    /**
     * Execute a general async computation task.
     */
    fun <T> compute(task: () -> T): CompletableFuture<T> = async.compute(task)

    /**
     * Submit a task to a specific operation executor.
     */
    fun <T> submit(operation: LspOperation, task: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync({ task() }, executors[operation]!!)

    override fun close() {
        async.shutdown(awaitTermination = true)
        executors.values.forEach { it.shutdown() }
        executors.values.forEach { executor ->
            LOG.info("Awaiting executor termination...")
            val terminated = executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!terminated) {
                LOG.warn("Executor did not terminate within {} seconds, forcing shutdown", SHUTDOWN_TIMEOUT_SECONDS)
                executor.shutdownNow()
            }
        }
    }

    companion object {
        private const val SHUTDOWN_TIMEOUT_SECONDS = 30L
    }
}

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
        executors.values.forEach {
            LOG.info("Awaiting executor termination...")
            it.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
        }
    }
}

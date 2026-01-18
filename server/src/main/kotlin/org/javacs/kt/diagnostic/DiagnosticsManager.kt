package org.javacs.kt.diagnostic

import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import org.javacs.kt.Configuration
import org.javacs.kt.LOG
import org.javacs.kt.SourceFiles
import org.javacs.kt.util.Debouncer
import org.javacs.kt.util.describeURI
import org.javacs.kt.util.describeURIs
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import java.io.Closeable
import java.net.URI
import java.time.Duration

class DiagnosticsManager(
    debounceTimeMs: Long,
    private val config: Configuration,
    private val sourceFiles: SourceFiles,
    private val isClassPathReady: () -> Boolean
) : Closeable {
    private lateinit var client: LanguageClient

    var debouncer = Debouncer(Duration.ofMillis(debounceTimeMs))
        private set

    private val pendingFilesLock = Any()
    private val pendingFiles = mutableSetOf<URI>()

    val pendingFilesSnapshot: Set<URI>
        get() = synchronized(pendingFilesLock) { pendingFiles.toSet() }

    var lintCount = 0
        private set

    var beforeLintCallback: () -> Unit = {}

    private var lintAction: ((cancelCallback: () -> Boolean) -> Unit)? = null

    fun connect(client: LanguageClient) {
        this.client = client
    }

    fun updateDebounceTime(ms: Long) {
        debouncer = Debouncer(Duration.ofMillis(ms))
    }

    fun setLintAction(action: (cancelCallback: () -> Boolean) -> Unit) {
        this.lintAction = action
    }

    fun scheduleLint(uri: URI) {
        synchronized(pendingFilesLock) { pendingFiles.add(uri) }
        debouncer.schedule(::doLint)
    }

    fun lintImmediately(uri: URI) {
        synchronized(pendingFilesLock) { pendingFiles.add(uri) }
        debouncer.submitImmediately(::doLint)
    }

    fun waitForPendingTask() {
        debouncer.waitForPendingTask()
    }

    fun clearPending(): List<URI> = synchronized(pendingFilesLock) {
        pendingFiles.toList().also { pendingFiles.clear() }
    }

    private fun doLint(cancelCallback: () -> Boolean) {
        if (!isClassPathReady()) {
            LOG.info("Skipping lint - classpath not ready")
            return
        }

        val snapshot = synchronized(pendingFilesLock) { pendingFiles.toList() }
        LOG.info("Linting {}", describeURIs(snapshot))
        beforeLintCallback()
        lintAction?.invoke(cancelCallback)
        lintCount++
    }

    fun reportDiagnostics(compiled: Collection<URI>, kotlinDiagnostics: Diagnostics) {
        val langServerDiagnostics = kotlinDiagnostics
            .flatMap(::convertDiagnostic)
            .filter { config.diagnostics.enabled && it.second.severity <= config.diagnostics.level }
        val byFile = langServerDiagnostics.groupBy({ it.first }, { it.second })

        for ((uri, diagnostics) in byFile) {
            if (sourceFiles.isOpen(uri)) {
                client.publishDiagnostics(PublishDiagnosticsParams(uri.toString(), diagnostics))
                LOG.info("Reported {} diagnostics in {}", diagnostics.size, describeURI(uri))
            } else {
                LOG.info("Ignore {} diagnostics in {} because it's not open", diagnostics.size, describeURI(uri))
            }
        }

        val noErrors = compiled - byFile.keys
        for (file in noErrors) {
            clearDiagnostics(file)
            LOG.info("No diagnostics in {}", file)
        }

        lintCount++
    }

    fun clearDiagnostics(uri: URI) {
        client.publishDiagnostics(PublishDiagnosticsParams(uri.toString(), listOf()))
    }

    override fun close() {
        debouncer.shutdown(awaitTermination = true)
    }
}

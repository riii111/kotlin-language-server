package org.javacs.kt.index

import org.javacs.kt.ClassPathDiff
import org.javacs.kt.IndexingConfiguration
import org.javacs.kt.LOG
import org.javacs.kt.progress.Progress
import org.javacs.kt.util.AsyncExecutor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import java.io.Closeable
import java.nio.file.Path

/**
 * Service responsible for managing symbol index operations.
 * Extracted from SourcePath to separate indexing concerns.
 */
class IndexingService(
    private val index: SymbolIndex,
    private val indexingConfig: IndexingConfiguration
) : Closeable {
    private val indexAsync = AsyncExecutor()

    val isEnabled: Boolean
        get() = indexingConfig.enabled

    var progressFactory: Progress.Factory = Progress.Factory.None
        set(factory: Progress.Factory) {
            field = factory
            index.progressFactory = factory
        }

    /**
     * Returns the underlying SymbolIndex for direct access when needed.
     */
    fun getIndex(): SymbolIndex = index

    /**
     * Refresh workspace indexes with old and new declarations.
     * Called after compilation to update the index with changed declarations.
     * Uses lazy evaluation to avoid computing declarations when indexing is disabled.
     */
    fun refreshWorkspaceIndexes(
        oldDeclarationsProvider: () -> Sequence<DeclarationDescriptor>,
        newDeclarationsProvider: () -> Sequence<DeclarationDescriptor>,
        moduleId: String?
    ) = indexAsync.execute {
        if (isEnabled) {
            index.updateIndexes(oldDeclarationsProvider(), newDeclarationsProvider(), moduleId)
        }
    }

    /**
     * Refresh dependency indexes from external dependencies.
     * Uses lazy evaluation to avoid computing declarations when indexing is disabled.
     */
    fun refreshDependencyIndexes(
        module: ModuleDescriptor,
        workspaceDeclarationsProvider: () -> Sequence<DeclarationDescriptor>,
        buildFileVersion: Long,
        skipIfValid: Boolean,
        batchSize: Int
    ) = indexAsync.execute {
        if (isEnabled) {
            index.refresh(module, workspaceDeclarationsProvider(), buildFileVersion, skipIfValid, batchSize)
        }
    }

    /**
     * Refresh dependency indexes incrementally based on classpath diff.
     */
    fun refreshDependencyIndexesIncrementally(
        diff: ClassPathDiff,
        module: ModuleDescriptor?
    ) = indexAsync.execute {
        if (!isEnabled) return@execute

        if (module == null) {
            LOG.warn("No module available for incremental indexing")
            return@execute
        }

        val removedJars = diff.removed.map { it.compiledJar }
        val addedJars = diff.added.map { it.compiledJar }

        if (removedJars.isNotEmpty()) {
            index.removeSymbolsFromJars(removedJars)
        }

        if (addedJars.isNotEmpty()) {
            val jarScanner = JarScanner()
            val packageToJarsMap = jarScanner.buildPackageToJarsMap(addedJars)
            index.indexJars(addedJars, module, packageToJarsMap, jarScanner)
        }
    }

    /**
     * Remove symbols for the given JAR files.
     */
    fun removeSymbolsForFiles(jars: List<Path>) = indexAsync.execute {
        if (isEnabled && jars.isNotEmpty()) {
            index.removeSymbolsFromJars(jars)
        }
    }

    override fun close() {
        indexAsync.shutdown(awaitTermination = true)
    }
}

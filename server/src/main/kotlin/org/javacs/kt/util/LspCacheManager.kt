package org.javacs.kt.util

import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.Location
import java.net.URI

/**
 * Centralized manager for all LSP response caches.
 */
class LspCacheManager {
    private val definitionCache = LspResponseCache<Location?>()
    private val hoverCache = LspResponseCache<Hover?>()
    private val completionCache = LspResponseCache<CompletionList>()
    private val referencesCache = LspResponseCache<List<Location>?>()

    // Definition cache operations
    fun getDefinition(uri: URI, line: Int, char: Int, version: Int): LspResponseCache.CacheResult<Location?>? =
        definitionCache.get(uri, line, char, version)

    fun putDefinition(uri: URI, line: Int, char: Int, version: Int, value: Location?) {
        definitionCache.put(uri, line, char, version, value)
    }

    // Hover cache operations
    fun getHover(uri: URI, line: Int, char: Int, version: Int): LspResponseCache.CacheResult<Hover?>? =
        hoverCache.get(uri, line, char, version)

    fun putHover(uri: URI, line: Int, char: Int, version: Int, value: Hover?) {
        hoverCache.put(uri, line, char, version, value)
    }

    // Completion cache operations
    fun getCompletion(uri: URI, line: Int, char: Int, version: Int): LspResponseCache.CacheResult<CompletionList>? =
        completionCache.get(uri, line, char, version)

    fun putCompletion(uri: URI, line: Int, char: Int, version: Int, value: CompletionList) {
        completionCache.put(uri, line, char, version, value)
    }

    // References cache operations
    fun getReferences(uri: URI, line: Int, char: Int, version: Int): LspResponseCache.CacheResult<List<Location>?>? =
        referencesCache.get(uri, line, char, version)

    fun putReferences(uri: URI, line: Int, char: Int, version: Int, value: List<Location>?) {
        referencesCache.put(uri, line, char, version, value)
    }

    /**
     * Invalidate all caches for a specific file.
     */
    fun invalidateFile(uri: URI) {
        definitionCache.invalidate(uri)
        hoverCache.invalidate(uri)
        completionCache.invalidate(uri)
        // Note: References cache is cleared entirely because cross-file references are affected
    }

    /**
     * Clear the references cache entirely.
     * This should be called when any file changes, as references can span files.
     */
    fun clearAllReferences() {
        referencesCache.clear()
    }

    /**
     * Clear all caches. Called when classpath changes or module assignments are updated.
     */
    fun clearAll() {
        definitionCache.clear()
        hoverCache.clear()
        completionCache.clear()
        referencesCache.clear()
    }
}

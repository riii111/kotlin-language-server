package org.javacs.kt.util

import java.net.URI

/**
 * A thread-safe LRU cache for LSP responses.
 * Cache key is composed of (URI, line, character, fileVersion).
 */
class LspResponseCache<T>(
    private val maxSize: Int = 200
) {
    data class CacheKey(
        val uri: URI,
        val line: Int,
        val character: Int,
        val fileVersion: Int
    )

    private val cache = object : LinkedHashMap<CacheKey, T>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableEntry<CacheKey, T>): Boolean = size > maxSize
    }

    @Synchronized
    fun get(uri: URI, line: Int, character: Int, fileVersion: Int): T? {
        val key = CacheKey(uri, line, character, fileVersion)
        return cache[key]
    }

    @Synchronized
    fun put(uri: URI, line: Int, character: Int, fileVersion: Int, value: T) {
        val key = CacheKey(uri, line, character, fileVersion)
        cache[key] = value
    }

    @Synchronized
    fun invalidate(uri: URI) {
        cache.keys.removeIf { it.uri == uri }
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    @Synchronized
    fun size(): Int = cache.size
}

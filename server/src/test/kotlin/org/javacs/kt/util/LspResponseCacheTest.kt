package org.javacs.kt.util

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.Test
import java.net.URI

class LspResponseCacheTest {

    @Test
    fun `stores and retrieves values`() {
        val cache = LspResponseCache<String>()
        val uri = URI.create("file:///test.kt")

        cache.put(uri, 1, 5, 1, "result")

        assertThat(cache.get(uri, 1, 5, 1)?.value, equalTo("result"))
    }

    @Test
    fun `returns null for missing entry`() {
        val cache = LspResponseCache<String>()
        val uri = URI.create("file:///test.kt")

        assertThat(cache.get(uri, 1, 5, 1), nullValue())
    }

    @Test
    fun `caches null values`() {
        val cache = LspResponseCache<String?>()
        val uri = URI.create("file:///test.kt")

        cache.put(uri, 1, 5, 1, null)

        val result = cache.get(uri, 1, 5, 1)
        assertThat(result, notNullValue())
        assertThat(result?.value, nullValue())
    }

    @Test
    fun `misses on different version`() {
        val cache = LspResponseCache<String>()
        val uri = URI.create("file:///test.kt")

        cache.put(uri, 1, 5, 1, "version1")

        assertThat(cache.get(uri, 1, 5, 2), nullValue())
    }

    @Test
    fun `misses on different position`() {
        val cache = LspResponseCache<String>()
        val uri = URI.create("file:///test.kt")

        cache.put(uri, 1, 5, 1, "pos1")

        assertThat(cache.get(uri, 1, 6, 1), nullValue())
        assertThat(cache.get(uri, 2, 5, 1), nullValue())
    }

    @Test
    fun `evicts oldest entries when full`() {
        val cache = LspResponseCache<String>(maxSize = 3)
        val uri = URI.create("file:///test.kt")

        cache.put(uri, 1, 0, 1, "first")
        cache.put(uri, 2, 0, 1, "second")
        cache.put(uri, 3, 0, 1, "third")
        cache.put(uri, 4, 0, 1, "fourth")

        assertThat(cache.get(uri, 1, 0, 1), nullValue())
        assertThat(cache.get(uri, 4, 0, 1)?.value, equalTo("fourth"))
        assertThat(cache.size(), equalTo(3))
    }

    @Test
    fun `uses LRU eviction`() {
        val cache = LspResponseCache<String>(maxSize = 3)
        val uri = URI.create("file:///test.kt")

        cache.put(uri, 1, 0, 1, "first")
        cache.put(uri, 2, 0, 1, "second")
        cache.put(uri, 3, 0, 1, "third")

        cache.get(uri, 1, 0, 1)

        cache.put(uri, 4, 0, 1, "fourth")

        assertThat(cache.get(uri, 1, 0, 1)?.value, equalTo("first"))
        assertThat(cache.get(uri, 2, 0, 1), nullValue())
        assertThat(cache.get(uri, 3, 0, 1)?.value, equalTo("third"))
    }

    @Test
    fun `invalidate removes entries for uri`() {
        val cache = LspResponseCache<String>()
        val uri1 = URI.create("file:///test1.kt")
        val uri2 = URI.create("file:///test2.kt")

        cache.put(uri1, 1, 0, 1, "file1-pos1")
        cache.put(uri1, 2, 0, 1, "file1-pos2")
        cache.put(uri2, 1, 0, 1, "file2-pos1")

        cache.invalidate(uri1)

        assertThat(cache.get(uri1, 1, 0, 1), nullValue())
        assertThat(cache.get(uri1, 2, 0, 1), nullValue())
        assertThat(cache.get(uri2, 1, 0, 1)?.value, equalTo("file2-pos1"))
    }

    @Test
    fun `clear removes all entries`() {
        val cache = LspResponseCache<String>()
        val uri = URI.create("file:///test.kt")

        cache.put(uri, 1, 0, 1, "first")
        cache.put(uri, 2, 0, 1, "second")

        cache.clear()

        assertThat(cache.size(), equalTo(0))
    }

    @Test
    fun `thread safety`() {
        val cache = LspResponseCache<Int>()
        val uri = URI.create("file:///test.kt")
        val threads = (1..10).map { threadId ->
            Thread {
                repeat(100) { i ->
                    cache.put(uri, threadId, i, 1, threadId * 1000 + i)
                    cache.get(uri, threadId, i, 1)
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertThat(cache.size(), lessThanOrEqualTo(200))
    }
}

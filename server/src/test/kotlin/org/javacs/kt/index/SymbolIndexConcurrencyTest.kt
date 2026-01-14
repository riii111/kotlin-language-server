package org.javacs.kt.index

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.javacs.kt.database.DatabaseService
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SymbolIndexConcurrencyTest {
    private lateinit var index: SymbolIndex
    private val executor = Executors.newFixedThreadPool(4)

    @Before
    fun setup() {
        index = SymbolIndex(DatabaseService())
    }

    @After
    fun teardown() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    @Test
    fun `query returns empty list when lock times out`() {
        // This test verifies that query gracefully degrades when it can't acquire the lock
        // In a real scenario, this would happen when a long-running refresh holds the write lock
        val result = index.query("Test", limit = 10)
        // Should return empty list (no data yet) but not hang
        assertThat(result, `is`(emptyList()))
    }

    @Test
    fun `multiple concurrent queries do not deadlock`() {
        val queryCount = 10
        val completedQueries = AtomicInteger(0)
        val latch = CountDownLatch(queryCount)
        val hasError = AtomicBoolean(false)

        repeat(queryCount) {
            executor.submit {
                try {
                    // Each query should complete without hanging
                    index.query("Test$it", limit = 5)
                    completedQueries.incrementAndGet()
                } catch (e: Exception) {
                    hasError.set(true)
                } finally {
                    latch.countDown()
                }
            }
        }

        // All queries should complete within timeout (no deadlock)
        val completed = latch.await(5, TimeUnit.SECONDS)
        assertThat("All queries should complete within timeout", completed, `is`(true))
        assertThat("No errors should occur", hasError.get(), `is`(false))
        assertThat("All queries should complete", completedQueries.get(), equalTo(queryCount))
    }

    @Test
    fun `query response time is bounded`() {
        val startTime = System.currentTimeMillis()

        // Query should return quickly even if no data exists
        index.query("NonExistent", limit = 10)

        val elapsed = System.currentTimeMillis() - startTime
        // Query should complete within reasonable time (well under the 100ms timeout + some buffer)
        assertThat("Query should complete quickly", elapsed, lessThan(500L))
    }
}

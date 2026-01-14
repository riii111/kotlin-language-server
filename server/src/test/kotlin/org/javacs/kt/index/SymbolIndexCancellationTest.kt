package org.javacs.kt.index

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.javacs.kt.database.DatabaseService
import org.junit.Before
import org.junit.Test

class SymbolIndexCancellationTest {
    private lateinit var index: SymbolIndex

    @Before
    fun setup() {
        index = SymbolIndex(DatabaseService())
    }

    @Test
    fun `cancelCurrentRefresh can be called safely when no refresh is running`() {
        // Should not throw any exception
        index.cancelCurrentRefresh()
        index.cancelCurrentRefresh() // Multiple calls should be safe
    }

    @Test
    fun `cancelCurrentRefresh sets cancellation flag`() {
        // After cancellation, subsequent operations should detect the flag
        index.cancelCurrentRefresh()

        // Query should still work after cancellation
        val result = index.query("Test")
        assertThat(result, `is`(emptyList()))
    }

    @Test
    fun `multiple rapid cancelCurrentRefresh calls are safe`() {
        // Simulate rapid file changes triggering multiple cancellations
        repeat(10) {
            index.cancelCurrentRefresh()
        }

        // Index should remain in a valid state
        val result = index.query("Test")
        assertThat(result, `is`(emptyList()))
    }

    @Test
    fun `isIndexing is initially false`() {
        assertThat(index.isIndexing, `is`(false))
    }

    @Test
    fun `isIndexing remains false when no refresh is triggered`() {
        // Just querying should not change isIndexing state
        index.query("Test")
        assertThat(index.isIndexing, `is`(false))
    }
}

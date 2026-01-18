package org.javacs.kt.diagnostic

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.javacs.kt.Configuration
import org.javacs.kt.SourceFiles
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DiagnosticsManagerConcurrencyTest {
    private lateinit var diagnosticsManager: DiagnosticsManager
    private val executor = Executors.newFixedThreadPool(8)

    @Before
    fun setup() {
        val config = Configuration()
        val sourceFiles = mock(SourceFiles::class.java)
        diagnosticsManager = DiagnosticsManager(
            debounceTimeMs = 50,
            config = config,
            sourceFiles = sourceFiles,
            isClassPathReady = { true }
        )
    }

    @After
    fun teardown() {
        diagnosticsManager.close()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    @Test
    fun `concurrent scheduleLint calls do not lose files`() {
        val fileCount = 100
        val latch = CountDownLatch(fileCount)
        val hasError = AtomicBoolean(false)

        repeat(fileCount) { i ->
            executor.submit {
                try {
                    diagnosticsManager.scheduleLint(URI("file:///test/file$i.kt"))
                } catch (e: Exception) {
                    hasError.set(true)
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertThat("All scheduleLint calls should complete", completed, `is`(true))
        assertThat("No errors should occur", hasError.get(), `is`(false))

        val pending = diagnosticsManager.clearPending()
        assertThat("All files should be in pending set", pending.size, equalTo(fileCount))
    }

    @Test
    fun `clearPending during concurrent adds does not throw`() {
        val addCount = 50
        val clearCount = 10
        val addLatch = CountDownLatch(addCount)
        val clearLatch = CountDownLatch(clearCount)
        val hasError = AtomicBoolean(false)
        val clearedCount = AtomicInteger(0)

        repeat(addCount) { i ->
            executor.submit {
                try {
                    diagnosticsManager.scheduleLint(URI("file:///test/file$i.kt"))
                    Thread.sleep(1)
                } catch (e: Exception) {
                    hasError.set(true)
                } finally {
                    addLatch.countDown()
                }
            }
        }

        repeat(clearCount) {
            executor.submit {
                try {
                    Thread.sleep(2)
                    val cleared = diagnosticsManager.clearPending()
                    clearedCount.addAndGet(cleared.size)
                } catch (e: Exception) {
                    hasError.set(true)
                } finally {
                    clearLatch.countDown()
                }
            }
        }

        val addCompleted = addLatch.await(5, TimeUnit.SECONDS)
        val clearCompleted = clearLatch.await(5, TimeUnit.SECONDS)

        assertThat("All add operations should complete", addCompleted, `is`(true))
        assertThat("All clear operations should complete", clearCompleted, `is`(true))
        assertThat("No errors should occur", hasError.get(), `is`(false))
    }

    @Test
    fun `lintImmediately is thread safe`() {
        val threadCount = 20
        val latch = CountDownLatch(threadCount)
        val hasError = AtomicBoolean(false)

        repeat(threadCount) { i ->
            executor.submit {
                try {
                    diagnosticsManager.lintImmediately(URI("file:///test/immediate$i.kt"))
                } catch (e: Exception) {
                    hasError.set(true)
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertThat("All lintImmediately calls should complete", completed, `is`(true))
        assertThat("No errors should occur", hasError.get(), `is`(false))
    }
}

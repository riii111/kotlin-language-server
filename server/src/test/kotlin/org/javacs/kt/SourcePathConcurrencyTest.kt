package org.javacs.kt

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.javacs.kt.database.DatabaseService
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SourcePathConcurrencyTest {
    private lateinit var sourcePath: SourcePath
    private lateinit var classPath: CompilerClassPath
    private lateinit var contentProvider: URIContentProvider
    private lateinit var databaseService: DatabaseService
    private val executor = Executors.newFixedThreadPool(8)

    @Before
    fun setup() {
        databaseService = DatabaseService()
        classPath = CompilerClassPath(
            CompilerConfiguration(),
            ScriptsConfiguration(),
            CodegenConfiguration(),
            databaseService
        )
        contentProvider = mock(URIContentProvider::class.java)
        `when`(contentProvider.contentOf(any())).thenReturn("// test content")
        sourcePath = SourcePath(
            classPath,
            contentProvider,
            IndexingConfiguration(),
            databaseService
        )
    }

    @After
    fun teardown() {
        classPath.close()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    @Test
    fun `concurrent put calls do not throw`() {
        val fileCount = 50
        val latch = CountDownLatch(fileCount)
        val hasError = AtomicBoolean(false)

        repeat(fileCount) { i ->
            executor.submit {
                try {
                    val uri = URI("file:///test/file$i.kt")
                    sourcePath.put(uri, "fun test$i() {}", null)
                } catch (e: Exception) {
                    hasError.set(true)
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        assertThat("All put calls should complete", completed, `is`(true))
        assertThat("No errors should occur", hasError.get(), `is`(false))
    }

    @Test
    fun `concurrent put and delete do not deadlock`() {
        val operationCount = 30
        val latch = CountDownLatch(operationCount * 2)
        val hasError = AtomicBoolean(false)

        repeat(operationCount) { i ->
            val uri = URI("file:///test/file$i.kt")

            executor.submit {
                try {
                    sourcePath.put(uri, "fun test$i() {}", null)
                } catch (e: Exception) {
                    hasError.set(true)
                } finally {
                    latch.countDown()
                }
            }

            executor.submit {
                try {
                    Thread.sleep(1)
                    sourcePath.delete(uri)
                } catch (e: Exception) {
                    hasError.set(true)
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        assertThat("All operations should complete without deadlock", completed, `is`(true))
        assertThat("No errors should occur", hasError.get(), `is`(false))
    }

    @Test
    fun `concurrent all() calls during modification do not throw`() {
        repeat(20) { i ->
            val uri = URI("file:///test/file$i.kt")
            sourcePath.put(uri, "fun test$i() {}", null)
        }

        val queryCount = 30
        val modifyCount = 10
        val latch = CountDownLatch(queryCount + modifyCount)
        val hasError = AtomicBoolean(false)

        repeat(queryCount) {
            executor.submit {
                try {
                    sourcePath.all()
                } catch (e: Exception) {
                    hasError.set(true)
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        repeat(modifyCount) { i ->
            executor.submit {
                try {
                    val uri = URI("file:///test/new$i.kt")
                    sourcePath.put(uri, "fun new$i() {}", null)
                } catch (e: Exception) {
                    hasError.set(true)
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        assertThat("All operations should complete", completed, `is`(true))
        assertThat("No errors should occur", hasError.get(), `is`(false))
    }

    @Test
    fun `concurrent content access is thread safe`() {
        val uri = URI("file:///test/file.kt")
        sourcePath.put(uri, "fun test() {}", null)

        val accessCount = 50
        val latch = CountDownLatch(accessCount)
        val hasError = AtomicBoolean(false)
        val successCount = AtomicInteger(0)

        repeat(accessCount) {
            executor.submit {
                try {
                    sourcePath.content(uri)
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    hasError.set(true)
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertThat("All content calls should complete", completed, `is`(true))
        assertThat("No errors should occur", hasError.get(), `is`(false))
        assertThat("All accesses should succeed", successCount.get(), equalTo(accessCount))
    }
}

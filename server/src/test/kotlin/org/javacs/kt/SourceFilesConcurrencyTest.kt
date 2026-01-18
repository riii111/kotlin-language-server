package org.javacs.kt

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.javacs.kt.database.DatabaseService
import org.javacs.kt.externalsources.ClassContentProvider
import org.javacs.kt.externalsources.ClassPathSourceArchiveProvider
import org.javacs.kt.externalsources.JdkSourceArchiveProvider
import org.javacs.kt.externalsources.CompositeSourceArchiveProvider
import org.javacs.kt.util.TemporaryDirectory
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SourceFilesConcurrencyTest {
    private lateinit var sourceFiles: SourceFiles
    private lateinit var sourcePath: SourcePath
    private lateinit var classPath: CompilerClassPath
    private lateinit var databaseService: DatabaseService
    private lateinit var tempDir: TemporaryDirectory
    private lateinit var workspaceDir: java.nio.file.Path
    private val executor = Executors.newFixedThreadPool(8)

    @Before
    fun setup() {
        databaseService = DatabaseService()
        tempDir = TemporaryDirectory()
        classPath = CompilerClassPath(
            CompilerConfiguration(),
            ScriptsConfiguration(),
            CodegenConfiguration(),
            databaseService
        )
        val sourceArchiveProvider = CompositeSourceArchiveProvider(
            JdkSourceArchiveProvider(classPath),
            ClassPathSourceArchiveProvider(classPath)
        )
        val classContentProvider = ClassContentProvider(
            ExternalSourcesConfiguration(),
            classPath,
            tempDir,
            sourceArchiveProvider
        )
        val contentProvider = URIContentProvider(classContentProvider)
        sourcePath = SourcePath(classPath, contentProvider, IndexingConfiguration(), databaseService)
        sourceFiles = SourceFiles(sourcePath, contentProvider, ScriptsConfiguration())

        workspaceDir = Files.createTempDirectory("kls-concurrency-test")
        sourceFiles.addWorkspaceRoot(workspaceDir)
    }

    @After
    fun teardown() {
        classPath.close()
        tempDir.close()
        workspaceDir.toFile().deleteRecursively()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun testFileUri(name: String): URI = workspaceDir.resolve(name).toUri()

    @Test
    fun `concurrent open calls do not throw`() {
        val fileCount = 50
        val latch = CountDownLatch(fileCount)
        val hasError = AtomicBoolean(false)

        repeat(fileCount) { i ->
            executor.submit {
                try {
                    val uri = testFileUri("file$i.kt")
                    sourceFiles.open(uri, "content $i", i)
                } catch (e: Exception) {
                    hasError.set(true)
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertThat("All open calls should complete", completed, `is`(true))
        assertThat("No errors should occur", hasError.get(), `is`(false))
    }

    @Test
    fun `concurrent isOpen calls are thread safe`() {
        val uri = testFileUri("file.kt")
        sourceFiles.open(uri, "content", 1)

        val queryCount = 100
        val latch = CountDownLatch(queryCount)
        val hasError = AtomicBoolean(false)
        val trueCount = AtomicInteger(0)

        repeat(queryCount) {
            executor.submit {
                try {
                    if (sourceFiles.isOpen(uri)) {
                        trueCount.incrementAndGet()
                    }
                } catch (e: Exception) {
                    hasError.set(true)
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertThat("All isOpen calls should complete", completed, `is`(true))
        assertThat("No errors should occur", hasError.get(), `is`(false))
        assertThat("All calls should return true", trueCount.get(), equalTo(queryCount))
    }

    @Test
    fun `concurrent open and close do not deadlock`() {
        val operationCount = 50
        val latch = CountDownLatch(operationCount * 2)
        val hasError = AtomicBoolean(false)

        repeat(operationCount) { i ->
            val uri = testFileUri("file$i.kt")

            executor.submit {
                try {
                    sourceFiles.open(uri, "content $i", i)
                } catch (e: Exception) {
                    hasError.set(true)
                } finally {
                    latch.countDown()
                }
            }

            executor.submit {
                try {
                    Thread.sleep(1)
                    sourceFiles.close(uri)
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
    fun `openFiles returns consistent snapshot`() {
        val fileCount = 20
        repeat(fileCount) { i ->
            val uri = testFileUri("file$i.kt")
            sourceFiles.open(uri, "content $i", i)
        }

        val queryCount = 50
        val latch = CountDownLatch(queryCount)
        val hasError = AtomicBoolean(false)

        repeat(queryCount) {
            executor.submit {
                try {
                    val files = sourceFiles.openFiles()
                    assertThat("openFiles should return a list", files, notNullValue())
                } catch (e: Exception) {
                    hasError.set(true)
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertThat("All openFiles calls should complete", completed, `is`(true))
        assertThat("No errors should occur", hasError.get(), `is`(false))
    }
}

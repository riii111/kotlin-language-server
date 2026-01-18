package org.javacs.kt.diagnostic

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.javacs.kt.Configuration
import org.javacs.kt.SourceFiles
import org.javacs.kt.SourcePath
import org.javacs.kt.URIContentProvider
import org.javacs.kt.ScriptsConfiguration
import org.javacs.kt.CompilerClassPath
import org.javacs.kt.CompilerConfiguration
import org.javacs.kt.CodegenConfiguration
import org.javacs.kt.IndexingConfiguration
import org.javacs.kt.ExternalSourcesConfiguration
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DiagnosticsManagerConcurrencyTest {
    private lateinit var diagnosticsManager: DiagnosticsManager
    private lateinit var classPath: CompilerClassPath
    private lateinit var sourcePath: SourcePath
    private lateinit var sourceFiles: SourceFiles
    private lateinit var databaseService: DatabaseService
    private lateinit var tempDir: TemporaryDirectory
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
        diagnosticsManager = DiagnosticsManager(
            debounceTimeMs = 50,
            config = Configuration(),
            sourceFiles = sourceFiles,
            isClassPathReady = { true }
        )
    }

    @After
    fun teardown() {
        diagnosticsManager.close()
        classPath.close()
        tempDir.close()
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

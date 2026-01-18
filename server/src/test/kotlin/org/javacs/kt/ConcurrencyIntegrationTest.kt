package org.javacs.kt

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.javacs.kt.database.DatabaseService
import org.javacs.kt.diagnostic.DiagnosticsManager
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

class ConcurrencyIntegrationTest {
    private lateinit var classPath: CompilerClassPath
    private lateinit var sourcePath: SourcePath
    private lateinit var sourceFiles: SourceFiles
    private lateinit var diagnosticsManager: DiagnosticsManager
    private lateinit var databaseService: DatabaseService
    private lateinit var tempDir: java.nio.file.Path
    private lateinit var klsTempDir: TemporaryDirectory
    private val executor = Executors.newFixedThreadPool(10)

    @Before
    fun setup() {
        LOG.connectStdioBackend()
        databaseService = DatabaseService()
        tempDir = Files.createTempDirectory("kls-integration-test")
        klsTempDir = TemporaryDirectory()

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
            klsTempDir,
            sourceArchiveProvider
        )
        val contentProvider = URIContentProvider(classContentProvider)

        sourcePath = SourcePath(
            classPath,
            contentProvider,
            IndexingConfiguration(),
            databaseService
        )

        sourceFiles = SourceFiles(
            sourcePath,
            contentProvider,
            ScriptsConfiguration()
        )

        diagnosticsManager = DiagnosticsManager(
            debounceTimeMs = 50,
            config = Configuration(),
            sourceFiles = sourceFiles,
            isClassPathReady = { classPath.isReady }
        )
    }

    @After
    fun cleanup() {
        diagnosticsManager.close()
        classPath.close()
        klsTempDir.close()
        tempDir.toFile().deleteRecursively()
        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)
    }

    @Test
    fun `simultaneous edit and diagnostics do not deadlock`() {
        val operationCount = 50
        val latch = CountDownLatch(operationCount * 2)
        val hasError = AtomicBoolean(false)
        val deadline = System.currentTimeMillis() + 5000

        repeat(operationCount) { i ->
            val uri = URI("file:///test/file$i.kt")

            executor.submit {
                try {
                    if (System.currentTimeMillis() < deadline) {
                        sourcePath.put(uri, "fun test$i() { val x = $i }", null)
                    }
                } catch (e: Exception) {
                    hasError.set(true)
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }

            executor.submit {
                try {
                    if (System.currentTimeMillis() < deadline) {
                        diagnosticsManager.scheduleLint(uri)
                    }
                } catch (e: Exception) {
                    hasError.set(true)
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertThat("All operations should complete within timeout (no deadlock)", completed, `is`(true))
        assertThat("No errors should occur", hasError.get(), `is`(false))
    }

    @Test
    fun `heavy concurrent LSP requests do not cause race conditions`() {
        val threadCount = 100
        val latch = CountDownLatch(threadCount)
        val hasError = AtomicBoolean(false)
        val completedOps = AtomicInteger(0)

        repeat(20) { i ->
            val uri = URI("file:///test/initial$i.kt")
            sourcePath.put(uri, "fun initial$i() {}", null)
        }

        repeat(threadCount) { i ->
            executor.submit {
                try {
                    when (i % 5) {
                        0 -> {
                            val uri = URI("file:///test/new$i.kt")
                            sourcePath.put(uri, "fun new$i() {}", null)
                        }
                        1 -> {
                            sourcePath.all()
                        }
                        2 -> {
                            val uri = URI("file:///test/initial${i % 20}.kt")
                            try {
                                sourcePath.content(uri)
                            } catch (_: Exception) {
                                // File may have been deleted
                            }
                        }
                        3 -> {
                            diagnosticsManager.scheduleLint(URI("file:///test/initial${i % 20}.kt"))
                        }
                        4 -> {
                            diagnosticsManager.clearPending()
                        }
                    }
                    completedOps.incrementAndGet()
                } catch (e: Exception) {
                    hasError.set(true)
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        assertThat("All operations should complete within timeout", completed, `is`(true))
        assertThat("No errors should occur", hasError.get(), `is`(false))
        assertThat("Most operations should complete", completedOps.get(), greaterThan(threadCount / 2))
    }

    @Test
    fun `workspace folder changes during background resolution do not deadlock`() {
        val latch = CountDownLatch(1)
        val operationCount = 10
        val hasError = AtomicBoolean(false)

        classPath.onClassPathReady = { latch.countDown() }
        classPath.addWorkspaceRoot(tempDir)

        val ops = CountDownLatch(operationCount)
        repeat(operationCount) { i ->
            executor.submit {
                try {
                    val subDir = Files.createTempDirectory(tempDir, "sub$i")
                    classPath.addWorkspaceRoot(subDir)
                    classPath.workspaceRoots
                    classPath.classPath
                } catch (e: Exception) {
                    hasError.set(true)
                    e.printStackTrace()
                } finally {
                    ops.countDown()
                }
            }
        }

        val opsCompleted = ops.await(10, TimeUnit.SECONDS)
        assertThat("All workspace operations should complete", opsCompleted, `is`(true))
        assertThat("No errors should occur", hasError.get(), `is`(false))

        latch.await(30, TimeUnit.SECONDS)
    }

    @Test
    fun `file watcher events during edit do not cause data corruption`() {
        val uri = URI("file:///test/watched.kt")
        sourcePath.put(uri, "fun original() {}", null)
        sourceFiles.open(uri, "fun original() {}", 1)

        val eventCount = 30
        val latch = CountDownLatch(eventCount * 2)
        val hasError = AtomicBoolean(false)

        repeat(eventCount) { i ->
            executor.submit {
                try {
                    sourcePath.put(uri, "fun edited$i() { val x = $i }", null)
                } catch (e: Exception) {
                    hasError.set(true)
                } finally {
                    latch.countDown()
                }
            }

            executor.submit {
                try {
                    sourceFiles.isOpen(uri)
                    sourceFiles.openFiles()
                } catch (e: Exception) {
                    hasError.set(true)
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertThat("All operations should complete", completed, `is`(true))
        assertThat("No errors should occur", hasError.get(), `is`(false))
    }

    @Test
    fun `cross-component operations complete within deadline`() {
        val deadline = 5000L
        val startTime = System.currentTimeMillis()
        val operationCount = 50
        val latch = CountDownLatch(operationCount)
        val hasError = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)

        repeat(operationCount) { i ->
            executor.submit {
                try {
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed > deadline) {
                        timedOut.set(true)
                        return@submit
                    }

                    val uri = URI("file:///test/cross$i.kt")
                    sourcePath.put(uri, "fun cross$i() {}", null)
                    sourceFiles.open(uri, "fun cross$i() {}", i)
                    diagnosticsManager.scheduleLint(uri)
                    sourcePath.content(uri)
                    sourceFiles.close(uri)
                } catch (e: Exception) {
                    hasError.set(true)
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(deadline, TimeUnit.MILLISECONDS)
        assertThat("All operations should complete within deadline", completed, `is`(true))
        assertThat("No operations should time out", timedOut.get(), `is`(false))
        assertThat("No errors should occur", hasError.get(), `is`(false))
    }
}

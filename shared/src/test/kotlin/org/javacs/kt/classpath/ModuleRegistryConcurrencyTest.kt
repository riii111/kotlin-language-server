package org.javacs.kt.classpath

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ModuleRegistryConcurrencyTest {
    private lateinit var registry: ModuleRegistry
    private lateinit var tempDir: Path
    private val executor = Executors.newFixedThreadPool(8)

    @Before
    fun setup() {
        registry = ModuleRegistry()
        tempDir = Files.createTempDirectory("module-registry-test")
    }

    @After
    fun teardown() {
        tempDir.toFile().deleteRecursively()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    @Test
    fun `concurrent register calls do not throw`() {
        val moduleCount = 50
        val latch = CountDownLatch(moduleCount)
        val hasError = AtomicBoolean(false)

        repeat(moduleCount) { i ->
            executor.submit {
                try {
                    val moduleDir = Files.createTempDirectory(tempDir, "module$i")
                    val moduleInfo = ModuleInfo(
                        name = "module$i",
                        rootPath = moduleDir,
                        sourceDirs = setOf(moduleDir),
                        classPath = emptySet()
                    )
                    registry.register(moduleInfo)
                } catch (e: Exception) {
                    hasError.set(true)
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertThat("All register calls should complete", completed, `is`(true))
        assertThat("No errors should occur", hasError.get(), `is`(false))
        assertThat("All modules should be registered", registry.size(), equalTo(moduleCount))
    }

    @Test
    fun `concurrent register and findModuleForFile do not throw`() {
        val moduleCount = 20
        val queryCount = 50
        val latch = CountDownLatch(moduleCount + queryCount)
        val hasError = AtomicBoolean(false)
        val moduleDirs = mutableListOf<Path>()

        repeat(moduleCount) { i ->
            val moduleDir = Files.createTempDirectory(tempDir, "module$i")
            moduleDirs.add(moduleDir)
        }

        repeat(moduleCount) { i ->
            executor.submit {
                try {
                    val moduleInfo = ModuleInfo(
                        name = "module$i",
                        rootPath = moduleDirs[i],
                        sourceDirs = setOf(moduleDirs[i]),
                        classPath = emptySet()
                    )
                    registry.register(moduleInfo)
                } catch (e: Exception) {
                    hasError.set(true)
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        repeat(queryCount) { i ->
            executor.submit {
                try {
                    val targetDir = moduleDirs[i % moduleDirs.size]
                    registry.findModuleForFile(targetDir.resolve("Test.kt"))
                } catch (e: Exception) {
                    hasError.set(true)
                    e.printStackTrace()
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
    fun `concurrent clear and register do not deadlock`() {
        val operationCount = 30
        val latch = CountDownLatch(operationCount * 2)
        val hasError = AtomicBoolean(false)

        repeat(operationCount) { i ->
            executor.submit {
                try {
                    val moduleDir = Files.createTempDirectory(tempDir, "module$i")
                    val moduleInfo = ModuleInfo(
                        name = "module$i",
                        rootPath = moduleDir,
                        sourceDirs = setOf(moduleDir),
                        classPath = emptySet()
                    )
                    registry.register(moduleInfo)
                } catch (e: Exception) {
                    hasError.set(true)
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }

            executor.submit {
                try {
                    Thread.sleep(1)
                    registry.clear()
                } catch (e: Exception) {
                    hasError.set(true)
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertThat("All operations should complete without deadlock", completed, `is`(true))
        assertThat("No errors should occur", hasError.get(), `is`(false))
    }

    @Test
    fun `concurrent read operations are thread safe`() {
        repeat(10) { i ->
            val moduleDir = Files.createTempDirectory(tempDir, "module$i")
            registry.register(ModuleInfo(
                name = "module$i",
                rootPath = moduleDir,
                sourceDirs = setOf(moduleDir),
                classPath = emptySet()
            ))
        }

        val queryCount = 100
        val latch = CountDownLatch(queryCount)
        val hasError = AtomicBoolean(false)
        val successCount = AtomicInteger(0)

        repeat(queryCount) { i ->
            executor.submit {
                try {
                    when (i % 5) {
                        0 -> registry.size()
                        1 -> registry.isEmpty()
                        2 -> registry.allModules()
                        3 -> registry.moduleNames()
                        4 -> registry.getModule("module${i % 10}")
                    }
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    hasError.set(true)
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertThat("All read operations should complete", completed, `is`(true))
        assertThat("No errors should occur", hasError.get(), `is`(false))
        assertThat("All reads should succeed", successCount.get(), equalTo(queryCount))
    }
}

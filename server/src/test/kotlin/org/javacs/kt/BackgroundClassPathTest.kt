package org.javacs.kt

import org.javacs.kt.database.DatabaseService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BackgroundClassPathTest {
    private lateinit var classPath: CompilerClassPath
    private lateinit var databaseService: DatabaseService
    private lateinit var tempDir: java.nio.file.Path

    @Before
    fun setup() {
        LOG.connectStdioBackend()
        databaseService = DatabaseService()
        tempDir = Files.createTempDirectory("kls-test")
        classPath = CompilerClassPath(
            CompilerConfiguration(),
            ScriptsConfiguration(),
            CodegenConfiguration(),
            databaseService
        )
    }

    @Test
    fun `initial state is PENDING`() {
        assertEquals(ClassPathResolutionState.PENDING, classPath.resolutionState)
        assertFalse(classPath.isReady)
    }

    @Test
    fun `addWorkspaceRoot transitions to RESOLVING`() {
        classPath.addWorkspaceRoot(tempDir)

        assertEquals(ClassPathResolutionState.RESOLVING, classPath.resolutionState)
    }

    @Test
    fun `addWorkspaceRoot returns immediately`() {
        val startTime = System.currentTimeMillis()
        classPath.addWorkspaceRoot(tempDir)
        val elapsedTime = System.currentTimeMillis() - startTime

        assertTrue("addWorkspaceRoot should return immediately (took ${elapsedTime}ms)", elapsedTime < 1000)
    }

    @Test
    fun `onClassPathReady callback is invoked after resolution`() {
        val latch = CountDownLatch(1)
        var callbackInvoked = false

        classPath.onClassPathReady = {
            callbackInvoked = true
            latch.countDown()
        }

        classPath.addWorkspaceRoot(tempDir)

        assertTrue("Callback should be invoked within 30 seconds", latch.await(30, TimeUnit.SECONDS))
        assertTrue("Callback should have been invoked", callbackInvoked)
        assertEquals(ClassPathResolutionState.READY, classPath.resolutionState)
        assertTrue(classPath.isReady)
    }

    @Test
    fun `isReady returns true only after resolution completes`() {
        val latch = CountDownLatch(1)

        assertFalse("isReady should be false before resolution", classPath.isReady)

        classPath.onClassPathReady = {
            latch.countDown()
        }

        classPath.addWorkspaceRoot(tempDir)
        assertFalse("isReady should be false during resolution", classPath.isReady)

        latch.await(30, TimeUnit.SECONDS)
        assertTrue("isReady should be true after resolution", classPath.isReady)
    }

    @Test
    fun `build script change triggers background resolution`() {
        val latch = CountDownLatch(1)
        classPath.addWorkspaceRoot(tempDir)

        val buildGradle = tempDir.resolve("build.gradle")
        Files.write(buildGradle, listOf("// test"))

        classPath.onClassPathReady = {
            latch.countDown()
        }

        classPath.changedOnDisk(buildGradle)

        assertEquals(ClassPathResolutionState.RESOLVING, classPath.resolutionState)
        assertTrue("Resolution should complete within 30 seconds", latch.await(30, TimeUnit.SECONDS))
    }

    @Test
    fun `concurrent resolution requests cancel previous`() {
        val completedResolutions = java.util.concurrent.atomic.AtomicInteger(0)
        val latch = CountDownLatch(1)

        classPath.onClassPathReady = {
            val count = completedResolutions.incrementAndGet()
            if (count >= 1) {
                latch.countDown()
            }
        }

        classPath.addWorkspaceRoot(tempDir)
        classPath.addWorkspaceRoot(tempDir)
        classPath.addWorkspaceRoot(tempDir)

        latch.await(30, TimeUnit.SECONDS)

        assertTrue("At least one resolution should complete", completedResolutions.get() >= 1)
        assertEquals(ClassPathResolutionState.READY, classPath.resolutionState)
    }
}

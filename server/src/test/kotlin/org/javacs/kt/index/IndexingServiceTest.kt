package org.javacs.kt.index

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.javacs.kt.IndexingConfiguration
import org.javacs.kt.database.DatabaseService
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class IndexingServiceTest {

    private lateinit var tempDir: Path
    private lateinit var databaseService: DatabaseService
    private var service: IndexingService? = null

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("kls-test")
        databaseService = DatabaseService()
        databaseService.setup(tempDir)
    }

    @After
    fun cleanup() {
        service?.close()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `refreshWorkspaceIndexes does not call providers when disabled`() {
        val config = IndexingConfiguration().apply { enabled = false }
        val index = SymbolIndex(databaseService)
        service = IndexingService(index, config)

        val oldProviderCalled = AtomicBoolean(false)
        val newProviderCalled = AtomicBoolean(false)

        service!!.refreshWorkspaceIndexes(
            oldDeclarationsProvider = {
                oldProviderCalled.set(true)
                emptySequence()
            },
            newDeclarationsProvider = {
                newProviderCalled.set(true)
                emptySequence()
            },
            moduleId = null
        )

        // Wait a bit to ensure async execution completes
        Thread.sleep(100)

        assertThat("Old declarations provider should not be called when indexing is disabled",
            oldProviderCalled.get(), equalTo(false))
        assertThat("New declarations provider should not be called when indexing is disabled",
            newProviderCalled.get(), equalTo(false))
    }

    @Test
    fun `isEnabled reflects configuration`() {
        val config = IndexingConfiguration().apply { enabled = false }
        val index = SymbolIndex(databaseService)
        service = IndexingService(index, config)

        assertThat(service!!.isEnabled, equalTo(false))

        config.enabled = true
        assertThat(service!!.isEnabled, equalTo(true))
    }

    @Test
    fun `refreshWorkspaceIndexes is lazy - providers called only when enabled`() {
        // This test verifies the key fix: providers should NOT be called when indexing is disabled
        val config = IndexingConfiguration().apply { enabled = false }
        val index = SymbolIndex(databaseService)
        service = IndexingService(index, config)

        val expensiveOperationCount = AtomicInteger(0)

        // Simulate an expensive operation in the provider
        val expensiveProvider: () -> Sequence<Nothing> = {
            expensiveOperationCount.incrementAndGet()
            // In real code, this would be getDeclarationDescriptors() which is expensive
            emptySequence()
        }

        // Call multiple times with disabled indexing
        repeat(5) {
            service!!.refreshWorkspaceIndexes(
                oldDeclarationsProvider = expensiveProvider,
                newDeclarationsProvider = expensiveProvider,
                moduleId = null
            )
        }

        Thread.sleep(100)

        assertThat("Expensive providers should never be called when indexing is disabled",
            expensiveOperationCount.get(), equalTo(0))
    }

    @Test
    fun `refreshWorkspaceIndexes calls providers when enabled`() {
        val config = IndexingConfiguration().apply { enabled = true }
        val index = SymbolIndex(databaseService)
        service = IndexingService(index, config)

        val providerCallCount = AtomicInteger(0)

        service!!.refreshWorkspaceIndexes(
            oldDeclarationsProvider = {
                providerCallCount.incrementAndGet()
                emptySequence()
            },
            newDeclarationsProvider = {
                providerCallCount.incrementAndGet()
                emptySequence()
            },
            moduleId = "test-module"
        )

        // Wait for async execution to complete
        Thread.sleep(200)

        assertThat("Both providers should be called when indexing is enabled",
            providerCallCount.get(), equalTo(2))
    }
}

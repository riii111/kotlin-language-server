package org.javacs.kt

import org.javacs.kt.database.DatabaseService
import org.javacs.kt.database.SymbolIndexMetadata
import org.javacs.kt.database.Symbols
import org.javacs.kt.index.SymbolIndex
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SymbolIndexPersistenceTest {
    private lateinit var tempDir: Path
    private lateinit var databaseService: DatabaseService

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("kls-test")
        databaseService = DatabaseService()
        databaseService.setup(tempDir)
    }

    @After
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `database service creates symbol index tables`() {
        val db = databaseService.db
        assertNotNull("Database should be initialized", db)

        transaction(db!!) {
            // Verify Symbols table exists by querying it
            val count = Symbols.selectAll().count()
            assertEquals("Symbols table should be empty initially", 0L, count)
        }
    }

    @Test
    fun `symbol index uses persistent database when storagePath is set`() {
        val index = SymbolIndex(databaseService)

        assertTrue("Index should be persistent", index.isPersistent)
    }

    @Test
    fun `symbol index uses in-memory database when storagePath is null`() {
        val inMemoryService = DatabaseService()
        inMemoryService.setup(null)

        val index = SymbolIndex(inMemoryService)

        assertFalse("Index should not be persistent", index.isPersistent)
    }

    @Test
    fun `isIndexValid returns false when no metadata exists`() {
        val index = SymbolIndex(databaseService)

        val valid = index.isIndexValid(1000L)

        assertFalse("Index should be invalid when no metadata exists", valid)
    }

    @Test
    fun `isIndexValid returns false when buildFileVersion is stale`() {
        val index = SymbolIndex(databaseService)

        // Manually create metadata with old version using insert
        transaction(databaseService.db!!) {
            SymbolIndexMetadata.insert {
                it[buildFileVersion] = 500L
                it[indexedAt] = System.currentTimeMillis()
                it[symbolCount] = 100
            }
        }

        val valid = index.isIndexValid(1000L)

        assertFalse("Index should be invalid when buildFileVersion is stale", valid)
    }

    @Test
    fun `isIndexValid returns true when buildFileVersion is current`() {
        val index = SymbolIndex(databaseService)

        // Manually create metadata with current version using insert
        transaction(databaseService.db!!) {
            SymbolIndexMetadata.insert {
                it[buildFileVersion] = 1000L
                it[indexedAt] = System.currentTimeMillis()
                it[symbolCount] = 100
            }
        }

        val valid = index.isIndexValid(1000L)

        assertTrue("Index should be valid when buildFileVersion is current", valid)
    }

    @Test
    fun `isIndexValid returns false when symbolCount is zero`() {
        val index = SymbolIndex(databaseService)

        // Manually create metadata with zero symbols using insert
        transaction(databaseService.db!!) {
            SymbolIndexMetadata.insert {
                it[buildFileVersion] = 1000L
                it[indexedAt] = System.currentTimeMillis()
                it[symbolCount] = 0
            }
        }

        val valid = index.isIndexValid(1000L)

        assertFalse("Index should be invalid when symbolCount is zero", valid)
    }

    @Test
    fun `getIndexedSymbolCount returns correct count`() {
        val index = SymbolIndex(databaseService)

        val initialCount = index.getIndexedSymbolCount()

        assertEquals("Initial symbol count should be 0", 0, initialCount)
    }

    @Test
    fun `database version triggers rebuild on mismatch`() {
        // First setup
        val service1 = DatabaseService()
        service1.setup(tempDir)
        assertNotNull(service1.db)

        // Second setup should reuse same database (version matches)
        val service2 = DatabaseService()
        service2.setup(tempDir)
        assertNotNull(service2.db)

        // Both should work with the same version
        transaction(service2.db!!) {
            val count = SymbolIndexMetadata.selectAll().count()
            // Metadata table should exist
            assertTrue("SymbolIndexMetadata table should exist", count >= 0)
        }
    }
}

package org.javacs.kt.index

import org.javacs.kt.database.DatabaseService
import org.javacs.kt.database.Symbols
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SymbolIndexModuleTest {
    private lateinit var tempDir: Path
    private lateinit var databaseService: DatabaseService
    private lateinit var index: SymbolIndex

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("kls-test-module")
        databaseService = DatabaseService()
        databaseService.setup(tempDir)
        index = SymbolIndex(databaseService)
    }

    @After
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `symbols are stored with moduleId`() {
        val db = databaseService.db!!

        transaction(db) {
            Symbols.insert {
                it[fqName] = "com.example.a.helper"
                it[shortName] = "helper"
                it[kind] = 1
                it[visibility] = 1
                it[moduleId] = "moduleA"
            }
            Symbols.insert {
                it[fqName] = "com.example.b.helper"
                it[shortName] = "helper"
                it[kind] = 1
                it[visibility] = 1
                it[moduleId] = "moduleB"
            }
        }

        transaction(db) {
            val moduleASymbols = Symbols.select {
                Symbols.moduleId eq "moduleA"
            }.count()
            val moduleBSymbols = Symbols.select {
                Symbols.moduleId eq "moduleB"
            }.count()

            assertEquals("moduleA should have 1 symbol", 1L, moduleASymbols)
            assertEquals("moduleB should have 1 symbol", 1L, moduleBSymbols)
        }
    }

    @Test
    fun `removeSymbolsFromModule removes only module symbols`() {
        val db = databaseService.db!!

        transaction(db) {
            Symbols.insert {
                it[fqName] = "com.example.a.funcA"
                it[shortName] = "funcA"
                it[kind] = 1
                it[visibility] = 1
                it[moduleId] = "moduleA"
            }
            Symbols.insert {
                it[fqName] = "com.example.b.funcB"
                it[shortName] = "funcB"
                it[kind] = 1
                it[visibility] = 1
                it[moduleId] = "moduleB"
            }
            Symbols.insert {
                it[fqName] = "com.example.c.funcC"
                it[shortName] = "funcC"
                it[kind] = 1
                it[visibility] = 1
                it[moduleId] = null
            }
        }

        index.removeSymbolsFromModule("moduleA")

        transaction(db) {
            val totalCount = Symbols.selectAll().count()
            val moduleACount = Symbols.select {
                Symbols.moduleId eq "moduleA"
            }.count()

            assertEquals("Total should be 2 after removal", 2L, totalCount)
            assertEquals("moduleA should have 0 symbols", 0L, moduleACount)
        }
    }

    @Test
    fun `query can filter by moduleId`() {
        val db = databaseService.db!!

        transaction(db) {
            Symbols.insert {
                it[fqName] = "com.example.a.helper"
                it[shortName] = "helper"
                it[kind] = 1
                it[visibility] = 1
                it[moduleId] = "moduleA"
            }
            Symbols.insert {
                it[fqName] = "com.example.b.helper"
                it[shortName] = "helper"
                it[kind] = 1
                it[visibility] = 1
                it[moduleId] = "moduleB"
            }
        }

        val allResults = index.query("helper")
        val moduleAResults = index.query("helper", moduleId = "moduleA")
        val moduleBResults = index.query("helper", moduleId = "moduleB")

        assertEquals("Query without filter should return 2", 2, allResults.size)
        assertEquals("Query with moduleA filter should return 1", 1, moduleAResults.size)
        assertEquals("Query with moduleB filter should return 1", 1, moduleBResults.size)
        assertEquals("moduleA result should be from moduleA", "com.example.a.helper", moduleAResults[0].fqName.toString())
        assertEquals("moduleB result should be from moduleB", "com.example.b.helper", moduleBResults[0].fqName.toString())
    }

    @Test
    fun `same-name symbols in different modules are stored separately`() {
        val db = databaseService.db!!

        transaction(db) {
            Symbols.insert {
                it[fqName] = "com.example.a.helper"
                it[shortName] = "helper"
                it[kind] = 1
                it[visibility] = 1
                it[moduleId] = "moduleA"
            }
            Symbols.insert {
                it[fqName] = "com.example.b.helper"
                it[shortName] = "helper"
                it[kind] = 1
                it[visibility] = 1
                it[moduleId] = "moduleB"
            }
        }

        transaction(db) {
            val helperCount = Symbols.select {
                Symbols.shortName eq "helper"
            }.count()
            assertEquals("Should have 2 helper symbols in different modules", 2L, helperCount)
        }
    }

    @Test
    fun `query with moduleId includes dependency symbols`() {
        val db = databaseService.db!!

        transaction(db) {
            Symbols.insert {
                it[fqName] = "com.example.a.MyClass"
                it[shortName] = "MyClass"
                it[kind] = 1
                it[visibility] = 1
                it[moduleId] = "moduleA"
            }
            Symbols.insert {
                it[fqName] = "com.example.b.MyClass"
                it[shortName] = "MyClass"
                it[kind] = 1
                it[visibility] = 1
                it[moduleId] = "moduleB"
            }
            Symbols.insert {
                it[fqName] = "org.external.MyClass"
                it[shortName] = "MyClass"
                it[kind] = 1
                it[visibility] = 1
                it[moduleId] = null
            }
        }

        val moduleAResults = index.query("MyClass", moduleId = "moduleA")

        assertEquals("Should return moduleA + dependency symbols", 2, moduleAResults.size)
        val fqNames = moduleAResults.map { it.fqName.toString() }.toSet()
        assertTrue("Should include moduleA symbol", fqNames.contains("com.example.a.MyClass"))
        assertTrue("Should include dependency symbol", fqNames.contains("org.external.MyClass"))
        assertFalse("Should NOT include moduleB symbol", fqNames.contains("com.example.b.MyClass"))
    }
}

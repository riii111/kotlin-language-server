package org.javacs.kt

import org.javacs.kt.classpath.ModuleInfo
import org.javacs.kt.database.DatabaseService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ModuleCompilerCacheTest {
    private lateinit var tempDir: Path
    private lateinit var compilerClassPath: CompilerClassPath

    @Before
    fun setup() {
        LOG.connectStdioBackend()
        tempDir = Files.createTempDirectory("kls-test-compiler-cache")

        val config = CompilerConfiguration()
        val scriptsConfig = ScriptsConfiguration()
        val codegenConfig = CodegenConfiguration()
        val databaseService = DatabaseService()
        databaseService.setup(null)

        compilerClassPath = CompilerClassPath(config, scriptsConfig, codegenConfig, databaseService)
    }

    @After
    fun cleanup() {
        compilerClassPath.close()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `getCompilerForModule returns default compiler when moduleId is null`() {
        val compiler = compilerClassPath.getCompilerForModule(null)

        assertSame("Should return default compiler", compilerClassPath.compiler, compiler)
    }

    @Test
    fun `getCompilerForModule returns default compiler when moduleRegistry is empty`() {
        val compiler = compilerClassPath.getCompilerForModule("someModule")

        assertSame("Should return default compiler when registry is empty", compilerClassPath.compiler, compiler)
    }

    @Test
    fun `getCompilerForModule creates module-specific compiler when module exists`() {
        val moduleSourceDir = tempDir.resolve("moduleA/src/main/kotlin")
        Files.createDirectories(moduleSourceDir)

        val moduleInfo = ModuleInfo(
            name = "moduleA",
            rootPath = tempDir.resolve("moduleA"),
            sourceDirs = setOf(moduleSourceDir),
            classPath = emptySet()
        )
        compilerClassPath.moduleRegistry.register(moduleInfo)

        val compiler = compilerClassPath.getCompilerForModule("moduleA")

        assertNotSame("Should create separate compiler for module", compilerClassPath.compiler, compiler)
    }

    @Test
    fun `getCompilerForModule reuses cached compiler for same module`() {
        val moduleSourceDir = tempDir.resolve("moduleA/src/main/kotlin")
        Files.createDirectories(moduleSourceDir)

        val moduleInfo = ModuleInfo(
            name = "moduleA",
            rootPath = tempDir.resolve("moduleA"),
            sourceDirs = setOf(moduleSourceDir),
            classPath = emptySet()
        )
        compilerClassPath.moduleRegistry.register(moduleInfo)

        val compiler1 = compilerClassPath.getCompilerForModule("moduleA")
        val compiler2 = compilerClassPath.getCompilerForModule("moduleA")

        assertSame("Should reuse cached compiler", compiler1, compiler2)
    }

    @Test
    fun `getCompilerForModule returns default when module not found in registry`() {
        val moduleSourceDir = tempDir.resolve("moduleA/src/main/kotlin")
        Files.createDirectories(moduleSourceDir)

        val moduleInfo = ModuleInfo(
            name = "moduleA",
            rootPath = tempDir.resolve("moduleA"),
            sourceDirs = setOf(moduleSourceDir),
            classPath = emptySet()
        )
        compilerClassPath.moduleRegistry.register(moduleInfo)

        val compiler = compilerClassPath.getCompilerForModule("nonExistentModule")

        assertSame("Should return default compiler for unknown module", compilerClassPath.compiler, compiler)
    }

    @Test
    fun `eldest compiler is evicted when cache exceeds max size`() {
        // MAX_MODULE_COMPILERS is 5, so create 6 modules
        val moduleNames = (1..6).map { "module$it" }

        for (name in moduleNames) {
            val moduleSourceDir = tempDir.resolve("$name/src/main/kotlin")
            Files.createDirectories(moduleSourceDir)
            val moduleInfo = ModuleInfo(
                name = name,
                rootPath = tempDir.resolve(name),
                sourceDirs = setOf(moduleSourceDir),
                classPath = emptySet()
            )
            compilerClassPath.moduleRegistry.register(moduleInfo)
        }

        // Access compilers for all 6 modules in order
        val compilers = moduleNames.map { compilerClassPath.getCompilerForModule(it) }

        // All should be different from default compiler
        compilers.forEach { compiler ->
            assertNotSame("Should be module-specific compiler", compilerClassPath.compiler, compiler)
        }

        // The first compiler (module1) should have been evicted
        // Getting it again should create a new instance
        val reacquiredCompiler = compilerClassPath.getCompilerForModule("module1")

        // The reacquired compiler should be a new instance (not the same as the original)
        assertNotSame("Evicted compiler should be recreated as new instance", compilers[0], reacquiredCompiler)
    }

    @Test
    fun `recently used compiler is not evicted`() {
        // MAX_MODULE_COMPILERS is 5, so create 6 modules
        val moduleNames = (1..6).map { "module$it" }

        for (name in moduleNames) {
            val moduleSourceDir = tempDir.resolve("$name/src/main/kotlin")
            Files.createDirectories(moduleSourceDir)
            val moduleInfo = ModuleInfo(
                name = name,
                rootPath = tempDir.resolve(name),
                sourceDirs = setOf(moduleSourceDir),
                classPath = emptySet()
            )
            compilerClassPath.moduleRegistry.register(moduleInfo)
        }

        // Access modules 1-5
        val compiler1 = compilerClassPath.getCompilerForModule("module1")
        compilerClassPath.getCompilerForModule("module2")
        compilerClassPath.getCompilerForModule("module3")
        compilerClassPath.getCompilerForModule("module4")
        compilerClassPath.getCompilerForModule("module5")

        // Access module1 again to make it recently used
        compilerClassPath.getCompilerForModule("module1")

        // Now access module6, which should evict module2 (the least recently used), not module1
        compilerClassPath.getCompilerForModule("module6")

        // module1 should still be the same instance
        val reacquiredCompiler1 = compilerClassPath.getCompilerForModule("module1")
        assertSame("Recently used compiler should not be evicted", compiler1, reacquiredCompiler1)
    }
}

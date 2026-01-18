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
}

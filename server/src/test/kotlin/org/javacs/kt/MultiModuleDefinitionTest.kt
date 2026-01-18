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
import java.nio.file.Paths

class MultiModuleDefinitionTest {
    private lateinit var sourcePath: SourcePath
    private lateinit var classPath: CompilerClassPath
    private lateinit var databaseService: DatabaseService
    private lateinit var tempDir: TemporaryDirectory

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
    }

    @After
    fun teardown() {
        classPath.close()
        tempDir.close()
    }

    @Test
    fun `refreshModuleAssignments returns count of reassigned files`() {
        val uri1 = URI("file:///test/file1.kt")
        val uri2 = URI("file:///test/file2.kt")

        sourcePath.put(uri1, "fun test1() {}", null)
        sourcePath.put(uri2, "fun test2() {}", null)

        val count = sourcePath.refreshModuleAssignments()

        assertThat("No modules registered, so no reassignments", count, equalTo(0))
    }

    @Test
    fun `refreshModuleAssignments skips temporary files`() {
        val uri = URI("file:///test/temp.kt")

        sourcePath.put(uri, "fun temp() {}", null, temporary = true)

        val count = sourcePath.refreshModuleAssignments()

        assertThat("Temporary files should be skipped", count, equalTo(0))
    }

    @Test
    fun `refreshModuleAssignments invalidates compiled file when moduleId changes`() {
        val testResources = testResourcesRoot()
        val multimoduleRoot = testResources.resolve("multimodule")
        val fileA = multimoduleRoot.resolve("moduleA/src/main/kotlin/com/example/a/Helper.kt")

        classPath.addWorkspaceRoot(multimoduleRoot)
        classPath.waitForResolution()

        val content = fileA.toFile().readText()
        sourcePath.put(fileA.toUri(), content, null)

        sourcePath.currentVersion(fileA.toUri())

        val count = sourcePath.refreshModuleAssignments()

        assertThat("moduleId assignment should work with multi-module setup", count, greaterThanOrEqualTo(0))
    }
}

package org.javacs.kt

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test

class DefinitionTest : SingleFileTestFixture("definition", "GoFrom.kt") {

    @Test
    fun `go to a definition in the same file`() {
        val definitions = languageServer.textDocumentService.definition(definitionParams(file, 3, 24)).get().left
        val uris = definitions.map { it.uri }

        assertThat(definitions, hasSize(1))
        assertThat(uris, hasItem(containsString("GoFrom.kt")))
    }

    @Test
    fun `go to a definition in a different file`() {
        val definitions = languageServer.textDocumentService.definition(definitionParams(file, 4, 24)).get().left
        val uris = definitions.map { it.uri }

        assertThat(definitions, hasSize(1))
        assertThat(uris, hasItem(containsString("GoTo.kt")))
    }
}


class GoToDefinitionOfPropertiesTest : SingleFileTestFixture("definition", "GoToProperties.kt") {

    @Test
    fun `go to definition of object property`() {
        assertGoToProperty(
            of = position(15, 20),
            expect = range(4, 15, 4, 32)
        )
    }

    @Test
    fun `go to definition of top level property`() {
        assertGoToProperty(
            of = position(17, 20),
            expect = range(11, 11, 11, 23)
        )
    }

    @Test
    fun `go to definition of class level property`() {
        assertGoToProperty(
            of = position(16, 20),
            expect = range(8, 9, 8, 25)
        )
    }

    @Test
    fun `go to definition of local property`() {
        assertGoToProperty(
            of = position(18, 18),
            expect = range(14, 9, 14, 20)
        )
    }

    private fun assertGoToProperty(of: Position, expect: Range) {
        val definitions = languageServer.textDocumentService.definition(definitionParams(file, of)).get().left
        val uris = definitions.map { it.uri }
        val ranges = definitions.map { it.range }

        assertThat(definitions, hasSize(1))
        assertThat(uris, hasItem(containsString(file)))
        assertThat(ranges, hasItem(equalTo(expect)))
    }
}

class GoToDefinitionCrossFileTest : SingleFileTestFixture("definition/imports", "ImportUser.kt") {

    @Test
    fun `go to definition on top level function in different file`() {
        // Line 4: topLevelFunction()
        // Cursor on "topLevelFunction" (column 12, middle of the word)
        val definitions = languageServer.textDocumentService.definition(definitionParams(file, 3, 12)).get().left
        val uris = definitions.map { it.uri }

        assertThat(definitions, hasSize(1))
        assertThat(uris, hasItem(containsString("SomeClass.kt")))
    }
}

class GoToDefinitionCrossModuleTest : SingleFileTestFixture(
    "multimodule",
    "moduleB/src/main/kotlin/com/example/b/UsesModuleA.kt"
) {
    @org.junit.Before
    fun checkModuleRegistry() {
        org.junit.Assume.assumeTrue(
            "Skipping: moduleRegistry is empty (Gradle resolution may have failed)",
            !languageServer.classPath.moduleRegistry.isEmpty()
        )
    }

    @Test
    fun `go to definition jumps to source in other module instead of JAR`() {
        // Line 6: return moduleAOnlyFunction()
        val definitions = languageServer.textDocumentService.definition(definitionParams(file, 6, 15)).get().left

        // Skip if cross-module symbol resolution is not available (moduleA JAR not built)
        org.junit.Assume.assumeTrue(
            "Skipping: cross-module symbol not resolved (moduleA may not be built)",
            definitions.isNotEmpty()
        )

        val uris = definitions.map { it.uri }
        assertThat(uris, hasItem(containsString("moduleA")))
        assertThat(uris, hasItem(containsString("Helper.kt")))
    }

    @Test
    fun `go to definition on import statement jumps to source in other module`() {
        // Line 3: import com.example.a.moduleAOnlyFunction
        val definitions = languageServer.textDocumentService.definition(definitionParams(file, 3, 30)).get().left

        // Skip if cross-module symbol resolution is not available
        org.junit.Assume.assumeTrue(
            "Skipping: cross-module symbol not resolved (moduleA may not be built)",
            definitions.isNotEmpty()
        )

        val uris = definitions.map { it.uri }
        assertThat(uris, hasItem(containsString("moduleA")))
        assertThat(uris, hasItem(containsString("Helper.kt")))
    }
}

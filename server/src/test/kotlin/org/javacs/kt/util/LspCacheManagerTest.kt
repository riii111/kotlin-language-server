package org.javacs.kt.util

import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Position
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.Test
import java.net.URI

class LspCacheManagerTest {

    private val uri = URI.create("file:///test.kt")
    private val uri2 = URI.create("file:///test2.kt")

    @Test
    fun `stores and retrieves definition`() {
        val manager = LspCacheManager()
        val location = Location("file:///target.kt", Range(Position(0, 0), Position(0, 10)))

        manager.putDefinition(uri, 1, 5, 1, location)

        val result = manager.getDefinition(uri, 1, 5, 1)
        assertThat(result, notNullValue())
        assertThat(result?.value, equalTo(location))
    }

    @Test
    fun `stores and retrieves hover`() {
        val manager = LspCacheManager()
        val hover = Hover()

        manager.putHover(uri, 1, 5, 1, hover)

        val result = manager.getHover(uri, 1, 5, 1)
        assertThat(result, notNullValue())
        assertThat(result?.value, equalTo(hover))
    }

    @Test
    fun `stores and retrieves completion`() {
        val manager = LspCacheManager()
        val completionList = CompletionList()

        manager.putCompletion(uri, 1, 5, 1, completionList)

        val result = manager.getCompletion(uri, 1, 5, 1)
        assertThat(result, notNullValue())
        assertThat(result?.value, equalTo(completionList))
    }

    @Test
    fun `stores and retrieves references`() {
        val manager = LspCacheManager()
        val locations = listOf(
            Location("file:///ref1.kt", Range(Position(0, 0), Position(0, 5))),
            Location("file:///ref2.kt", Range(Position(1, 0), Position(1, 5)))
        )

        manager.putReferences(uri, 1, 5, 1, locations)

        val result = manager.getReferences(uri, 1, 5, 1)
        assertThat(result, notNullValue())
        assertThat(result?.value, equalTo(locations))
    }

    @Test
    fun `invalidateFile removes definition, hover, and completion for uri`() {
        val manager = LspCacheManager()

        manager.putDefinition(uri, 1, 5, 1, Location())
        manager.putHover(uri, 1, 5, 1, Hover())
        manager.putCompletion(uri, 1, 5, 1, CompletionList())
        manager.putReferences(uri, 1, 5, 1, emptyList())

        manager.putDefinition(uri2, 1, 5, 1, Location())

        manager.invalidateFile(uri)

        assertThat(manager.getDefinition(uri, 1, 5, 1), nullValue())
        assertThat(manager.getHover(uri, 1, 5, 1), nullValue())
        assertThat(manager.getCompletion(uri, 1, 5, 1), nullValue())
        // References are NOT invalidated by invalidateFile because they span files
        // Use clearAllReferences() to clear references
        assertThat(manager.getReferences(uri, 1, 5, 1), notNullValue())

        // Other URI should not be affected
        assertThat(manager.getDefinition(uri2, 1, 5, 1), notNullValue())
    }

    @Test
    fun `clearAllReferences clears only references cache`() {
        val manager = LspCacheManager()

        manager.putDefinition(uri, 1, 5, 1, Location())
        manager.putHover(uri, 1, 5, 1, Hover())
        manager.putCompletion(uri, 1, 5, 1, CompletionList())
        manager.putReferences(uri, 1, 5, 1, emptyList())

        manager.clearAllReferences()

        assertThat(manager.getDefinition(uri, 1, 5, 1), notNullValue())
        assertThat(manager.getHover(uri, 1, 5, 1), notNullValue())
        assertThat(manager.getCompletion(uri, 1, 5, 1), notNullValue())
        assertThat(manager.getReferences(uri, 1, 5, 1), nullValue())
    }

    @Test
    fun `version mismatch returns null`() {
        val manager = LspCacheManager()

        manager.putDefinition(uri, 1, 5, 1, Location())

        assertThat(manager.getDefinition(uri, 1, 5, 2), nullValue())
    }

    @Test
    fun `position mismatch returns null`() {
        val manager = LspCacheManager()

        manager.putDefinition(uri, 1, 5, 1, Location())

        assertThat(manager.getDefinition(uri, 1, 6, 1), nullValue())
        assertThat(manager.getDefinition(uri, 2, 5, 1), nullValue())
    }
}

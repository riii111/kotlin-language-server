package org.javacs.kt

import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Ignore
import org.junit.Test

class HoverLiteralsTest : SingleFileTestFixture("hover", "Literals.kt") {
    @Test fun `string reference`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 3, 19)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("val stringLiteral: String"))
    }
}

class HoverFunctionReferenceTest : SingleFileTestFixture("hover", "FunctionReference.kt") {
    @Test fun `function reference`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 2, 45)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("fun isFoo(s: String): Boolean"))
    }
}

class HoverObjectReferenceTest : SingleFileTestFixture("hover", "ObjectReference.kt") {
    @Test fun `object reference`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 2, 7)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("object AnObject"))
    }

    @Test fun `object reference with incomplete method`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 6, 7)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("object AnObject"))
    }

    @Test fun `object reference with method`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 10, 7)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("object AnObject"))
    }

    @Test fun `object method`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 10, 15)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("fun doh(): Unit"))
    }
}

@Ignore
class HoverRecoverTest : SingleFileTestFixture("hover", "Recover.kt") {
    @Test fun `incrementally repair a single-expression function`() {
        replace(file, 2, 9, "\"Foo\"", "intFunction()")

        val hover = languageServer.textDocumentService.hover(hoverParams(file, 2, 11)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("fun intFunction(): Int"))
    }

    @Test fun `incrementally repair a block function`() {
        replace(file, 5, 13, "\"Foo\"", "intFunction()")

        val hover = languageServer.textDocumentService.hover(hoverParams(file, 5, 13)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("fun intFunction(): Int"))
    }
}

class HoverAcrossFilesTest : LanguageServerTestFixture("hover") {
    @Test fun `resolve across files`() {
        val from = "ResolveFromFile.kt"
        val to = "ResolveToFile.kt"
        open(from)
        open(to)

        val hover = languageServer.textDocumentService.hover(hoverParams(from, 3, 26)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("fun target(): Unit"))
    }
}

class HoverJdkSymbolTest : SingleFileTestFixture("hover", "JdkSymbol.kt") {
    @Test fun `hover on String length property`() {
        // line 3: "    val len = text.length", hover on "length" at column 20
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 3, 20)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("length"))
    }

    @Test fun `hover on List size property`() {
        // line 5: "    val size = list.size", hover on "size" at column 22
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 5, 22)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("size"))
    }
}

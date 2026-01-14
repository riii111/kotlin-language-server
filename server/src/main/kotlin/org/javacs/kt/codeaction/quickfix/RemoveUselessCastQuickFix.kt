package org.javacs.kt.codeaction.quickfix

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.javacs.kt.CompiledFile
import org.javacs.kt.index.SymbolIndex
import org.javacs.kt.position.offset
import org.javacs.kt.position.position
import org.javacs.kt.position.range
import org.javacs.kt.util.toPath

private val USELESS_CAST_DIAGNOSTICS = setOf("USELESS_CAST")

class RemoveUselessCastQuickFix : QuickFix {
    override fun compute(
        file: CompiledFile,
        index: SymbolIndex,
        range: Range,
        diagnostics: List<Diagnostic>
    ): List<Either<Command, CodeAction>> {
        val uri = file.parse.toPath().toUri().toString()
        val startCursor = offset(file.content, range.start)
        val endCursor = offset(file.content, range.end)

        // Use server-side Kotlin compiler diagnostics (more reliable than client-provided ones)
        val kotlinDiagnostics = file.compile.diagnostics

        return kotlinDiagnostics
            .filter { diag ->
                val factoryName = diag.factory.name
                val inRange = diag.textRanges.any { textRange ->
                    val diagStart = textRange.startOffset
                    val diagEnd = textRange.endOffset
                    // Compiler diagnostics may reference stale offsets when file content is out of sync
                    if (diagStart < 0 || diagEnd > file.content.length || diagStart >= diagEnd) {
                        return@any false
                    }
                    // For zero-length ranges (cursor position), check if on the same line
                    if (startCursor == endCursor) {
                        val diagRange = range(file.content, textRange)
                        diagRange.start.line == range.start.line
                    } else {
                        // For non-zero ranges, check overlap
                        diagStart <= endCursor && diagEnd >= startCursor
                    }
                }
                USELESS_CAST_DIAGNOSTICS.contains(factoryName) && inRange
            }
            .flatMap { kotlinDiag ->
                kotlinDiag.textRanges.mapNotNull { textRange ->
                    val start = textRange.startOffset
                    val end = textRange.endOffset
                    if (start < 0 || end > file.content.length || start >= end) return@mapNotNull null

                    val originalText = file.content.substring(start, end)

                    // Kotlin compiler highlights only "as Type" part, not the whole expression
                    // Handle both cases:
                    // 1. Text contains " as " (full expression like "error as Type")
                    // 2. Text starts with "as " (only cast part like "as Type")
                    val asIndex = originalText.indexOf(" as ")
                    val replacement: String
                    val editRange: Range

                    if (asIndex != -1) {
                        // Full expression case
                        replacement = originalText.substring(0, asIndex).trimEnd()
                        editRange = range(file.content, textRange)
                    } else if (originalText.startsWith("as ")) {
                        // Only "as Type" part - need to remove the space before "as" too
                        replacement = ""
                        val adjustedStart = if (start > 0 && file.content[start - 1] == ' ') start - 1 else start
                        editRange = Range(
                            position(file.content, adjustedStart),
                            position(file.content, end)
                        )
                    } else {
                        return@mapNotNull null
                    }

                    if (replacement.isEmpty() && asIndex != -1) {
                        return@mapNotNull null
                    }

                    val edit = TextEdit(editRange, replacement)

                    // Try to find matching client diagnostic to include in the CodeAction
                    val clientDiagnostic = diagnostics.find { clientDiag ->
                        clientDiag.range?.start?.line == editRange.start.line &&
                        (clientDiag.code?.left == "USELESS_CAST" ||
                         clientDiag.message?.contains("No cast needed") == true)
                    }

                    val codeAction = CodeAction()
                    codeAction.title = "Remove unnecessary cast"
                    codeAction.kind = CodeActionKind.QuickFix
                    if (clientDiagnostic != null) {
                        codeAction.diagnostics = listOf(clientDiagnostic)
                    }
                    codeAction.edit = WorkspaceEdit(mapOf(uri to listOf(edit)))

                    Either.forRight<Command, CodeAction>(codeAction)
                }
            }
    }
}

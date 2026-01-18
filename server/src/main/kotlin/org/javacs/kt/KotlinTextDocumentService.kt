package org.javacs.kt

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.javacs.kt.codeaction.codeActions
import org.javacs.kt.completion.completions
import org.javacs.kt.definition.goToDefinition
import org.javacs.kt.diagnostic.DiagnosticsManager
import org.javacs.kt.formatting.FormattingService
import org.javacs.kt.hover.hoverAt
import org.javacs.kt.position.offset
import org.javacs.kt.position.extractRange
import org.javacs.kt.position.position
import org.javacs.kt.references.findReferences
import org.javacs.kt.semantictokens.encodedSemanticTokens
import org.javacs.kt.signaturehelp.fetchSignatureHelpAt
import org.javacs.kt.rename.renameSymbol
import org.javacs.kt.highlight.documentHighlightsAt
import org.javacs.kt.inlayhints.provideHints
import org.javacs.kt.symbols.documentSymbols
import org.javacs.kt.util.LspExecutorPool
import org.javacs.kt.util.LspOperation
import org.javacs.kt.util.LspResponseCache
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.util.describeURI
import org.javacs.kt.util.filePath
import org.javacs.kt.util.noResult
import org.javacs.kt.util.parseURI
import java.net.URI
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class KotlinTextDocumentService(
    private val sf: SourceFiles,
    private val sp: SourcePath,
    private val config: Configuration,
    private val tempDirectory: TemporaryDirectory,
    private val uriContentProvider: URIContentProvider,
    private val cp: CompilerClassPath
) : TextDocumentService, Closeable {
    private lateinit var client: LanguageClient
    private val executorPool = LspExecutorPool()
    private val formattingService = FormattingService(config.formatting)

    private val definitionCache = LspResponseCache<Location?>()
    private val hoverCache = LspResponseCache<Hover?>()
    private val completionCache = LspResponseCache<CompletionList>()
    private val referencesCache = LspResponseCache<List<Location>?>()

    val diagnosticsManager = DiagnosticsManager(
        config.diagnostics.debounceTime,
        config,
        sf,
        { cp.isReady }
    )

    // Test compatibility passthroughs
    val debounceLint get() = diagnosticsManager.debouncer
    val lintTodo get() = diagnosticsManager.pendingFiles
    val lintCount get() = diagnosticsManager.lintCount

    var lintRecompilationCallback: () -> Unit
        get() = sp.beforeCompileCallback
        set(callback) { sp.beforeCompileCallback = callback }

    private val TextDocumentItem.filePath: Path?
        get() = parseURI(uri).filePath

    private val TextDocumentIdentifier.filePath: Path?
        get() = parseURI(uri).filePath

    private val TextDocumentIdentifier.isKotlinScript: Boolean
        get() = uri.endsWith(".kts")

    private val TextDocumentIdentifier.content: String
        get() = sp.content(parseURI(uri))

    fun connect(client: LanguageClient) {
        this.client = client
        diagnosticsManager.connect(client)
        diagnosticsManager.setLintAction { cancelCallback ->
            val files = diagnosticsManager.clearPending()
            val context = sp.compileFiles(files)
            if (!cancelCallback.invoke()) {
                diagnosticsManager.reportDiagnostics(files, context.diagnostics)
            }
        }
    }

    private enum class Recompile {
        ALWAYS, AFTER_DOT, NEVER
    }

    private fun recover(position: TextDocumentPositionParams, recompile: Recompile): Pair<CompiledFile, Int>? {
        return recover(position.textDocument.uri, position.position, recompile)
    }

    private fun recover(uriString: String, position: Position, recompile: Recompile): Pair<CompiledFile, Int>? {
        val uri = parseURI(uriString)
        if (!sf.isIncluded(uri)) {
            LOG.warn("URI is excluded, therefore cannot be recovered: $uri")
            return null
        }
        val content = sp.content(uri)
        val offset = offset(content, position.line, position.character)
        val shouldRecompile = when (recompile) {
            Recompile.ALWAYS -> true
            Recompile.AFTER_DOT -> offset > 0 && content[offset - 1] == '.'
            Recompile.NEVER -> false
        }
        val compiled = if (shouldRecompile) sp.currentVersion(uri) else sp.latestCompiledVersion(uri)
        return Pair(compiled, offset)
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> = executorPool.compute {
        val (file, _) = recover(params.textDocument.uri, params.range.start, Recompile.NEVER) ?: return@compute emptyList()
        codeActions(file, sp.index, params.range, params.context)
    }

    override fun inlayHint(params: InlayHintParams): CompletableFuture<List<InlayHint>> = executorPool.compute {
        val (file, _) = recover(params.textDocument.uri, params.range.start, Recompile.ALWAYS) ?: return@compute emptyList()
        provideHints(file, config.inlayHints)
    }

    override fun hover(position: HoverParams): CompletableFuture<Hover?> =
        executorPool.submit(LspOperation.HOVER) {
            reportTime {
                LOG.info("Hovering at {}", describePosition(position))
                val uri = parseURI(position.textDocument.uri)
                val fileVersion = sf.getVersion(uri)
                val line = position.position.line
                val character = position.position.character

                hoverCache.get(uri, line, character, fileVersion)?.let { cached ->
                    LOG.info("Hover cache hit")
                    return@submit cached.value
                }

                val (file, cursor) = recover(position, Recompile.NEVER) ?: return@submit null
                val result = hoverAt(file, cursor)

                hoverCache.put(uri, line, character, fileVersion, result)

                result ?: noResult("No hover found at ${describePosition(position)}", null)
            }
        }

    override fun documentHighlight(position: DocumentHighlightParams): CompletableFuture<List<DocumentHighlight>> = executorPool.compute {
        val (file, cursor) = recover(position.textDocument.uri, position.position, Recompile.NEVER) ?: return@compute emptyList()
        documentHighlightsAt(file, cursor)
    }

    override fun onTypeFormatting(params: DocumentOnTypeFormattingParams): CompletableFuture<List<TextEdit>> {
        TODO("not implemented")
    }

    override fun definition(position: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> =
        executorPool.submit(LspOperation.DEFINITION) {
            reportTime {
                LOG.info("Go-to-definition at {}", describePosition(position))
                val uri = parseURI(position.textDocument.uri)
                val fileVersion = sf.getVersion(uri)
                val line = position.position.line
                val character = position.position.character

                definitionCache.get(uri, line, character, fileVersion)?.let { cached ->
                    LOG.info("Definition cache hit")
                    return@submit cached.value?.let(::listOf)?.let { Either.forLeft(it) }
                        ?: Either.forLeft(emptyList())
                }

                val (file, cursor) = recover(position, Recompile.NEVER)
                    ?: return@submit Either.forLeft(emptyList())
                val result = goToDefinition(file, cursor, uriContentProvider.classContentProvider, tempDirectory, config.externalSources, cp)

                definitionCache.put(uri, line, character, fileVersion, result)

                result?.let(::listOf)
                    ?.let { Either.forLeft<List<Location>, List<LocationLink>>(it) }
                    ?: noResult("Couldn't find definition at ${describePosition(position)}", Either.forLeft(emptyList()))
            }
        }

    override fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<List<TextEdit>> = executorPool.compute {
        val code = extractRange(params.textDocument.content, params.range)
        listOf(TextEdit(
            params.range,
            formattingService.formatKotlinCode(code, params.options)
        ))
    }

    override fun codeLens(params: CodeLensParams): CompletableFuture<List<CodeLens>> {
        TODO("not implemented")
    }

    override fun rename(params: RenameParams) = executorPool.compute {
        val (file, cursor) = recover(params, Recompile.NEVER) ?: return@compute null
        renameSymbol(file, cursor, sp, params.newName)
    }

    override fun completion(position: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> =
        executorPool.submit(LspOperation.COMPLETION) {
            reportTime {
                LOG.info("Completing at {}", describePosition(position))
                val uri = parseURI(position.textDocument.uri)
                val fileVersion = sf.getVersion(uri)
                val line = position.position.line
                val character = position.position.character

                completionCache.get(uri, line, character, fileVersion)?.let { cached ->
                    LOG.info("Completion cache hit with {} items", cached.value.items.size)
                    return@submit Either.forRight(cached.value)
                }

                val (file, cursor) = recover(position, Recompile.NEVER)
                    ?: return@submit Either.forRight(CompletionList()) // TODO: Investigate when to recompile
                val result = completions(file, cursor, sp.index, config.completion)

                completionCache.put(uri, line, character, fileVersion, result)

                LOG.info("Found {} items", result.items.size)
                Either.forRight(result)
            }
        }

    override fun resolveCompletionItem(unresolved: CompletionItem): CompletableFuture<CompletionItem> {
        TODO("not implemented")
    }

    @Suppress("DEPRECATION")
    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> = executorPool.compute {
        LOG.info("Find symbols in {}", describeURI(params.textDocument.uri))

        reportTime {
            val uri = parseURI(params.textDocument.uri)
            val parsed = sp.parsedFile(uri)

            documentSymbols(parsed)
        }
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        sf.open(uri, params.textDocument.text, params.textDocument.version)
        diagnosticsManager.lintImmediately(uri)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        // Lint after saving to prevent inconsistent diagnostics
        val uri = parseURI(params.textDocument.uri)
        diagnosticsManager.lintImmediately(uri)
        diagnosticsManager.debouncer.schedule {
            sp.save(uri)
        }
    }

    override fun signatureHelp(position: SignatureHelpParams): CompletableFuture<SignatureHelp?> = executorPool.compute {
        reportTime {
            LOG.info("Signature help at {}", describePosition(position))

            val (file, cursor) = recover(position, Recompile.NEVER) ?: return@compute null
            fetchSignatureHelpAt(file, cursor) ?: noResult("No function call around ${describePosition(position)}", null)
        }
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        sf.close(uri)
        diagnosticsManager.clearDiagnostics(uri)
    }

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> = executorPool.compute {
        val code = params.textDocument.content
        LOG.info("Formatting {}", describeURI(params.textDocument.uri))
        listOf(TextEdit(
            Range(Position(0, 0), position(code, code.length)),
            formattingService.formatKotlinCode(code, params.options)
        ))
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        definitionCache.invalidate(uri)
        hoverCache.invalidate(uri)
        completionCache.invalidate(uri)
        referencesCache.clear() // Clear all because any file change can affect cross-file references
        sf.edit(uri, params.textDocument.version, params.contentChanges)
        diagnosticsManager.scheduleLint(uri)
    }

    override fun references(position: ReferenceParams): CompletableFuture<List<Location>?> =
        executorPool.submit(LspOperation.REFERENCES) {
            val uri = parseURI(position.textDocument.uri)
            val fileVersion = sf.getVersion(uri)
            val line = position.position.line
            val character = position.position.character

            referencesCache.get(uri, line, character, fileVersion)?.let { cached ->
                LOG.info("References cache hit with {} locations", cached.value?.size ?: 0)
                return@submit cached.value
            }

            val result = position.textDocument.filePath
                ?.let { file ->
                    val content = sp.content(uri)
                    val cursorOffset = offset(content, line, character)
                    findReferences(file, cursorOffset, sp)
                }

            referencesCache.put(uri, line, character, fileVersion, result)

            result
        }

    override fun semanticTokensFull(params: SemanticTokensParams) = executorPool.compute {
        LOG.info("Full semantic tokens in {}", describeURI(params.textDocument.uri))

        reportTime {
            val uri = parseURI(params.textDocument.uri)
            val file = sp.currentVersion(uri)

            val tokens = encodedSemanticTokens(file)
            LOG.info("Found {} tokens", tokens.size)

            SemanticTokens(tokens)
        }
    }

    override fun semanticTokensRange(params: SemanticTokensRangeParams) = executorPool.compute {
        LOG.info("Ranged semantic tokens in {}", describeURI(params.textDocument.uri))

        reportTime {
            val uri = parseURI(params.textDocument.uri)
            val file = sp.currentVersion(uri)

            val tokens = encodedSemanticTokens(file, params.range)
            LOG.info("Found {} tokens", tokens.size)

            SemanticTokens(tokens)
        }
    }

    override fun resolveCodeLens(unresolved: CodeLens): CompletableFuture<CodeLens> {
        TODO("not implemented")
    }

    private fun describePosition(position: TextDocumentPositionParams): String {
        return "${describeURI(position.textDocument.uri)} ${position.position.line + 1}:${position.position.character + 1}"
    }

    fun updateDebouncer() {
        diagnosticsManager.updateDebounceTime(config.diagnostics.debounceTime)
    }

    fun lintAll() {
        diagnosticsManager.debouncer.submitImmediately {
            sp.compileAllFiles()
            sp.saveAllFiles()
            sp.refreshDependencyIndexes()
        }
    }

    fun lintAllOpenFiles() {
        val openFiles = sf.openFiles()
        if (openFiles.isEmpty()) {
            LOG.info("No open files to re-lint")
            return
        }

        LOG.info("Re-linting {} open files after classpath ready", openFiles.size)
        // Clean ALL cached parse/compile results since compiler environment has changed
        sp.cleanAllFiles()
        diagnosticsManager.debouncer.submitImmediately {
            val context = sp.compileFiles(openFiles)
            diagnosticsManager.reportDiagnostics(openFiles, context.diagnostics)
        }
    }

    private fun shutdownExecutors(awaitTermination: Boolean) {
        executorPool.close()
        diagnosticsManager.close()
    }

    override fun close() {
        shutdownExecutors(awaitTermination = true)
    }
}

private inline fun<T> reportTime(block: () -> T): T {
    val started = System.currentTimeMillis()
    try {
        return block()
    } finally {
        val finished = System.currentTimeMillis()
        LOG.info("Finished in {} ms", finished - started)
    }
}

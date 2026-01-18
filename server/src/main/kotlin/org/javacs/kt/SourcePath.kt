package org.javacs.kt

import org.javacs.kt.compiler.CompilationKind
import org.javacs.kt.util.fileExtension
import org.javacs.kt.util.filePath
import org.javacs.kt.util.describeURI
import org.javacs.kt.index.IndexingService
import org.javacs.kt.index.SymbolIndex
import org.javacs.kt.progress.Progress
import com.intellij.lang.Language
import org.javacs.kt.database.DatabaseService
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.CompositeBindingContext
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import kotlin.concurrent.withLock
import java.nio.file.Path
import java.nio.file.Paths
import java.net.URI
import java.util.concurrent.locks.ReentrantLock

class SourcePath(
    private val cp: CompilerClassPath,
    private val contentProvider: URIContentProvider,
    private val indexingConfig: IndexingConfiguration,
    private val databaseService: DatabaseService
) {
    private val files = mutableMapOf<URI, SourceFile>()
    private val parseDataWriteLock = ReentrantLock()

    private val indexingService = IndexingService(SymbolIndex(databaseService), indexingConfig)
    var indexEnabled: Boolean by indexingConfig::enabled
    val index: SymbolIndex get() = indexingService.getIndex()

    var beforeCompileCallback: () -> Unit = {}

    var progressFactory: Progress.Factory = Progress.Factory.None
        set(factory: Progress.Factory) {
            field = factory
            indexingService.progressFactory = factory
        }

    private inner class SourceFile(
        val uri: URI,
        var content: String,
        val path: Path? = uri.filePath,
        var parsed: KtFile? = null,
        var compiledFile: KtFile? = null,
        var compiledContext: BindingContext? = null,
        var module: ModuleDescriptor? = null,
        val language: Language? = null,
        val isTemporary: Boolean = false, // A temporary source file will not be returned by .all()
        var lastSavedFile: KtFile? = null,
        var moduleId: String? = null,
    ) {
        val extension: String? = uri.fileExtension ?: "kt" // TODO: Use language?.associatedFileType?.defaultExtension again
        val isScript: Boolean = extension == "kts"
        val kind: CompilationKind =
            if (path?.fileName?.toString()?.endsWith(".gradle.kts") ?: false) CompilationKind.BUILD_SCRIPT
            else CompilationKind.DEFAULT

        fun put(newContent: String) {
            content = newContent
        }

        fun clean() {
            parsed = null
            compiledFile = null
            compiledContext = null
            module = null
        }

        fun parse() {
            // TODO: Create PsiFile using the stored language instead
            parsed = cp.compiler.createKtFile(content, path ?: Paths.get("sourceFile.virtual.$extension"), kind)
        }

        fun parseIfChanged() {
            if (content != parsed?.text) {
                parse()
            }
        }

        fun compileIfNull() = parseIfChanged().apply { doCompileIfNull() }

        private fun doCompileIfNull() {
            if (compiledFile == null) {
                doCompileIfChanged()
            }
        }

        fun compileIfChanged() = parseIfChanged().apply { doCompileIfChanged() }

        fun compile() = parse().apply { doCompile() }

        private fun doCompile() {
            LOG.debug("Compiling {}", path?.fileName)

            val oldFile = clone()

            val (context, module) = cp.compiler.compileKtFile(parsed!!, allIncludingThis(), kind)
            parseDataWriteLock.withLock {
                compiledContext = context
                this.module = module
                compiledFile = parsed
            }

            val oldDeclarations = getDeclarationDescriptors(listOfNotNull(oldFile))
            val newDeclarations = getDeclarationDescriptors(listOfNotNull(this))
            indexingService.refreshWorkspaceIndexes(oldDeclarations, newDeclarations, moduleId)
        }

        private fun doCompileIfChanged() {
            if (parsed?.text != compiledFile?.text) {
                doCompile()
            }
        }

        fun prepareCompiledFile(): CompiledFile =
                parseIfChanged().apply { compileIfNull() }.let { doPrepareCompiledFile() }

        private fun doPrepareCompiledFile(): CompiledFile =
                CompiledFile(content, compiledFile!!, compiledContext!!, module!!, allIncludingThis(), cp, isScript, kind, moduleId)

        private fun allIncludingThis(): Collection<KtFile> = parseIfChanged().let {
            val moduleFiles = if (cp.moduleRegistry.isEmpty()) {
                all()
            } else {
                allInModule(moduleId)
            }
            if (isTemporary) (moduleFiles.asSequence() + sequenceOf(parsed!!)).toList()
            else moduleFiles
        }

        fun clone(): SourceFile = SourceFile(uri, content, path, parsed, compiledFile, compiledContext, module, language, isTemporary, lastSavedFile, moduleId)
    }

    private fun sourceFile(uri: URI): SourceFile {
        if (uri !in files) {
            // Fallback solution, usually *all* source files
            // should be added/opened through SourceFiles
            LOG.warn("Requested source file {} is not on source path, this is most likely a bug. Adding it now temporarily...", describeURI(uri))
            put(uri, contentProvider.contentOf(uri), null, temporary = true)
        }
        return files[uri]!!
    }

    fun put(uri: URI, content: String, language: Language?, temporary: Boolean = false) {
        assert(!content.contains('\r'))

        if (temporary) {
            LOG.info("Adding temporary source file {} to source path", describeURI(uri))
        }

        if (uri in files) {
            sourceFile(uri).put(content)
        } else {
            val path = uri.filePath
            val moduleId = if (temporary || path == null) {
                null
            } else {
                cp.moduleRegistry.findModuleForFile(path)?.name
            }
            files[uri] = SourceFile(uri, content, language = language, isTemporary = temporary, moduleId = moduleId)
        }
    }

    fun deleteIfTemporary(uri: URI): Boolean =
        if (sourceFile(uri).isTemporary) {
            LOG.info("Removing temporary source file {} from source path", describeURI(uri))
            delete(uri)
            true
        } else {
            false
        }

    fun delete(uri: URI) {
        files[uri]?.let {
            val oldDeclarations = getDeclarationDescriptors(listOf(it))
            indexingService.refreshWorkspaceIndexes(oldDeclarations, emptySequence(), it.moduleId)
            cp.compiler.removeGeneratedCode(listOfNotNull(it.lastSavedFile))
        }

        files.remove(uri)
    }

    fun content(uri: URI): String = sourceFile(uri).content

    fun parsedFile(uri: URI): KtFile = sourceFile(uri).apply { parseIfChanged() }.parsed!!

    fun currentVersion(uri: URI): CompiledFile =
            sourceFile(uri).apply { compileIfChanged() }.prepareCompiledFile()

    fun latestCompiledVersion(uri: URI): CompiledFile =
            sourceFile(uri).prepareCompiledFile()

    fun compileFiles(all: Collection<URI>): BindingContext {
        val sources = all.map { files[it]!! }
        val allChanged = sources.filter { it.content != it.compiledFile?.text }
        val (changedBuildScripts, changedSources) = allChanged.partition { it.kind == CompilationKind.BUILD_SCRIPT }

        fun compileAndUpdate(changed: List<SourceFile>, kind: CompilationKind, moduleId: String? = null): BindingContext? {
            if (changed.isEmpty()) return null

            val oldFiles = changed.mapNotNull {
                if (it.compiledFile?.text != it.content || it.parsed?.text != it.content) {
                    it.clone()
                } else {
                    null
                }
            }

            val parse = changed.associateWith { it.apply { parseIfChanged() }.parsed!! }

            val allFiles = if (cp.moduleRegistry.isEmpty() || kind == CompilationKind.BUILD_SCRIPT) {
                all()
            } else {
                allInModule(moduleId)
            }
            beforeCompileCallback.invoke()
            val moduleCompiler = if (kind == CompilationKind.BUILD_SCRIPT) {
                cp.compiler
            } else {
                cp.getCompilerForModule(moduleId)
            }
            val (context, module) = moduleCompiler.compileKtFiles(parse.values, allFiles, kind)

            for ((f, parsed) in parse) {
                parseDataWriteLock.withLock {
                    if (f.parsed == parsed) {
                        f.compiledFile = parsed
                        f.compiledContext = context
                        f.module = module
                    }
                }
            }

            if (kind == CompilationKind.DEFAULT) {
                val oldDeclarations = getDeclarationDescriptors(oldFiles)
                val newDeclarations = getDeclarationDescriptors(parse.keys.toList())
                indexingService.refreshWorkspaceIndexes(oldDeclarations, newDeclarations, moduleId)
            }

            return context
        }

        val buildScriptsContext = compileAndUpdate(changedBuildScripts, CompilationKind.BUILD_SCRIPT)

        val sourcesContexts = if (cp.moduleRegistry.isEmpty()) {
            listOfNotNull(compileAndUpdate(changedSources, CompilationKind.DEFAULT))
        } else {
            changedSources.groupBy { it.moduleId }.mapNotNull { (moduleId, moduleFiles) ->
                compileAndUpdate(moduleFiles, CompilationKind.DEFAULT, moduleId)
            }
        }

        val same = sources - allChanged
        val combined = listOfNotNull(buildScriptsContext) + sourcesContexts + same.map { it.compiledContext!! }

        return CompositeBindingContext.create(combined)
    }

    fun compileAllFiles() {
        // TODO: Investigate the possibility of compiling all files at once, instead of iterating here
        // At the moment, compiling all files at once sometimes leads to an internal error from the TopDownAnalyzer
        files.keys.forEach {
            // If one of the files fails to compile, we compile the others anyway
            try {
                compileFiles(listOf(it))
            } catch (ex: Exception) {
                LOG.printStackTrace(ex)
            }
        }
    }

    fun save(uri: URI) {
        files[uri]?.let {
            if (!it.isScript) {
                // If the code generation fails for some reason, we generate code for the other files anyway
                try {
                    val moduleCompiler = cp.getCompilerForModule(it.moduleId)
                    moduleCompiler.removeGeneratedCode(listOfNotNull(it.lastSavedFile))
                    it.module?.let { module ->
                        it.compiledContext?.let { context ->
                            moduleCompiler.generateCode(module, context, listOfNotNull(it.compiledFile))
                            it.lastSavedFile = it.compiledFile
                        }
                    }
                } catch (ex: Exception) {
                    LOG.printStackTrace(ex)
                }
            }
        }
    }

    fun saveAllFiles() {
        files.keys.forEach { save(it) }
    }

    fun cleanFiles(uris: Collection<URI>) {
        uris.forEach { uri ->
            files[uri]?.clean()
        }
    }

    fun cleanAllFiles() {
        files.values.forEach { it.clean() }
    }

    fun refreshDependencyIndexes() {
        compileAllFiles()

        val module = files.values.firstOrNull { it.module != null }?.module
        if (module != null) {
            val diff = cp.lastClassPathDiff
            if (diff != null && diff.hasChanges) {
                indexingService.refreshDependencyIndexesIncrementally(diff, module)
            } else {
                val declarations = getDeclarationDescriptors(files.values)
                indexingService.refreshDependencyIndexes(
                    module,
                    declarations,
                    cp.currentBuildFileVersion,
                    skipIfValid = true,
                    indexingConfig.batchSize
                )
            }
        }
    }

    fun refreshDependencyIndexesIncrementally(diff: ClassPathDiff) {
        val module = files.values.firstOrNull { it.module != null }?.module
        indexingService.refreshDependencyIndexesIncrementally(diff, module)
    }

    private fun getDeclarationDescriptors(files: Collection<SourceFile>) =
        files.flatMap { file ->
            val compiledFile = file.compiledFile ?: file.parsed
            val module = file.module
            if (compiledFile != null && module != null) {
                module.getPackage(compiledFile.packageFqName).memberScope.getContributedDescriptors(
                    DescriptorKindFilter.ALL
                ) { name -> compiledFile.declarations.map { it.name }.contains(name.toString()) }
            } else {
                listOf()
            }
        }.asSequence()

    fun refresh() {
        val initialized = files.values.any { it.parsed != null }
        if (initialized) {
            LOG.info("Refreshing source path")
            files.values.forEach { it.clean() }
            files.values.forEach { it.compile() }
        }
    }

    fun refreshModuleAssignments() {
        for (sourceFile in files.values) {
            if (sourceFile.isTemporary) continue
            val path = sourceFile.path ?: continue
            sourceFile.moduleId = cp.moduleRegistry.findModuleForFile(path)?.name
        }
    }

    /**
     * Get parsed trees for all .kt files in a specific module.
     * If moduleId is null, returns files not belonging to any module.
     */
    fun allInModule(moduleId: String?, includeHidden: Boolean = false): Collection<KtFile> =
            files.values
                .filter { (includeHidden || !it.isTemporary) && it.moduleId == moduleId }
                .map { it.apply { parseIfChanged() }.parsed!! }

    /**
     * Get parsed trees for all .kt files on source path
     */
    fun all(includeHidden: Boolean = false): Collection<KtFile> =
            files.values
                .filter { includeHidden || !it.isTemporary }
                .map { it.apply { parseIfChanged() }.parsed!! }
}

package org.javacs.kt

import org.javacs.kt.classpath.ClassPathEntry
import org.javacs.kt.classpath.ClassPathResolver
import org.javacs.kt.classpath.GradleClassPathResolver
import org.javacs.kt.classpath.ModuleInfo
import org.javacs.kt.classpath.ModuleRegistry
import org.javacs.kt.classpath.defaultClassPathResolver
import org.javacs.kt.compiler.Compiler
import org.javacs.kt.database.DatabaseService
import org.javacs.kt.progress.Progress
import org.javacs.kt.util.AsyncExecutor
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

data class ClassPathDiff(
    val added: Set<ClassPathEntry>,
    val removed: Set<ClassPathEntry>
) {
    val hasChanges: Boolean get() = added.isNotEmpty() || removed.isNotEmpty()
}

enum class ClassPathResolutionState {
    PENDING,
    RESOLVING,
    READY,
    FAILED
}

class CompilerClassPath(
    private val config: CompilerConfiguration,
    private val scriptsConfig: ScriptsConfiguration,
    private val codegenConfig: CodegenConfiguration,
    private val databaseService: DatabaseService
) : Closeable {
    companion object {
        private const val MAX_MODULE_COMPILERS = 5
    }

    private val pathsLock = ReentrantReadWriteLock()
    private val _workspaceRoots = mutableSetOf<Path>()
    private val _javaSourcePath = mutableSetOf<Path>()
    private val _buildScriptClassPath = mutableSetOf<Path>()
    private val _classPath = mutableSetOf<ClassPathEntry>()

    val workspaceRoots: Set<Path>
        get() = pathsLock.read { _workspaceRoots.toSet() }

    val classPath: Set<ClassPathEntry>
        get() = pathsLock.read { _classPath.toSet() }
    val outputDirectory: File = Files.createTempDirectory("klsBuildOutput").toFile()
    val javaHome: String? = System.getProperty("java.home", null)

    val moduleRegistry = ModuleRegistry()

    private val moduleCompilerCache = object : LinkedHashMap<String, Compiler>(MAX_MODULE_COMPILERS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Compiler>?): Boolean {
            if (size > MAX_MODULE_COMPILERS) {
                eldest?.value?.close()
                LOG.info("Evicted compiler for module '${eldest?.key}' from cache (size exceeded $MAX_MODULE_COMPILERS)")
                return true
            }
            return false
        }
    }

    @Volatile
    private var cachedResolver: ClassPathResolver? = null

    /** Returns the current build file version (max timestamp of all build files) */
    val currentBuildFileVersion: Long
        get() = getOrCreateResolver().currentBuildFileVersion

    private fun getOrCreateResolver(): ClassPathResolver {
        val roots = pathsLock.read { _workspaceRoots.toSet() }
        return cachedResolver ?: defaultClassPathResolver(roots, databaseService.db).also {
            cachedResolver = it
        }
    }

    var compiler = Compiler(
        _javaSourcePath,
        _classPath.map { it.compiledJar }.toSet(),
        _buildScriptClassPath,
        scriptsConfig,
        codegenConfig,
        outputDirectory
    )
        private set

    private val async = AsyncExecutor()
    private val resolutionFuture = AtomicReference<CompletableFuture<*>?>(null)

    @Volatile
    var resolutionState: ClassPathResolutionState = ClassPathResolutionState.PENDING
        private set

    val isReady: Boolean get() = resolutionState == ClassPathResolutionState.READY

    var onClassPathReady: (() -> Unit)? = null

    var progressFactory: Progress.Factory = Progress.Factory.None

    init {
        compiler.updateConfiguration(config)
    }

    @Volatile
    var lastClassPathDiff: ClassPathDiff? = null
        private set

    // TODO: Fetch class path and build script class path concurrently
    private fun refresh(
        updateClassPath: Boolean = true,
        updateBuildScriptClassPath: Boolean = true,
        updateJavaSourcePath: Boolean = true
    ): Boolean {
        cachedResolver = null
        val resolver = getOrCreateResolver()
        var refreshCompiler = updateJavaSourcePath
        lastClassPathDiff = null

        if (updateClassPath) {
            val newClassPath = resolver.classpathOrEmpty
            val currentClassPath = pathsLock.read { _classPath.toSet() }
            if (newClassPath != currentClassPath) {
                pathsLock.write {
                    val (added, removed) = syncClassPath(newClassPath)
                    lastClassPathDiff = ClassPathDiff(added, removed)
                }
                refreshCompiler = true
            }

            updateModuleRegistry(resolver)
            invalidateModuleCompilers()

            async.compute {
                val newClassPathWithSources = resolver.classpathWithSources
                pathsLock.write {
                    syncClassPath(newClassPathWithSources)
                }
            }
        }

        if (updateBuildScriptClassPath) {
            LOG.info("Update build script path")
            val newBuildScriptClassPath = resolver.buildScriptClasspathOrEmpty
            val currentBuildScriptClassPath = pathsLock.read { _buildScriptClassPath.toSet() }
            if (newBuildScriptClassPath != currentBuildScriptClassPath) {
                pathsLock.write {
                    syncPaths(_buildScriptClassPath, newBuildScriptClassPath, "build script class path") { it }
                }
                refreshCompiler = true
            }
        }

        if (refreshCompiler) {
            LOG.info("Reinstantiating compiler")
            compiler.close()
            invalidateModuleCompilers()
            val (javaSourceSnapshot, classPathSnapshot, buildScriptSnapshot) = pathsLock.read {
                Triple(
                    _javaSourcePath.toSet(),
                    _classPath.map { it.compiledJar }.toSet(),
                    _buildScriptClassPath.toSet()
                )
            }
            compiler = Compiler(
                javaSourceSnapshot,
                classPathSnapshot,
                buildScriptSnapshot,
                scriptsConfig,
                codegenConfig,
                outputDirectory
            )
            updateCompilerConfiguration()
        }

        return refreshCompiler
    }

    // Must be called while holding pathsLock.write
    private fun syncClassPath(newClassPath: Set<ClassPathEntry>): Pair<Set<ClassPathEntry>, Set<ClassPathEntry>> {
        val added = newClassPath - _classPath
        val removed = _classPath - newClassPath

        logAdded(added.map { it.compiledJar }, "class path")
        logRemoved(removed.map { it.compiledJar }, "class path")

        _classPath.removeAll(removed)
        _classPath.addAll(added)

        return Pair(added, removed)
    }

    private fun <T> syncPaths(dest: MutableSet<T>, new: Set<T>, name: String, toPath: (T) -> Path) {
        val added = new - dest
        val removed = dest - new

        logAdded(added.map(toPath), name)
        logRemoved(removed.map(toPath), name)

        dest.removeAll(removed)
        dest.addAll(added)
    }

    private fun updateModuleRegistry(resolver: ClassPathResolver) {
        moduleRegistry.clear()

        val gradleResolver = findGradleResolver(resolver) ?: return
        val moduleClassPaths = gradleResolver.moduleClassPaths
        if (moduleClassPaths.isEmpty()) return

        for ((name, moduleClassPath) in moduleClassPaths) {
            if (moduleClassPath.sourceDirs.isEmpty()) continue

            val rootPath = moduleClassPath.sourceDirs.firstOrNull()?.parent?.parent?.parent ?: continue
            val moduleInfo = ModuleInfo(
                name = name,
                rootPath = rootPath,
                sourceDirs = moduleClassPath.sourceDirs,
                classPath = moduleClassPath.classPath
            )
            moduleRegistry.register(moduleInfo)
        }

        if (moduleRegistry.size() > 0) {
            LOG.info("Module registry updated with {} modules", moduleRegistry.size())
        }
    }

    private fun findGradleResolver(resolver: ClassPathResolver): GradleClassPathResolver? {
        if (resolver is GradleClassPathResolver) {
            return resolver
        }
        for (wrapped in resolver.wrappedResolvers) {
            val found = findGradleResolver(wrapped)
            if (found != null) {
                return found
            }
        }
        return null
    }

    fun updateCompilerConfiguration() {
        compiler.updateConfiguration(config)
    }

    /**
     * Get a compiler for a specific module. Uses module-specific classpath for isolation.
     * Falls back to the default compiler if module not found or moduleId is null.
     */
    @Synchronized
    fun getCompilerForModule(moduleId: String?): Compiler {
        if (moduleId == null || moduleRegistry.isEmpty()) {
            return compiler
        }

        val moduleInfo = moduleRegistry.getModule(moduleId) ?: return compiler

        return moduleCompilerCache.getOrPut(moduleId) {
            val (javaSourceSnapshot, buildScriptSnapshot) = pathsLock.read {
                Pair(_javaSourcePath.toSet(), _buildScriptClassPath.toSet())
            }
            LOG.info("Creating compiler for module '$moduleId' with ${moduleInfo.classPath.size} classpath entries")
            Compiler(
                javaSourceSnapshot,
                moduleInfo.classPath,
                buildScriptSnapshot,
                scriptsConfig,
                codegenConfig,
                outputDirectory
            ).also { it.updateConfiguration(config) }
        }
    }

    @Synchronized
    private fun invalidateModuleCompilers() {
        if (moduleCompilerCache.isNotEmpty()) {
            LOG.info("Invalidating ${moduleCompilerCache.size} module-specific compilers")
            moduleCompilerCache.values.forEach { it.close() }
            moduleCompilerCache.clear()
        }
    }

    fun addWorkspaceRoot(root: Path): Boolean {
        LOG.info("Searching for dependencies and Java sources in workspace root {}", root)

        val javaFiles = findJavaSourceFiles(root)
        pathsLock.write {
            _workspaceRoots.add(root)
            _javaSourcePath.addAll(javaFiles)
        }

        // startBackgroundResolution OUTSIDE lock
        startBackgroundResolution()
        return false
    }

    @Synchronized
    private fun startBackgroundResolution() {
        val oldFuture = resolutionFuture.getAndSet(null)
        oldFuture?.cancel(false)

        resolutionState = ClassPathResolutionState.RESOLVING
        LOG.info("Starting background classpath resolution")

        val future = progressFactory.create("Resolving dependencies").thenApplyAsync { progress ->
            try {
                progress.update("Scanning build files...", 10)
                refresh()
                progress.update("Complete", 100)
                resolutionState = ClassPathResolutionState.READY
                LOG.info("Classpath resolution completed")
                onClassPathReady?.invoke()
            } catch (e: Exception) {
                LOG.error("Classpath resolution failed", e)
                resolutionState = ClassPathResolutionState.FAILED
            } finally {
                progress.close()
            }
        }
        resolutionFuture.set(future)
    }

    fun removeWorkspaceRoot(root: Path): Boolean {
        LOG.info("Removing dependencies and Java source path from workspace root {}", root)

        val javaFiles = findJavaSourceFiles(root)
        pathsLock.write {
            _workspaceRoots.remove(root)
            _javaSourcePath.removeAll(javaFiles)
        }

        return refresh()
    }

    fun createdOnDisk(file: Path): Boolean {
        if (isJavaSource(file)) {
            pathsLock.write { _javaSourcePath.add(file) }
        }
        return changedOnDisk(file)
    }

    fun deletedOnDisk(file: Path): Boolean {
        if (isJavaSource(file)) {
            pathsLock.write { _javaSourcePath.remove(file) }
        }
        return changedOnDisk(file)
    }

    fun changedOnDisk(file: Path): Boolean {
        val buildScript = isBuildScript(file)
        val javaSource = isJavaSource(file)

        if (buildScript) {
            LOG.info("Build script changed: {}, triggering background resolution", file)
            startBackgroundResolution()
            return false
        } else if (javaSource) {
            return refresh(updateClassPath = false, updateBuildScriptClassPath = false, updateJavaSourcePath = true)
        }
        return false
    }

    private fun isJavaSource(file: Path): Boolean = file.fileName.toString().endsWith(".java")

    private fun isBuildScript(file: Path): Boolean = file.fileName.toString().let { it == "pom.xml" || it == "build.gradle" || it == "build.gradle.kts" }

    private fun findJavaSourceFiles(root: Path): Set<Path> {
        val sourceMatcher = FileSystems.getDefault().getPathMatcher("glob:*.java")
        return SourceExclusions(listOf(root), scriptsConfig)
            .walkIncluded()
            .filter { sourceMatcher.matches(it.fileName) }
            .toSet()
    }

    fun waitForResolution(timeout: Long = 60, unit: TimeUnit = TimeUnit.SECONDS) {
        resolutionFuture.get()?.get(timeout, unit)
    }

    override fun close() {
        resolutionFuture.get()?.cancel(true)
        async.shutdown(awaitTermination = true)
        invalidateModuleCompilers()
        compiler.close()
        outputDirectory.delete()
    }
}

private fun logAdded(sources: Collection<Path>, name: String) {
    when {
        sources.isEmpty() -> return
        sources.size > 5 -> LOG.info("Adding {} files to {}", sources.size, name)
        else -> LOG.info("Adding {} to {}", sources, name)
    }
}

private fun logRemoved(sources: Collection<Path>, name: String) {
    when {
        sources.isEmpty() -> return
        sources.size > 5 -> LOG.info("Removing {} files from {}", sources.size, name)
        else -> LOG.info("Removing {} from {}", sources, name)
    }
}

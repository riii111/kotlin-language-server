package org.javacs.kt

import org.javacs.kt.classpath.ClassPathEntry
import org.javacs.kt.classpath.ClassPathResolver
import org.javacs.kt.classpath.defaultClassPathResolver
import org.javacs.kt.compiler.Compiler
import org.javacs.kt.database.DatabaseService
import org.javacs.kt.progress.Progress
import org.javacs.kt.util.AsyncExecutor
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
    val workspaceRoots = mutableSetOf<Path>()

    private val javaSourcePath = mutableSetOf<Path>()
    private val buildScriptClassPath = mutableSetOf<Path>()
    val classPath = mutableSetOf<ClassPathEntry>()
    val outputDirectory: File = Files.createTempDirectory("klsBuildOutput").toFile()
    val javaHome: String? = System.getProperty("java.home", null)

    @Volatile
    private var cachedResolver: ClassPathResolver? = null

    /** Returns the current build file version (max timestamp of all build files) */
    val currentBuildFileVersion: Long
        get() = getOrCreateResolver().currentBuildFileVersion

    private fun getOrCreateResolver(): ClassPathResolver {
        return cachedResolver ?: defaultClassPathResolver(workspaceRoots, databaseService.db).also {
            cachedResolver = it
        }
    }

    var compiler = Compiler(
        javaSourcePath,
        classPath.map { it.compiledJar }.toSet(),
        buildScriptClassPath,
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
            if (newClassPath != classPath) {
                synchronized(classPath) {
                    val (added, removed) = syncClassPath(newClassPath)
                    lastClassPathDiff = ClassPathDiff(added, removed)
                }
                refreshCompiler = true
            }

            async.compute {
                val newClassPathWithSources = resolver.classpathWithSources
                synchronized(classPath) {
                    syncClassPath(newClassPathWithSources)
                }
            }
        }

        if (updateBuildScriptClassPath) {
            LOG.info("Update build script path")
            val newBuildScriptClassPath = resolver.buildScriptClasspathOrEmpty
            if (newBuildScriptClassPath != buildScriptClassPath) {
                syncPaths(buildScriptClassPath, newBuildScriptClassPath, "build script class path") { it }
                refreshCompiler = true
            }
        }

        if (refreshCompiler) {
            LOG.info("Reinstantiating compiler")
            compiler.close()
            compiler = Compiler(
                javaSourcePath,
                classPath.map { it.compiledJar }.toSet(),
                buildScriptClassPath,
                scriptsConfig,
                codegenConfig,
                outputDirectory
            )
            updateCompilerConfiguration()
        }

        return refreshCompiler
    }

    private fun syncClassPath(newClassPath: Set<ClassPathEntry>): Pair<Set<ClassPathEntry>, Set<ClassPathEntry>> {
        val added = newClassPath - classPath
        val removed = classPath - newClassPath

        logAdded(added.map { it.compiledJar }, "class path")
        logRemoved(removed.map { it.compiledJar }, "class path")

        classPath.removeAll(removed)
        classPath.addAll(added)

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

    fun updateCompilerConfiguration() {
        compiler.updateConfiguration(config)
    }

    fun addWorkspaceRoot(root: Path): Boolean {
        LOG.info("Searching for dependencies and Java sources in workspace root {}", root)

        workspaceRoots.add(root)
        javaSourcePath.addAll(findJavaSourceFiles(root))

        startBackgroundResolution()
        return false
    }

    private fun startBackgroundResolution() {
        resolutionFuture.get()?.cancel(false)

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
                LOG.error("Classpath resolution failed: {}", e.message)
                resolutionState = ClassPathResolutionState.FAILED
            } finally {
                progress.close()
            }
        }
        resolutionFuture.set(future)
    }

    fun removeWorkspaceRoot(root: Path): Boolean {
        LOG.info("Removing dependencies and Java source path from workspace root {}", root)

        workspaceRoots.remove(root)
        javaSourcePath.removeAll(findJavaSourceFiles(root))

        return refresh()
    }

    fun createdOnDisk(file: Path): Boolean {
        if (isJavaSource(file)) {
            javaSourcePath.add(file)
        }
        return changedOnDisk(file)
    }

    fun deletedOnDisk(file: Path): Boolean {
        if (isJavaSource(file)) {
            javaSourcePath.remove(file)
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

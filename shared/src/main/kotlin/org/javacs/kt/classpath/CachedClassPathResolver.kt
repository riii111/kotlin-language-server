package org.javacs.kt.classpath

import org.javacs.kt.LOG
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.nio.file.Paths

private const val MAX_PATH_LENGTH = 2047

private object ClassPathMetadataCache : IntIdTable() {
    val includesSources = bool("includessources")
    val buildFileVersion = long("buildfileversion").nullable()
}

private object ClassPathCacheEntry : IntIdTable() {
    val compiledJar = varchar("compiledjar", length = MAX_PATH_LENGTH)
    val sourceJar = varchar("sourcejar", length = MAX_PATH_LENGTH).nullable()
}

private object BuildScriptClassPathCacheEntry : IntIdTable() {
    val jar = varchar("jar", length = MAX_PATH_LENGTH)
}

/** A classpath resolver that caches another resolver */
internal class CachedClassPathResolver(
    private val wrapped: ClassPathResolver,
    private val db: Database
) : ClassPathResolver {
    override val resolverType: String get() = "Cached + ${wrapped.resolverType}"
    override val wrappedResolvers: List<ClassPathResolver> get() = listOf(wrapped)

    private var cachedClassPathEntries: Set<ClassPathEntry>
        get() = transaction(db) {
            ClassPathCacheEntry.selectAll().map { row ->
                ClassPathEntry(
                    compiledJar = Paths.get(row[ClassPathCacheEntry.compiledJar]),
                    sourceJar = row[ClassPathCacheEntry.sourceJar]?.let(Paths::get)
                )
            }.toSet()
        }
        set(newEntries) = transaction(db) {
            ClassPathCacheEntry.deleteAll()
            newEntries.forEach {
                ClassPathCacheEntry.insert { row ->
                    row[compiledJar] = it.compiledJar.toString()
                    row[sourceJar] = it.sourceJar?.toString()
                }
            }
        }

    private var cachedBuildScriptClassPathEntries: Set<Path>
        get() = transaction(db) { BuildScriptClassPathCacheEntry.selectAll().map { row -> Paths.get(row[BuildScriptClassPathCacheEntry.jar]) }.toSet() }
        set(newEntries) = transaction(db) {
            BuildScriptClassPathCacheEntry.deleteAll()
            newEntries.forEach { entry ->
                BuildScriptClassPathCacheEntry.insert { row -> row[jar] = entry.toString() }
            }
        }

    private var cachedClassPathMetadata
        get() = transaction(db) {
            ClassPathMetadataCache.selectAll().map { row ->
                ClasspathMetadata(
                    includesSources = row[ClassPathMetadataCache.includesSources],
                    buildFileVersion = row[ClassPathMetadataCache.buildFileVersion]
                )
            }.firstOrNull()
        }
        set(newClassPathMetadata) = transaction(db) {
            ClassPathMetadataCache.deleteAll()
            val newClassPathMetadataRow = newClassPathMetadata ?: ClasspathMetadata()
            ClassPathMetadataCache.insert { row ->
                row[includesSources] = newClassPathMetadataRow.includesSources
                row[buildFileVersion] = newClassPathMetadataRow.buildFileVersion
            }
        }

    init {
        transaction(db) {
            SchemaUtils.createMissingTablesAndColumns(
                ClassPathMetadataCache, ClassPathCacheEntry, BuildScriptClassPathCacheEntry
            )
        }
    }

    override val classpath: Set<ClassPathEntry> get() {
        cachedClassPathEntries.let { if (!dependenciesChanged()) {
            LOG.info("Classpath has not changed. Fetching from cache")
            return it
        } }

        LOG.info("Cached classpath is outdated or not found. Resolving again")

        val newClasspath = wrapped.classpath
        updateClasspathCache(newClasspath, false)

        return newClasspath
    }

    override val buildScriptClasspath: Set<Path> get() {
        if (!dependenciesChanged()) {
            LOG.info("Build script classpath has not changed. Fetching from cache")
            return cachedBuildScriptClassPathEntries
        }

        LOG.info("Cached build script classpath is outdated or not found. Resolving again")

        val newBuildScriptClasspath = wrapped.buildScriptClasspath

        updateBuildScriptClasspathCache(newBuildScriptClasspath)
        return newBuildScriptClasspath
    }

    override val classpathWithSources: Set<ClassPathEntry> get() {
        cachedClassPathMetadata?.let { if (!dependenciesChanged() && it.includesSources) return cachedClassPathEntries }

        val newClasspath = wrapped.classpathWithSources
        updateClasspathCache(newClasspath, true)

        return newClasspath
    }

    override val currentBuildFileVersion: Long get() = wrapped.currentBuildFileVersion

    private fun updateClasspathCache(newClasspathEntries: Set<ClassPathEntry>, includesSources: Boolean) {
        transaction(db) {
            cachedClassPathEntries = newClasspathEntries
            cachedClassPathMetadata = cachedClassPathMetadata?.copy(
                includesSources = includesSources,
                buildFileVersion = currentBuildFileVersion
            ) ?: ClasspathMetadata()
        }
    }

    private fun updateBuildScriptClasspathCache(newClasspath: Set<Path>) {
        transaction(db) {
            cachedBuildScriptClassPathEntries = newClasspath
            cachedClassPathMetadata = cachedClassPathMetadata?.copy(
                buildFileVersion = currentBuildFileVersion
            ) ?: ClasspathMetadata()
        }
    }

    private fun dependenciesChanged(): Boolean {
        return (cachedClassPathMetadata?.buildFileVersion ?: 0) < wrapped.currentBuildFileVersion
    }
}

private data class ClasspathMetadata(
    val includesSources: Boolean = false,
    val buildFileVersion: Long? = null
)

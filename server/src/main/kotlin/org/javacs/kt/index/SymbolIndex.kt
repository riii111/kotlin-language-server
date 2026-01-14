package org.javacs.kt.index

import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.name.FqName
import org.javacs.kt.LOG
import org.javacs.kt.database.DatabaseService
import org.javacs.kt.database.Symbols
import org.javacs.kt.database.Locations
import org.javacs.kt.database.Ranges
import org.javacs.kt.database.Positions
import org.javacs.kt.database.SymbolIndexMetadata
import org.javacs.kt.database.SymbolIndexMetadataEntity
import org.javacs.kt.progress.Progress
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import kotlin.sequences.Sequence

private const val MAX_FQNAME_LENGTH = 255
private const val MAX_SHORT_NAME_LENGTH = 80

class SymbolEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<SymbolEntity>(Symbols)

    var fqName by Symbols.fqName
    var shortName by Symbols.shortName
    var kind by Symbols.kind
    var visibility by Symbols.visibility
    var extensionReceiverType by Symbols.extensionReceiverType
    var location by LocationEntity optionalReferencedOn Symbols.location
}

class LocationEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<LocationEntity>(Locations)

    var uri by Locations.uri
    var range by RangeEntity referencedOn Locations.range
}

class RangeEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<RangeEntity>(Ranges)

    var start by PositionEntity referencedOn Ranges.start
    var end by PositionEntity referencedOn Ranges.end
}

class PositionEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PositionEntity>(Positions)

    var line by Positions.line
    var character by Positions.character
}

/**
 * A global view of all available symbols across all packages.
 * Uses SQLite for persistence when storagePath is configured,
 * otherwise falls back to H2 in-memory database.
 */
class SymbolIndex(
    private val databaseService: DatabaseService
) {
    private companion object {
        const val PROGRESS_UPDATE_INTERVAL_MS = 100L
    }

    private val db: Database by lazy {
        databaseService.db ?: Database.connect("jdbc:h2:mem:symbolindex;DB_CLOSE_DELAY=-1", "org.h2.Driver").also {
            transaction(it) {
                SchemaUtils.createMissingTablesAndColumns(Symbols, Locations, Ranges, Positions)
            }
            LOG.info("Using in-memory H2 database for symbol index (no storagePath configured)")
        }
    }

    /** Whether the index is persisted to disk (SQLite) or in-memory (H2) */
    val isPersistent: Boolean
        get() = databaseService.db != null

    var progressFactory: Progress.Factory = Progress.Factory.None

    /**
     * Checks if the persisted index is valid for the given build file version.
     * Returns true if the index exists and was built with the same or newer build file version.
     */
    fun isIndexValid(currentBuildFileVersion: Long): Boolean {
        if (!isPersistent) return false

        return try {
            transaction(db) {
                val metadata = SymbolIndexMetadataEntity.all().firstOrNull()
                if (metadata == null) {
                    LOG.info("No symbol index metadata found, index needs to be rebuilt")
                    false
                } else if (metadata.buildFileVersion < currentBuildFileVersion) {
                    LOG.info("Symbol index is stale (indexed at version ${metadata.buildFileVersion}, current version $currentBuildFileVersion)")
                    false
                } else if (metadata.symbolCount == 0) {
                    LOG.info("Symbol index is empty, needs to be rebuilt")
                    false
                } else {
                    LOG.info("Symbol index is valid (${metadata.symbolCount} symbols, indexed at version ${metadata.buildFileVersion})")
                    true
                }
            }
        } catch (e: Exception) {
            LOG.warn("Error checking symbol index validity: ${e.message}")
            false
        }
    }

    /**
     * Returns the number of symbols in the persisted index, or 0 if not available.
     */
    fun getIndexedSymbolCount(): Int {
        return try {
            transaction(db) {
                countSymbols()
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun countSymbols(): Int =
        Symbols.slice(Symbols.fqName.count()).selectAll().first()[Symbols.fqName.count()].toInt()

    private fun clearAllSymbolTables() {
        Symbols.deleteAll()
        Locations.deleteAll()
        Ranges.deleteAll()
        Positions.deleteAll()
    }

    private fun updateMetadata(buildFileVersion: Long, symbolCount: Int) {
        if (!isPersistent) return

        SymbolIndexMetadata.deleteAll()
        SymbolIndexMetadataEntity.new {
            this.buildFileVersion = buildFileVersion
            this.indexedAt = System.currentTimeMillis()
            this.symbolCount = symbolCount
        }
        LOG.info("Symbol index metadata updated: buildFileVersion=$buildFileVersion, symbolCount=$symbolCount")
    }

    /** Rebuilds the entire index. May take a while. */
    fun refresh(module: ModuleDescriptor, exclusions: Sequence<DeclarationDescriptor>, buildFileVersion: Long = 0L, skipIfValid: Boolean = false) {
        if (skipIfValid && buildFileVersion > 0 && isIndexValid(buildFileVersion)) {
            LOG.info("Skipping index rebuild - persisted index is valid")
            return
        }

        val started = System.currentTimeMillis()
        LOG.info("Updating full symbol index...")

        progressFactory.create("Indexing").thenApplyAsync { progress ->
            try {
                val packages = collectAllPackages(module)
                LOG.info("Found ${packages.size} packages to index")

                val symbolCount = transaction(db) {
                    clearAllSymbolTables()

                    var lastUpdateTime = System.currentTimeMillis()

                    packages.forEachIndexed { index, pkgName ->
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime >= PROGRESS_UPDATE_INTERVAL_MS || index == 0 || index == packages.size - 1) {
                            val percent = ((index + 1) * 100) / packages.size
                            val shortName = pkgName.shortName().asString().takeIf { it.isNotEmpty() } ?: "root"
                            progress.update(message = shortName, percent = percent)
                            lastUpdateTime = now
                        }

                        val pkg = module.getPackage(pkgName)
                        try {
                            val descriptors = pkg.memberScope.getContributedDescriptors(
                                DescriptorKindFilter.ALL
                            ) { name -> !exclusions.any { declaration -> declaration.name == name } }
                            addDeclarations(descriptors.asSequence())
                        } catch (e: IllegalStateException) {
                            LOG.warn("Could not query descriptors in package $pkgName")
                        }
                    }

                    val finished = System.currentTimeMillis()
                    val count = countSymbols()
                    LOG.info("Updated full symbol index in ${finished - started} ms! (${count} symbol(s))")

                    if (buildFileVersion > 0) {
                        updateMetadata(buildFileVersion, count)
                    }

                    count
                }
            } catch (e: Exception) {
                LOG.error("Error while updating symbol index")
                LOG.printStackTrace(e)
            }

            progress.close()
        }
    }

    fun updateIndexes(remove: Sequence<DeclarationDescriptor>, add: Sequence<DeclarationDescriptor>) {
        val started = System.currentTimeMillis()
        LOG.info("Updating symbol index...")

        try {
            val symbolCount = transaction(db) {
                removeDeclarations(remove)
                addDeclarations(add)

                val finished = System.currentTimeMillis()
                val count = countSymbols()
                LOG.info("Updated symbol index in ${finished - started} ms! (${count} symbol(s))")
                count
            }

            updateSymbolCount(symbolCount)
        } catch (e: Exception) {
            LOG.error("Error while updating symbol index")
            LOG.printStackTrace(e)
        }
    }

    private fun updateSymbolCount(symbolCount: Int) {
        if (!isPersistent) return

        try {
            transaction(db) {
                val existing = SymbolIndexMetadataEntity.all().firstOrNull()
                if (existing != null) {
                    existing.symbolCount = symbolCount
                    existing.indexedAt = System.currentTimeMillis()
                }
            }
        } catch (e: Exception) {
            LOG.warn("Error updating symbol count in metadata: ${e.message}")
        }
    }

    private fun removeDeclarations(declarations: Sequence<DeclarationDescriptor>) =
        declarations.forEach { declaration ->
            val (descriptorFqn, extensionReceiverFqn) = getFqNames(declaration)

            if (validFqName(descriptorFqn) && (extensionReceiverFqn?.let { validFqName(it) } != false)) {
                Symbols.deleteWhere {
                    (Symbols.fqName eq descriptorFqn.toString()) and (Symbols.extensionReceiverType eq extensionReceiverFqn?.toString())
                }
            } else {
                LOG.warn("Excluding symbol {} from index since its name is too long", descriptorFqn.toString())
            }
        }

    private fun addDeclarations(declarations: Sequence<DeclarationDescriptor>) =
        declarations.forEach { declaration ->
            val (descriptorFqn, extensionReceiverFqn) = getFqNames(declaration)

            if (validFqName(descriptorFqn) && (extensionReceiverFqn?.let { validFqName(it) } != false)) {
                SymbolEntity.new {
                    fqName = descriptorFqn.toString()
                    shortName = descriptorFqn.shortName().toString()
                    kind = declaration.accept(ExtractSymbolKind, Unit).rawValue
                    visibility = declaration.accept(ExtractSymbolVisibility, Unit).rawValue
                    extensionReceiverType = extensionReceiverFqn?.toString()
                }
            } else {
                LOG.warn("Excluding symbol {} from index since its name is too long", descriptorFqn.toString())
            }
        }

    private fun getFqNames(declaration: DeclarationDescriptor): Pair<FqName, FqName?> {
        val descriptorFqn = declaration.fqNameSafe
        val extensionReceiverFqn = declaration.accept(ExtractSymbolExtensionReceiverType, Unit)?.takeIf { !it.isRoot }

        return Pair(descriptorFqn, extensionReceiverFqn)
    }

    private fun validFqName(fqName: FqName) =
        fqName.toString().length <= MAX_FQNAME_LENGTH
            && fqName.shortName().toString().length <= MAX_SHORT_NAME_LENGTH

    fun query(prefix: String, receiverType: FqName? = null, limit: Int = 20, suffix: String = "%"): List<Symbol> = transaction(db) {
        // TODO: Extension completion currently only works if the receiver matches exactly,
        //       ideally this should work with subtypes as well
        SymbolEntity.find {
            (Symbols.shortName like "$prefix$suffix") and (Symbols.extensionReceiverType eq receiverType?.toString())
        }.limit(limit)
            .map { Symbol(
                fqName = FqName(it.fqName),
                kind = Symbol.Kind.fromRaw(it.kind),
                visibility = Symbol.Visibility.fromRaw(it.visibility),
                extensionReceiverType = it.extensionReceiverType?.let(::FqName)
            ) }
    }

    private fun collectAllPackages(module: ModuleDescriptor): List<FqName> {
        val result = mutableListOf<FqName>()
        fun collect(pkgName: FqName) {
            module.getSubPackagesOf(pkgName) { it.toString() != "META-INF" }.forEach { subPkg ->
                result.add(subPkg)
                collect(subPkg)
            }
        }
        collect(FqName.ROOT)
        return result
    }
}

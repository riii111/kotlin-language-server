package org.javacs.kt.database

import org.javacs.kt.LOG
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path

private object DatabaseMetadata : IntIdTable() {
    var version = integer("version")
}

class DatabaseMetadataEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DatabaseMetadataEntity>(DatabaseMetadata)

    var version by DatabaseMetadata.version
}

private const val MAX_FQNAME_LENGTH = 255
private const val MAX_SHORT_NAME_LENGTH = 80
private const val MAX_URI_LENGTH = 511

object Symbols : IntIdTable() {
    val fqName = varchar("fqname", length = MAX_FQNAME_LENGTH).index()
    val shortName = varchar("shortname", length = MAX_SHORT_NAME_LENGTH)
    val kind = integer("kind")
    val visibility = integer("visibility")
    val extensionReceiverType = varchar("extensionreceivertype", length = MAX_FQNAME_LENGTH).nullable()
    val location = optReference("location", Locations)
    val sourceJar = varchar("sourcejar", length = MAX_URI_LENGTH).nullable().index()

    val byShortName = index("symbol_shortname_index", false, shortName)
}

object Locations : IntIdTable() {
    val uri = varchar("uri", length = MAX_URI_LENGTH)
    val range = reference("range", Ranges)
}

object Ranges : IntIdTable() {
    val start = reference("start", Positions)
    val end = reference("end", Positions)
}

object Positions : IntIdTable() {
    val line = integer("line")
    val character = integer("character")
}

object SymbolIndexMetadata : IntIdTable() {
    val buildFileVersion = long("buildfileversion")
    val indexedAt = long("indexedat")
    val symbolCount = integer("symbolcount")
}

class SymbolIndexMetadataEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<SymbolIndexMetadataEntity>(SymbolIndexMetadata)

    var buildFileVersion by SymbolIndexMetadata.buildFileVersion
    var indexedAt by SymbolIndexMetadata.indexedAt
    var symbolCount by SymbolIndexMetadata.symbolCount
}

object IndexedJars : IntIdTable() {
    val jarPath = varchar("jarpath", length = MAX_URI_LENGTH).uniqueIndex()
    val indexedAt = long("indexedat")
    val symbolCount = integer("symbolcount")
}

class IndexedJarEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<IndexedJarEntity>(IndexedJars)

    var jarPath by IndexedJars.jarPath
    var indexedAt by IndexedJars.indexedAt
    var symbolCount by IndexedJars.symbolCount
}

class DatabaseService {

    companion object {
        /**
         * Database schema version. Increment this when changing table structures.
         * When version mismatches, the database will be deleted and recreated.
         */
        const val DB_VERSION = 6
        const val DB_FILENAME = "kls_database.db"
    }

    var db: Database? = null
        private set

    fun setup(storagePath: Path?) {
        db = getDbFromFile(storagePath)

        val database = db
        if (database == null) {
            LOG.info("No storagePath configured, using in-memory database for symbol index")
            return
        }

        val currentVersion = transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(DatabaseMetadata)

            DatabaseMetadataEntity.all().firstOrNull()?.version ?: 0
        }

        if (currentVersion != DB_VERSION) {
            LOG.info("Database has version $currentVersion != $DB_VERSION (the required version), therefore it will be rebuilt...")

            deleteDb(storagePath)
            db = getDbFromFile(storagePath)

            transaction(db) {
                SchemaUtils.createMissingTablesAndColumns(DatabaseMetadata)

                DatabaseMetadata.deleteAll()
                DatabaseMetadata.insert { it[version] = DB_VERSION }
            }
        } else {
            LOG.info("Database has the correct version $currentVersion and will be used as-is")
        }

        db?.let { dbInstance ->
            transaction(dbInstance) {
                SchemaUtils.createMissingTablesAndColumns(Symbols, Locations, Ranges, Positions, SymbolIndexMetadata, IndexedJars)
            }
        }
    }

    private fun getDbFromFile(storagePath: Path?): Database? {
        return storagePath?.let {
            // Create directory if it doesn't exist
            if (!Files.exists(it)) {
                try {
                    Files.createDirectories(it)
                    LOG.info("Created storage directory: $it")
                } catch (e: Exception) {
                    LOG.warn("Failed to create storage directory: $it - ${e.message}")
                    return@let null
                }
            }
            if (Files.isDirectory(it)) {
                Database.connect("jdbc:sqlite:${getDbFilePath(it)}")
            } else {
                LOG.warn("storagePath exists but is not a directory: $it")
                null
            }
        }
    }

    private fun deleteDb(storagePath: Path?) {
        storagePath?.let { Files.deleteIfExists(getDbFilePath(it)) }
    }

    private fun getDbFilePath(storagePath: Path) = Path.of(storagePath.toString(), DB_FILENAME)
}

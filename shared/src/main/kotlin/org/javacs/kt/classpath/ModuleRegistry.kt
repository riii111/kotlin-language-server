package org.javacs.kt.classpath

import org.javacs.kt.LOG
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class ModuleRegistry {
    private val lock = ReentrantReadWriteLock()
    private val modules = mutableMapOf<String, ModuleInfo>()

    fun register(module: ModuleInfo) = lock.write {
        modules[module.name] = module
        LOG.debug("Registered module '{}' with {} source directories", module.name, module.sourceDirs.size)
    }

    fun findModuleForFile(filePath: Path): ModuleInfo? = lock.read {
        val normalizedPath = filePath.toAbsolutePath().normalize()
        modules.values.find { it.containsFile(normalizedPath) }
    }

    fun getModule(name: String): ModuleInfo? = lock.read { modules[name] }

    fun allModules(): Collection<ModuleInfo> = lock.read { modules.values.toList() }

    fun moduleNames(): Set<String> = lock.read { modules.keys.toSet() }

    fun size(): Int = lock.read { modules.size }

    fun isEmpty(): Boolean = lock.read { modules.isEmpty() }

    fun clear() = lock.write {
        modules.clear()
        LOG.debug("Cleared all modules from registry")
    }
}

package org.javacs.kt.classpath

import org.javacs.kt.LOG
import java.nio.file.Path

class ModuleRegistry {
    private val modules = mutableMapOf<String, ModuleInfo>()

    fun register(module: ModuleInfo) {
        modules[module.name] = module
        LOG.debug("Registered module '{}' with {} source directories", module.name, module.sourceDirs.size)
    }

    fun findModuleForFile(filePath: Path): ModuleInfo? {
        val normalizedPath = filePath.toAbsolutePath().normalize()
        return modules.values.find { it.containsFile(normalizedPath) }
    }

    fun getModule(name: String): ModuleInfo? = modules[name]

    fun allModules(): Collection<ModuleInfo> = modules.values.toList()

    fun moduleNames(): Set<String> = modules.keys.toSet()

    fun size(): Int = modules.size

    fun isEmpty(): Boolean = modules.isEmpty()

    fun clear() {
        modules.clear()
        LOG.debug("Cleared all modules from registry")
    }
}

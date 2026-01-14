package org.javacs.kt.index

import org.javacs.kt.LOG
import java.nio.file.Path
import java.util.jar.JarFile

class JarScanner {
    private val jarEntriesCache = mutableMapOf<Path, Set<String>>()

    private fun getJarEntries(jarPath: Path): Set<String> {
        return jarEntriesCache.getOrPut(jarPath) {
            try {
                JarFile(jarPath.toFile()).use { jar ->
                    jar.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .map { it.name }
                        .toSet()
                }
            } catch (e: Exception) {
                LOG.warn("Failed to read JAR entries: {} - {}", jarPath, e.message)
                emptySet()
            }
        }
    }

    fun scanPackages(jarPath: Path): Set<String> {
        return getJarEntries(jarPath)
            .filter { !it.contains("module-info") }
            .map { entry ->
                val className = entry.removeSuffix(".class").replace("/", ".")
                val lastDot = className.lastIndexOf('.')
                if (lastDot > 0) className.substring(0, lastDot) else ""
            }
            .filter { it.isNotEmpty() && !it.startsWith("META-INF") }
            .toSet()
    }

    // A package can span multiple JARs (e.g., kotlin.collections in stdlib + coroutines)
    fun buildPackageToJarsMap(jars: Collection<Path>): Map<String, Set<Path>> {
        val result = mutableMapOf<String, MutableSet<Path>>()
        for (jar in jars) {
            scanPackages(jar).forEach { pkg ->
                result.getOrPut(pkg) { mutableSetOf() }.add(jar)
            }
        }
        return result
    }

    fun containsClass(jarPath: Path, fqClassName: String): Boolean {
        val classFilePath = fqClassName.replace(".", "/") + ".class"
        return classFilePath in getJarEntries(jarPath)
    }
}

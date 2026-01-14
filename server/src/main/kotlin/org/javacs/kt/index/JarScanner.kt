package org.javacs.kt.index

import org.javacs.kt.LOG
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Scans JAR files to build a mapping from package/class names to source JARs.
 * This is used during incremental indexing to track symbol origins.
 */
class JarScanner {

    /**
     * Scans a JAR file and returns all package names it contains.
     */
    fun scanPackages(jarPath: Path): Set<String> {
        return try {
            JarFile(jarPath.toFile()).use { jar ->
                jar.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.contains("module-info") }
                    .map { entry ->
                        val className = entry.name.removeSuffix(".class").replace("/", ".")
                        val lastDot = className.lastIndexOf('.')
                        if (lastDot > 0) className.substring(0, lastDot) else ""
                    }
                    .filter { it.isNotEmpty() && !it.startsWith("META-INF") }
                    .toSet()
            }
        } catch (e: Exception) {
            LOG.warn("Failed to scan JAR: {} - {}", jarPath, e.message)
            emptySet()
        }
    }

    /**
     * Builds a mapping from package name to the set of JARs that contribute to it.
     * Note: A package can span multiple JARs (e.g., kotlin.collections in stdlib + coroutines).
     */
    fun buildPackageToJarsMap(jars: Collection<Path>): Map<String, Set<Path>> {
        val result = mutableMapOf<String, MutableSet<Path>>()
        for (jar in jars) {
            scanPackages(jar).forEach { pkg ->
                result.getOrPut(pkg) { mutableSetOf() }.add(jar)
            }
        }
        return result
    }

    /**
     * Checks if a specific class exists in the given JAR.
     * Used to disambiguate when a package spans multiple JARs.
     */
    fun containsClass(jarPath: Path, fqClassName: String): Boolean {
        val classFilePath = fqClassName.replace(".", "/") + ".class"
        return try {
            JarFile(jarPath.toFile()).use { jar ->
                jar.getEntry(classFilePath) != null
            }
        } catch (e: Exception) {
            false
        }
    }
}
